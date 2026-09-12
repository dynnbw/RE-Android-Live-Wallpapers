package com.reandroid.wallpaper.droid;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.util.Log;
import android.view.MotionEvent;

import com.reandroid.gles.GLESScene;
import com.reandroid.utils.AssetLoader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;

/**
 * 坠落的安卓机器人 —— GLES 2.0 渲染层。
 * 物理与装配在 {@link DroidScene}(纯逻辑);本类负责精灵绘制与传感器输入。
 */
public class DroidGL extends GLESScene implements SensorEventListener {

    private static final String TAG = "DroidGL";

    private static final int TEX_BODY = 0;
    private static final int TEX_LEFT_ARM = 1;
    private static final int TEX_RIGHT_ARM = 2;
    private static final int TEX_LEFT_LEG = 3;
    private static final int TEX_RIGHT_LEG = 4;
    private static final int TEX_COUNT = 5;

    private static final String[] TEX_PATHS = {
            "droid/drawable/droid_body.png",
            "droid/drawable/droid_left_arm.png",
            "droid/drawable/droid_right_arm.png",
            "droid/drawable/droid_left_leg.png",
            "droid/drawable/droid_right_leg.png"
    };

    /** 原版默认背景色 rgb(48, 88, 124)。 */
    private static final float BG_R = 48.0f / 255.0f;
    private static final float BG_G = 88.0f / 255.0f;
    private static final float BG_B = 124.0f / 255.0f;

    private static final long PREF_POLL_INTERVAL_MS = 1000L;

    /** 性能采样:每 5 秒输出一次帧内耗时构成(定位卡顿用,稳定后可关闭)。 */
    private static final boolean PERF_LOG = true;
    private static final long PERF_LOG_INTERVAL_MS = 5000L;
    private long mPerfLogMs;
    private int mPerfFrames;
    private double mPerfPhysicsMs;
    private double mPerfDrawMs;
    private long mPerfMaxFrameMs;

    private final Context mContext;
    private final DroidScene mScene;

    private FloatBuffer mVertexBuffer;
    private FloatBuffer mTexBuffer;

    private final float[] mProjection = new float[16];
    private final float[] mModel = new float[16];
    private final float[] mMvp = new float[16];

    private int mProgram;
    private int mPositionHandle = -1;
    private int mTexCoordHandle = -1;
    private int mMvpHandle = -1;
    private int mTintHandle = -1;
    private int mAlphaHandle = -1;
    private int mSamplerHandle = -1;

    private final int[] mTextures = new int[TEX_COUNT];

    private boolean mInitialized;
    private boolean mGlReady;

    // 设置项
    private SharedPreferences mPrefs;
    private long mLastPrefPollMs = Long.MIN_VALUE;
    private float mTintR = 151.0f / 255.0f;
    private float mTintG = 192.0f / 255.0f;
    private float mTintB = 61.0f / 255.0f;
    private double mShakeFactor;
    private double mLightFactor;

    // 传感器
    private SensorManager mSensorManager;
    private Sensor mLightSensor;
    private boolean mSensorsRegistered;
    private boolean mLightRegistered;
    private final float[] mAccel = new float[3];
    private final float[] mMag = new float[3];
    private boolean mHasAccel;
    private boolean mHasMag;
    private float mLastAccelX;
    private float mLastAccelY;
    private boolean mHasLastAccel;
    private final float[] mRotationMatrix = new float[9];
    private final float[] mOrientation = new float[3];

    public DroidGL(Context context, int width, int height) {
        super(width, height);
        mContext = context != null ? context.getApplicationContext() : null;
        mScene = new DroidScene(width, height);
    }

    @Override
    protected void onCreate() {
        if (mInitialized) {
            return;
        }
        mInitialized = true;
        mVertexBuffer = createFloatBuffer(new float[] {
                -0.5f, -0.5f,
                0.5f, -0.5f,
                -0.5f, 0.5f,
                0.5f, 0.5f
        });
        // 纹理坐标:GL 的 v=0 对应位图首行(即图像顶部),而本场景投影 y 轴向下
        // (ortho 的 bottom=height, top=0),因此屏幕上方顶点必须取 v=0,否则贴图上下颠倒。
        mTexBuffer = createFloatBuffer(new float[] {
                0.0f, 0.0f,
                1.0f, 0.0f,
                0.0f, 1.0f,
                1.0f, 1.0f
        });
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        mScene.resize(width, height);
        Matrix.orthoM(mProjection, 0, 0.0f, width, height, 0.0f, -1.0f, 1.0f);
    }

    @Override
    public void start() {
        registerSensors();
    }

    @Override
    public void stop() {
        unregisterSensors();
    }

