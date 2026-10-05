package com.reandroid.wallpaper.musicvis;

import android.media.audiofx.Visualizer;
import android.util.Log;

public class AudioCapture {
    private static final String TAG = "AudioCapture";
    private static final long MAX_IDLE_TIME_MS = 3000;

    /** 采样音量的间隔：读音量是系统调用，而音量键的观感延迟远小于这个数。 */
    private static final long VOLUME_POLL_INTERVAL_MS = 200;

    public static final int TYPE_PCM = 0;
    public static final int TYPE_FFT = 1;
    public static final int TYPE_BOTH = 2;

    /** 「跟随系统音量」的偏好键（各 vis 壁纸共用同一个键名）。 */
    public static final String PREF_FOLLOW_VOLUME = "musicvis_follow_volume";

    private final int mType;
    private final int mSize;

    private Visualizer mVisualizer;

    /** 幅度增益（1 = 不跟随）；由采集线程按 {@link #VOLUME_POLL_INTERVAL_MS} 刷新。 */
    private volatile float mVolumeGain = 1f;

    /** 采集线程读、渲染线程写，所以是 volatile。 */
    private volatile VolumeGainSource mVolumeGainSource;

    private long mLastVolumePollMs;

    // PCM double-buffering
    private byte[] mRawBufferA;
    private byte[] mRawBufferB;
    private volatile byte[] mReadyRawBuffer;
    private int[] mFormattedBufferA;
    private int[] mFormattedBufferB;
    private volatile int[] mReadyFormattedBuffer;

    // FFT double-buffering (TYPE_BOTH only)
    private byte[] mFftRawA;
    private byte[] mFftRawB;
    private volatile byte[] mReadyFftRaw;
    private int[] mFftFmtA;
    private int[] mFftFmtB;
    private volatile int[] mReadyFftFmt;
    private volatile boolean mFftHasData;

    private final byte[] mRawNullData = new byte[0];
    private final int[] mFormattedNullData = new int[0];

    private CaptureThread mCaptureThread;
    private volatile boolean mRunning;
    private volatile boolean mHasData;

    private long mLastValidCaptureTimeMs;

    public AudioCapture(int type, int size) {
        mType = type;
        int[] range = Visualizer.getCaptureSizeRange();
        if (size < range[0]) size = range[0];
        if (size > range[1]) size = range[1];
        mSize = size;

        mRawBufferA = new byte[size];
        mRawBufferB = new byte[size];
        mFormattedBufferA = new int[size];
        mFormattedBufferB = new int[size];

        if (mType == TYPE_BOTH) {
            mFftRawA = new byte[size];
            mFftRawB = new byte[size];
            mFftFmtA = new int[size];
            mFftFmtB = new int[size];
        }

        try {
            mVisualizer = new Visualizer(0);
            if (mVisualizer != null) {
                if (mVisualizer.getEnabled()) {
                    mVisualizer.setEnabled(false);
                }
                mVisualizer.setCaptureSize(size);
            }
        } catch (UnsupportedOperationException e) {
            Log.e(TAG, "Visualizer cstor UnsupportedOperationException");
        } catch (IllegalStateException e) {
            Log.e(TAG, "Visualizer cstor IllegalStateException");
        } catch (RuntimeException e) {
            Log.e(TAG, "Visualizer cstor RuntimeException");
        }
    }

    public int getSize() {
        return mSize;
    }

    // ---- 幅度跟随系统音量 ----

    /**
     * 由宿主注入的「当前音量 → 幅度增益」。{@code AudioCapture} 不碰 Android API：
     * 读音量与映射都在宿主那侧（见 {@code AudioVisBase.volumeGainSource}）。
     */
    public interface VolumeGainSource {
        /** @return 0..1 的线性增益，1 = 满音量 */
        float gain();
    }

