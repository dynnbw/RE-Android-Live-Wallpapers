package com.reandroid.wallpaper.cube;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import android.view.MotionEvent;

/**
 * Cube wallpaper scene logic — pure Java.
 * Handles 3D rotation math, wireframe shape data, and touch state.
 */
final class CubeScene {
    static final String PREFS_NAME = "cube";
    static final String KEY_SHAPE = "cube_shape";
    /** 触摸滑动改变视角(默认关:保持原版的时间驱动旋转 + 圆圈标记)。 */
    static final String KEY_TOUCH_ROTATE = "cube_touch_rotate";

    // ---- 触摸旋转参数(仅开关打开时生效)----
    /** 自动旋转角速度(rad/s),与原版 (timeMs - mStartTimeMs) / 1000 等价。 */
    private static final float AUTO_ROTATE_SPEED = 1.0f;
    /** 松手后角速度指数衰减的时间常数(s)。 */
    private static final float INERTIA_DECAY = 3.0f;
    /** 惯性角速度上限(rad/s),滤掉单帧抖动造成的疯狂旋转。 */
    private static final float MAX_FLING_SPEED = 12.0f;
    /** 松手后静止多久接回自动旋转(ms)。 */
    private static final long TOUCH_IDLE_RESUME_MS = 2500L;
    /** 单帧时间上限(s):卡顿或从后台恢复后不让角度跳变。 */
    private static final float MAX_FRAME_DELTA = 0.05f;
    /** 惯性归零阈值(rad/s)。 */
    private static final float FLING_EPSILON = 0.01f;

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

