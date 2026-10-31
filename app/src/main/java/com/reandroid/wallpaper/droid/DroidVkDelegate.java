package com.reandroid.wallpaper.droid;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;
import android.view.MotionEvent;

import com.reandroid.plugin.ColorPrefs;
import com.reandroid.plugin.VkPass;
import com.reandroid.plugin.VkPassFrame;
import com.reandroid.plugin.VkPassRendererDelegate;
import com.reandroid.plugin.VkPassScene;
import com.reandroid.utils.AssetLoader;
import com.reandroid.wallpaper.droid.physics.Body;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * 坠落机器人(Shake Them All)的 Vulkan 渲染器 —— 壁纸与预览共用。
 *
 * 物理、装配与传感器输入沿用 {@link DroidScene}；这里重做 {@link DroidGL} 的绘制：
 * 把 5 张部件贴图按原版方式逐像素拼成一张图集，每帧把所有机器人写进同一个顶点批，
 * 一次 draw 画完。顶点已在 CPU 侧按屏幕坐标与正交投影算成裁剪坐标，再由顶点着色器
 * 做 Vulkan 的裁剪空间修正(y 取反、z 映射到 0.5z + 0.5w)。
 *
 * 混合与原版一致：{@code SRC_ALPHA / ONE_MINUS_SRC_ALPHA}，且对颜色和 alpha 通道
 * 同时生效。触摸在每个事件上施加径向冲量(按下与拖动都会推动机器人)，
 * 加速度计负责重力方向与摇晃冲量，光照传感器驱动双臂马达。
 */
public final class DroidVkDelegate extends VkPassRendererDelegate implements SensorEventListener {

    private static final String TAG = "DroidVK";

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

    /** 图集布局与 {@link DroidGL} 相同：v=0 是位图首行(顶部)，区间之间留 2px 防止渗色。 */
    private static final int ATLAS_W = 164;

    private static final int ATLAS_H = 148;
    private static final int[][] ATLAS_RECTS = {
        {0, 0, 100, 143},
        {102, 0, 132, 69},
        {134, 0, 164, 69},
        {102, 71, 132, 119},
        {134, 71, 164, 119}
    };

    /** 单帧最大精灵数：30 个机器人 × 5 个部件。 */
    private static final int MAX_SPRITES = 30 * 5;

    private static final int VERTICES_PER_SPRITE = 6;
    private static final int MAX_VERTICES = MAX_SPRITES * VERTICES_PER_SPRITE;

    /** 交错顶点：x, y, u, v。 */
    private static final int FLOATS_PER_VERTEX = 4;

    private static final int STRIDE_BYTES = FLOATS_PER_VERTEX * 4;
    /** 着色器 push constant：tint.rgb + alpha。 */
    private static final int PUSH_BYTES = 4 * 4;

    private static final String SPRITE_BUFFER = "sprites";
    private static final String ATLAS_TEXTURE = "atlas";
    private static final String VERT = "shaders/droid_vk.vert.spv";
    private static final String FRAG = "shaders/droid_vk.frag.spv";

    private static final long PREF_POLL_INTERVAL_MS = 1000L;

    private volatile SharedPreferences mPrefs;
    private volatile DroidScene mScene;
    private volatile VkPassScene mPassScene;

    private VkPass mPass;
    private ByteBuffer mVertices;
    private int mVertexCount;

    private volatile int mWidth;
    private volatile int mHeight;

    /** 图集是否已上传；native 场景重建后要重新上传。 */
    private boolean mAtlasUploaded;
    /** 位掩码：缺失的部件贴图(见 TEX_* 序号)。 */
    private int mMissingParts;

    private boolean mPartsApplied;

    /** 主线程设置的脏标记，渲染线程取走后清掉。 */
    private volatile boolean mPrefsDirty;

    private long mLastPrefPollMs = Long.MIN_VALUE;

