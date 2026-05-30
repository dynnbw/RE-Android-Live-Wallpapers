package com.reandroid.wallpaper.musicvis;

import android.media.audiofx.Visualizer;
import android.util.Log;

public class AudioCapture {
    private static final String TAG = "AudioCapture";
    private static final long MAX_IDLE_TIME_MS = 3000;

    public static final int TYPE_PCM = 0;
    public static final int TYPE_FFT = 1;

    private final int mType;
    private final int mSize;

    private Visualizer mVisualizer;

    // Double-buffering: capture thread fills one buffer while render thread reads the other
    private byte[] mRawBufferA;
    private byte[] mRawBufferB;
    private volatile byte[] mReadyRawBuffer;

    private int[] mFormattedBufferA;
    private int[] mFormattedBufferB;
    private volatile int[] mReadyFormattedBuffer;

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

    public int getSize() { return mSize; }

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
            try { mCaptureThread.join(500); } catch (InterruptedException ignored) {}
            mCaptureThread = null;
        }
        mReadyRawBuffer = null;
        mReadyFormattedBuffer = null;
        mHasData = false;

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
        if (mType == TYPE_PCM) {
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

    private class CaptureThread extends Thread {
        // Indexing: which buffer are we capturing INTO right now (0 = A, 1 = B)
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

            while (mRunning) {
                int status = Visualizer.ERROR;
                try {
                    if (mVisualizer != null) {
                        byte[] target = (mCaptureIndex == 0) ? bufA : bufB;
                        if (mType == TYPE_PCM) {
                            status = mVisualizer.getWaveForm(target);
                        } else {
                            status = mVisualizer.getFft(target);
                        }
                    }
                } catch (IllegalStateException e) {
                    Log.e(TAG, "capture IllegalStateException");
                }

                if (status == Visualizer.SUCCESS) {
                    byte[] captured = (mCaptureIndex == 0) ? bufA : bufB;
                    // Check if all zeros (silence)
                    if (!isAllSilence(captured)) {
                        mLastValidCaptureTimeMs = System.currentTimeMillis();
                        mHasData = true;
                    } else if ((System.currentTimeMillis() - mLastValidCaptureTimeMs) > MAX_IDLE_TIME_MS) {
                        mHasData = false;
                    }

                    // Swap: publish the just-filled buffer as ready
                    mReadyRawBuffer = captured;
                    mReadyFormattedBuffer = (mCaptureIndex == 0) ? fmtA : fmtB;
                    mCaptureIndex ^= 1; // toggle 0↔1
                }

                try { Thread.sleep(5); } catch (InterruptedException ignored) {}
            }
        }
    }

    private boolean isAllSilence(byte[] data) {
        byte nullValue = (mType == TYPE_PCM) ? (byte) 0x80 : 0;
        for (byte b : data) {
            if (b != nullValue) return false;
        }
        return true;
    }
}
