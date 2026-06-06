package com.reandroid.wallpaper.musicvis;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Pure-logic waveform scene for vis2 (PCM) and vis3 (FFT).
 * Contains ALL data processing, idle/fade, buffer building, and MVP computation.
 * Has ZERO GL imports.
 */
final class WaveScene extends AudioVisBase {
    enum Mode { PCM, FFT }

    static final int LINE_COUNT = 1024;
    private static final int FADEOUT_LENGTH = 100;
    private static final float FADEOUT_FACTOR = 0.95f;
    private static final int FADEIN_LENGTH = 15;

    final Mode mMode;

    // Wave data — GL renderer reads these arrays after update
    final float[] mPointData = new float[LINE_COUNT * 8];
    final float[] mPositions = new float[LINE_COUNT * 2 * 2];
    final float[] mTexCoords = new float[LINE_COUNT * 2 * 2];
    final float[] mAdjustData = new float[LINE_COUNT * 2 * 3];

    // Matrices
    final float[] mProj = new float[16];
    final float[] mModel = new float[16];
    final float[] mMvp = new float[16];

    // Idle / fade state
    int mIdle = 0;
    int mWaveCounter = 0;
    float mYRotation = 0f;

    int fadeoutcounter = 0;
    int fadeincounter = 0;
    int wave1pos = 0, wave1amp = 0;
    int wave2pos = 0, wave2amp = 0;
    int wave3pos = 0, wave3amp = 0;
    int wave4pos = 0, wave4amp = 0;
    final float[] idleWave = new float[8192];
    int lastWaveCounter = 0;

    // ---- constructors ----

    WaveScene(int width, int height, Mode mode, Context context) {
        super(width, height, context);
        mMode = mode;
        initPointData();
    }

    // ---- AudioVisBase ----

    @Override
    protected int getAudioType() {
        return (mMode == Mode.FFT) ? AudioCapture.TYPE_FFT : AudioCapture.TYPE_PCM;
    }

    @Override
    protected int getAudioCaptureSize() {
        return (mMode == Mode.FFT) ? mFftSize : 1024;
    }

    @Override
    public void start() {
        SharedPreferences p;
        if (mPluginPrefs != null) {
            p = mPluginPrefs;
        } else {
            String pn = (mMode == Mode.PCM) ? "musicvis2_prefs" : "musicvis3_prefs";
            p = mContext.getSharedPreferences(pn, Context.MODE_PRIVATE);
        }
        readPrefs(p);
        p.registerOnSharedPreferenceChangeListener(this);

        int type = getAudioType();
        int size = getAudioCaptureSize();
        if (mAudioCapture == null || mAudioCapture.getSize() != size) {
            if (mAudioCapture != null) mAudioCapture.release();
            mAudioCapture = new AudioCapture(type, size);
            int capSize = mAudioCapture.getSize();
            mVizData = new int[capSize];
            mAnalyzer = new int[capSize / 2];
            mPcmSmoothed = new float[capSize];
        }
        mAudioCapture.start();
    }

    @Override
    public void stop() {
        if (mAudioCapture != null) {
            SharedPreferences p;
            if (mPluginPrefs != null) {
                p = mPluginPrefs;
            } else {
                String pn = (mMode == Mode.PCM) ? "musicvis2_prefs" : "musicvis3_prefs";
                p = mContext.getSharedPreferences(pn, Context.MODE_PRIVATE);
            }
            p.unregisterOnSharedPreferenceChangeListener(this);
            mAudioCapture.stop();
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        int oldFftSize = mFftSize;
        readPrefs(p);
        if ("musicvis_fft_size".equals(key) && mFftSize != oldFftSize) {
            mFftSizeChanged = true;
        }
    }

    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        mYRotation = (xOffset * 4f) * (mMode == Mode.FFT ? 360f : 180f);
    }

    // ---- point data init ----

    void initPointData() {
        int outlen = mPointData.length / 8;
        int half = outlen / 2;
        for (int i = 0; i < outlen; i++) {
            mPointData[i * 8] = i - half;          // start X
            mPointData[i * 8 + 2] = 0f;           // start S
            mPointData[i * 8 + 3] = 0f;           // start T
            mPointData[i * 8 + 4] = i - half;      // end X
            mPointData[i * 8 + 6] = 1.0f;          // end S
            mPointData[i * 8 + 7] = 0f;            // end T
        }
    }

    // ---- wave data update ----