    // 设置项(默认值与原版一致)
    private volatile float mBgR = 48.0f / 255.0f;
    private volatile float mBgG = 88.0f / 255.0f;
    private volatile float mBgB = 124.0f / 255.0f;
    private volatile float mTintR = 151.0f / 255.0f;
    private volatile float mTintG = 192.0f / 255.0f;
    private volatile float mTintB = 61.0f / 255.0f;
    private volatile double mShakeFactor;
    private volatile double mLightFactor;

    private final float[] mPush = new float[4];

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

    public DroidVkDelegate(Context context, SharedPreferences prefs) {
        super(context);
        mPrefs = prefs;
        readPrefs();
    }

    @Override
    public String logTag() {
        return TAG;
    }

    /**
     * 设置页按方法名反射 {@code setPluginPrefs(SharedPreferences)} 把新设置推给预览。
     * {@link DroidScene} 的方法不是 public，所以返回 delegate 自身。
     */
    @Override
    public Object getScene() {
        return this;
    }

    @Override
    public boolean isReady() {
        return mScene != null;
    }

    /** 预览路径的设置注入（与 GLES 版同名方法）。 */
    public void setPluginPrefs(SharedPreferences prefs) {
        mPrefs = prefs;
        mPrefsDirty = true;
    }

    @Override
    public void onPluginPrefsChanged(SharedPreferences prefs) {
        setPluginPrefs(prefs);
    }

    @Override
    public void ensureScene(int width, int height) {
        if (width <= 0 || height <= 0) return;

        DroidScene scene = mScene;
        if (scene == null) {
            mWidth = width;
            mHeight = height;
            scene = new DroidScene(width, height);
            scene.setPluginPrefs(mPrefs);
            mScene = scene;
        } else {
            synchronized (scene) {
                mWidth = width;
                mHeight = height;
                scene.resize(width, height);
            }
        }
    }

    /* ==================== 触摸 ==================== */

    @Override
    public void onTouchEvent(MotionEvent event) {
        // 原版对每个触摸事件都施加径向冲量(按下 / 拖动都会推动机器人)
        DroidScene scene = mScene;
        if (scene == null || event == null) return;
        double strength = scene.touchFactor();
        if (strength <= 0.0) return;
        double worldX = event.getX() - mWidth * 0.5;
        double worldY = mHeight * 0.5 - event.getY();
        synchronized (scene) {
            scene.applyRadialImpulse(worldX, worldY, strength);
        }
    }

    /* ==================== 生命周期 ==================== */

    @Override
    public void onVisibilityChanged(boolean visible) {
        if (visible) {
            registerSensors();
        } else {
            unregisterSensors();
        }
    }

    @Override
    public void onSurfaceDestroyed() {
        unregisterSensors();
        super.onSurfaceDestroyed();
    }

    /** native 销毁后句柄不能再碰：先停传感器、清场景引用，再交给基类。 */
    @Override
    public void destroyRenderer() {
        unregisterSensors();
        mPassScene = null;
        super.destroyRenderer();
    }

    /* ==================== 声明场景 ==================== */

    @Override
    protected void buildScene(VkPassScene scene) {
        mPassScene = scene;
        // native 渲染器可能被销毁后重建：新场景里的纹理是空的，图集要重新上传。
        mAtlasUploaded = false;
        mMissingParts = 0;
        mPartsApplied = false;
        mVertexCount = 0;

        scene.setClearColor(mBgR, mBgG, mBgB, 1.0f);
        scene.createBuffer(SPRITE_BUFFER, STRIDE_BYTES, MAX_VERTICES, true);
        // GLES 的图集是 LINEAR + CLAMP_TO_EDGE，正好是 createTexture 的默认值。
        scene.createTexture(ATLAS_TEXTURE);

        mVertices = ByteBuffer.allocateDirect(MAX_VERTICES * STRIDE_BYTES)
                .order(ByteOrder.nativeOrder());

        mPass = scene.add(new VkPass(
                        VERT,
                        FRAG,
                        new int[] {2, 2},
                        STRIDE_BYTES,
                        VkPass.TOPOLOGY_TRIANGLE_LIST,
                        VkPass.BLEND_ALPHA))
                .buffer(SPRITE_BUFFER, 0)
                .pushConstants(PUSH_BYTES)
                // GLES 的 glBlendFunc(SRC_ALPHA, ONE_MINUS_SRC_ALPHA) 对 alpha
                // 通道同样生效；通用 BLEND_ALPHA 预设给 srcAlpha 用的是 ONE。
                .blendFactors(
                        VkPass.FACTOR_SRC_ALPHA,
                        VkPass.FACTOR_ONE_MINUS_SRC_ALPHA,
                        VkPass.FACTOR_SRC_ALPHA,
                        VkPass.FACTOR_ONE_MINUS_SRC_ALPHA);
        mPass.texture(0, ATLAS_TEXTURE);
    }

