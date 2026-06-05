package com.reandroid.wallpaper.cube;

import android.content.Context;
import android.content.SharedPreferences;

import android.view.MotionEvent;

/**
 * Cube wallpaper scene logic — pure Java.
 * Handles 3D rotation math, wireframe shape data, and touch state.
 */
final class CubeScene {
    static final String PREFS_NAME = "cube";
    static final String KEY_SHAPE = "cube_shape";

    private final Context mContext;
    private SharedPreferences mPrefs;
    private SharedPreferences mPluginPrefs;

    ThreeDPoint[] mOriginalPoints;
    ThreeDPoint[] mRotatedPoints;
    ThreeDLine[] mLines;
    float[] mProjectedX;
    float[] mProjectedY;

    float mXOffset = 0.5f;
    float mScaleSize = 1.0f;
    long mStartTimeMs;

    float mTouchX = -1f;
    float mTouchY = -1f;

    int mScreenWidth;
    int mScreenHeight;

    String mShapeName;

    static class ThreeDPoint {
        float x, y, z;
    }

    static class ThreeDLine {
        int startPoint;
        int endPoint;
    }

    CubeScene(Context context) {
        mContext = context;
        mStartTimeMs = System.currentTimeMillis();
    }

    void ensurePrefs() {
        if (mPluginPrefs != null) return;
        if (mPrefs == null && mContext != null) {
            mPrefs = mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }
    }

    SharedPreferences getPrefs() {
        return mPluginPrefs != null ? mPluginPrefs : mPrefs;
    }

    public void setPluginPrefs(SharedPreferences prefs) {
        mPluginPrefs = prefs;
    }

    void loadShape(String shapeName) {
        if (shapeName == null || shapeName.equals(mShapeName)) return;
        mShapeName = shapeName;

        String[] p = com.reandroid.utils.AssetLoader.readText(mContext,
                "cube/data/cube_" + shapeName + "_points.csv").trim().split("\\s+");
        String[] l = com.reandroid.utils.AssetLoader.readText(mContext,
                "cube/data/cube_" + shapeName + "_lines.csv").trim().split("\\s+");

        int numPoints = p.length / 3;
        mOriginalPoints = new ThreeDPoint[numPoints];
        mRotatedPoints = new ThreeDPoint[numPoints];
        mProjectedX = new float[numPoints];
        mProjectedY = new float[numPoints];

        for (int i = 0; i < numPoints; i++) {
            mOriginalPoints[i] = new ThreeDPoint();
            mRotatedPoints[i] = new ThreeDPoint();
            mOriginalPoints[i].x = Float.parseFloat(p[i * 3]);
            mOriginalPoints[i].y = Float.parseFloat(p[i * 3 + 1]);
            mOriginalPoints[i].z = Float.parseFloat(p[i * 3 + 2]);
        }

        int numLines = l.length / 2;
        mLines = new ThreeDLine[numLines];
        for (int i = 0; i < numLines; i++) {
            mLines[i] = new ThreeDLine();
            mLines[i].startPoint = Integer.parseInt(l[i * 2]);
            mLines[i].endPoint = Integer.parseInt(l[i * 2 + 1]);
        }
    }

    void setOffset(float xOffset) {
        mXOffset = xOffset;
    }

    void setScreenSize(int width, int height) {
        mScreenWidth = width;
        mScreenHeight = height;
    }

    void rotateAndProject(long timeMs) {
        float xrot = (timeMs - mStartTimeMs) / 1000f;
        float yrot = (0.5f - mXOffset) * 2.0f;

        float cosX = (float) Math.cos(xrot);
        float sinX = (float) Math.sin(xrot);
        float cosY = (float) Math.cos(yrot);
        float sinY = (float) Math.sin(yrot);

        for (int i = 0; i < mOriginalPoints.length; i++) {
            ThreeDPoint p = mOriginalPoints[i];
            float x = p.x, y = p.y, z = p.z;

            // Rotate around X axis
            float newZ = cosX * z - sinX * y;
            float newY = sinX * z + cosX * y;

            // Rotate around Y axis
            float newX = sinY * newZ + cosY * x;
            newZ = cosY * newZ - sinY * x;

            // Perspective projection (matching original Android Cube live wallpaper)
            float scale = 4.0f - newZ / 400.0f;
            mProjectedX[i] = newX / scale * mScaleSize;
            mProjectedY[i] = newY / scale * mScaleSize;

            mRotatedPoints[i].x = newX;
            mRotatedPoints[i].y = newY;
            mRotatedPoints[i].z = newZ;
        }
    }

    void onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_MOVE) {
            mTouchX = event.getX();
            mTouchY = event.getY();
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            mTouchX = -1f;
            mTouchY = -1f;
        }
    }
}