    void updateWaveData() {
        int len = 0;
        if (mAudioCapture != null) {
            if (mMode == Mode.PCM) {
                mVizData = mAudioCapture.getFormattedData(1, 1);
                len = mVizData.length;
            } else {
                mVizData = mAudioCapture.getFormattedData(1, 1);
                len = mVizData.length / 2;
            }
        }

        if (len == 0) {
            if (mIdle == 0) mIdle = 1;
            return;
        }

        if (mMode == Mode.PCM) {
            int outlen = mPointData.length / 8;
            if (len > outlen) len = outlen;
            if (mIdle != 0) mIdle = 0;
            float alpha = 0.3f;
            for (int i = 0; i < len; i++) {
                float smoothed = alpha * mVizData[i] + (1f - alpha) * mPcmSmoothed[i];
                mPcmSmoothed[i] = smoothed;
                mPointData[i * 8 + 1] = smoothed;
                mPointData[i * 8 + 5] = -smoothed;
            }
        } else {
            // FFT mode
            len = len / 2; // bins are in pairs
            if (len > mAnalyzer.length) len = mAnalyzer.length;
            if (mIdle != 0) mIdle = 0;

            for (int i = 1; i < len - 1; i++) {
                int val1 = mVizData[i * 2];
                int val2 = mVizData[i * 2 + 1];
                int val = val1 * val1 + val2 * val2;
                int newval = val * (i / 16 + 1);
                int oldval = mAnalyzer[i];
                if (newval < oldval - 800) {
                    newval = oldval - 800;
                }
                mAnalyzer[i] = newval;
            }

            int outlen = mPointData.length / 8;
            int width = Math.min(mWidth, outlen);
            int skip = (outlen - width) / 2;

            // Logarithmic bin-to-bar mapping
            for (int i = 0; i < width; i++) {
                double frac = (double) i / width;
                int binIdx = (int) (Math.pow(frac, 1.5) * (len - 1));
                if (binIdx < 0) binIdx = 0;
                if (binIdx >= len) binIdx = len - 1;
                float val = mAnalyzer[binIdx] / 8f;
                if (val < 1f && val > -1f) val = 1f;
                int idx = (i + skip) * 8;
                mPointData[idx + 1] = val;
                mPointData[idx + 5] = -val;
            }
        }
        mWaveCounter++;
        if (mRecolorDynamic && mRecolorEnabled && len > 0) {
            updateDynamicHue();
        }
    }

    /**
     * Process externally-supplied PCM data directly (used by ManyScene).
     * @param data  raw PCM samples
     * @param len   number of samples to process
     */
    void updateWaveDataFromArray(int[] data, int len) {
        if (len == 0) {
            if (mIdle == 0) mIdle = 1;
            return;
        }
        if (mIdle != 0) mIdle = 0;

        int outlen = mPointData.length / 8;
        if (len > outlen) len = outlen;
        for (int i = 0; i < len; i++) {
            float val = data[i];
            mPointData[i * 8 + 1] = val;
            mPointData[i * 8 + 5] = -val;
        }
        mWaveCounter++;
        if (mRecolorDynamic && mRecolorEnabled && len > 0) {
            updateDynamicHue();
        }
    }

    private void updateDynamicHue() {
        float sum = 0f;
        int count = 0;
        int outlen = mPointData.length / 8;
        int width = Math.min(mWidth, outlen);
        if (width <= 0) width = outlen;
        int skip = (outlen - width) / 2;
        int end = outlen - skip;
        if (skip < 0) skip = 0;
        if (end > outlen) end = outlen;
        for (int i = skip; i < end; i++) {
            sum += Math.abs(mPointData[i * 8 + 1]);
            count++;
        }
        if (count > 0) {
            float avg = sum / count;
            float norm = Math.min(1f, avg / 800f);
            mHue = (mHue + norm * 0.03f) % 1.0f;
        }
    }

    // ---- idle / fade ----