    /* ==================== 每帧 ==================== */

    @Override
    protected void updateFrame(VkPassFrame frame, long timeMs) {
        DroidScene scene = mScene;
        VkPassScene passScene = mPassScene;
        if (scene == null || passScene == null || mWidth <= 0 || mHeight <= 0) {
            if (mPass != null) frame.setVertexCount(mPass, 0);
            return;
        }

        ensureAtlas();

        synchronized (scene) {
            // 与 DroidGL.drawFrame 相同的顺序：设置 → 物理 → 绘制。
            applyPendingPrefs(scene);
            pollPrefs(timeMs);
            applyAvailableParts(scene);
            scene.update(timeMs);
            buildVertices(scene);
        }

        // 背景色可在设置里用取色器修改，每帧同步给 native。
        passScene.setClearColor(mBgR, mBgG, mBgB, 1.0f);

        if (mVertexCount > 0) {
            mVertices.position(0);
            frame.upload(SPRITE_BUFFER, mVertices, mVertexCount * STRIDE_BYTES);
        }
        frame.setVertexCount(mPass, mVertexCount);

        mPush[0] = mTintR;
        mPush[1] = mTintG;
        mPush[2] = mTintB;
        mPush[3] = 1.0f;
        frame.pushConstants(mPass, mPush);
    }

    /** 渲染线程上应用主线程写进来的新设置。 */
    private void applyPendingPrefs(DroidScene scene) {
        if (!mPrefsDirty) return;
        mPrefsDirty = false;
        SharedPreferences prefs = mPrefs;
        if (prefs == null) return;
        scene.setPluginPrefs(prefs);
        // 立刻重新读一次，不用等下一个轮询周期。
        mLastPrefPollMs = Long.MIN_VALUE;
    }

    /* ==================== 图集 ==================== */

    /**
     * 把 5 张部件贴图拼成一张图集并上传。
     *
     * 与 GLES 版的图集构建相同，按像素拷贝而不是用 Canvas：
     * 解码出的位图是非预乘的，逐像素拷贝保持直通 alpha，与 GLES 逐张上传的结果一致。
     * 缺失的部件在图集里留空(透明)，对应刚体也不会创建。
     */
    private void ensureAtlas() {
        VkPassScene passScene = mPassScene;
        if (passScene == null || mAtlasUploaded) return;

        int[] dst = new int[ATLAS_W * ATLAS_H];
        mMissingParts = 0;
        for (int i = 0; i < TEX_COUNT; i++) {
            Bitmap part = null;
            try {
                part = AssetLoader.decodeBitmap(context, TEX_PATHS[i]);
            } catch (RuntimeException e) {
                Log.e(TAG, "纹理解码失败: " + TEX_PATHS[i], e);
            }
            if (part == null) {
                mMissingParts |= (1 << i);
                continue;
            }
            int w = part.getWidth();
            int h = part.getHeight();
            int[] rect = ATLAS_RECTS[i];
            w = Math.min(w, rect[2] - rect[0]);
            h = Math.min(h, rect[3] - rect[1]);
            if (w <= 0 || h <= 0) {
                part.recycle();
                mMissingParts |= (1 << i);
                continue;
            }
            int[] src = new int[w * h];
            part.getPixels(src, 0, w, 0, 0, w, h);
            for (int y = 0; y < h; y++) {
                System.arraycopy(src, y * w, dst, (rect[1] + y) * ATLAS_W + rect[0], w);
            }
            part.recycle();
        }

        passScene.setTexture(ATLAS_TEXTURE, dst, ATLAS_W, ATLAS_H);
        mAtlasUploaded = true;
        mPartsApplied = false;
        if (mMissingParts != 0) {
            Log.w(TAG, "部分部件贴图缺失");
        }
    }

