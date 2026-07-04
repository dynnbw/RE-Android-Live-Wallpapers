package com.reandroid.wallpaper.nixietube;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.Visualizer;
import android.util.Log;

/**
 * Unified audio capture: system audio (Visualizer) or microphone (AudioRecord).
 * Calls back with instantaneous dBFS level at ~30 Hz.
 */
final class NixieTubeAudioSource {
    private static final String TAG = "NixieTubeAudio";
    private static final int SAMPLE_RATE = 16000;
    private static final int CAPTURE_SIZE = 512;

    public interface Callback {
        void onLevel(float dbFS);
    }

    private final Callback mCallback;
    private final int mSource;

    private Visualizer mVisualizer;
    private AudioRecord mAudioRecord;
    private Thread mMicThread;
    private volatile boolean mRunning;

    NixieTubeAudioSource(Callback callback, int source) {
        mCallback = callback;
        mSource = source;
    }

    void start() {
        if (mRunning) return;
        mRunning = true;
        if (mSource == 0) startSystemAudio();
        else startMicrophone();
    }

    void stop() {
        mRunning = false;
        if (mVisualizer != null) {
            try { mVisualizer.setEnabled(false); } catch (Exception ignored) {}
            mVisualizer.release();
            mVisualizer = null;
        }
        if (mAudioRecord != null) {
            try { mAudioRecord.stop(); } catch (Exception ignored) {}
            mAudioRecord.release();
            mAudioRecord = null;
        }
        if (mMicThread != null) {
            try { mMicThread.join(500); } catch (InterruptedException ignored) {}
            mMicThread = null;
        }
    }

    // ---- System audio (Visualizer with DataCaptureListener) ----

    private void startSystemAudio() {
        int[] range = Visualizer.getCaptureSizeRange();
        final int size = CAPTURE_SIZE;
        try {
            mVisualizer = new Visualizer(0);
            mVisualizer.setCaptureSize(size);
            mVisualizer.setEnabled(true);
        } catch (Exception e) {
            Log.w(TAG, "Visualizer init failed, falling back to mic", e);
            startMicrophone();
            return;
        }

        final NixieTubeAudioSource self = this;
        mMicThread = new Thread(() -> {
            byte[] buf = new byte[size];
            while (mRunning && mVisualizer != null) {
                try {
                    int status = mVisualizer.getWaveForm(buf);
                    if (status == Visualizer.SUCCESS) {
                        float rms = computeRms(buf);
                        mCallback.onLevel(linearToDb(rms));
                    } else {
                        // No waveform available (e.g. no audio playing).
                        // Send silence so the scene's peak can decay and
                        // switch back to clock display.
                        mCallback.onLevel(-60f);
                    }
                } catch (Exception ignored) {}
                try { Thread.sleep(30); } catch (InterruptedException ignored) {}
            }
        }, "NixieTube-SysAudio");
        mMicThread.start();
    }

    // ---- Microphone (AudioRecord) ----

    private void startMicrophone() {
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBuf, CAPTURE_SIZE * 2);
        try {
            mAudioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufSize);
            mAudioRecord.startRecording();
        } catch (SecurityException e) {
            Log.w(TAG, "No mic permission");
            return;
        }

        mMicThread = new Thread(() -> {
            short[] buf = new short[CAPTURE_SIZE];
            while (mRunning && mAudioRecord != null) {
                int read = mAudioRecord.read(buf, 0, buf.length);
                if (read > 0) {
                    float rms = computeRmsShort(buf, read);
                    mCallback.onLevel(linearToDb(rms));
                } else {
                    mCallback.onLevel(-60f); // silence
                }
                try { Thread.sleep(30); } catch (InterruptedException ignored) {}
            }
        }, "NixieTube-Mic");
        mMicThread.start();
    }

    // ---- Audio math ----

    private float computeRms(byte[] buf) {
        // Visualizer waveform is unsigned 8-bit PCM centered at 128 (silence = 128).
        // Must center around 0 before RMS, otherwise a flat/silent buffer (all 128s)
        // would compute as full-scale (0 dB) and keep the wallpaper stuck in AUDIO mode.
        float sum = 0;
        for (byte b : buf) {
            int v = (b & 0xFF) - 128;
            sum += v * v;
        }
        return (float) Math.sqrt(sum / buf.length) / 128f;
    }

    private float computeRmsShort(short[] buf, int len) {
        float sum = 0;
        for (int i = 0; i < len; i++) sum += buf[i] * buf[i];
        return (float) Math.sqrt(sum / len) / 32768f;
    }

    private float linearToDb(float linear) {
        if (linear < 1e-9f) return -60f;
        return 20f * (float) Math.log10(linear);
    }
}
