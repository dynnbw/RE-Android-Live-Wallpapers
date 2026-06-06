package com.reandroid.wallpaper.musicvis;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

/**
 * Pure-logic composite scene for vis5.
 * Contains WaveScene and VuScene internally, plus 6-fold 3D layout state.
 * Has ZERO GL imports.
 */
final class ManyScene {
    private static final int LINE_COUNT = 256;

    // Composed sub-scenes
    final WaveScene mWave;
    final VuScene mNeedle;

    // Audio
    private AudioCapture mAudioCapture;
    int[] mVizData = new int[1024];

    // Wave data (stored in mWave's arrays)
    final float[] mLinePositions = new float[LINE_COUNT * 4];
    final float[] mLineTexCoords = new float[LINE_COUNT * 4];

    // Needle state (read from mNeedle fields)

    // ManyScene-specific state
    float mRotate = 0f;
    float mTilt = -20f;
    private int mIdle = 0;
    private int mWaveCounter = 0;

    private int fadeoutcounter = 0;
    private int fadeincounter = 0;
    private int wave1pos = 0, wave1amp = 0;
    private int wave2pos = 0, wave2amp = 0;
    private int wave3pos = 0, wave3amp = 0;
    private int wave4pos = 0, wave4amp = 0;
    private final float[] idleWave = new float[LINE_COUNT * 8];
    private int lastWaveCounter = 0;

    float mAutoRotation = 0f;
    long mLastTimeMs = 0L;

    boolean mUseTriangleStrip = true;
    boolean mHasPrefInit = false;
    private SharedPreferences mPluginPrefs;

    final float[] mBgColor = new float[3];
    final float[] mProj = new float[16];

    ManyScene(int width, int height, Context context) {
        mWave = new WaveScene(width, height, WaveScene.Mode.PCM, context);
        mNeedle = new VuScene(width, height, context);
        initPointData();
    }

    // ---- lifecycle ----

    void start() {
        if (mAudioCapture == null) {
            mAudioCapture = new AudioCapture(AudioCapture.TYPE_PCM, 1024);
        }
        mAudioCapture.start();
    }

    void stop() {
        if (mAudioCapture != null) mAudioCapture.stop();
    }

    void release() {
        if (mAudioCapture != null) {
            mAudioCapture.release();
            mAudioCapture = null;
        }
    }

    void setPluginPrefs(SharedPreferences p) {
        mPluginPrefs = p;
    }