    /** 图集就绪后把可用部件告诉场景(缺图时对应刚体不创建，与原版 hasLeftArm 等一致)。 */
    private void applyAvailableParts(DroidScene scene) {
        if (mPartsApplied || !mAtlasUploaded) return;
        scene.setAvailableParts(
                hasPart(TEX_LEFT_ARM),
                hasPart(TEX_RIGHT_ARM),
                hasPart(TEX_LEFT_LEG),
                hasPart(TEX_RIGHT_LEG));
        mPartsApplied = true;
    }

    private boolean hasPart(int index) {
        return (mMissingParts & (1 << index)) == 0 && mAtlasUploaded;
    }

    /* ==================== 精灵批 ==================== */

    /**
     * 把所有精灵写进交错顶点数组(屏幕坐标 + 图集 UV)。
     * 顺序与原版 draw() 回调一致：腿 → 躯干 → 手臂。
     */
    private void buildVertices(DroidScene scene) {
        mVertices.clear();
        mVertexCount = 0;

        if (!mAtlasUploaded) return;
        List<DroidScene.Droid> droids = scene.droids();
        if (droids.isEmpty()) return;

        double scale = scene.sizeScale();
        float bodyW = (float) (DroidScene.SRC_BODY_W * scale);
        float bodyH = (float) (DroidScene.SRC_BODY_H * scale);
        float armW = (float) (DroidScene.SRC_ARM_W * scale);
        float armH = (float) (DroidScene.SRC_ARM_H * scale);
        float legW = (float) (DroidScene.SRC_LEG_W * scale);
        float legH = (float) (DroidScene.SRC_LEG_H * scale);

        double alpha = scene.interpolationAlpha();
        for (int i = 0; i < droids.size(); i++) {
            DroidScene.Droid droid = droids.get(i);
            writeSprite(TEX_LEFT_LEG, droid.leftLeg, legW, legH, alpha);
            writeSprite(TEX_RIGHT_LEG, droid.rightLeg, legW, legH, alpha);
            writeSprite(TEX_BODY, droid.body, bodyW, bodyH, alpha);
            writeSprite(TEX_LEFT_ARM, droid.leftArm, armW, armH, alpha);
            writeSprite(TEX_RIGHT_ARM, droid.rightArm, armW, armH, alpha);
        }
    }

    /** 写入一个精灵的 6 个顶点(两个三角形)。 */
    private void writeSprite(int textureIndex, Body body, float width, float height, double alpha) {
        if (body == null) return;
        if (mVertexCount + VERTICES_PER_SPRITE > MAX_VERTICES) return;

        // 世界坐标 → 屏幕坐标(y 轴翻转)，旋转角取反；
        // 位置 / 角度按累加器比例在上一物理步与当前物理步之间插值。
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
        putVertex(x0, y0, u0, v0);
        putVertex(x1, y1, u1, v0);
        putVertex(x2, y2, u1, v1);
        putVertex(x0, y0, u0, v0);
        putVertex(x2, y2, u1, v1);
        putVertex(x3, y3, u0, v1);
    }

    /** 屏幕坐标 → 裁剪坐标；Vulkan 的 y 翻转交给顶点着色器。 */
    private void putVertex(float x, float y, float u, float v) {
        mVertices
                .putFloat(2f * x / mWidth - 1f)
                .putFloat(1f - 2f * y / mHeight)
                .putFloat(u)
                .putFloat(v);
        mVertexCount++;
    }

    /* ==================== 设置项 ==================== */

    private void pollPrefs(long timeMs) {
        if (mPrefs == null) return;
        if (mLastPrefPollMs != Long.MIN_VALUE && timeMs - mLastPrefPollMs < PREF_POLL_INTERVAL_MS) {
            return;
        }
        mLastPrefPollMs = timeMs;
        readPrefs();
    }

