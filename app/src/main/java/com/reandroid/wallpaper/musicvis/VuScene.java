package com.reandroid.wallpaper.musicvis;

import android.content.Context;
import com.reandroid.utils.Mat4;

/**
 * Pure-logic VU meter scene for vis4.
 * Contains needle physics, projection computation.
 * Has ZERO GL imports.
 */
public final class VuScene extends AudioVisBase {

    // Needle physics
    int mNeedlePos = 0;
    int mNeedleSpeed = 0;
    int mNeedleMass = 10;
    int mSpringForceAtOrigin = 200;

    float mAngle = 0f;
    int mPeak = 0;

    // Matrices
    final float[] mProj = new float[16];
    final float[] mModel = new float[16];
    final float[] mMvp = new float[16];

    VuScene(int width, int height, Context context) {
        super(width, height, context);
    }

    // ---- AudioVisBase ----

    @Override
    protected int getAudioType() {
        return AudioCapture.TYPE_PCM;
    }

    @Override
    protected int getAudioCaptureSize() {
        return 1024;
    }

    @Override
    public void start() {
        if (mAudioCapture == null) {
            mAudioCapture = new AudioCapture(AudioCapture.TYPE_PCM, 1024);
        }
        mAudioCapture.start();
    }

    @Override
    public void stop() {
        if (mAudioCapture != null) mAudioCapture.stop();
    }

    // ---- needle update ----

    void updateNeedle() {
        int len = 0;
        if (mAudioCapture != null) {
            mVizData = mAudioCapture.getFormattedData(512, 1);
            len = mVizData.length;
        }

        int volt = 0;
        if (len > 0) {
            for (int i = 0; i < len; i++) {
                int val = mVizData[i];
                if (val < 0) val = -val;
                volt += val;
            }
            volt = volt / len;
        }

        applyNeedlePhysics(volt);
    }

    /**
     * Process externally-supplied audio data (used by ManyScene).
     * @param data  raw PCM data
     * @param len   number of samples
     */
    void updateNeedleFromArray(int[] data, int len) {
        int volt = 0;
        if (len > 0) {
            for (int i = 0; i < len; i++) {
                int val = data[i];
                if (val < 0) val = -val;
                volt += val;
            }
            volt = volt / len;
        }
        applyNeedlePhysics(volt);
    }

    private void applyNeedlePhysics(int volt) {
        int netforce = volt - mNeedleSpeed * 3 - (mNeedlePos + mSpringForceAtOrigin);
        int acceleration = netforce / mNeedleMass;
        mNeedleSpeed += acceleration;
        mNeedlePos += mNeedleSpeed;
        if (mNeedlePos < 0) {
            mNeedlePos = 0;
            mNeedleSpeed = 0;
        } else if (mNeedlePos > 32767) {
            if (mNeedlePos > 33333) {
                mPeak = 10;
            }
            mNeedlePos = 32767;
            mNeedleSpeed = 0;
        }
        if (mPeak > 0) mPeak--;

        mAngle = 131f - (mNeedlePos / 410f);
    }

    // ---- projection ----

    void updateProjection() {
        float aspect;
        if (mWidth > mHeight) {
            aspect = (float) mWidth / (float) mHeight;
            Mat4.orthoM(mProj, -aspect, aspect, -1f, 1f, -1f, 1f);
        } else {
            aspect = (float) mHeight / (float) mWidth;
            Mat4.orthoM(mProj, -1f, 1f, -aspect, aspect, -1f, 1f);
        }
    }
}