    private boolean mTouchRotateEnabled;
    private boolean mDragging;
    private float mRadiansPerPixel = 0.003f;
    private float mLastTouchX;
    private float mLastTouchY;
    /** 上一次触摸事件的时刻(MotionEvent 时间戳,uptimeMillis 基准)。 */
    private long mLastTouchEventMs;
    /** 渲染帧时刻(uptimeMillis 基准,用于积分;不能与墙钟混用)。 */
    private long mLastFrameMs;
    /** 自动旋转累积角,仅开关打开时使用。 */
    private float mAutoAngleX;
    /**
     * 视角姿态(四元数维护,屏幕空间的外层姿态)。
     * 自动旋转与桌面视差按原版算在内层,它作用在两者之外:单位姿态时渲染结果
     * 与原版逐位一致,拖拽只是额外换了个观察角度。
     */
    private final CubeViewRotation mView = new CubeViewRotation();
    private final float[] mViewMatrix = new float[9];
    private float mFlingPitch;
    private float mFlingYaw;

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
        if (mPluginPrefs == null && mPrefs == null && mContext != null) {
            mPrefs = mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }
        refreshFlags();
    }

    SharedPreferences getPrefs() {
        return mPluginPrefs != null ? mPluginPrefs : mPrefs;
    }

    public void setPluginPrefs(SharedPreferences prefs) {
        mPluginPrefs = prefs;
        refreshFlags();
    }

    private void refreshFlags() {
        SharedPreferences prefs = getPrefs();
        boolean enabled = prefs != null && prefs.getBoolean(KEY_TOUCH_ROTATE, false);
        if (enabled == mTouchRotateEnabled) return;
        if (enabled) {
            // 从当前自动旋转相位无缝接管,开关打开的瞬间不跳变
            mAutoAngleX = (System.currentTimeMillis() - mStartTimeMs) / 1000f;
            mFlingPitch = 0f;
            mFlingYaw = 0f;
        } else {
            // 关闭时反向对齐,回到按时间算的角度也不跳变
            mStartTimeMs = System.currentTimeMillis()
                    - (long) (mAutoAngleX * 1000f);
            resetView();
        }
        mTouchRotateEnabled = enabled;
    }

    /** 从后台恢复:重置时间基准,避免累积出一大段 dt。 */
    void onResume() {
        mStartTimeMs = System.currentTimeMillis();
        mLastFrameMs = 0L;
        mDragging = false;
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
        // 短边拖满一屏 ≈ 180°,两个轴用同一比例,斜向拖拽的手感才均匀
        int span = Math.min(width, height);
        if (span > 0) {
            mRadiansPerPixel = (float) (Math.PI / span);
        }
    }

    void rotateAndProject(long timeMs) {
        float xrot;
        float yrot;
        if (mTouchRotateEnabled) {
            updateTouchRotation();
            xrot = mAutoAngleX;
            yrot = (0.5f - mXOffset) * 2.0f;
        } else {
            xrot = (timeMs - mStartTimeMs) / 1000f;
            yrot = (0.5f - mXOffset) * 2.0f;
        }

        float cosX = (float) Math.cos(xrot);
        float sinX = (float) Math.sin(xrot);
        float cosY = (float) Math.cos(yrot);
        float sinY = (float) Math.sin(yrot);

        // 视角姿态对应的旋转矩阵(单位姿态时整段跳过,渲染与原版逐位一致)
        boolean viewRotated = !mView.isIdentity();
        if (viewRotated) {
            mView.toMatrix(mViewMatrix);
        }

        for (int i = 0; i < mOriginalPoints.length; i++) {
            ThreeDPoint p = mOriginalPoints[i];
            float x = p.x, y = p.y, z = p.z;

            // Rotate around X axis
            float newZ = cosX * z - sinX * y;
            float newY = sinX * z + cosX * y;

            // Rotate around Y axis
            float newX = sinY * newZ + cosY * x;
            newZ = cosY * newZ - sinY * x;

            if (viewRotated) {
                float vx = mViewMatrix[0] * newX + mViewMatrix[1] * newY + mViewMatrix[2] * newZ;
                float vy = mViewMatrix[3] * newX + mViewMatrix[4] * newY + mViewMatrix[5] * newZ;
                float vz = mViewMatrix[6] * newX + mViewMatrix[7] * newY + mViewMatrix[8] * newZ;
                newX = vx;
                newY = vy;
                newZ = vz;
            }

            // Perspective projection (matching original Android Cube live wallpaper)
            float scale = 4.0f - newZ / 400.0f;
            mProjectedX[i] = newX / scale * mScaleSize;
            mProjectedY[i] = newY / scale * mScaleSize;

            mRotatedPoints[i].x = newX;
            mRotatedPoints[i].y = newY;
            mRotatedPoints[i].z = newZ;
        }
    }

    /**
     * 触摸旋转的时间推进:自动旋转 + 松手惯性。
     * 拖拽期间不推进,角度完全跟手。
     */
    private void updateTouchRotation() {
        long now = SystemClock.uptimeMillis();
        float dt = 0f;
        if (mLastFrameMs != 0L) {
            dt = (now - mLastFrameMs) / 1000f;
            if (dt < 0f) dt = 0f;
            if (dt > MAX_FRAME_DELTA) dt = MAX_FRAME_DELTA;
        }
        mLastFrameMs = now;

        // 上一次触摸是多久以前(MotionEvent 时间戳同为 uptimeMillis 基准)
        boolean idle = mLastTouchEventMs == 0L
                || now - mLastTouchEventMs >= TOUCH_IDLE_RESUME_MS;

        if (mDragging) return;

        if (mFlingPitch != 0f || mFlingYaw != 0f) {
            applyViewRotation(mFlingYaw * dt, mFlingPitch * dt);
            float decay = (float) Math.exp(-INERTIA_DECAY * dt);
            mFlingPitch *= decay;
            mFlingYaw *= decay;
            if (Math.abs(mFlingPitch) < FLING_EPSILON) mFlingPitch = 0f;
            if (Math.abs(mFlingYaw) < FLING_EPSILON) mFlingYaw = 0f;
        }

        if (idle) {
            mAutoAngleX += AUTO_ROTATE_SPEED * dt;
        }
    }

    void onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (!mTouchRotateEnabled) {
            if (action == MotionEvent.ACTION_MOVE) {
                mTouchX = event.getX();
                mTouchY = event.getY();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                mTouchX = -1f;
                mTouchY = -1f;
            }
            return;
        }

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                beginDrag(event.getX(), event.getY(), event.getEventTime());
                break;
            case MotionEvent.ACTION_MOVE:
                if (!mDragging) {
                    // launcher 可能吞掉 DOWN(翻页/抽屉),把首个 MOVE 当起点,避免跳变
                    beginDrag(event.getX(), event.getY(), event.getEventTime());
                } else {
                    dragTo(event.getX(), event.getY(), event.getEventTime());
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                endDrag(event.getEventTime());
                break;
            default:
                break;
        }
    }

    private void beginDrag(float x, float y, long eventTimeMs) {
        mDragging = true;
        mLastTouchX = x;
        mLastTouchY = y;
        mLastTouchEventMs = eventTimeMs;
        mFlingPitch = 0f;
        mFlingYaw = 0f;
        mTouchX = x;
        mTouchY = y;
    }

    private void dragTo(float x, float y, long eventTimeMs) {
        float dx = x - mLastTouchX;
        float dy = y - mLastTouchY;
        // 直接操纵:指尖按住的那一面跟着手指走
        float dYaw = dx * mRadiansPerPixel;
        float dPitch = dy * mRadiansPerPixel;
        applyViewRotation(dYaw, dPitch);

        long elapsedMs = eventTimeMs - mLastTouchEventMs;
        if (elapsedMs > 0) {
            mFlingYaw = clampFling(dYaw * 1000f / elapsedMs);
            mFlingPitch = clampFling(dPitch * 1000f / elapsedMs);
        }

        mLastTouchX = x;
        mLastTouchY = y;
        mLastTouchEventMs = eventTimeMs;
        mTouchX = x;
        mTouchY = y;
    }

    private void endDrag(long eventTimeMs) {
        mDragging = false;
        mLastTouchEventMs = eventTimeMs;
        mTouchX = -1f;
        mTouchY = -1f;
    }

    private static float clampFling(float speed) {
        if (speed > MAX_FLING_SPEED) return MAX_FLING_SPEED;
        if (speed < -MAX_FLING_SPEED) return -MAX_FLING_SPEED;
        return speed;
    }

    private void applyViewRotation(float yaw, float pitch) {
        mView.apply(yaw, pitch);
    }

    private void resetView() {
        mView.reset();
    }
}
