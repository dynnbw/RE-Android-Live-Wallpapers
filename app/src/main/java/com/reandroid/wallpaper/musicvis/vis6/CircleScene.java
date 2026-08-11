package com.reandroid.wallpaper.musicvis.vis6;

import android.content.Context;
import com.reandroid.wallpaper.musicvis.AudioCapture;
import com.reandroid.wallpaper.musicvis.AudioVisBase;
import com.reandroid.utils.Mat4;
import android.content.SharedPreferences;
import android.graphics.Color;

/**
 * Pure-logic circle/ring scene for vis6.
 * Contains FFT audio processing, ring vertex generation, and MVP computation.
 * Has ZERO GL imports.
 */
final class CircleScene extends AudioVisBase {
    static final int RING_SEGMENTS = 128;
    static final int STRIP_VERTS = (RING_SEGMENTS + 1) * 2;
    static final float TWO_PI = (float) (Math.PI * 2.0);
    private static final int DEFAULT_RING_COUNT = 16;
    private static final float HALF_THICKNESS_SCALE = 0.002f;

    // Ring data — GL renderer reads these
    int mRingCount;
    float mHalfThickness;
    float[][] mRingVertices;
    float[][] mRingAdjust;
    float[] mRingAmps;
    int[] mBinStart, mBinEnd;

    // Matrices
    final float[] mProj = new float[16];
    final float[] mMvp = new float[16];

    // State
    float mRotation;

    CircleScene(int width, int height, Context context) {
        super(width, height, context);
        mRingCount = DEFAULT_RING_COUNT;
        mHalfThickness = 8 * HALF_THICKNESS_SCALE;
        allocateRingData();
    }

    private void allocateRingData() {
        mRingVertices = new float[mRingCount][STRIP_VERTS * 2];
        mRingAdjust = new float[mRingCount][STRIP_VERTS * 3];
        mRingAmps = new float[mRingCount];
        mBinStart = new int[mRingCount];
        mBinEnd = new int[mRingCount];
        final int binMin = 2;
        final int binMax = 120;
        for (int i = 0; i < mRingCount; i++) {
            double t0 = (double) i / mRingCount;
            double t1 = (double) (i + 1) / mRingCount;
            mBinStart[i] = (int) (binMin * Math.pow((double) binMax / binMin, t0));
            mBinEnd[i]   = (int) (binMin * Math.pow((double) binMax / binMin, t1));
        }
    }

    // ---- AudioVisBase ----

    @Override
    protected int getAudioType() {
        return AudioCapture.TYPE_FFT;
    }

    @Override
    protected int getAudioCaptureSize() {
        return 512;
    }

    @Override
    public void start() {
        if (mAudioCapture == null) {
            mAudioCapture = new AudioCapture(AudioCapture.TYPE_FFT, 512);
        }
        mAudioCapture.start();
        SharedPreferences p;
        if (mPluginPrefs != null) {
            p = mPluginPrefs;
        } else {
            p = mContext.getSharedPreferences("musicvis6_prefs", Context.MODE_PRIVATE);
        }
        readPrefs(p);
    }

    @Override
    public void stop() {
        if (mAudioCapture != null) {
            mAudioCapture.stop();
        }
    }

    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        mRotation = xOffset * 90f;
    }

    @Override
    protected void readPrefs(SharedPreferences p) {
        mRecolorEnabled = p.getBoolean("musicvis_recolor", false);
        mRecolorDynamic = "dynamic".equals(p.getString("musicvis_recolor_mode", "static"));
        if (!mRecolorDynamic) mHue = safeGetInt(p, "musicvis_hue", 0) / 255f;
        mSaturation = safeGetInt(p, "musicvis_saturation", 255) / 255f;
        mBrightness = safeGetInt(p, "musicvis_brightness", 255) / 255f;
        String hex = p.getString("musicvis_bg_color", "#000000");
        try {
            int c = Color.parseColor(hex);
            mBgColor[0] = Color.red(c) / 255f;
            mBgColor[1] = Color.green(c) / 255f;
            mBgColor[2] = Color.blue(c) / 255f;
        } catch (Exception e) {
            mBgColor[0] = mBgColor[1] = mBgColor[2] = 0f;
        }

        int newRingCount = safeGetInt(p, "circle_ring_count", DEFAULT_RING_COUNT);
        float newHalfThickness = safeGetInt(p, "circle_line_width", 8) * HALF_THICKNESS_SCALE;
        if (newRingCount != mRingCount) {
            mRingCount = newRingCount;
            allocateRingData();
        }
        mHalfThickness = newHalfThickness;
    }

    // ---- audio processing ----

    void updateAudio() {
        if (mAudioCapture == null) return;
        mVizData = mAudioCapture.getFormattedData(1, 1);
        int len = mVizData.length / 2;
        if (len == 0) return;

        for (int r = 0; r < mRingCount; r++) {
            int start = mBinStart[r];
            int end = Math.min(mBinEnd[r], len);
            float sum = 0;
            int count = 0;
            for (int b = start; b < end; b++) {
                int v1 = mVizData[b * 2], v2 = mVizData[b * 2 + 1];
                sum += (float) Math.sqrt(v1 * v1 + v2 * v2);
                count++;
            }
            mRingAmps[r] = count > 0 ? Math.min(1f, sum / count / 40f) : 0f;
        }

        if (mRecolorDynamic && mRecolorEnabled) {
            float avg = 0;
            for (int r = 0; r < mRingCount; r++) avg += mRingAmps[r];
            avg /= mRingCount;
            mHue = (mHue + avg * 0.02f) % 1f;
        }
    }

    // ---- ring vertex building ----

    void updateRingVertices() {
        for (int i = 0; i < mRingCount; i++) {
            float baseR = 0.15f + 0.85f * i / Math.max(1, mRingCount - 1f);
            float amp = mRingAmps[i] * 0.15f;
            for (int j = 0; j <= RING_SEGMENTS; j++) {
                float a = TWO_PI * (j % RING_SEGMENTS) / RING_SEGMENTS;
                float r = baseR + amp * (float) Math.sin(a * 5 + i);
                float cos = (float) Math.cos(a);
                float sin = (float) Math.sin(a);
                float ir = r - mHalfThickness;
                float or = r + mHalfThickness;
                int ip = j * 4;
                int op = j * 4 + 2;
                mRingVertices[i][ip]     = cos * ir;
                mRingVertices[i][ip + 1] = sin * ir;
                mRingVertices[i][op]     = cos * or;
                mRingVertices[i][op + 1] = sin * or;
            }
        }
    }

    // ---- MVP ----

    void updateMvp() {
        float aspect = (float) mWidth / mHeight;
        float w = 1.2f, h = 1.2f;
        if (aspect >= 1f) w *= aspect; else h /= aspect;
        Mat4.orthoM(mProj, -w, w, -h, h, -1, 1);
        Mat4.setIdentityM(mMvp);
        Mat4.rotateM(mMvp, mRotation, 0, 0, 1);
        float[] tmp = new float[16];
        Mat4.multiplyMM(tmp, mProj, mMvp);
        System.arraycopy(tmp, 0, mMvp, 0, 16);
    }
}