    void applyIdleAndFade() {
        int width = mWidth;
        if (width > 1024) width = 1024;
        int skip = (1024 - width) / 2;
        int end = 1024 - skip;

        if (mIdle != 0) {
            if (fadeoutcounter > 0) {
                for (int i = skip; i < end; i++) {
                    float val = Math.abs(mPointData[i * 8 + 1]);
                    val = val * FADEOUT_FACTOR;
                    if (val < 2f) val = 2f;
                    mPointData[i * 8 + 1] = val;
                    mPointData[i * 8 + 5] = -val;
                }
                fadeoutcounter--;
                if (fadeoutcounter == 0) {
                    wave1amp = 0; wave2amp = 0; wave3amp = 0; wave4amp = 0;
                }
            } else {
                makeIdleWave(mPointData);
            }
            fadeincounter = FADEIN_LENGTH;
        } else {
            if (fadeincounter > 0 && fadeoutcounter == 0) {
                makeIdleWave(idleWave);
                if (lastWaveCounter != mWaveCounter) {
                    lastWaveCounter = mWaveCounter;
                    for (int i = skip; i < end; i++) {
                        float val = Math.abs(mPointData[i * 8 + 1]);
                        mPointData[i * 8 + 1] = (val * (FADEIN_LENGTH - fadeincounter)
                                + idleWave[i * 8 + 1] * fadeincounter) / FADEIN_LENGTH;
                        mPointData[i * 8 + 5] = (-val * (FADEIN_LENGTH - fadeincounter)
                                + idleWave[i * 8 + 5] * fadeincounter) / FADEIN_LENGTH;
                    }
                }
                fadeincounter--;
                if (fadeincounter == 0) {
                    fadeoutcounter = FADEOUT_LENGTH;
                }
            } else {
                fadeoutcounter = FADEOUT_LENGTH;
            }
        }
    }

    void makeIdleWave(float[] points) {
        float amp1 = (float) Math.sin(0.007f * wave1amp) * 120f;
        float amp2 = (float) Math.sin(0.023f * wave2amp) * 80f;
        float amp3 = (float) Math.sin(0.011f * wave3amp) * 40f;
        float amp4 = (float) Math.sin(0.031f * wave4amp) * 20f;
        int skip = (1024 - mWidth) / 2;
        if (skip < 0) skip = 0;
        int end = 1024 - skip;
        for (int i = skip; i < end; i++) {
            float val = (float) (Math.sin(0.013f * (wave1pos + i)) * amp1
                    + Math.sin(0.029f * (wave2pos + i)) * amp2);
            float off = (float) (Math.sin(0.005f * (wave3pos + i)) * amp3
                    + Math.sin(0.017f * (wave4pos + i)) * amp4);
            if (val < 2f && val > -2f) val = 2f;
            points[i * 8 + 1] = val + off;
            points[i * 8 + 5] = -val + off;
        }
        wave1pos++; wave1amp++;
        wave2pos--; wave2amp++;
        wave3pos++; wave3amp++;
        wave4pos++; wave4amp++;
    }

    // ---- buffer building ----

    /** Build mPositions[] and mTexCoords[] from mPointData (no GL calls). */
    void updateBuffers() {
        for (int i = 0; i < LINE_COUNT; i++) {
            int base = i * 8;
            int out = i * 4;
            mPositions[out]     = mPointData[base];
            mPositions[out + 1] = mPointData[base + 1];
            mPositions[out + 2] = mPointData[base + 4];
            mPositions[out + 3] = mPointData[base + 5];

            mTexCoords[out]     = mPointData[base + 2];
            mTexCoords[out + 1] = mPointData[base + 3];
            mTexCoords[out + 2] = mPointData[base + 6];
            mTexCoords[out + 3] = mPointData[base + 7];
        }
    }

    /** Build mAdjustData[] from HSL state. */
    void updateAdjustBuffer() {
        float h = mRecolorEnabled ? mHue : -1f;
        float s = mRecolorEnabled ? mSaturation : 1f;
        float v = mRecolorEnabled ? mBrightness : 1f;
        for (int i = 0; i < LINE_COUNT * 2; i++) {
            int b = i * 3;
            mAdjustData[b]     = h;
            mAdjustData[b + 1] = s;
            mAdjustData[b + 2] = v;
        }
    }

    // ---- MVP ----

    void updateMvp() {
        float left, right, bottom, top;
        if (mWidth > mHeight) {
            float aspect = (float) mWidth / (float) mHeight;
            left = -aspect;
            right = aspect;
            bottom = -1f;
            top = 1f;
        } else {
            float aspect = (float) mHeight / (float) mWidth;
            left = -1f;
            right = 1f;
            bottom = -aspect;
            top = aspect;
        }
        Mat4.orthoM(mProj, left, right, bottom, top, -1f, 1f);

        float scale = 0.004165f * (1.0f + 2f * Math.abs((float) Math.sin(Math.toRadians(mYRotation))));
        Mat4.setIdentityM(mModel);
        Mat4.rotateM(mModel, mYRotation, 0f, 0f, 1f);
        Mat4.scaleM(mModel, scale, scale, scale);

        Mat4.multiplyMM(mMvp, mProj, mModel);
    }
}