    private void readPrefs() {
        SharedPreferences prefs = mPrefs;
        if (prefs == null) return;

        int bgColor =
                ColorPrefs.getColor(prefs, DroidScene.PREF_BG_COLOR, DroidScene.DEFAULT_BG_COLOR);
        mBgR = ((bgColor >> 16) & 0xFF) / 255.0f;
        mBgG = ((bgColor >> 8) & 0xFF) / 255.0f;
        mBgB = (bgColor & 0xFF) / 255.0f;

        int color = ColorPrefs.getColor(prefs, DroidScene.PREF_COLOR, DroidScene.DEFAULT_COLOR);
        mTintR = ((color >> 16) & 0xFF) / 255.0f;
        mTintG = ((color >> 8) & 0xFF) / 255.0f;
        mTintB = (color & 0xFF) / 255.0f;

        int shake = prefs.getInt(DroidScene.PREF_SHAKE, DroidScene.DEFAULT_SHAKE);
        mShakeFactor = Math.max(0, Math.min(100, shake));

        int light = prefs.getInt(DroidScene.PREF_LIGHT, DroidScene.DEFAULT_LIGHT);
        boolean lightEnabled = light > 0;
        mLightFactor = light;
        if (mSensorsRegistered) {
            updateLightSensorRegistration(lightEnabled);
        }
    }

    /* ==================== 传感器 ==================== */

    private void registerSensors() {
        if (mSensorsRegistered) return;
        SensorManager sensorManager =
                (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager == null) return;
        mSensorManager = sensorManager;

        Sensor accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        Sensor magnetic = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
        }
        if (magnetic != null) {
            sensorManager.registerListener(this, magnetic, SensorManager.SENSOR_DELAY_UI);
        }

        mLightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        mSensorsRegistered = true;
        mLightRegistered = false;
        if (mLightSensor != null && mLightFactor > 0) {
            sensorManager.registerListener(this, mLightSensor, SensorManager.SENSOR_DELAY_UI);
            mLightRegistered = true;
        }
    }

    private void unregisterSensors() {
        if (!mSensorsRegistered || mSensorManager == null) return;
        try {
            mSensorManager.unregisterListener(this);
        } catch (Throwable ignored) {
        }
        mSensorsRegistered = false;
        mLightRegistered = false;
    }

    private void updateLightSensorRegistration(boolean enabled) {
        if (mSensorManager == null || mLightSensor == null) return;
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

        DroidScene scene = mScene;

        // 姿态 → 重力(与原版一致:重力 = (roll_angle, pitch_angle) × 系数 × 16)
        if (mHasMag
                && SensorManager.getRotationMatrix(mRotationMatrix, null, mAccel, mMag)
                && scene != null) {
            SensorManager.getOrientation(mRotationMatrix, mOrientation);
            synchronized (scene) {
                scene.setOrientation(mOrientation[1], mOrientation[2]);
            }
        }

        // 摇晃 → 线性冲量(加速度差值超过 1.0 才触发)
        if (mHasLastAccel) {
            float dx = mLastAccelX - values[0];
            float dy = mLastAccelY - values[1];
            if ((Math.abs(dx) > 1.0f || Math.abs(dy) > 1.0f) && scene != null) {
                double sx = dx * mShakeFactor;
                double sy = dy * mShakeFactor;
                synchronized (scene) {
                    scene.applyImpulse(sx, sy);
                }
            }
        }
        mLastAccelX = values[0];
        mLastAccelY = values[1];
        mHasLastAccel = true;
    }

    private void onLight(float lux) {
        if (mLightFactor <= 0.0) return;
        DroidScene scene = mScene;
        if (scene == null) return;
        double factor = mLightFactor >= 100.0 ? 99.0 : mLightFactor;
        double rate = lux / ((100.0 - factor) * 100.0);
        synchronized (scene) {
            scene.updateMotorRate(rate);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
