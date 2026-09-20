package com.reandroid.wallpaper.droid;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.opengl.GLES30;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.util.Log;
import android.view.MotionEvent;

import com.reandroid.gles.GLESScene;
import com.reandroid.plugin.ColorPrefs;
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

    /**
     * 纹理图集布局:把 5 张部件贴图拼进一张图,这样每帧所有精灵只需一次 draw call。
     * 坐标按 v=0 为位图首行(顶部)摆放。
     */
    private static final int ATLAS_W = 164;
    private static final int ATLAS_H = 148;
    /** 各部件在图集中的像素区间:x0, y0, x1, y1(区间之间留 2px 间隔,避免线性采样互相渗色)。 */
    private static final int[][] ATLAS_RECTS = {
            {0, 0, 100, 143},
            {102, 0, 132, 69},
            {134, 0, 164, 69},
            {102, 71, 132, 119},
            {134, 71, 164, 119}
    };

    /** 单帧最大精灵数:30 个机器人 × 5 个部件。 */
    private static final int MAX_SPRITES = 30 * 5;
    /** 交错顶点数据 (x, y, u, v),每精灵 6 个顶点。 */
    private static final int FLOATS_PER_VERTEX = 4;
    private static final int VERTICES_PER_SPRITE = 6;
    private static final int MAX_BATCH_FLOATS = MAX_SPRITES * VERTICES_PER_SPRITE * FLOATS_PER_VERTEX;

    /** 背景色(默认原版 rgb(48,88,124),可在设置中用取色器修改)。 */
    private float mBgR = 48.0f / 255.0f;
    private float mBgG = 88.0f / 255.0f;
    private float mBgB = 124.0f / 255.0f;

    private static final long PREF_POLL_INTERVAL_MS = 1000L;

    /**
     * 性能采样:每 5 秒输出一次帧内耗时构成(定位卡顿用)。
     * 经设备实测(30 机器人 / 尺寸 100% / 目标 180fps):physics ≈ 0.4ms,
     * 帧率被屏幕刷新率限制在 120fps,故默认关闭;需要复测时置 true。
     */
    private static final boolean PERF_LOG = false;
    private static final long PERF_LOG_INTERVAL_MS = 5000L;
    private long mPerfLogMs;
    private int mPerfFrames;
    private double mPerfPhysicsMs;
    private double mPerfDrawMs;
    private long mPerfMaxFrameMs;

    private final Context mContext;
    private final DroidScene mScene;

    private FloatBuffer mBatchBuffer;
    private float[] mBatchScratch;

    private final float[] mProjection = new float[16];

    private int mProgram;
    private int mPositionHandle = -1;
    private int mTexCoordHandle = -1;
    private int mMvpHandle = -1;
    private int mTintHandle = -1;
    private int mAlphaHandle = -1;
    private int mSamplerHandle = -1;

    private int mAtlasTexture;
    /** 位掩码:缺失的部件贴图(见 TEX_* 序号)。 */
    private int mMissingParts;

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
        mBatchScratch = new float[MAX_BATCH_FLOATS];
        mBatchBuffer = createFloatBuffer(mBatchScratch);
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
        if (mAtlasTexture != 0) {
            GLES30.glDeleteTextures(1, new int[] {mAtlasTexture}, 0);
            mAtlasTexture = 0;
        }
        if (mProgram != 0) {
            GLES30.glDeleteProgram(mProgram);
            mProgram = 0;
        }
        mGlReady = false;
        mInitialized = false;
        /*
         * 这几个标志必须一起清掉。mMissingParts 是 |= 累积的，重复 init 时
         * 会把上一次"缺了哪块"的位保留下来，于是那些部位永远显示不出来。
         */
        mMissingParts = 0;
        mHasAccel = false;
        mHasMag = false;
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

        GLES30.glClearColor(mBgR, mBgG, mBgB, 1.0f);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);

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
        if (droids.isEmpty() || mProgram == 0 || mAtlasTexture == 0) {
            return;
        }
        int floats = fillBatch(droids);
        if (floats == 0) {
            return;
        }

        GLES30.glUseProgram(mProgram);
        GLES30.glUniformMatrix4fv(mMvpHandle, 1, false, mProjection, 0);
        GLES30.glUniform3f(mTintHandle, mTintR, mTintG, mTintB);
        GLES30.glUniform1f(mAlphaHandle, 1.0f);
        GLES30.glUniform1i(mSamplerHandle, 0);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mAtlasTexture);

        mBatchBuffer.position(0);
        mBatchBuffer.limit(floats);
        GLES30.glVertexAttribPointer(mPositionHandle, 2, GLES30.GL_FLOAT, false,
                FLOATS_PER_VERTEX * 4, mBatchBuffer);
        GLES30.glEnableVertexAttribArray(mPositionHandle);
        mBatchBuffer.position(2);
        GLES30.glVertexAttribPointer(mTexCoordHandle, 2, GLES30.GL_FLOAT, false,
                FLOATS_PER_VERTEX * 4, mBatchBuffer);
        GLES30.glEnableVertexAttribArray(mTexCoordHandle);

        // 所有精灵一次提交:150 个精灵 × 2 三角形
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, floats / FLOATS_PER_VERTEX);

        GLES30.glDisableVertexAttribArray(mPositionHandle);
        GLES30.glDisableVertexAttribArray(mTexCoordHandle);
    }

    /**
     * 把所有精灵写进交错顶点数组(屏幕坐标 + 图集 UV),返回写入的 float 数。
     * 顶点直接算在屏幕空间,因此 MVP 只需投影矩阵,逐精灵零 GL 调用。
     * 顺序与原版 draw() 回调一致:腿 → 躯干 → 手臂。
     */
    private int fillBatch(List<DroidScene.Droid> droids) {
        double scale = mScene.sizeScale();
        float bodyW = (float) (DroidScene.SRC_BODY_W * scale);
        float bodyH = (float) (DroidScene.SRC_BODY_H * scale);
        float armW = (float) (DroidScene.SRC_ARM_W * scale);
        float armH = (float) (DroidScene.SRC_ARM_H * scale);
        float legW = (float) (DroidScene.SRC_LEG_W * scale);
        float legH = (float) (DroidScene.SRC_LEG_H * scale);

        double alpha = mScene.interpolationAlpha();
        float[] out = mBatchScratch;
        int p = 0;
        int maxFloats = out.length - VERTICES_PER_SPRITE * FLOATS_PER_VERTEX;

        for (int i = 0; i < droids.size(); i++) {
            DroidScene.Droid droid = droids.get(i);
            p = writeSprite(out, p, TEX_LEFT_LEG, droid.leftLeg, legW, legH, alpha);
            p = writeSprite(out, p, TEX_RIGHT_LEG, droid.rightLeg, legW, legH, alpha);
            p = writeSprite(out, p, TEX_BODY, droid.body, bodyW, bodyH, alpha);
            p = writeSprite(out, p, TEX_LEFT_ARM, droid.leftArm, armW, armH, alpha);
            p = writeSprite(out, p, TEX_RIGHT_ARM, droid.rightArm, armW, armH, alpha);
            if (p > maxFloats) {
                break;
            }
        }

        if (p == 0) {
            return 0;
        }
        /*
         * 先恢复 limit 再写。绘制那边设过 limit(floats)，而 put 的长度受 remaining
         * (= limit - position) 限制 —— 精灵数比上一帧多时就会 BufferOverflowException。
         * 缓冲容量等于 mBatchScratch.length，而 p 有 maxFloats 兜着，永远装得下。
         */
        mBatchBuffer.limit(mBatchBuffer.capacity());
        mBatchBuffer.position(0);
        mBatchBuffer.put(out, 0, p);
        return p;
    }

    /** 写入一个精灵的 6 个顶点(两个三角形),返回新的写入位置。 */
    private int writeSprite(float[] out, int p, int textureIndex,
            com.reandroid.wallpaper.droid.physics.Body body, float width, float height,
            double alpha) {
        if (body == null) {
            return p;
        }
        // 世界坐标 → 屏幕坐标(y 轴翻转),旋转角取反;
        // 位置 / 角度按累加器比例在上一物理步与当前物理步之间插值(30Hz 物理 → 平滑画面)
        float cx = (float) body.renderX(alpha) + mWidth * 0.5f;
        float cy = mHeight * 0.5f - (float) body.renderY(alpha);
        float angle = -(float) body.renderAngle(alpha);
        float cos = (float) Math.cos(angle);
        float sin = (float) Math.sin(angle);
        float hw = width * 0.5f;
        float hh = height * 0.5f;

        int[] rect = ATLAS_RECTS[textureIndex];
        float u0 = rect[0] / (float) ATLAS_W;
        float v0 = rect[1] / (float) ATLAS_H;
        float u1 = rect[2] / (float) ATLAS_W;
        float v1 = rect[3] / (float) ATLAS_H;

        // 四角:左上、右上、右下、左下(旋转后)
        float x0 = cx + (-hw * cos - -hh * sin);
        float y0 = cy + (-hw * sin + -hh * cos);
        float x1 = cx + (hw * cos - -hh * sin);
        float y1 = cy + (hw * sin + -hh * cos);
        float x2 = cx + (hw * cos - hh * sin);
        float y2 = cy + (hw * sin + hh * cos);
        float x3 = cx + (-hw * cos - hh * sin);
        float y3 = cy + (-hw * sin + hh * cos);

        // 三角形 1:左上、右上、右下;三角形 2:左上、右下、左下
        p = putVertex(out, p, x0, y0, u0, v0);
        p = putVertex(out, p, x1, y1, u1, v0);
        p = putVertex(out, p, x2, y2, u1, v1);
        p = putVertex(out, p, x0, y0, u0, v0);
        p = putVertex(out, p, x2, y2, u1, v1);
        p = putVertex(out, p, x3, y3, u0, v1);
        return p;
    }

    private int putVertex(float[] out, int p, float x, float y, float u, float v) {
        out[p] = x;
        out[p + 1] = y;
        out[p + 2] = u;
        out[p + 3] = v;
        return p + FLOATS_PER_VERTEX;
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
        mPositionHandle = GLES30.glGetAttribLocation(mProgram, "aPosition");
        mTexCoordHandle = GLES30.glGetAttribLocation(mProgram, "aTexCoord");
        mMvpHandle = GLES30.glGetUniformLocation(mProgram, "uMvpMatrix");
        mTintHandle = GLES30.glGetUniformLocation(mProgram, "uTint");
        mAlphaHandle = GLES30.glGetUniformLocation(mProgram, "uAlpha");
        mSamplerHandle = GLES30.glGetUniformLocation(mProgram, "uTexture");

        boolean complete = buildAtlas();
        // 皮肤缺少某部件位图时,对应刚体也不创建(与原版 hasLeftArm 等参数一致)
        mScene.setAvailableParts(
                hasPart(TEX_LEFT_ARM),
                hasPart(TEX_RIGHT_ARM),
                hasPart(TEX_LEFT_LEG),
                hasPart(TEX_RIGHT_LEG));
        if (!complete) {
            Log.w(TAG, "Some droid part textures are missing");
        }

        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glDisable(GLES30.GL_CULL_FACE);
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);

        mGlReady = true;
    }

    /**
     * 把 5 张部件贴图拼成一张图集并上传,返回是否全部部件都在。
     * 图集让每帧所有精灵只需一次 draw call(30 个机器人时从 150 次降到 1 次)。
     *
     * 这里按像素拷贝而不是用 Canvas:AssetLoader 解出的位图是非预乘的,
     * Canvas.drawBitmap 会抛 "non-premultiplied bitmap";逐像素拷贝也能保持
     * 直通 alpha 语义,与逐张上传时的渲染结果完全一致。
     */
    private boolean buildAtlas() {
        Bitmap atlas = Bitmap.createBitmap(ATLAS_W, ATLAS_H, Bitmap.Config.ARGB_8888);
        atlas.setPremultiplied(false);
        int[] dst = new int[ATLAS_W * ATLAS_H];
        boolean complete = true;

        for (int i = 0; i < TEX_COUNT; i++) {
            Bitmap part = AssetLoader.decodeBitmap(mContext, TEX_PATHS[i]);
            if (part == null) {
                Log.e(TAG, "Failed to decode texture: " + TEX_PATHS[i]);
                complete = false;
                mMissingParts |= (1 << i);
                continue;
            }
            int w = part.getWidth();
            int h = part.getHeight();
            int[] rect = ATLAS_RECTS[i];
            w = Math.min(w, rect[2] - rect[0]);
            h = Math.min(h, rect[3] - rect[1]);
            int[] src = new int[w * h];
            part.getPixels(src, 0, w, 0, 0, w, h);
            for (int y = 0; y < h; y++) {
                System.arraycopy(src, y * w, dst, (rect[1] + y) * ATLAS_W + rect[0], w);
            }
            part.recycle();
        }

        atlas.setPixels(dst, 0, ATLAS_W, 0, 0, ATLAS_W, ATLAS_H);
        mAtlasTexture = uploadTexture(atlas);
        atlas.recycle();
        return complete && mAtlasTexture != 0;
    }

    private boolean hasPart(int index) {
        return (mMissingParts & (1 << index)) == 0 && mAtlasTexture != 0;
    }

    private int uploadTexture(Bitmap bitmap) {
        int[] ids = new int[1];
        GLES30.glGenTextures(1, ids, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0]);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0);
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
        int bgColor = ColorPrefs.getColor(mPrefs, DroidScene.PREF_BG_COLOR, DroidScene.DEFAULT_BG_COLOR);
        mBgR = ((bgColor >> 16) & 0xFF) / 255.0f;
        mBgG = ((bgColor >> 8) & 0xFF) / 255.0f;
        mBgB = (bgColor & 0xFF) / 255.0f;

        int color = ColorPrefs.getColor(mPrefs, DroidScene.PREF_COLOR, DroidScene.DEFAULT_COLOR);
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