    /**
     * 媒体音量档位 → 幅度增益。用**档位比例**，不是物理响度（dB）。
     *
     * <p>为什么不用 dB：媒体音量曲线在低档跨度极大，而渲染端把幅度平方（{@code re²+im²}）
     * 或取平均，两者相乘之后低档就落到万分之一 —— 看上去就是**突然变小**。
     * 按档位比例是线性的：50% 档 → 约一半。
     *
     * <p>抽成纯函数是为了能在 JVM 测试里钉住这条映射。
     */
    public static float gainForVolume(int index, int maxIndex) {
        if (maxIndex <= 0) {
            return 1f;
        }
        float gain = (float) index / maxIndex;
        return gain < 0f ? 0f : (gain > 1f ? 1f : gain);
    }

    /** 注入增益来源；传 null 表示不跟随（恒 1）。 */
    public void setVolumeGainSource(VolumeGainSource source) {
        mVolumeGainSource = source;
        if (source == null) {
            mVolumeGain = 1f;
        }
    }

    public boolean hasVolumeGainSource() {
        return mVolumeGainSource != null;
    }

    /**
     * 当前幅度增益。渲染端把它乘到**自己那个域**上：幅度驱动的画面乘在幅度上，
     * 已经平方过的分析值（{@code mAnalyzer}）乘在分析值上 —— 两边都得到"尺寸 ∝ 档位比例"。
     */
    public float getVolumeGain() {
        return mVolumeGain;
    }

    /** 读音量是系统调用，按固定间隔取样即可（音量键的观感延迟远小于一帧）。 */
    private void pollVolumeGain() {
        VolumeGainSource source = mVolumeGainSource;
        if (source == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - mLastVolumePollMs < VOLUME_POLL_INTERVAL_MS) {
            return;
        }
        mLastVolumePollMs = now;
        float gain = source.gain();
        mVolumeGain = gain < 0f ? 0f : (gain > 1f ? 1f : gain);
    }

    public void start() {
        if (mVisualizer == null) return;
        try {
            if (!mVisualizer.getEnabled()) {
                mVisualizer.setEnabled(true);
                mLastValidCaptureTimeMs = System.currentTimeMillis();
            }
        } catch (IllegalStateException e) {
            Log.e(TAG, "start() IllegalStateException");
            return;
        }

        if (mCaptureThread == null) {
            mRunning = true;
            mCaptureThread = new CaptureThread();
            mCaptureThread.start();
        }
    }

    public void stop() {
        mRunning = false;
        if (mCaptureThread != null) {
            try {
                mCaptureThread.join(500);
            } catch (InterruptedException ignored) {
            }
            mCaptureThread = null;
        }
        mReadyRawBuffer = null;
        mReadyFormattedBuffer = null;
        mHasData = false;
        if (mType == TYPE_BOTH) {
            mReadyFftRaw = null;
            mReadyFftFmt = null;
            mFftHasData = false;
        }

        if (mVisualizer != null) {
            try {
                if (mVisualizer.getEnabled()) {
                    mVisualizer.setEnabled(false);
                }
            } catch (IllegalStateException e) {
                Log.e(TAG, "stop() IllegalStateException");
            }
        }
    }

    public void release() {
        stop();
        if (mVisualizer != null) {
            mVisualizer.release();
            mVisualizer = null;
        }
    }

    public byte[] getRawData() {
        return mHasData ? mReadyRawBuffer : mRawNullData;
    }

    public int[] getFormattedData(int num, int den) {
        if (!mHasData || mReadyFormattedBuffer == null) {
            return mFormattedNullData;
        }
        if (mType == TYPE_PCM || mType == TYPE_BOTH) {
            byte[] raw = mReadyRawBuffer;
            int[] fmt = mReadyFormattedBuffer;
            for (int i = 0; i < mSize; i++) {
                int tmp = ((int) raw[i] & 0xFF) - 128;
                fmt[i] = (tmp * num) / den;
            }
        } else {
            byte[] raw = mReadyRawBuffer;
            int[] fmt = mReadyFormattedBuffer;
            for (int i = 0; i < mSize; i++) {
                fmt[i] = ((int) raw[i] * num) / den;
            }
        }
        return mReadyFormattedBuffer;
    }

