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

    // Composed sub-scenes — reuse vis2 (PCM) and vis3 (FFT) WaveScene directly
    final WaveScene mWave;      // PCM waveform
    final WaveScene mWaveFFT;   // FFT spectrum (reuses vis3 logic)
    final VuScene mNeedle;

    // Audio — single TYPE_BOTH capture produces PCM + FFT simultaneously
    private AudioCapture mAudioCapture;
    int[] mVizData = new int[1024];

    // Wave data: PCM
    final float[] mLinePositions = new float[LINE_COUNT * 4];
    final float[] mLineTexCoords = new float[LINE_COUNT * 4];
    // Wave data: FFT (reads from mWaveFFT.mPositions/mTexCoords)

    // Wave mode: 0=PCM, 1=FFT, 2=Mixed (FFT left 240°, PCM front 0° + right 120°)
    int mWaveMode = 0;

    // Needle state (read from mNeedle fields)

    // ManyScene-specific state
    float mRotate = 0f;
    float mTilt = 0f;
    float mFloorY = -200f; // reflection floor Y position
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
    private int mFrameCount;
    private SharedPreferences mPluginPrefs;

    // HSL recolor state — synced from vis2 (PCM) and vis3 (FFT) prefs
    boolean mRecolorPCM, mRecolorFFT;
    boolean mRecolorDynPCM, mRecolorDynFFT; // audio-driven hue
    float mHuePCM, mHueFFT;
    float mSatPCM = 1f, mSatFFT = 1f;
    float mBriPCM = 1f, mBriFFT = 1f;
    int mFftSize = 512; // synced from vis3 prefs
    final float[] mAdjustData = new float[LINE_COUNT * 2 * 3 * 2]; // PCM + FFT: 2 verts × 3 HSL × 256 lines × 2 types
    // Texture index: 0=fire (PCM vis2), 1=ice (FFT vis3)
    int mLineTexPCM, mLineTexFFT;

    final float[] mBgColor = new float[3];
    final float[] mProj = new float[16];

    ManyScene(int width, int height, Context context) {
        mWave = new WaveScene(width, height, WaveScene.Mode.PCM, context);
        mWaveFFT = new WaveScene(width, height, WaveScene.Mode.FFT, context);
        mNeedle = new VuScene(width, height, context);
        initPointData();
        initPointDataSubset(mWaveFFT.mPointData);
    }

    // ---- lifecycle ----

    void start() {
        if (mAudioCapture == null) {
            mAudioCapture = new AudioCapture(AudioCapture.TYPE_BOTH, 1024);
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
        initPointDataSubset(mWave.mPointData);
    }

    /** Initialize X/S/T coords for the first LINE_COUNT entries. */
    private static void initPointDataSubset(float[] pd) {
        int half = LINE_COUNT / 2;
        for (int i = 0; i < LINE_COUNT; i++) {
            pd[i * 8]     = i - half;
            pd[i * 8 + 2] = 0f;
            pd[i * 8 + 3] = 0f;
            pd[i * 8 + 4] = i - half;
            pd[i * 8 + 6] = 1.0f;
            pd[i * 8 + 7] = 0f;
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
        }
        mWaveMode = Integer.parseInt(p.getString("musicvis_wave_mode", "0"));
        // Sync static HSL + FFT config from vis2/vis3 prefs (only on first call — dynamic hue manages itself)
        if (!mHasPrefInit) {
            syncVis2Prefs();
            syncVis3Prefs();
            mHasPrefInit = true;
        }
        // Re-sync periodically in case user changed vis2/vis3 settings at runtime
        if (mFrameCount++ % 120 == 0) {
            syncVis2Prefs();
            syncVis3Prefs();
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

    private void syncVis2Prefs() {
        SharedPreferences p2 = getPluginPrefs("vis2");
        if (p2 == null) return;
        mWave.readPrefs(p2);
        mRecolorPCM = mWave.mRecolorEnabled;
        mRecolorDynPCM = mWave.mRecolorDynamic;
        if (!mRecolorDynPCM) mHuePCM = mWave.mHue; // keep dynamic hue if running
        mSatPCM = mWave.mSaturation;
        mBriPCM = mWave.mBrightness;
    }

    private void syncVis3Prefs() {
        SharedPreferences p3 = getPluginPrefs("vis3");
        if (p3 == null) return;
        mWaveFFT.readPrefs(p3);
        mRecolorFFT = mWaveFFT.mRecolorEnabled;
        mRecolorDynFFT = mWaveFFT.mRecolorDynamic;
        if (!mRecolorDynFFT) mHueFFT = mWaveFFT.mHue;
        mSatFFT = mWaveFFT.mSaturation;
        mBriFFT = mWaveFFT.mBrightness;
        mFftSize = mWaveFFT.mFftSize;
    }

    /** Try plugin pref name first, then legacy name as fallback. */
    private SharedPreferences getPluginPrefs(String pluginId) {
        Context ctx = mWave.mContext;
        SharedPreferences p = ctx.getSharedPreferences("plugin_" + pluginId, Context.MODE_PRIVATE);
        if (p.getAll().isEmpty()) {
            String legacy = "musicvis" + pluginId + "_prefs";
            SharedPreferences lp = ctx.getSharedPreferences(legacy, Context.MODE_PRIVATE);
            if (!lp.getAll().isEmpty()) return lp;
        }
        return p;
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

    // ---- FFT wave data (reuses WaveScene FFT, same as vis3) ----

    void updateWaveDataFFT() {
        if (mAudioCapture == null) return;
        int[] fft = mAudioCapture.getFftFormattedData();
        if (fft == null || fft.length == 0) return;
        // mWaveFFT never started — init analyzer lazily
        if (mWaveFFT.mAnalyzer == null) mWaveFFT.mAnalyzer = new int[512];
        // Limit bins to synced FFT size
        int maxBins = mFftSize / 2;
        int len = Math.min(fft.length / 2, maxBins);
        if (len > mWaveFFT.mAnalyzer.length) len = mWaveFFT.mAnalyzer.length;

        // FFT bin processing (matching Visualization3RS algorithm)
        for (int i = 1; i < len - 1; i++) {
            int val1 = fft[i * 2];
            int val2 = fft[i * 2 + 1];
            int val = val1 * val1 + val2 * val2;
            int newval = val * (i / 16 + 1);
            int oldval = mWaveFFT.mAnalyzer[i];
            if (newval >= oldval - 800) {
            } else {
                newval = oldval - 800;
            }
            mWaveFFT.mAnalyzer[i] = newval;
        }

        // Map bins to LINE_COUNT bars
        int srcidx = 0;
        int cnt = 0;
        float[] pd = mWaveFFT.mPointData;
        for (int i = 0; i < LINE_COUNT; i++) {
            float val = mWaveFFT.mAnalyzer[srcidx] * 64f / 8f; // gain to match PCM amplitude range
            if (val < 1f && val > -1f) val = 1f;
            pd[i * 8 + 1] = val;
            pd[i * 8 + 5] = -val;
            cnt += len;
            if (cnt > LINE_COUNT) { srcidx++; cnt -= LINE_COUNT; }
        }
    }

    // ---- line buffer building ----

    void updateLineBuffers() {
        // PCM
        float[] pd = mWave.mPointData;
        for (int i = 0; i < LINE_COUNT; i++) {
            int base = i * 8, out = i * 4;
            mLinePositions[out]     = pd[base];
            mLinePositions[out + 1] = pd[base + 1];
            mLinePositions[out + 2] = pd[base + 4];
            mLinePositions[out + 3] = pd[base + 5];
            mLineTexCoords[out]     = pd[base + 2];
            mLineTexCoords[out + 1] = pd[base + 3];
            mLineTexCoords[out + 2] = pd[base + 6];
            mLineTexCoords[out + 3] = pd[base + 7];
        }
        // FFT — convert point data to position/texcoord arrays
        mWaveFFT.updateBuffers();
    }

    /** Build HSL adjust buffer + advance dynamic hue for both PCM and FFT. */
    void updateAdjustBuffer() {
        // Dynamic hue: compute average amplitude and shift hue (matching WaveScene.updateDynamicHue)
        if (mRecolorPCM && mRecolorDynPCM) updateDynamicHue(false); // PCM → mWave.mPointData
        if (mRecolorFFT && mRecolorDynFFT) updateDynamicHue(true);  // FFT → mWaveFFT.mPointData
        // PCM section
        float h = mRecolorPCM ? mHuePCM : -1f;
        for (int i = 0; i < LINE_COUNT * 2; i++) {
            int b = i * 3;
            mAdjustData[b] = h; mAdjustData[b + 1] = mSatPCM; mAdjustData[b + 2] = mBriPCM;
        }
        // FFT section
        int off = LINE_COUNT * 2 * 3;
        h = mRecolorFFT ? mHueFFT : -1f;
        for (int i = 0; i < LINE_COUNT * 2; i++) {
            int b = off + i * 3;
            mAdjustData[b] = h; mAdjustData[b + 1] = mSatFFT; mAdjustData[b + 2] = mBriFFT;
        }
    }

    private void updateDynamicHue(boolean isFFT) {
        float[] pd = isFFT ? mWaveFFT.mPointData : mWave.mPointData;
        float sum = 0f;
        for (int i = 0; i < LINE_COUNT; i++) {
            sum += Math.abs(pd[i * 8 + 1]);
        }
        float avg = sum / LINE_COUNT;
        float norm = Math.min(1f, avg / 800f);
        if (isFFT) mHueFFT = (mHueFFT + norm * 0.03f) % 1f;
        else       mHuePCM = (mHuePCM + norm * 0.03f) % 1f;
    }

    /** Propagate resize to WaveScene instances (FFT bin mapping needs current width). */
    void resizeWaves(int width, int height) {
        mWave.mWidth = width;
        mWave.mHeight = height;
        mWaveFFT.mWidth = width;
        mWaveFFT.mHeight = height;
    }

    // ---- projection ----

    void updateProjection() {
        float aspect = (float) mWave.mWidth / (float) mWave.mHeight;
        // Flip X and Y to match RenderScript→GL coordinate conversion
        Mat4.frustumM(mProj, aspect, -aspect, 1f, -1f, 1f, 6000f);
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