    void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        mRotate = (xOffset - 0.5f) * 90f;
    }

    // ---- point data init ----

    void initPointData() {
        // Initialize the first LINE_COUNT entries of mWave.mPointData
        float[] pd = mWave.mPointData;
        int outlen = LINE_COUNT;
        int half = outlen / 2;
        for (int i = 0; i < outlen; i++) {
            pd[i * 8]     = i - half;   // start X
            pd[i * 8 + 2] = 0f;        // start S
            pd[i * 8 + 3] = 0f;        // start T
            pd[i * 8 + 4] = i - half;  // end X
            pd[i * 8 + 6] = 1.0f;      // end S
            pd[i * 8 + 7] = 0f;        // end T
        }
    }

    // ---- render mode ----

    void updateRenderMode() {
        SharedPreferences p = mPluginPrefs != null ? mPluginPrefs
                : androidx.preference.PreferenceManager.getDefaultSharedPreferences(
                        mWave.mContext);
        boolean pref = p.getBoolean("musicvis_use_triangle_strip", true);
        if (!mHasPrefInit || pref != mUseTriangleStrip) {
            mUseTriangleStrip = pref;
            mHasPrefInit = true;
        }
        String hex = p.getString("musicvis_bg_color", "#000000");
        try {
            int c = Color.parseColor(hex);
            mBgColor[0] = Color.red(c) / 255f;
            mBgColor[1] = Color.green(c) / 255f;
            mBgColor[2] = Color.blue(c) / 255f;
        } catch (Exception e) {
            mBgColor[0] = mBgColor[1] = mBgColor[2] = 0f;
        }
    }

    // ---- auto rotation ----

    void updateAutoRotation(long timeMs) {
        if (mLastTimeMs == 0L) {
            mLastTimeMs = timeMs;
            return;
        }
        long delta = timeMs - mLastTimeMs;
        if (delta > 80) delta = 80;
        mAutoRotation += 0.3f * delta / 35f;
        while (mAutoRotation > 360f) mAutoRotation -= 360f;
        mLastTimeMs = timeMs;
    }

    // ---- needle update (delegates to VuScene) ----

    void updateNeedle() {
        int len = 0;
        if (mAudioCapture != null) {
            mVizData = mAudioCapture.getFormattedData(512, 1);
            len = mVizData.length;
        }
        if (len > 0) {
            mNeedle.updateNeedleFromArray(mVizData, len);
        }
    }

    // ---- wave data update ----

    void updateWaveData() {
        int len = 0;
        if (mAudioCapture != null) {
            mVizData = mAudioCapture.getFormattedData(512, 1);
            len = mVizData.length;
        }

        if (len == 0) {
            if (mIdle == 0) mIdle = 1;
            return;
        }

        if (mIdle != 0) mIdle = 0;

        len /= 4;
        if (len > LINE_COUNT) len = LINE_COUNT;
        float[] pd = mWave.mPointData;
        for (int i = 0; i < len; i++) {
            int amp = (mVizData[i * 4] + mVizData[i * 4 + 1]
                     + mVizData[i * 4 + 2] + mVizData[i * 4 + 3]);
            pd[i * 8 + 1] = amp;
            pd[i * 8 + 5] = -amp;
        }
        mWaveCounter++;
    }

    // ---- idle / fade ----

    void applyIdleAndFade() {
        float[] pd = mWave.mPointData;
        if (mIdle != 0) {
            if (fadeoutcounter > 0) {
                for (int i = 0; i < LINE_COUNT; i++) {
                    float val = Math.abs(pd[i * 8 + 1]);
                    val = val * 0.95f;
                    if (val < 2f) val = 2f;
                    pd[i * 8 + 1] = val;
                    pd[i * 8 + 5] = -val;
                }
                fadeoutcounter--;
                if (fadeoutcounter == 0) {
                    wave1amp = 0; wave2amp = 0; wave3amp = 0; wave4amp = 0;
                }
            } else {
                makeIdleWave(pd);
            }
            fadeincounter = 15;
        } else {
            if (fadeincounter > 0 && fadeoutcounter == 0) {
                makeIdleWave(idleWave);
                if (lastWaveCounter != mWaveCounter) {
                    lastWaveCounter = mWaveCounter;
                    for (int i = 0; i < LINE_COUNT; i++) {
                        float val = Math.abs(pd[i * 8 + 1]);
                        pd[i * 8 + 1] = (val * (15 - fadeincounter)
                                + idleWave[i * 8 + 1] * fadeincounter) / 15f;
                        pd[i * 8 + 5] = (-val * (15 - fadeincounter)
                                + idleWave[i * 8 + 5] * fadeincounter) / 15f;
                    }
                }
                fadeincounter--;
                if (fadeincounter == 0) {
                    fadeoutcounter = 100;
                }
            } else {
                fadeoutcounter = 100;
            }
        }
    }

    private void makeIdleWave(float[] points) {
        float amp1 = (float) Math.sin(0.007f * wave1amp) * 120f * 1024f;
        float amp2 = (float) Math.sin(0.023f * wave2amp) * 80f * 1024f;
        float amp3 = (float) Math.sin(0.011f * wave3amp) * 40f * 1024f;
        float amp4 = (float) Math.sin(0.031f * wave4amp) * 20f * 1024f;
        for (int i = 0; i < LINE_COUNT; i++) {
            float val = (float) (Math.sin(0.013f * (wave1pos + i * 4)) * amp1
                    + Math.sin(0.029f * (wave2pos + i * 4)) * amp2);
            float off = (float) (Math.sin(0.005f * (wave3pos + i * 4)) * amp3
                    + Math.sin(0.017f * (wave4pos + i * 4)) * amp4);
            if (val < 2f && val > -2f) val = 2f;
            points[i * 8 + 1] = val + off;
            points[i * 8 + 5] = -val + off;
        }
        wave1pos++; wave1amp++;
        wave2pos--; wave2amp++;
        wave3pos++; wave3amp++;
        wave4pos++; wave4amp++;
    }

    // ---- line buffer building ----

    void updateLineBuffers() {
        float[] pd = mWave.mPointData;
        for (int i = 0; i < LINE_COUNT; i++) {
            int base = i * 8;
            int out = i * 4;
            mLinePositions[out]     = pd[base];
            mLinePositions[out + 1] = pd[base + 1];
            mLinePositions[out + 2] = pd[base + 4];
            mLinePositions[out + 3] = pd[base + 5];

            mLineTexCoords[out]     = pd[base + 2];
            mLineTexCoords[out + 1] = pd[base + 3];
            mLineTexCoords[out + 2] = pd[base + 6];
            mLineTexCoords[out + 3] = pd[base + 7];
        }
    }

    // ---- projection ----

    void updateProjection() {
        float aspect = (float) mWave.mWidth / (float) mWave.mHeight;
        Mat4.frustumM(mProj, -aspect, aspect, -1f, 1f, 1f, 6000f);
    }

    // ---- per-frame tick ----

    /** Called at end of each frame to advance idle wave counters. */
    void endFrame() {
        wave1pos++; wave1amp++;
        wave2pos--; wave2amp++;
        wave3pos++; wave3amp++;
        wave4pos++; wave4amp++;
    }
}