    /** Returns FFT data captured simultaneously with PCM (TYPE_BOTH only). */
    public int[] getFftFormattedData() {
        if (mType != TYPE_BOTH || !mFftHasData || mReadyFftFmt == null) {
            return mFormattedNullData;
        }
        byte[] raw = mReadyFftRaw;
        int[] fmt = mReadyFftFmt;
        // FFT bytes are signed — matching TYPE_FFT getFormattedData(1,1)
        for (int i = 0; i < mSize; i++) {
            fmt[i] = raw[i];
        }
        return mReadyFftFmt;
    }

    private class CaptureThread extends Thread {
        private int mCaptureIndex;

        CaptureThread() {
            super("AudioCaptureThread");
            setPriority(Thread.NORM_PRIORITY - 1);
        }

        @Override
        public void run() {
            byte[] bufA = mRawBufferA;
            byte[] bufB = mRawBufferB;
            int[] fmtA = mFormattedBufferA;
            int[] fmtB = mFormattedBufferB;
            // FFT buffers (TYPE_BOTH)
            byte[] fftA = mFftRawA;
            byte[] fftB = mFftRawB;
            int[] fftFmtA = mFftFmtA;
            int[] fftFmtB = mFftFmtB;

            while (mRunning) {
                pollVolumeGain();
                int pcmStatus = Visualizer.ERROR;
                int fftStatus = Visualizer.ERROR;
                try {
                    if (mVisualizer != null) {
                        byte[] pcmTarget = (mCaptureIndex == 0) ? bufA : bufB;
                        if (mType == TYPE_FFT) {
                            fftStatus = mVisualizer.getFft(pcmTarget);
                            pcmStatus = fftStatus;
                        } else {
                            pcmStatus = mVisualizer.getWaveForm(pcmTarget);
                            if (mType == TYPE_BOTH) {
                                byte[] fftTarget = (mCaptureIndex == 0) ? fftA : fftB;
                                fftStatus = mVisualizer.getFft(fftTarget);
                            }
                        }
                    }
                } catch (IllegalStateException e) {
                    Log.e(TAG, "capture IllegalStateException");
                }

                if (pcmStatus == Visualizer.SUCCESS) {
                    byte[] captured = (mCaptureIndex == 0) ? bufA : bufB;
                    // FFT data has null value 0, not 0x80 — match the original
                    // Android source which selects nullValue based on capture type.
                    boolean isPcm = (mType != TYPE_FFT);
                    if (!isAllSilence(captured, isPcm)) {
                        mLastValidCaptureTimeMs = System.currentTimeMillis();
                        mHasData = true;
                        // Only publish when there is real audio — keep the last
                        // valid frame in the buffer so the scene's fade-out has
                        // something to fade from.
                        mReadyRawBuffer = captured;
                        mReadyFormattedBuffer = (mCaptureIndex == 0) ? fmtA : fmtB;
                        mCaptureIndex ^= 1;
                    } else if ((System.currentTimeMillis() - mLastValidCaptureTimeMs)
                            > MAX_IDLE_TIME_MS) {
                        mHasData = false;
                    }
                    // Silent: don't publish, don't toggle — last valid data stays.
                }

                if (mType == TYPE_BOTH && fftStatus == Visualizer.SUCCESS) {
                    byte[] fftCaptured = (mCaptureIndex == 0) ? fftA : fftB;
                    if (!isAllSilence(fftCaptured, false)) {
                        mFftHasData = true;
                        mReadyFftRaw = fftCaptured;
                        mReadyFftFmt = (mCaptureIndex == 0) ? fftFmtA : fftFmtB;
                    }
                    // Silent FFT: don't publish — keep last valid frame.
                }

                // Adaptive polling: fast (200 Hz) when audio is present,
                // slow (5 Hz) during silence to save CPU.
                long delay = mHasData ? 5 : 200;
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    private boolean isAllSilence(byte[] data, boolean isPcm) {
        byte nullValue = isPcm ? (byte) 0x80 : 0;
        for (byte b : data) {
            if (b != nullValue) return false;
        }
        return true;
    }
}