    @Override
    public void release() {
        unregisterSensors();
        for (int i = 0; i < TEX_COUNT; i++) {
            if (mTextures[i] != 0) {
                GLES20.glDeleteTextures(1, mTextures, i);
                mTextures[i] = 0;
            }
        }
        if (mProgram != 0) {
            GLES20.glDeleteProgram(mProgram);
            mProgram = 0;
        }
        mGlReady = false;
        mInitialized = false;
    }

    public void setPluginPrefs(SharedPreferences prefs) {
        mPrefs = prefs;
        mLastPrefPollMs = Long.MIN_VALUE;
        mScene.setPluginPrefs(prefs);
        readPrefs();
    }

    @Override
    public void onTouchEvent(MotionEvent event) {
        // 原版对每个触摸事件都施加径向冲量(按下 / 拖动都会推动机器人)
        double strength = mScene.touchFactor();
        if (strength <= 0.0) {
            return;
        }
        double worldX = event.getX() - mWidth * 0.5;
        double worldY = mHeight * 0.5 - event.getY();
        mScene.applyRadialImpulse(worldX, worldY, strength);
    }

    @Override
    public void drawFrame(long timeMs) {
        if (!mInitialized) {
            return;
        }
        if (!mGlReady) {
            initGlResources();
            if (!mGlReady) {
                return;
            }
        }

        pollPrefs(timeMs);

        long physicsStart = System.nanoTime();
        mScene.update(timeMs);
        long drawStart = System.nanoTime();

        GLES20.glClearColor(BG_R, BG_G, BG_B, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        drawDroids();
        long drawEnd = System.nanoTime();

        logPerf(timeMs, physicsStart, drawStart, drawEnd);
    }

    private void logPerf(long timeMs, long physicsStart, long drawStart, long drawEnd) {
        if (!PERF_LOG) {
            return;
        }
        double physicsMs = (drawStart - physicsStart) / 1e6;
        double drawMs = (drawEnd - drawStart) / 1e6;
        mPerfFrames++;
        mPerfPhysicsMs += physicsMs;
        mPerfDrawMs += drawMs;
        long frameMs = (drawEnd - physicsStart) / 1_000_000L;
        if (frameMs > mPerfMaxFrameMs) {
            mPerfMaxFrameMs = frameMs;
        }
        if (mPerfLogMs == 0L) {
            mPerfLogMs = timeMs;
            return;
        }
        if (timeMs - mPerfLogMs < PERF_LOG_INTERVAL_MS) {
            return;
        }
        // 帧外耗时(swapBuffers / 驱动 / 引擎节拍)需与 ProxyEngineRenderer 的 FrameStats 对照
        if (mPerfFrames > 0) {
            Log.i(TAG, String.format(
                    "perf: fps=%.1f physics=%.2fms draw=%.2fms inFrameMax=%dms droids=%d",
                    mPerfFrames * 1000.0 / (timeMs - mPerfLogMs),
                    mPerfPhysicsMs / mPerfFrames,
                    mPerfDrawMs / mPerfFrames,
                    mPerfMaxFrameMs,
                    mScene.droidCount()));
        }
        mPerfLogMs = timeMs;
        mPerfFrames = 0;
        mPerfPhysicsMs = 0.0;
        mPerfDrawMs = 0.0;
        mPerfMaxFrameMs = 0L;
    }

    // --- 绘制 ---

    private void drawDroids() {
        List<DroidScene.Droid> droids = mScene.droids();
        if (droids.isEmpty() || mProgram == 0) {
            return;
        }
        double scale = mScene.sizeScale();
        float bodyW = (float) (DroidScene.SRC_BODY_W * scale);
        float bodyH = (float) (DroidScene.SRC_BODY_H * scale);
        float armW = (float) (DroidScene.SRC_ARM_W * scale);
        float armH = (float) (DroidScene.SRC_ARM_H * scale);
        float legW = (float) (DroidScene.SRC_LEG_W * scale);
        float legH = (float) (DroidScene.SRC_LEG_H * scale);

        // 程序、常量 uniform、顶点属性每帧只设置一次:
        // 逐精灵的 GL 状态切换在低端驱动(尤其 ANGLE/Vulkan 转译层)上开销显著。
        GLES20.glUseProgram(mProgram);
        GLES20.glUniform3f(mTintHandle, mTintR, mTintG, mTintB);
        GLES20.glUniform1f(mAlphaHandle, 1.0f);
        GLES20.glUniform1i(mSamplerHandle, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);

        mVertexBuffer.position(0);
        GLES20.glVertexAttribPointer(mPositionHandle, 2, GLES20.GL_FLOAT, false, 0, mVertexBuffer);
        GLES20.glEnableVertexAttribArray(mPositionHandle);
        mTexBuffer.position(0);
        GLES20.glVertexAttribPointer(mTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, mTexBuffer);
        GLES20.glEnableVertexAttribArray(mTexCoordHandle);

        // 绘制顺序必须与原版一致(原版 draw() 的回调顺序为
        // LeftLeg → RightLeg → Body → LeftArm → RightArm):
        // 腿在躯干之下、手臂盖在躯干之上。
        double alpha = mScene.interpolationAlpha();
        int boundTexture = 0;
        for (int i = 0; i < droids.size(); i++) {
            DroidScene.Droid droid = droids.get(i);
            boundTexture = drawPart(TEX_LEFT_LEG, droid.leftLeg, legW, legH, boundTexture, alpha);
            boundTexture = drawPart(TEX_RIGHT_LEG, droid.rightLeg, legW, legH, boundTexture, alpha);
            boundTexture = drawPart(TEX_BODY, droid.body, bodyW, bodyH, boundTexture, alpha);
            boundTexture = drawPart(TEX_LEFT_ARM, droid.leftArm, armW, armH, boundTexture, alpha);
            boundTexture = drawPart(TEX_RIGHT_ARM, droid.rightArm, armW, armH, boundTexture, alpha);
        }

        GLES20.glDisableVertexAttribArray(mPositionHandle);
        GLES20.glDisableVertexAttribArray(mTexCoordHandle);
    }

    /** 返回当前绑定的纹理,避免重复绑定。 */
    private int drawPart(int textureIndex, com.reandroid.wallpaper.droid.physics.Body body,
            float width, float height, int boundTexture, double alpha) {
        int texture = mTextures[textureIndex];
        if (body == null || texture == 0) {
            return boundTexture;
        }
        // 世界坐标 → 屏幕坐标(y 轴翻转),旋转角同样取反;
        // 位置 / 角度按累加器比例在上一物理步与当前物理步之间插值(30Hz 物理 → 平滑画面)
        float screenX = (float) body.renderX(alpha) + mWidth * 0.5f;
        float screenY = mHeight * 0.5f - (float) body.renderY(alpha);
        float angleDeg = (float) Math.toDegrees(body.renderAngle(alpha));

        Matrix.setIdentityM(mModel, 0);
        Matrix.translateM(mModel, 0, screenX, screenY, 0.0f);
        Matrix.rotateM(mModel, 0, -angleDeg, 0.0f, 0.0f, 1.0f);
        Matrix.scaleM(mModel, 0, width, height, 1.0f);
        Matrix.multiplyMM(mMvp, 0, mProjection, 0, mModel, 0);
        GLES20.glUniformMatrix4fv(mMvpHandle, 1, false, mMvp, 0);

        if (texture != boundTexture) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            boundTexture = texture;
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        return boundTexture;
    }

    // --- GL 资源 ---

    private void initGlResources() {
        String vs = AssetLoader.readText(mContext, "droid/shaders/GLES/droid_sprite_vs.glsl");
        String fs = AssetLoader.readText(mContext, "droid/shaders/GLES/droid_sprite_fs.glsl");
        mProgram = createProgram(vs, fs);
        if (mProgram == 0) {
            Log.e(TAG, "Failed to create sprite program");
            return;
        }
        mPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
        mTexCoordHandle = GLES20.glGetAttribLocation(mProgram, "aTexCoord");
        mMvpHandle = GLES20.glGetUniformLocation(mProgram, "uMvpMatrix");
        mTintHandle = GLES20.glGetUniformLocation(mProgram, "uTint");
        mAlphaHandle = GLES20.glGetUniformLocation(mProgram, "uAlpha");
        mSamplerHandle = GLES20.glGetUniformLocation(mProgram, "uTexture");

        boolean missingPart = false;
        for (int i = 0; i < TEX_COUNT; i++) {
            mTextures[i] = loadTexture(TEX_PATHS[i]);
            if (mTextures[i] == 0) {
                missingPart = true;
            }
        }
        // 皮肤缺少某部件位图时,对应刚体也不创建(与原版 hasLeftArm 等参数一致)
        mScene.setAvailableParts(
                mTextures[TEX_LEFT_ARM] != 0,
                mTextures[TEX_RIGHT_ARM] != 0,
                mTextures[TEX_LEFT_LEG] != 0,
                mTextures[TEX_RIGHT_LEG] != 0);
        if (missingPart) {
            Log.w(TAG, "Some droid part textures are missing");
        }

        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        mGlReady = true;
    }

    private int loadTexture(String assetPath) {
        Bitmap bitmap = AssetLoader.decodeBitmap(mContext, assetPath);
        if (bitmap == null) {
            Log.e(TAG, "Failed to decode texture: " + assetPath);
            return 0;
        }
        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        bitmap.recycle();
        return ids[0];
    }

    // --- 设置项 ---

    private void pollPrefs(long timeMs) {
        if (mPrefs == null) {
            return;
        }
        if (mLastPrefPollMs != Long.MIN_VALUE && timeMs - mLastPrefPollMs < PREF_POLL_INTERVAL_MS) {
            return;
        }
        mLastPrefPollMs = timeMs;
        readPrefs();
    }

    private void readPrefs() {
        if (mPrefs == null) {
            return;
        }
        int color = mPrefs.getInt(DroidScene.PREF_COLOR, DroidScene.DEFAULT_COLOR);
        mTintR = ((color >> 16) & 0xFF) / 255.0f;
        mTintG = ((color >> 8) & 0xFF) / 255.0f;
        mTintB = (color & 0xFF) / 255.0f;

        int shake = mPrefs.getInt(DroidScene.PREF_SHAKE, DroidScene.DEFAULT_SHAKE);
        mShakeFactor = Math.max(0, Math.min(100, shake));

        int light = mPrefs.getInt(DroidScene.PREF_LIGHT, DroidScene.DEFAULT_LIGHT);
        boolean lightEnabled = light > 0;
        mLightFactor = light;
        if (mSensorsRegistered) {
            updateLightSensorRegistration(lightEnabled);
        }
    }

    // --- 传感器 ---

    private void registerSensors() {
        if (mSensorsRegistered) {
            return;
        }
        Context context = mContext;
        if (context == null) {
            return;
        }
        mSensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        if (mSensorManager == null) {
            return;
        }
        Sensor accelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        Sensor magnetic = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        if (accelerometer != null) {
            mSensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
        }
        if (magnetic != null) {
            mSensorManager.registerListener(this, magnetic, SensorManager.SENSOR_DELAY_UI);
        }
        mLightSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        mSensorsRegistered = true;
        mLightRegistered = false;
        if (mLightSensor != null && mLightFactor > 0) {
            mSensorManager.registerListener(this, mLightSensor, SensorManager.SENSOR_DELAY_UI);
            mLightRegistered = true;
        }
    }

    private void unregisterSensors() {
        if (!mSensorsRegistered || mSensorManager == null) {
            return;
        }
        try {
            mSensorManager.unregisterListener(this);
        } catch (Throwable ignored) {
        }
        mSensorsRegistered = false;
        mLightRegistered = false;
    }

    private void updateLightSensorRegistration(boolean enabled) {
        if (mSensorManager == null || mLightSensor == null) {
            return;
        }
        if (enabled && !mLightRegistered) {
            mSensorManager.registerListener(this, mLightSensor, SensorManager.SENSOR_DELAY_UI);
            mLightRegistered = true;
        } else if (!enabled && mLightRegistered) {
            mSensorManager.unregisterListener(this, mLightSensor);
            mLightRegistered = false;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        switch (event.sensor.getType()) {
            case Sensor.TYPE_ACCELEROMETER:
                onAccelerometer(event.values);
                break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                System.arraycopy(event.values, 0, mMag, 0, Math.min(3, event.values.length));
                mHasMag = true;
                break;
            case Sensor.TYPE_LIGHT:
                onLight(event.values[0]);
                break;
            default:
                break;
        }
    }

    private void onAccelerometer(float[] values) {
        System.arraycopy(values, 0, mAccel, 0, Math.min(3, values.length));
        mHasAccel = true;

        // 姿态 → 重力(与原版一致:重力 = (roll_angle, pitch_angle) × 系数 × 16)
        if (mHasMag && SensorManager.getRotationMatrix(mRotationMatrix, null, mAccel, mMag)) {
            SensorManager.getOrientation(mRotationMatrix, mOrientation);
            mScene.setOrientation(mOrientation[1], mOrientation[2]);
        }

        // 摇晃 → 线性冲量(加速度差值超过 1.0 才触发)
        if (mHasLastAccel) {
            float dx = mLastAccelX - values[0];
            float dy = mLastAccelY - values[1];
            if (Math.abs(dx) > 1.0f || Math.abs(dy) > 1.0f) {
                mScene.applyImpulse(dx * mShakeFactor, dy * mShakeFactor);
            }
        }
        mLastAccelX = values[0];
        mLastAccelY = values[1];
        mHasLastAccel = true;
    }

    private void onLight(float lux) {
        if (mLightFactor <= 0.0) {
            return;
        }
        double factor = mLightFactor >= 100.0 ? 99.0 : mLightFactor;
        mScene.updateMotorRate(lux / ((100.0 - factor) * 100.0));
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
