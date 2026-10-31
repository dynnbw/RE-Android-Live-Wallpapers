package com.reandroid.wallpaper.deepsea;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.opengl.Matrix;
import android.os.BatteryManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import com.reandroid.plugin.VkPass;
import com.reandroid.plugin.VkPassFrame;
import com.reandroid.plugin.VkPassRendererDelegate;
import com.reandroid.plugin.VkPassScene;
import com.reandroid.utils.AssetLoader;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Random;

/**
 * DeepSea 的 Vulkan 渲染器 —— 壁纸与预览共用。
 *
 * <p>场景逻辑（水母移动、出现/移除、摇动、模糊、电量）从 {@link DeepSeaContainer} 移植。
 * GLES 版每次 draw 一个精灵；这里的每一帧是一组固定 pass：背景、阳光、海面水滴、
 * 水母四边形、水母周围的水滴。全部使用 {@code glBlendFunc(GL_SRC_ALPHA, GL_ONE)}
 * 的加法混合，加法可交换，所以按贴图合批后画面与 GLES 的逐次绘制一致。
 *
 * <p>水母四边形与水母水滴的模型矩阵逐只不同，没法放 push constant：四边形在 CPU 侧
 * 直接算成裁剪坐标；水滴把旋转角与平移作为顶点属性，push constant 只放一次性的
 * 视图投影矩阵。
 */
final class DeepSeaVkDelegate extends VkPassRendererDelegate implements SensorEventListener {

    private static final String TAG = "DeepSeaVK";

    // 设置键（与 DeepSeaScene 读取的一致）
    private static final String KEY_CUSTOM_BACKGROUND = "pref_custom_background_uri";
    private static final String KEY_BACKGROUND_IMAGE_TYPE = "backgroundimagetype";
    private static final String KEY_UNIT_SCALE = "unitscale";
    private static final String KEY_UNIT_NUMBER = "unitnumber";
    private static final String KEY_BACKGROUND_BRIGHTNESS = "backgroundbrightness";
    private static final String KEY_BACKGROUND_LIGHTING = "backgroundlighting";
    private static final String KEY_PARTICLE_COLOR = "particlecolor";

    // 资源路径
    private static final String ASSET_SEA = "deepsea/drawable/bg_s512x512_opt.png";
    private static final String ASSET_SUNSHINE = "deepsea/drawable/light_s512x512_opt.png";
    private static final String ASSET_SUNSHINE_0 =
            "deepsea/drawable/light_animation_0_s512x512_opt.png";
    private static final String ASSET_SUNSHINE_1 =
            "deepsea/drawable/light_animation_1_s512x512_opt.png";
    private static final String ASSET_JELLY_A = "deepsea/drawable/unit_a_s256x256_e_mip_0.png";
    private static final String ASSET_JELLY_A_ALPHA =
            "deepsea/drawable/unit_a_s256x256_e_mip_0_alpha.png";
    private static final String ASSET_JELLY_B = "deepsea/drawable/unit_b_s256x256_e_mip_0.png";
    private static final String ASSET_JELLY_B_ALPHA =
            "deepsea/drawable/unit_b_s256x256_e_mip_0_alpha.png";
    private static final String ASSET_BLUR_A =
            "deepsea/drawable/unit_a_blured_12_s256x256_mip_0.png";
    private static final String ASSET_BLUR_A_ALPHA =
            "deepsea/drawable/unit_a_blured_12_s256x256_mip_0_alpha.png";
    private static final String ASSET_BLUR_B =
            "deepsea/drawable/unit_b_blured_12_s256x256_mip_0.png";
    private static final String ASSET_BLUR_B_ALPHA =
            "deepsea/drawable/unit_b_blured_12_s256x256_mip_0_alpha.png";
    private static final String ASSET_CORE =
            "deepsea/drawable/unit_a_core_glow_s256x256_eeee_mip_0.png";
    private static final String ASSET_CORE_ALPHA =
            "deepsea/drawable/unit_a_core_glow_s256x256_eeee_mip_0_alpha.png";
    private static final String ASSET_PARTICLE = "deepsea/drawable/particle_mip_0.png";
    private static final String ASSET_PARTICLE_ALPHA = "deepsea/drawable/particle_mip_0_alpha.png";

    // 着色器
    private static final String VS_BG = "shaders/deepsea_vk_bg.vert.spv";
    private static final String FS_BG = "shaders/deepsea_vk_bg.frag.spv";
    private static final String VS_SUNSHINE = "shaders/deepsea_vk_sunshine.vert.spv";
    private static final String FS_SUNSHINE = "shaders/deepsea_vk_sunshine.frag.spv";
    private static final String VS_JELLYFISH = "shaders/deepsea_vk_jellyfish.vert.spv";
    private static final String FS_JELLYFISH = "shaders/deepsea_vk_jellyfish.frag.spv";
    private static final String VS_DROPS = "shaders/deepsea_vk_drops.vert.spv";
    private static final String FS_DROPS = "shaders/deepsea_vk_drops.frag.spv";
    private static final String VS_DROPS2 = "shaders/deepsea_vk_drops2.vert.spv";
    private static final String FS_DROPS2 = "shaders/deepsea_vk_drops2.frag.spv";
    private static final String VS_DROPS3 = "shaders/deepsea_vk_drops3.vert.spv";
    private static final String FS_DROPS3 = "shaders/deepsea_vk_drops3.frag.spv";
    private static final String VS_JELLY_DROPS = "shaders/deepsea_vk_jellydrops.vert.spv";
    private static final String FS_JELLY_DROPS = "shaders/deepsea_vk_jellydrops.frag.spv";

    // 纹理名
    private static final String TEX_SEA = "sea";
    private static final String TEX_BACKGROUND = "background";
    private static final String TEX_SUNSHINE = "sunshine";
    private static final String TEX_SUNSHINE_0 = "sunshine0";
    private static final String TEX_SUNSHINE_1 = "sunshine1";
    private static final String TEX_JELLY_A = "jellyA";
    private static final String TEX_JELLY_A_ALPHA = "jellyAAlpha";
    private static final String TEX_JELLY_B = "jellyB";
    private static final String TEX_JELLY_B_ALPHA = "jellyBAlpha";
    private static final String TEX_BLUR_A = "blurA";
    private static final String TEX_BLUR_A_ALPHA = "blurAAlpha";
    private static final String TEX_BLUR_B = "blurB";
    private static final String TEX_BLUR_B_ALPHA = "blurBAlpha";
    private static final String TEX_CORE = "core";
    private static final String TEX_CORE_ALPHA = "coreAlpha";
    private static final String TEX_PARTICLE = "particle";
    private static final String TEX_PARTICLE_ALPHA = "particleAlpha";

    // 缓冲名
    private static final String BUF_SEA = "seaQuad";
    private static final String BUF_BACKGROUND = "backgroundQuad";
    private static final String BUF_SUNSHINE = "sunshineQuad";
    private static final String BUF_DROPS = "drops";
    private static final String BUF_DROPS2 = "drops2";
    private static final String BUF_DROPS3 = "drops3";
    private static final String BUF_JELLY_A = "jellyAQuad";
    private static final String BUF_JELLY_B = "jellyBQuad";
    private static final String BUF_BLUR_A = "blurAQuad";
    private static final String BUF_BLUR_B = "blurBQuad";
    private static final String BUF_CORE = "coreQuad";
    private static final String BUF_JELLY_DROPS = "jellyDrops";
    private static final String BUF_JELLY_DROPS2 = "jellyDrops2";

    /** 顶点格式（浮动数个数）。 */
    private static final int QUAD_FLOATS = 5; // x, y, z, u, v

    private static final int DROP_FLOATS = 13; // 位置、移动、生命、年龄、大小、速度、发射点
    private static final int DROP2_FLOATS = 10; // 没有发射点
    private static final int JELLY_FLOATS = 10; // 裁剪 xyzw, u, v, 附加 alpha, 附加颜色 rgb
    private static final int JELLY_DROP_FLOATS = 16; // 粒子属性 + 模型变换 + 缩放 + alpha + 时间

    private static final int QUAD_VERTICES = 6;
    private static final int DROPS_COUNT = 90;
    private static final int DROPS2_COUNT = 6;
    private static final int DROPS3_COUNT = 120;
    /** 水母数量上限：JellyfishVO 的 10 加上 unitnumber 的上限 5。 */
    private static final int MAX_JELLYFISH = 15;

    private static final int MAX_JELLY_VERTICES = MAX_JELLYFISH * 6;

    private static final int BG_PUSH_BYTES = 17 * 4;
    private static final int SUNSHINE_PUSH_BYTES = 18 * 4;
    private static final int DROPS_PUSH_BYTES = 17 * 4;
    private static final int JELLY_DROPS_PUSH_BYTES = 19 * 4;

    private static final long BATTERY_POLL_MS = 5000L;

    private static final int JELLY_DROPS_A_COUNT = 35;
    private static final int JELLY_DROPS_B_COUNT = 25;

    /** GL 裁剪空间 → Vulkan：y 取反，z 映射到 [0, w]。 */
    private static final float[] CLIP_CORRECTION = {
        1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 0.5f, 0, 0, 0, 0.5f, 1,
    };

    /** 水母四边形（Jellyfish.mVerticesData），顺序 0,1,2, 0,2,3。 */
    private static final float[] QUAD_X = {-0.6f, -0.6f, 0.6f, 0.6f};

    private static final float[] QUAD_Y = {0.6f, -0.6f, -0.6f, 0.6f};
    private static final float[] QUAD_U = {0f, 0f, 1f, 1f};
    private static final float[] QUAD_V = {0f, 1f, 1f, 0f};
    private static final int[] QUAD_ORDER = {0, 1, 2, 0, 2, 3};

    // ─────────────────────────── 宿主与线程 ───────────────────────────

    private final Object mLock = new Object();
    private volatile SharedPreferences mPrefs;
    private volatile VkPassScene mPassScene;
    private volatile boolean mSceneReady;
    private int mWidth;
    private int mHeight;

    // 主线程写入、渲染线程消费的请求
    private volatile boolean mSettingsSyncRequested;
    private volatile boolean mResetRequested;
    private volatile boolean mHasPendingShake;
    private volatile float mPendingShakeForce;

    // 陀螺仪
    private SensorManager mSensorManager;
    private Sensor mGyro;
    private boolean mSensorRegistered;
    private boolean mVisible = true;

    // 电量
    private int mBatteryState;
    private long mLastBatteryPollMs;

    // ─────────────────────────── 场景状态（移植自 DeepSeaContainer） ───────────────────────────

    private final JellyfishVO mJellyfishVO = new JellyfishVO();
    private final IntervalManager mIntervalManager = new IntervalManager();
    private int mUnitScaleVal;
    private int mUnitNumberVal;
    private float mScale = 1.0f;
    private float mDefaultScale = 1.0f;
    private float mBrightness;
    private float mLight;
    private final float[] mBlurColor = new float[3];

    private float mTime;
    private boolean mIsShaking;
    private boolean mIsAfterShaking;
    private float mForce;
    private float mStartShakingTime;
    private boolean mIsAllFinishedAppearing;
    private int mAppearingIndex;
    private boolean mIsAllFinishedRemoving;
    private int mNumberOfRemoving;
    private boolean mIsRemoving;
    private boolean mIsAppearingRemoved;
    private boolean mIsBluring = true;
    private float mBlurAlpha;
    private boolean mSlow;

    private float mAlphaOfSunshine = 1.0f;
    private float mAlphaOfSunshine1 = 1.0f;
    private float mAlphaOfSunshine2 = 0.0f;
    private float mDestinationAlphaOfSunshine = 0.2f;
    private float mDestinationAlphaOfSunshine1 = 0.0f;
    private float mDestinationAlphaOfSunshine2 = 1.0f;

    // 海面水滴的时间（GLES 版在每次 setForDrawing 时推进）
    private float mDropsTime;
    private float mDropsTime2;
    private float mDropsTime3;
    // 水母周围水滴的时间：每只水母各推进一次
    private float mJellyDropsTimeA;
    private float mJellyDropsTimeB;

    // 背景
    private String mBackgroundImagePath;
    private String mAppliedBackgroundKey;
    private boolean mHasBackground;
    private boolean mStaticTexturesUploaded;

    // ─────────────────────────── 矩阵 ───────────────────────────

    private final float[] mViewMatrix = new float[16];
    private final float[] mProjectionMatrix = new float[16];
    private final float[] mModelMatrix = new float[16];
    private final float[] mMvpMatrix = new float[16];
    private final float[] mClipMvp = new float[16];
    private final float[] mTmpMvp = new float[16];
    private final float[] mViewProj = new float[16];
    private final float[] mDropsMvp = new float[16];
    private final float[] mTmpTranslate = new float[16];
    private final float[] mIdentity = new float[16];
    private final float[] mQuadPoint = new float[4];
    private final float[] mQuadOut = new float[4];

    // ─────────────────────────── 渲染资源 ───────────────────────────

    private VkPass mSeaPass;
    private VkPass mSea2Pass;
    private VkPass mSunPass;
    private VkPass mSunPass0;
    private VkPass mSunPass1;
    private VkPass mDropsPass;
    private VkPass mDrops2Pass;
    private VkPass mDrops3Pass;
    private VkPass mJellyAPass;
    private VkPass mJellyBPass;
    private VkPass mBlurAPass;
    private VkPass mBlurBPass;
    private VkPass mCorePass;
    private VkPass mJellyDropsPass;
    private VkPass mJellyDrops2Pass;

    private ByteBuffer mSeaBuffer;
    private ByteBuffer mBackgroundBuffer;
    private ByteBuffer mSunBuffer;
    private ByteBuffer mDropsBuffer;
    private ByteBuffer mDrops2Buffer;
    private ByteBuffer mDrops3Buffer;
    private ByteBuffer mJellyABuffer;
    private ByteBuffer mJellyBBuffer;
    private ByteBuffer mBlurABuffer;
    private ByteBuffer mBlurBBuffer;
    private ByteBuffer mCoreBuffer;
    private ByteBuffer mJellyDropsBuffer;
    private ByteBuffer mJellyDrops2Buffer;

    private int mJellyACount;
    private int mJellyBCount;
    private int mBlurACount;
    private int mBlurBCount;
    private int mCoreCount;
    private int mJellyDropsCount;
    private int mJellyDrops2Count;
    private int mDrawDrops3;

    private final float[] mPushBg = new float[17];
    private final float[] mPushSun = new float[18];
    private final float[] mPushDrops = new float[17];
    private final float[] mPushJellyDrops = new float[19];

    // ─────────────────────────── 粒子数据 ───────────────────────────

    private final float[] mSeaDrops = new float[DROPS_COUNT * DROP_FLOATS];
    private final float[] mSeaDropsBackup = new float[DROPS_COUNT * DROP_FLOATS];
    private final float[] mSeaDrops2 = new float[DROPS2_COUNT * DROP2_FLOATS];
    private final float[] mSeaDrops2Backup = new float[DROPS2_COUNT * DROP2_FLOATS];
    private final float[] mSeaDrops3 = new float[DROPS3_COUNT * DROP2_FLOATS];
    private final float[] mSeaDrops3Backup = new float[DROPS3_COUNT * DROP2_FLOATS];
    private final float[] mJellyDropsA = new float[JELLY_DROPS_A_COUNT * DROP_FLOATS];
    private final float[] mJellyDropsB = new float[JELLY_DROPS_B_COUNT * DROP_FLOATS];
    private final Random mRandom = new Random(System.currentTimeMillis());

    DeepSeaVkDelegate(Context context, SharedPreferences prefs) {
        super(context);
        mPrefs = prefs;
        Matrix.setIdentityM(mIdentity, 0);
    }

    @Override
    public String logTag() {
        return TAG;
    }

    /**
     * 设置页按方法名反射 {@code setPluginPrefs(SharedPreferences)} 把新设置推给预览，
     * 所以返回 delegate 自身；{@link DeepSeaContainer} 是 GLES 专有的，没法和 VK 共用。
     */
    @Override
    public Object getScene() {
        return this;
    }

    @Override
    public boolean isReady() {
        return mSceneReady;
    }

    public void setPluginPrefs(SharedPreferences prefs) {
        mPrefs = prefs;
        mSettingsSyncRequested = true;
    }

    @Override
    public void onPluginPrefsChanged(SharedPreferences prefs) {
        setPluginPrefs(prefs);
    }

    // ─────────────────────────── 生命周期 ───────────────────────────

    @Override
    public void ensureScene(int width, int height) {
        if (width <= 0 || height <= 0) return;
        synchronized (mLock) {
            mWidth = width;
            mHeight = height;
            if (!mSceneReady) {
                Matrix.setLookAtM(
                        mViewMatrix, 0, 0.0f, 0.0f, -0.5f, 0.0f, 0.0f, -5.0f, 0.0f, 1.0f, 0.0f);
                mBackgroundImagePath = cacheDir();
                generateParticleData();
                mSceneReady = true;
                // 与 DeepSeaScene.onCreate 的顺序一致：先应用设置（初始化水母列表），再设投影
                applyPreferences(mPrefs);
            }
            setProjection(width, height);
        }
    }

    @Override
    public void onVisibilityChanged(boolean visible) {
        mVisible = visible;
        if (visible) {
            registerSensor();
            // 与 GLES 的 start() 相同：重新开始时重置水母
            mResetRequested = true;
        } else {
            unregisterSensor();
        }
    }

    @Override
    public void onSurfaceCreated(Surface surface, int width, int height) {
        super.onSurfaceCreated(surface, width, height);
        registerSensor();
        mResetRequested = true;
    }

    @Override
    public void onSurfaceDestroyed() {
        unregisterSensor();
        super.onSurfaceDestroyed();
    }

    @Override
    public void destroyRenderer() {
        unregisterSensor();
        super.destroyRenderer();
    }

    // ─────────────────────────── 陀螺仪 ───────────────────────────

    /** 只在可见时注册；重复调用是幂等的。 */
    private void registerSensor() {
        if (!mVisible || mSensorRegistered) return;
        if (mSensorManager == null) {
            mSensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            if (mSensorManager != null) {
                mGyro = mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            }
        }
        if (mSensorManager == null || mGyro == null) return;
        mSensorRegistered =
                mSensorManager.registerListener(this, mGyro, SensorManager.SENSOR_DELAY_GAME);
    }

    private void unregisterSensor() {
        if (mSensorManager != null && mSensorRegistered) {
            mSensorManager.unregisterListener(this);
        }
        mSensorRegistered = false;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null || event.values == null || event.values.length < 3) return;
        // 与 DeepSeaGL.onSensorChanged 相同的阈值与力度换算
        float z = event.values[2];
        if (z > 2.5f || z < -2.5f) {
            if (z > 0.0f) z += 10.0f;
            float force = -(z < 0.0f ? z - 10.0f : z);
            mPendingShakeForce = force;
            mHasPendingShake = true;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    // ─────────────────────────── 声明场景 ───────────────────────────

    @Override
    protected void buildScene(VkPassScene scene) {
        mPassScene = scene;
        // 新建的 native 场景没有纹理，全部重新上传
        mStaticTexturesUploaded = false;
        mAppliedBackgroundKey = null;
        mHasBackground = false;
        mDrawDrops3 = 0;

        // glClearColor(0, 0, 0, 0.5)
        scene.setClearColor(0.0f, 0.0f, 0.0f, 0.5f);

        scene.createTexture(TEX_SEA);
        scene.createTexture(TEX_BACKGROUND);
        scene.createTexture(TEX_SUNSHINE);
        scene.createTexture(TEX_SUNSHINE_0);
        scene.createTexture(TEX_SUNSHINE_1);
        scene.createTexture(TEX_JELLY_A);
        scene.createTexture(TEX_JELLY_A_ALPHA);
        scene.createTexture(TEX_JELLY_B);
        scene.createTexture(TEX_JELLY_B_ALPHA);
        scene.createTexture(TEX_BLUR_A);
        scene.createTexture(TEX_BLUR_A_ALPHA);
        scene.createTexture(TEX_BLUR_B);
        scene.createTexture(TEX_BLUR_B_ALPHA);
        scene.createTexture(TEX_CORE);
        scene.createTexture(TEX_CORE_ALPHA);
        scene.createTexture(TEX_PARTICLE);
        scene.createTexture(TEX_PARTICLE_ALPHA);

        scene.createBuffer(BUF_SEA, QUAD_FLOATS * 4, QUAD_VERTICES, true);
        scene.createBuffer(BUF_BACKGROUND, QUAD_FLOATS * 4, QUAD_VERTICES, true);
        scene.createBuffer(BUF_SUNSHINE, QUAD_FLOATS * 4, QUAD_VERTICES, true);
        scene.createBuffer(BUF_DROPS, DROP_FLOATS * 4, DROPS_COUNT, true);
        scene.createBuffer(BUF_DROPS2, DROP2_FLOATS * 4, DROPS2_COUNT, true);
        scene.createBuffer(BUF_DROPS3, DROP2_FLOATS * 4, DROPS3_COUNT, true);
        scene.createBuffer(BUF_JELLY_A, JELLY_FLOATS * 4, MAX_JELLY_VERTICES, true);
        scene.createBuffer(BUF_JELLY_B, JELLY_FLOATS * 4, MAX_JELLY_VERTICES, true);
        scene.createBuffer(BUF_BLUR_A, JELLY_FLOATS * 4, MAX_JELLY_VERTICES, true);
        scene.createBuffer(BUF_BLUR_B, JELLY_FLOATS * 4, MAX_JELLY_VERTICES, true);
        scene.createBuffer(BUF_CORE, JELLY_FLOATS * 4, MAX_JELLY_VERTICES, true);
        scene.createBuffer(
                BUF_JELLY_DROPS, JELLY_DROP_FLOATS * 4, MAX_JELLYFISH * JELLY_DROPS_A_COUNT, true);
        scene.createBuffer(
                BUF_JELLY_DROPS2, JELLY_DROP_FLOATS * 4, MAX_JELLYFISH * JELLY_DROPS_B_COUNT, true);

        mSeaBuffer = ByteBuffer.allocateDirect(QUAD_VERTICES * QUAD_FLOATS * 4)
                .order(ByteOrder.nativeOrder());
        mBackgroundBuffer = ByteBuffer.allocateDirect(QUAD_VERTICES * QUAD_FLOATS * 4)
                .order(ByteOrder.nativeOrder());
        mSunBuffer = ByteBuffer.allocateDirect(QUAD_VERTICES * QUAD_FLOATS * 4)
                .order(ByteOrder.nativeOrder());
        mDropsBuffer = ByteBuffer.allocateDirect(DROPS_COUNT * DROP_FLOATS * 4)
                .order(ByteOrder.nativeOrder());
        mDrops2Buffer = ByteBuffer.allocateDirect(DROPS2_COUNT * DROP2_FLOATS * 4)
                .order(ByteOrder.nativeOrder());
        mDrops3Buffer = ByteBuffer.allocateDirect(DROPS3_COUNT * DROP2_FLOATS * 4)
                .order(ByteOrder.nativeOrder());
        mJellyABuffer = allocateJellyBuffer();
        mJellyBBuffer = allocateJellyBuffer();
        mBlurABuffer = allocateJellyBuffer();
        mBlurBBuffer = allocateJellyBuffer();
        mCoreBuffer = allocateJellyBuffer();
        mJellyDropsBuffer = ByteBuffer.allocateDirect(
                        MAX_JELLYFISH * JELLY_DROPS_A_COUNT * JELLY_DROP_FLOATS * 4)
                .order(ByteOrder.nativeOrder());
        mJellyDrops2Buffer = ByteBuffer.allocateDirect(
                        MAX_JELLYFISH * JELLY_DROPS_B_COUNT * JELLY_DROP_FLOATS * 4)
                .order(ByteOrder.nativeOrder());

        mSeaPass = addAdditivePass(
                scene,
                VS_BG,
                FS_BG,
                new int[] {3, 2},
                QUAD_FLOATS * 4,
                VkPass.TOPOLOGY_TRIANGLE_LIST,
                BUF_SEA,
                BG_PUSH_BYTES);
        mSeaPass.texture(0, TEX_SEA);

        mSea2Pass = addAdditivePass(
                scene,
                VS_BG,
                FS_BG,
                new int[] {3, 2},
                QUAD_FLOATS * 4,
                VkPass.TOPOLOGY_TRIANGLE_LIST,
                BUF_BACKGROUND,
                BG_PUSH_BYTES);
        mSea2Pass.texture(0, TEX_BACKGROUND);

        mSunPass = addAdditivePass(
                scene,
                VS_SUNSHINE,
                FS_SUNSHINE,
                new int[] {3, 2},
                QUAD_FLOATS * 4,
                VkPass.TOPOLOGY_TRIANGLE_LIST,
                BUF_SUNSHINE,
                SUNSHINE_PUSH_BYTES);
        mSunPass.texture(0, TEX_SUNSHINE);
        mSunPass0 = addAdditivePass(
                scene,
                VS_SUNSHINE,
                FS_SUNSHINE,
                new int[] {3, 2},
                QUAD_FLOATS * 4,
                VkPass.TOPOLOGY_TRIANGLE_LIST,
                BUF_SUNSHINE,
                SUNSHINE_PUSH_BYTES);
        mSunPass0.texture(0, TEX_SUNSHINE_0);
        mSunPass1 = addAdditivePass(
                scene,
                VS_SUNSHINE,
                FS_SUNSHINE,
                new int[] {3, 2},
                QUAD_FLOATS * 4,
                VkPass.TOPOLOGY_TRIANGLE_LIST,
                BUF_SUNSHINE,
                SUNSHINE_PUSH_BYTES);
        mSunPass1.texture(0, TEX_SUNSHINE_1);

        mDropsPass = addAdditivePass(
                scene,
                VS_DROPS,
                FS_DROPS,
                new int[] {3, 3, 1, 1, 1, 1},
                DROP_FLOATS * 4,
                VkPass.TOPOLOGY_POINT_LIST,
                BUF_DROPS,
                DROPS_PUSH_BYTES);
        mDropsPass.texture(0, TEX_PARTICLE);
        mDropsPass.texture(1, TEX_PARTICLE_ALPHA);

        mDrops2Pass = addAdditivePass(
                scene,
                VS_DROPS2,
                FS_DROPS2,
                new int[] {3, 3, 1, 1, 1, 1},
                DROP2_FLOATS * 4,
                VkPass.TOPOLOGY_POINT_LIST,
                BUF_DROPS2,
                DROPS_PUSH_BYTES);
        mDrops2Pass.texture(0, TEX_PARTICLE);
        mDrops2Pass.texture(1, TEX_PARTICLE_ALPHA);

        mDrops3Pass = addAdditivePass(
                scene,
                VS_DROPS3,
                FS_DROPS3,
                new int[] {3, 3, 1, 1, 1, 1},
                DROP2_FLOATS * 4,
                VkPass.TOPOLOGY_POINT_LIST,
                BUF_DROPS3,
                DROPS_PUSH_BYTES);
        mDrops3Pass.texture(0, TEX_PARTICLE);
        mDrops3Pass.texture(1, TEX_PARTICLE_ALPHA);

        mJellyAPass = addJellyPass(scene, BUF_JELLY_A, TEX_JELLY_A, TEX_JELLY_A_ALPHA);
        mJellyBPass = addJellyPass(scene, BUF_JELLY_B, TEX_JELLY_B, TEX_JELLY_B_ALPHA);
        mBlurAPass = addJellyPass(scene, BUF_BLUR_A, TEX_BLUR_A, TEX_BLUR_A_ALPHA);
        mBlurBPass = addJellyPass(scene, BUF_BLUR_B, TEX_BLUR_B, TEX_BLUR_B_ALPHA);
        mCorePass = addJellyPass(scene, BUF_CORE, TEX_CORE, TEX_CORE_ALPHA);

        mJellyDropsPass = addAdditivePass(
                scene,
                VS_JELLY_DROPS,
                FS_JELLY_DROPS,
                new int[] {3, 3, 1, 1, 1, 1, 3, 1, 3, 1, 1, 1},
                JELLY_DROP_FLOATS * 4,
                VkPass.TOPOLOGY_POINT_LIST,
                BUF_JELLY_DROPS,
                JELLY_DROPS_PUSH_BYTES);
        mJellyDropsPass.texture(0, TEX_PARTICLE);
        mJellyDropsPass.texture(1, TEX_PARTICLE_ALPHA);

        mJellyDrops2Pass = addAdditivePass(
                scene,
                VS_JELLY_DROPS,
                FS_JELLY_DROPS,
                new int[] {3, 3, 1, 1, 1, 1, 3, 1, 3, 1, 1, 1},
                JELLY_DROP_FLOATS * 4,
                VkPass.TOPOLOGY_POINT_LIST,
                BUF_JELLY_DROPS2,
                JELLY_DROPS_PUSH_BYTES);
        mJellyDrops2Pass.texture(0, TEX_PARTICLE);
        mJellyDrops2Pass.texture(1, TEX_PARTICLE_ALPHA);
    }

    private static ByteBuffer allocateJellyBuffer() {
        return ByteBuffer.allocateDirect(MAX_JELLY_VERTICES * JELLY_FLOATS * 4)
                .order(ByteOrder.nativeOrder());
    }

    /** 所有 pass 都是 GLES 的 glBlendFunc(GL_SRC_ALPHA, GL_ONE)。 */
    private static VkPass addAdditivePass(
            VkPassScene scene,
            String vs,
            String fs,
            int[] attribSizes,
            int strideBytes,
            int topology,
            String buffer,
            int pushBytes) {
        VkPass pass = scene.add(new VkPass(
                        vs, fs, attribSizes, strideBytes, topology, VkPass.BLEND_ADDITIVE))
                .buffer(buffer, 0)
                .pushConstants(pushBytes)
                .blendFactors(
                        VkPass.FACTOR_SRC_ALPHA,
                        VkPass.FACTOR_ONE,
                        VkPass.FACTOR_SRC_ALPHA,
                        VkPass.FACTOR_ONE);
        return pass;
    }

    private static VkPass addJellyPass(
            VkPassScene scene, String buffer, String texture, String alphaTexture) {
        VkPass pass = scene.add(new VkPass(
                        VS_JELLYFISH,
                        FS_JELLYFISH,
                        new int[] {4, 2, 1, 3},
                        JELLY_FLOATS * 4,
                        VkPass.TOPOLOGY_TRIANGLE_LIST,
                        VkPass.BLEND_ADDITIVE))
                .buffer(buffer, 0)
                .blendFactors(
                        VkPass.FACTOR_SRC_ALPHA,
                        VkPass.FACTOR_ONE,
                        VkPass.FACTOR_SRC_ALPHA,
                        VkPass.FACTOR_ONE);
        pass.texture(0, texture);
        pass.texture(1, alphaTexture);
        return pass;
    }

    // ─────────────────────────── 每帧 ───────────────────────────

    @Override
    protected void updateFrame(VkPassFrame frame, long timeMs) {
        if (mPassScene == null) return;
        synchronized (mLock) {
            if (!mSceneReady) {
                setEmptyFrame(frame);
                return;
            }
            if (mSettingsSyncRequested) {
                mSettingsSyncRequested = false;
                applyPreferences(mPrefs);
            }
            if (mResetRequested) {
                mResetRequested = false;
                // 对应 DeepSeaContainer.screenOn()
                setNumberOfUnit(mUnitNumberVal);
            }
            if (mHasPendingShake) {
                mHasPendingShake = false;
                setByShake(mPendingShakeForce);
            }
            pollBattery(timeMs);
            ensureStaticTextures();
            syncBackground();

            buildSeaVertices();
            buildSunshineVertices();
            buildSeaDropsVertices();

            updateScene();

            buildJellyfishVertices();

            changeDestination();
            if (mTime > 800.0f) mTime = 0.0f;

            uploadAndDraw(frame);
        }
    }

    private void setEmptyFrame(VkPassFrame frame) {
        frame.setVertexCount(mSeaPass, 0);
        frame.setVertexCount(mSea2Pass, 0);
        frame.setVertexCount(mSunPass, 0);
        frame.setVertexCount(mSunPass0, 0);
        frame.setVertexCount(mSunPass1, 0);
        frame.setVertexCount(mDropsPass, 0);
        frame.setVertexCount(mDrops2Pass, 0);
        frame.setVertexCount(mDrops3Pass, 0);
        frame.setVertexCount(mJellyAPass, 0);
        frame.setVertexCount(mJellyBPass, 0);
        frame.setVertexCount(mBlurAPass, 0);
        frame.setVertexCount(mBlurBPass, 0);
        frame.setVertexCount(mCorePass, 0);
        frame.setVertexCount(mJellyDropsPass, 0);
        frame.setVertexCount(mJellyDrops2Pass, 0);
    }

    // ─────────────────────────── 每帧上传 ───────────────────────────

    private void uploadAndDraw(VkPassFrame frame) {
        computeMvp(mIdentity, mViewProj);
        Matrix.setIdentityM(mTmpTranslate, 0);
        Matrix.translateM(mTmpTranslate, 0, 0.0f, 0.0f, -2.0f);
        computeMvp(mTmpTranslate, mDropsMvp);

        // 背景
        if (mHasBackground) {
            frame.upload(BUF_BACKGROUND, mBackgroundBuffer, QUAD_VERTICES * QUAD_FLOATS * 4);
            frame.setVertexCount(mSea2Pass, QUAD_VERTICES);
            frame.setVertexCount(mSeaPass, 0);
        } else {
            frame.upload(BUF_SEA, mSeaBuffer, QUAD_VERTICES * QUAD_FLOATS * 4);
            frame.setVertexCount(mSeaPass, QUAD_VERTICES);
            frame.setVertexCount(mSea2Pass, 0);
        }
        System.arraycopy(mViewProj, 0, mPushBg, 0, 16);
        mPushBg[16] = mBrightness;
        frame.pushConstants(mSeaPass, mPushBg);
        // GLES 版从未把 mBrightness 设到 Sea2 上（它自己的默认值是 0）
        mPushBg[16] = 0.0f;
        frame.pushConstants(mSea2Pass, mPushBg);

        // 三张阳光贴图
        if (mHasBackground) {
            frame.setVertexCount(mSunPass, 0);
            frame.setVertexCount(mSunPass0, 0);
            frame.setVertexCount(mSunPass1, 0);
        } else {
            frame.upload(BUF_SUNSHINE, mSunBuffer, QUAD_VERTICES * QUAD_FLOATS * 4);
            frame.setVertexCount(mSunPass, QUAD_VERTICES);
            frame.setVertexCount(mSunPass0, QUAD_VERTICES);
            frame.setVertexCount(mSunPass1, QUAD_VERTICES);
            pushSunshine(frame, mSunPass, mAlphaOfSunshine);
            pushSunshine(frame, mSunPass0, mAlphaOfSunshine1);
            pushSunshine(frame, mSunPass1, mAlphaOfSunshine2);
        }

        // 海面水滴
        copyToBuffer(mDropsBuffer, mSeaDrops);
        frame.upload(BUF_DROPS, mDropsBuffer, DROPS_COUNT * DROP_FLOATS * 4);
        frame.setVertexCount(mDropsPass, DROPS_COUNT);
        pushDrops(frame, mDropsPass, mDropsTime);

        copyToBuffer(mDrops2Buffer, mSeaDrops2);
        frame.upload(BUF_DROPS2, mDrops2Buffer, DROPS2_COUNT * DROP2_FLOATS * 4);
        frame.setVertexCount(mDrops2Pass, DROPS2_COUNT);
        pushDrops(frame, mDrops2Pass, mDropsTime2);

        if (mDrawDrops3 > 0) {
            copyToBuffer(mDrops3Buffer, mSeaDrops3);
            frame.upload(BUF_DROPS3, mDrops3Buffer, DROPS3_COUNT * DROP2_FLOATS * 4);
            frame.setVertexCount(mDrops3Pass, DROPS3_COUNT);
            pushDrops(frame, mDrops3Pass, mDropsTime3);
        } else {
            frame.setVertexCount(mDrops3Pass, 0);
        }

        // 水母与光晕
        uploadJelly(frame, BUF_JELLY_A, mJellyABuffer, mJellyAPass, mJellyACount);
        uploadJelly(frame, BUF_JELLY_B, mJellyBBuffer, mJellyBPass, mJellyBCount);
        uploadJelly(frame, BUF_BLUR_A, mBlurABuffer, mBlurAPass, mBlurACount);
        uploadJelly(frame, BUF_BLUR_B, mBlurBBuffer, mBlurBPass, mBlurBCount);
        uploadJelly(frame, BUF_CORE, mCoreBuffer, mCorePass, mCoreCount);

        // 水母周围的水滴
        if (mJellyDropsCount > 0) {
            frame.upload(
                    BUF_JELLY_DROPS, mJellyDropsBuffer, mJellyDropsCount * JELLY_DROP_FLOATS * 4);
        }
        frame.setVertexCount(mJellyDropsPass, mJellyDropsCount);
        pushJellyDrops(frame, mJellyDropsPass, 0.6f, 0.0f, 0.5f);

        if (mJellyDrops2Count > 0) {
            frame.upload(
                    BUF_JELLY_DROPS2,
                    mJellyDrops2Buffer,
                    mJellyDrops2Count * JELLY_DROP_FLOATS * 4);
        }
        frame.setVertexCount(mJellyDrops2Pass, mJellyDrops2Count);
        pushJellyDrops(frame, mJellyDrops2Pass, 0.09f, 1.0f, 0.25f);
    }

    private void uploadJelly(
            VkPassFrame frame, String buffer, ByteBuffer data, VkPass pass, int count) {
        if (count > 0) {
            frame.upload(buffer, data, count * JELLY_FLOATS * 4);
        }
        frame.setVertexCount(pass, count);
    }

    private void pushSunshine(VkPassFrame frame, VkPass pass, float addAlpha) {
        System.arraycopy(mViewProj, 0, mPushSun, 0, 16);
        mPushSun[16] = addAlpha;
        mPushSun[17] = mLight;
        frame.pushConstants(pass, mPushSun);
    }

    private void pushDrops(VkPassFrame frame, VkPass pass, float time) {
        System.arraycopy(mDropsMvp, 0, mPushDrops, 0, 16);
        mPushDrops[16] = time;
        frame.pushConstants(pass, mPushDrops);
    }

    private void pushJellyDrops(
            VkPassFrame frame, VkPass pass, float moveScale, float emitterPull, float alphaScale) {
        System.arraycopy(mViewProj, 0, mPushJellyDrops, 0, 16);
        mPushJellyDrops[16] = moveScale;
        mPushJellyDrops[17] = emitterPull;
        mPushJellyDrops[18] = alphaScale;
        frame.pushConstants(pass, mPushJellyDrops);
    }

    // ─────────────────────────── 背景与阳光几何 ───────────────────────────

    private void buildSeaVertices() {
        if (mHasBackground) {
            float aspect = mWidth / (float) mHeight;
            float width;
            float top;
            float bottom;
            if (mWidth > mHeight) {
                width = 10.6f * aspect;
                top = 9.5f * aspect;
                bottom = 10.2f * aspect;
            } else {
                width = 10.6f;
                top = 9.8f;
                bottom = 10.6f;
            }
            mBackgroundBuffer.clear();
            putTexturedQuad(mBackgroundBuffer, -width, top, width, -bottom, -10.0f);
        } else {
            // Sea.changeDisplay
            float aspectScaled = (mWidth / (float) mHeight) * 10.0f;
            float width = aspectScaled + (0.4f * aspectScaled);
            mSeaBuffer.clear();
            putTexturedQuad(mSeaBuffer, -width, 15.5f, width, -15.5f, -10.0f);
        }
    }

    private void buildSunshineVertices() {
        if (mHasBackground) return;
        updateSunshineAlpha();
        // Sunshine.changeDisplay
        float aspectScaled = (mWidth / (float) mHeight) * 10.0f;
        float width = aspectScaled + (0.5f * aspectScaled);
        float height = (0.55f * 10.0f) + 10.0f;
        mSunBuffer.clear();
        putTexturedQuad(mSunBuffer, -width, height, width, -height, -10.0f);
    }

    /** 与 DeepSeaContainer.draw 中的三条阳光 alpha 动画相同。 */
    private void updateSunshineAlpha() {
        if (mBatteryState == 0) {
            mAlphaOfSunshine += 0.01f * (mDestinationAlphaOfSunshine - mAlphaOfSunshine);
            mAlphaOfSunshine1 += 0.02f * (mDestinationAlphaOfSunshine1 - mAlphaOfSunshine1);
            mAlphaOfSunshine2 += 0.012f * (mDestinationAlphaOfSunshine2 - mAlphaOfSunshine2);
        } else {
            mAlphaOfSunshine += 0.001f * (mDestinationAlphaOfSunshine - mAlphaOfSunshine);
            mAlphaOfSunshine1 += 0.002f * (mDestinationAlphaOfSunshine1 - mAlphaOfSunshine1);
            mAlphaOfSunshine2 += 0.0012f * (mDestinationAlphaOfSunshine2 - mAlphaOfSunshine2);
        }
        if (mDestinationAlphaOfSunshine == 0.2f) {
            if (mAlphaOfSunshine < 0.78f) mDestinationAlphaOfSunshine = 1.0f;
        } else if (mAlphaOfSunshine > 0.9f) {
            mDestinationAlphaOfSunshine = 0.2f;
        }
        if (mDestinationAlphaOfSunshine1 == 0.0f) {
            if (mAlphaOfSunshine1 < 0.4f) mDestinationAlphaOfSunshine1 = 1.0f;
        } else if (mAlphaOfSunshine1 > 0.8f) {
            mDestinationAlphaOfSunshine1 = 0.0f;
        }
        if (mDestinationAlphaOfSunshine2 == 0.0f) {
            if (mAlphaOfSunshine2 < 0.4f) mDestinationAlphaOfSunshine2 = 1.0f;
        } else if (mAlphaOfSunshine2 > 0.9f) {
            mDestinationAlphaOfSunshine2 = 0.0f;
        }
    }

    private static void putTexturedQuad(
            ByteBuffer buffer, float left, float top, float right, float bottom, float z) {
        // 与 GLES 的顶点顺序一致：左上、左下、右下，再左上、右下、右上
        putQuadVertex(buffer, left, top, z, 0f, 0f);
        putQuadVertex(buffer, left, bottom, z, 0f, 1f);
        putQuadVertex(buffer, right, bottom, z, 1f, 1f);
        putQuadVertex(buffer, left, top, z, 0f, 0f);
        putQuadVertex(buffer, right, bottom, z, 1f, 1f);
        putQuadVertex(buffer, right, top, z, 1f, 0f);
    }

    private static void putQuadVertex(
            ByteBuffer buffer, float x, float y, float z, float u, float v) {
        buffer.putFloat(x).putFloat(y).putFloat(z).putFloat(u).putFloat(v);
    }

    // ─────────────────────────── 海面水滴几何 ───────────────────────────

    private void buildSeaDropsVertices() {
        // GLES 在每次 setForDrawing 时推进时间：前两组每帧都画，第三组只在摇动后画
        float step = mSlow ? 0.003f : 0.01f;
        mDropsTime += step;
        mDropsTime2 += step;
        mDrawDrops3 = 0;
        if (mIsAfterShaking) {
            mDropsTime3 += 0.05f;
            mDrawDrops3 = DROPS3_COUNT;
        }
    }

    private static void copyToBuffer(ByteBuffer buffer, float[] data) {
        buffer.clear();
        buffer.asFloatBuffer().put(data);
    }

    // ─────────────────────────── 场景更新 ───────────────────────────

    private void updateScene() {
        if (mTime % 1.0f == 0.0f && !mIsAllFinishedAppearing) {
            checkAppearing();
        }
        if (!mIsShaking) {
            updateForGoingToMove();
        } else {
            updateForGoingToMoveWhenShaking(mForce);
        }
        mTime += 1.0f;
        if (mTime % 150.0f == 0.0f && !mIsShaking) {
            startZMove();
        }
        updateBlurAlpha();
    }

    private void updateBlurAlpha() {
        if (mIsBluring) {
            mBlurAlpha += 0.006f;
            if (mBlurAlpha >= 1.0f) {
                mBlurAlpha = 1.0f;
                mIsBluring = false;
            }
        } else {
            mBlurAlpha -= 0.006f;
            if (mIsRemoving) {
                mBlurAlpha -= 0.01f;
            }
            if (mBlurAlpha <= 0.0f) {
                mBlurAlpha = 0.0f;
                mIsBluring = true;
                checkBlur();
            }
        }
    }

    // ─────────────────────────── 水母四边形 ───────────────────────────

    private void buildJellyfishVertices() {
        mJellyABuffer.clear();
        mJellyBBuffer.clear();
        mBlurABuffer.clear();
        mBlurBBuffer.clear();
        mCoreBuffer.clear();
        mJellyACount = 0;
        mJellyBCount = 0;
        mBlurACount = 0;
        mBlurBCount = 0;
        mCoreCount = 0;
        mJellyDropsCount = 0;
        mJellyDrops2Count = 0;

        int numberOfJellyfish = mJellyfishVO.getNumberOfJellyfish();
        ArrayList<JellyfishVO> aList = mJellyfishVO.getAList();
        ArrayList<JellyfishVO> bList = mJellyfishVO.getBList();
        if (aList == null || bList == null) return;
        int divide = mJellyfishVO.getABDivide();

        for (int i = 0; i < numberOfJellyfish; i++) {
            JellyfishVO vo = mJellyfishVO.getVOByIndex(i);
            boolean useB = i >= divide;
            JellyfishVO alphaVO = useB
                    ? bList.get(MathHelper.getIndexForFragmentShader(i - divide, bList.size()))
                    : aList.get(MathHelper.getIndexForFragmentShader(i, aList.size()));

            // 与 DeepSeaContainer.drawForGoingToMove 相同的深度 alpha
            float depthAlpha = 1.0f - ((-(alphaVO.getPositionZ() + 3.0f)) / 7.0f);
            if (depthAlpha < 0.0f) depthAlpha = 0.0f;
            float invertedDepthAlpha = 1.0f - depthAlpha;
            float f;
            float f2;
            if (alphaVO.isAppearing()
                    || alphaVO.isRemoving()
                    || alphaVO.isFinishedRemoving()
                    || !alphaVO.isFinishedAppearing()) {
                f = invertedDepthAlpha - alphaVO.getReverseAlpha();
                f2 = depthAlpha - alphaVO.getReverseAlpha();
            } else {
                f = invertedDepthAlpha;
                f2 = depthAlpha;
            }

            // 模型矩阵：平移 · 绕 z 旋转 · 缩放（缩放也在 MVP 之前）
            Matrix.setIdentityM(mModelMatrix, 0);
            Matrix.translateM(
                    mModelMatrix, 0, vo.getPositionX(), vo.getPositionY(), vo.getPositionZ());
            Matrix.rotateM(mModelMatrix, 0, vo.getRotate(), 0.0f, 0.0f, 1.0f);
            Matrix.scaleM(mModelMatrix, 0, mScale, mScale, 1.0f);
            computeMvp(mModelMatrix, mMvpMatrix);
            Matrix.multiplyMM(mClipMvp, 0, CLIP_CORRECTION, 0, mMvpMatrix, 0);

            if (useB) {
                mJellyBCount = addJellyQuad(mJellyBBuffer, mJellyBCount, f2 - 1.0f, 0f, 0f, 0f);
                mBlurBCount = addJellyQuad(mBlurBBuffer, mBlurBCount, f - 1.0f, 0f, 0f, 0f);
            } else {
                mJellyACount = addJellyQuad(mJellyABuffer, mJellyACount, f2 - 1.0f, 0f, 0f, 0f);
                mBlurACount = addJellyQuad(mBlurABuffer, mBlurACount, f - 1.0f, 0f, 0f, 0f);
            }
            if (vo.hasBlur()) {
                mCoreCount = addJellyQuad(
                        mCoreBuffer,
                        mCoreCount,
                        mBlurAlpha - 1.0f,
                        mBlurColor[0],
                        mBlurColor[1],
                        mBlurColor[2]);
            }

            // 水母周围的水滴：位置用当前水母，alpha 用下一只（与 GLES 一致）
            JellyfishVO nextVO = mJellyfishVO.getVOByIndex(
                    MathHelper.getIndexForFragmentShader(i, numberOfJellyfish));
            float positionZ = nextVO.getPositionZ();
            float positionDepthAlpha = 1.0f - ((-(positionZ + 3.0f)) / 7.0f);
            if (positionDepthAlpha < 0.0f) positionDepthAlpha = 0.0f;
            if (nextVO.isAppearing()
                    || nextVO.isRemoving()
                    || nextVO.isFinishedRemoving()
                    || !nextVO.isFinishedAppearing()) {
                positionDepthAlpha -= 0.4f + nextVO.getReverseAlpha();
            }

            // GLES 每个水母各调用一次 setForDrawing，时间各推进一次
            float step = mSlow ? 0.0025f : 0.005f;
            mJellyDropsTimeA += step;
            mJellyDropsTimeB += step;
            mJellyDropsCount = addJellyDrops(
                    mJellyDropsBuffer,
                    mJellyDropsCount,
                    mJellyDropsA,
                    JELLY_DROPS_A_COUNT,
                    vo,
                    positionDepthAlpha - 1.0f,
                    mJellyDropsTimeA);
            mJellyDrops2Count = addJellyDrops(
                    mJellyDrops2Buffer,
                    mJellyDrops2Count,
                    mJellyDropsB,
                    JELLY_DROPS_B_COUNT,
                    vo,
                    positionDepthAlpha - 1.0f,
                    mJellyDropsTimeB);
        }
    }

    /**
     * 写一个水母四边形（六个顶点）。
     *
     * <p>裁剪坐标在 CPU 侧算好，顶点着色器直接输出，省掉逐只水母的 push constant。
     */
    private int addJellyQuad(
            ByteBuffer buffer, int count, float addAlpha, float r, float g, float b) {
        if (count + QUAD_VERTICES > MAX_JELLY_VERTICES) return count;
        for (int k = 0; k < QUAD_ORDER.length; k++) {
            int index = QUAD_ORDER[k];
            mQuadPoint[0] = QUAD_X[index];
            mQuadPoint[1] = QUAD_Y[index];
            mQuadPoint[2] = 0.0f;
            mQuadPoint[3] = 1.0f;
            Matrix.multiplyMV(mQuadOut, 0, mClipMvp, 0, mQuadPoint, 0);
            buffer.putFloat(mQuadOut[0])
                    .putFloat(mQuadOut[1])
                    .putFloat(mQuadOut[2])
                    .putFloat(mQuadOut[3])
                    .putFloat(QUAD_U[index])
                    .putFloat(QUAD_V[index])
                    .putFloat(addAlpha)
                    .putFloat(r)
                    .putFloat(g)
                    .putFloat(b);
        }
        return count + QUAD_VERTICES;
    }

    // ─────────────────────────── 水母周围的水滴 ───────────────────────────

    private int addJellyDrops(
            ByteBuffer buffer,
            int count,
            float[] data,
            int particleCount,
            JellyfishVO vo,
            float addAlpha,
            float time) {
        int maxCount = particleCount * MAX_JELLYFISH;
        if (count + particleCount > maxCount) return count;
        float angle = (float) Math.toRadians(vo.getRotate());
        float tx = vo.getPositionX();
        float ty = vo.getPositionY();
        float tz = vo.getPositionZ();
        for (int i = 0; i < particleCount; i++) {
            int offset = i * DROP_FLOATS;
            // 只有位置、移动、生命、年龄、大小进顶点；速度与发射点在这套着色器里用不到
            for (int k = 0; k < 9; k++) {
                buffer.putFloat(data[offset + k]);
            }
            buffer.putFloat(angle)
                    .putFloat(tx)
                    .putFloat(ty)
                    .putFloat(tz)
                    .putFloat(mScale)
                    .putFloat(addAlpha)
                    .putFloat(time);
        }
        return count + particleCount;
    }

    // ─────────────────────────── 设置 ───────────────────────────

    private void applyPreferences(SharedPreferences prefs) {
        if (!mSceneReady) return;
        mPrefs = prefs;
        int unitScale = readIntPreference(prefs, KEY_UNIT_SCALE, 0);
        int unitNumber = readIntPreference(prefs, KEY_UNIT_NUMBER, 0);
        int brightness = readIntPreference(prefs, KEY_BACKGROUND_BRIGHTNESS, 0);
        int lighting = readIntPreference(prefs, KEY_BACKGROUND_LIGHTING, 0);
        int particleColor = readIntPreference(prefs, KEY_PARTICLE_COLOR, 8);
        String imageType = readStringPreference(prefs, KEY_BACKGROUND_IMAGE_TYPE, "0");

        mUnitScaleVal = unitScale;
        setScale();
        mUnitNumberVal = unitNumber;
        setNumberOfUnit(unitNumber);
        setBrightness(brightness);
        setLight(lighting);
        setColorByColorValue(particleColor);
        // 与 DeepSeaScene 相同：不再使用缓存背景时删掉那份文件
        if ("0".equals(imageType)) {
            DeepSeaSettings.deleteBitmap(mBackgroundImagePath);
        }
        // 设置变了就重新读一次背景
        mAppliedBackgroundKey = null;
    }

    private void setScale() {
        mScale = (mUnitScaleVal * 0.1f) + mDefaultScale;
    }

    private void setBrightness(int value) {
        if (value < 0) {
            mBrightness = ((value * 0.2f) / 5.0f) - 0.1f;
        } else if (value == 0) {
            mBrightness = -0.1f;
        } else {
            mBrightness = ((value * 0.3f) / 5.0f) - 0.1f;
        }
    }

    private void setLight(int value) {
        if (value < 0) {
            mLight = ((value * 0.52f) / 5.0f) + 0.22f;
        } else if (value == 0) {
            mLight = 0.22f;
        } else {
            mLight = ((value * 0.65f) / 5.0f) + 0.22f;
        }
    }

    private void setColorByColorValue(int colorIndex) {
        int[] colors = Palette.mValuesOfColor;
        int index = Math.max(0, Math.min(colors.length - 1, colorIndex));
        float[] rgb = MathHelper.getRGB(colors[index]);
        float[] base = MathHelper.getRGB(-5789785);
        mBlurColor[0] = rgb[0] - base[0];
        mBlurColor[1] = rgb[1] - base[1];
        mBlurColor[2] = rgb[2] - base[2];
    }

    private static int readIntPreference(SharedPreferences prefs, String key, int defaultValue) {
        if (prefs == null) return defaultValue;
        try {
            return prefs.getInt(key, defaultValue);
        } catch (ClassCastException e) {
            String value = prefs.getString(key, null);
            if (value == null) return defaultValue;
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException ex) {
                return defaultValue;
            }
        }
    }

    private static String readStringPreference(
            SharedPreferences prefs, String key, String defaultValue) {
        if (prefs == null) return defaultValue;
        try {
            return prefs.getString(key, defaultValue);
        } catch (ClassCastException e) {
            Object value = prefs.getAll().get(key);
            return value != null ? String.valueOf(value) : defaultValue;
        }
    }

    private String cacheDir() {
        if (context.getExternalCacheDir() != null) {
            return context.getExternalCacheDir().getAbsolutePath();
        }
        return context.getCacheDir().getAbsolutePath();
    }

    private void setProjection(int width, int height) {
        mWidth = width;
        mHeight = height;
        float aspect = width / (float) height;
        if (width > 720) {
            mDefaultScale = aspect;
        } else {
            mDefaultScale = 1.0f;
        }
        setScale();
        PositionVO.changePositions(width, height);
        mIntervalManager.changeMove(width, height);
        mIntervalManager.initList();
        mIntervalManager.setNotOccupiedList();
        changeSeaDropsData(width, height);
        Matrix.frustumM(mProjectionMatrix, 0, -aspect, aspect, -1.0f, 1.0f, 1.0f, 10.0f);
    }

    // ─────────────────────────── 背景 ───────────────────────────

    private void ensureStaticTextures() {
        if (mStaticTexturesUploaded || mPassScene == null) return;
        mStaticTexturesUploaded = true;
        uploadAsset(TEX_SEA, ASSET_SEA);
        uploadAsset(TEX_SUNSHINE, ASSET_SUNSHINE);
        uploadAsset(TEX_SUNSHINE_0, ASSET_SUNSHINE_0);
        uploadAsset(TEX_SUNSHINE_1, ASSET_SUNSHINE_1);
        uploadAsset(TEX_JELLY_A, ASSET_JELLY_A);
        uploadAsset(TEX_JELLY_A_ALPHA, ASSET_JELLY_A_ALPHA);
        uploadAsset(TEX_JELLY_B, ASSET_JELLY_B);
        uploadAsset(TEX_JELLY_B_ALPHA, ASSET_JELLY_B_ALPHA);
        uploadAsset(TEX_BLUR_A, ASSET_BLUR_A);
        uploadAsset(TEX_BLUR_A_ALPHA, ASSET_BLUR_A_ALPHA);
        uploadAsset(TEX_BLUR_B, ASSET_BLUR_B);
        uploadAsset(TEX_BLUR_B_ALPHA, ASSET_BLUR_B_ALPHA);
        uploadAsset(TEX_CORE, ASSET_CORE);
        uploadAsset(TEX_CORE_ALPHA, ASSET_CORE_ALPHA);
        uploadAsset(TEX_PARTICLE, ASSET_PARTICLE);
        uploadAsset(TEX_PARTICLE_ALPHA, ASSET_PARTICLE_ALPHA);
    }

    private void uploadAsset(String name, String assetPath) {
        try {
            Bitmap bitmap = AssetLoader.decodeBitmap(context, assetPath);
            if (bitmap == null) {
                Log.e(TAG, "纹理解码失败: " + assetPath);
                return;
            }
            uploadBitmap(name, bitmap);
        } catch (RuntimeException e) {
            Log.e(TAG, "纹理解码失败: " + assetPath, e);
        }
    }

    /** 与 GLES 的 DeepSeaScene.applyCachedBackground 一致：自定义图片优先，其次缓存文件。 */
    private void syncBackground() {
        if (mPassScene == null) return;
        SharedPreferences prefs = mPrefs;
        String customUri = readStringPreference(prefs, KEY_CUSTOM_BACKGROUND, null);
        String imageType = readStringPreference(prefs, KEY_BACKGROUND_IMAGE_TYPE, "0");

        String key;
        if (customUri != null && !customUri.isEmpty()) {
            key = "uri:" + customUri;
        } else if (!"0".equals(imageType)) {
            key = "cache";
        } else {
            key = null;
        }
        if (key == null ? mAppliedBackgroundKey == null : key.equals(mAppliedBackgroundKey)) {
            return;
        }

        Bitmap bitmap = null;
        if (key != null) {
            if (key.startsWith("uri:")) {
                bitmap = loadBitmapFromUri(customUri);
            } else {
                bitmap = DeepSeaSettings.loadBitmap(mBackgroundImagePath);
            }
        }
        if (bitmap != null) {
            uploadBitmap(TEX_BACKGROUND, bitmap);
            mHasBackground = true;
        } else {
            mHasBackground = false;
        }
        mAppliedBackgroundKey = key;
    }

    private Bitmap loadBitmapFromUri(String uriString) {
        try {
            Uri uri = Uri.parse(uriString);
            InputStream stream = context.getContentResolver().openInputStream(uri);
            if (stream == null) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            Bitmap bitmap = BitmapFactory.decodeStream(stream, null, options);
            stream.close();
            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "自定义背景读取失败", e);
            return null;
        }
    }

    /** 与 NexusVkDelegate.uploadBitmap 相同：位图第一行就是纹理 v=0。 */
    private boolean uploadBitmap(String name, Bitmap bitmap) {
        if (bitmap == null || mPassScene == null) return false;
        Bitmap argb = bitmap.getConfig() == Bitmap.Config.ARGB_8888
                ? bitmap
                : bitmap.copy(Bitmap.Config.ARGB_8888, false);
        if (argb != bitmap) bitmap.recycle();
        if (argb == null) return false;
        int width = argb.getWidth();
        int height = argb.getHeight();
        int[] pixels = new int[width * height];
        argb.getPixels(pixels, 0, width, 0, 0, width, height);
        argb.recycle();
        mPassScene.setTexture(name, pixels, width, height);
        return true;
    }

    // ─────────────────────────── 电量 ───────────────────────────

    private void pollBattery(long now) {
        if (now - mLastBatteryPollMs < BATTERY_POLL_MS) return;
        mLastBatteryPollMs = now;
        int state;
        try {
            BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            boolean charging = bm != null && bm.isCharging();
            int level =
                    bm != null ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : 100;
            // 取不到电量时按满电处理，避免误触发低电量的移除流程
            if (level < 0) level = 100;
            if (charging || level > 30) {
                state = 0;
            } else if (level > 15) {
                state = 1;
            } else {
                state = 2;
            }
        } catch (RuntimeException e) {
            return;
        }
        if (state != mBatteryState) {
            setStateByBattery(state);
        }
    }

    private void setStateByBattery(int batteryState) {
        switch (batteryState) {
            case 0:
                mBatteryState = 0;
                mSlow = false;
                if (mIsAllFinishedRemoving || mIsRemoving) {
                    setAppearingRemoved();
                }
                break;
            case 1:
            case 2:
                mBatteryState = batteryState;
                mSlow = true;
                if (!mIsAllFinishedRemoving || mIsAppearingRemoved) {
                    setRemoving();
                }
                break;
            default:
                break;
        }
    }

    // ─────────────────────────── 水母状态（移植自 DeepSeaContainer） ───────────────────────────

    private void setNumberOfUnit(int unitCount) {
        int maxNumberOfJellyfish = mJellyfishVO.getMaxNumberOfJellyfish();
        mBlurAlpha = 0.0f;
        mIsBluring = true;
        mTime = 0.0f;
        mIntervalManager.initList();
        mIntervalManager.setNotOccupiedList();
        mJellyfishVO.setNumberOfJellyfish(maxNumberOfJellyfish + unitCount);
        mJellyfishVO.initList();
        mJellyDropsTimeA = 0.0f;
        mJellyDropsTimeB = 0.0f;
        initListVO();
        if (mIsAllFinishedRemoving) {
            setFinishRemoving();
        }
        checkBlur();
    }

    private void setFinishRemoving() {
        int numberOfJellyfish = mJellyfishVO.getNumberOfJellyfish();
        int count = (int) (numberOfJellyfish * 0.2d);
        for (int i = 0; i < count; i++) {
            JellyfishVO vo = mJellyfishVO.getVOByIndex((numberOfJellyfish - 1) - i);
            vo.setReverseAlpha(1.0f);
            vo.setIsFinishedRemoving(true);
        }
    }

    private void setRemoving() {
        int numberOfJellyfish = mJellyfishVO.getNumberOfJellyfish();
        int count = (int) (numberOfJellyfish * 0.2d);
        mNumberOfRemoving = count;
        mIsAppearingRemoved = false;
        mIsRemoving = true;
        mIsBluring = false;
        for (int i = 0; i < count; i++) {
            JellyfishVO vo = mJellyfishVO.getVOByIndex((numberOfJellyfish - 1) - i);
            vo.setReverseAlpha(0.0f);
            vo.setIsRemoving(true);
            vo.setIsFinishedRemoving(false);
            vo.setIsFinishedAppearing(false);
        }
    }

    private void setAppearingRemoved() {
        int numberOfJellyfish = mJellyfishVO.getNumberOfJellyfish();
        mIsAppearingRemoved = true;
        mIsAllFinishedRemoving = false;
        for (int i = 0; i < numberOfJellyfish; i++) {
            JellyfishVO vo = mJellyfishVO.getVOByIndex(i);
            if (vo.isFinishedRemoving() || vo.isRemoving()) {
                vo.setIsAppearing(true);
                vo.setReverseAlpha(1.0f);
                vo.setIsFinishedAppearing(false);
                vo.setIsFinishedRemoving(false);
                vo.setIsRemoving(false);
            }
        }
    }

    private void setByShake(float force) {
        if (!mIsAllFinishedAppearing) return;
        mForce = force;
        mJellyfishVO.initMovingZ();
        mStartShakingTime = (float) SystemClock.uptimeMillis();
        mDropsTime3 = 0.0f;
        int numberOfJellyfish = mJellyfishVO.getNumberOfJellyfish();
        for (int i = 0; i < numberOfJellyfish; i++) {
            JellyfishVO vo = mJellyfishVO.getVOByIndex(i);
            vo.setIsShaking(true);
            float positionX = vo.getPositionX();
            vo.setShakingX(positionX);
            float forceX = vo.getForceX();
            if (vo.getPositionY() > 2.0f) {
                vo.setIsShakeUp(false);
            }
            if (vo.getPositionY() < -2.0f) {
                vo.setIsShakeUp(true);
            }
            if (positionX < -2.0f) positionX = -2.0f;
            if (positionX > 2.0f) positionX = 2.0f;
            if (force > 0.0f) {
                vo.setForceX(forceX + force);
                vo.setForceY(force - (((-2.0f) - positionX) * 4.0f));
            } else {
                vo.setForceX(forceX + force);
                vo.setForceY(force - ((positionX + 2.0f) * 4.0f));
            }
            if (!mIsShaking) {
                vo.setShakingO(0.0f);
                vo.setShakingR(2.0f);
                vo.setShakingAngle(0.0f);
            }
        }
        mIsShaking = true;
    }

    private void initListVO() {
        mIsAllFinishedAppearing = false;
        mAppearingIndex = 0;
        int numberOfJellyfish = mJellyfishVO.getNumberOfJellyfish();
        PositionVO.initRandomPositions();
        int numberOfZs = PositionVO.getNumberOfZs();
        for (int i = 0; i < numberOfJellyfish; i++) {
            JellyfishVO vo = mJellyfishVO.getVOByIndex(i);
            float zByIndex = PositionVO.getZByIndex(i % numberOfZs);
            IntervalVO interval;
            if (zByIndex < -4.0f) {
                interval = mIntervalManager.getNotOccupiedVOByZDown();
            } else {
                interval = mIntervalManager.getNotOccupiedVOByZUp();
            }
            if (vo != null && interval != null) {
                vo.setIndexOfInterval(interval.getID());
                float randomX = MathHelper.getRandomFloat(interval.getMinX(), interval.getMaxX());
                float randomY = MathHelper.getRandomFloat(interval.getMinY(), interval.getMaxY());
                float randomSpeed = MathHelper.getRandomFloat(0.004f, 0.007f);
                vo.setPosition(randomX, randomY, zByIndex);
                vo.setStartPosition(randomX, randomY, zByIndex);
                vo.setDestination(randomX, randomY, zByIndex);
                vo.setDestinationRotate(destinationRotation(
                        vo.getStartPositionX(),
                        vo.getStartPositionY(),
                        vo.getDestinationX(),
                        vo.getDestinationY()));
                vo.setSpeed(randomSpeed);
                vo.setStartTime((float) SystemClock.uptimeMillis());
                vo.setIsAppearing(false);
                vo.setIsFinishedAppearing(false);
                vo.setReverseAlpha(1.0f);
            }
        }
        mJellyfishVO.setABList();
    }

    private void changeDestination() {
        int numberOfJellyfish = mJellyfishVO.getNumberOfJellyfish();
        for (int i = 0; i < numberOfJellyfish; i++) {
            JellyfishVO vo = mJellyfishVO.getVOByIndex(i);
            float positionX = vo.getPositionX();
            float positionY = vo.getPositionY();
            float positionZ = vo.getPositionZ();
            boolean isMovingZ = vo.isMovingZ();
            float destinationX;
            float destinationY;
            float destinationZ;
            if (isMovingZ) {
                destinationX = vo.getMovingZDestinationX();
                destinationY = vo.getMovingZDestinationY();
                destinationZ = vo.getMovingZDestinationZ();
            } else {
                destinationX = vo.getDestinationX();
                destinationY = vo.getDestinationY();
                destinationZ = vo.getDestinationZ();
            }
            boolean reached = positionX <= destinationX + 0.3f
                    && positionX >= destinationX - 0.3f
                    && positionY <= destinationY + 0.3f
                    && positionY >= destinationY - 0.3f
                    && positionZ <= destinationZ + 0.3f
                    && positionZ >= destinationZ - 0.3f;
            if (!reached) continue;
            if (isMovingZ) {
                vo.setIsMovingZ(false);
            }
            float currentZ = vo.getPositionZ();
            IntervalVO interval;
            if (currentZ < -4.0f) {
                interval = mIntervalManager.getNotOccupiedVOByZDown();
            } else {
                interval = mIntervalManager.getNotOccupiedVOByZUp();
            }
            if (interval == null) continue;
            float randomX = MathHelper.getRandomFloat(interval.getMinX(), interval.getMaxX());
            float randomY = MathHelper.getRandomFloat(interval.getMinY(), interval.getMaxY());
            float randomSpeed = MathHelper.getRandomFloat(0.004f, 0.007f);
            vo.setStartPosition(vo.getPositionX(), vo.getPositionY(), vo.getPositionZ());
            vo.setDestination(randomX, randomY, currentZ);
            vo.setSpeed(randomSpeed);
            vo.setDestinationRotate(destinationRotation(
                    vo.getStartPositionX(),
                    vo.getStartPositionY(),
                    vo.getDestinationX(),
                    vo.getDestinationY()));
            mIntervalManager.setIsOccupiedToFalseByIndex(vo.getIndexOfInterval());
            vo.setIndexOfInterval(interval.getID());
            vo.setStartTime((float) SystemClock.uptimeMillis());
        }
    }

    private void changeDestinationByIndex(int index) {
        JellyfishVO vo = mJellyfishVO.getVOByIndex(index);
        if (!vo.isShaking()) return;
        float positionZ = vo.getPositionZ();
        IntervalVO interval;
        if (positionZ < -4.0f) {
            interval = mIntervalManager.getNotOccupiedVOByZDown();
        } else {
            interval = mIntervalManager.getNotOccupiedVOByZUp();
        }
        if (interval == null) {
            vo.setIsShaking(false);
            return;
        }
        float randomX = MathHelper.getRandomFloat(interval.getMinX(), interval.getMaxX());
        float randomY = MathHelper.getRandomFloat(interval.getMinY(), interval.getMaxY());
        float randomSpeed = MathHelper.getRandomFloat(0.004f, 0.007f);
        vo.setStartPosition(vo.getPositionX(), vo.getPositionY(), vo.getPositionZ());
        vo.setDestination(randomX, randomY, positionZ);
        vo.setSpeed(randomSpeed);
        mIntervalManager.setIsOccupiedToFalseByIndex(vo.getIndexOfInterval());
        vo.setIndexOfInterval(interval.getID());
        vo.setStartTime((float) SystemClock.uptimeMillis());
        vo.setIsShaking(false);
    }

    private void updateForGoingToMove() {
        int numberOfJellyfish = mJellyfishVO.getNumberOfJellyfish();
        for (int i = 0; i < numberOfJellyfish; i++) {
            updateForGoingToMoveByIndex(i);
        }
        if (mIsRemoving) {
            mIsAllFinishedRemoving = mJellyfishVO.isAllFinishedRemoving(mNumberOfRemoving);
            if (mIsAllFinishedRemoving) {
                mIsRemoving = false;
            }
        }
        if (mIsAppearingRemoved && mJellyfishVO.isAllFinishedAppearing()) {
            mIsAppearingRemoved = false;
        }
    }

    private void updateForGoingToMoveByIndex(int index) {
        float baseIncrement = 8.0E-4f;
        JellyfishVO vo = mJellyfishVO.getVOByIndex(index);
        float rotate = vo.getRotate();
        float destinationRotate = vo.getDestinationRotate();
        float rotationIncrement;
        if (mBatteryState == 0) {
            rotationIncrement = 0.02f;
        } else if (mBatteryState == 1) {
            rotationIncrement = 0.002f;
        } else {
            rotationIncrement = 8.0E-4f;
        }
        vo.setRotate((rotationIncrement * (destinationRotate - rotate)) + rotate);

        float newPositionX;
        float newPositionY;
        float newPositionZ;
        if (vo.isMovingZ()) {
            float positionX = vo.getPositionX();
            float positionY = vo.getPositionY();
            float positionZ = vo.getPositionZ();
            float movingZDestinationX = vo.getMovingZDestinationX();
            float movingZDestinationY = vo.getMovingZDestinationY();
            float movingZDestinationZ = vo.getMovingZDestinationZ();
            float movingZStartY = vo.getMovingZStartY();
            float progressRatio =
                    0.01f + ((positionY - movingZStartY) / (movingZDestinationY - movingZStartY));
            float resistance;
            if (mBatteryState == 0) {
                resistance = vo.getResistance();
            } else if (mBatteryState == 1) {
                resistance = 600.0f;
            } else {
                resistance = 300.0f;
            }
            float zStep = (movingZDestinationZ - positionZ) / resistance;
            newPositionX =
                    positionX + (((movingZDestinationX - positionX) / resistance) * progressRatio);
            newPositionY = (((movingZDestinationY - positionY) / resistance)
                            * progressRatio
                            * vo.getDisport())
                    + positionY;
            newPositionZ = (zStep * progressRatio) + positionZ;
        } else {
            if (mBatteryState == 0) {
                baseIncrement = vo.getSpeed();
            } else if (mBatteryState == 1) {
                baseIncrement = vo.getLowBatterySpeed();
            }
            float positionX = vo.getPositionX();
            float positionY = vo.getPositionY();
            float positionZ = vo.getPositionZ();
            newPositionZ = positionZ + ((vo.getDestinationZ() - positionZ) * baseIncrement);
            newPositionY = positionY + ((vo.getDestinationY() - positionY) * baseIncrement);
            newPositionX = (baseIncrement * (vo.getDestinationX() - positionX)) + positionX;
        }
        vo.setPosition(newPositionX, newPositionY, newPositionZ);

        boolean isAppearing = vo.isAppearing();
        boolean isRemoving = vo.isRemoving();
        float reverseAlpha = vo.getReverseAlpha();
        if (isAppearing) {
            reverseAlpha += (0.0f - reverseAlpha) * 0.1f;
            if (reverseAlpha < 0.1f) {
                vo.setIsAppearing(false);
                vo.setIsFinishedAppearing(true);
                reverseAlpha = 0.0f;
            }
            vo.setReverseAlpha(reverseAlpha);
        }
        if (isRemoving) {
            float value = reverseAlpha + ((1.0f - reverseAlpha) * 0.1f);
            if (value > 0.92f) {
                vo.setIsRemoving(false);
                vo.setIsFinishedRemoving(true);
                value = 1.0f;
            }
            vo.setReverseAlpha(value);
        }
    }

    private void updateForGoingToMoveWhenShaking(float force) {
        int numberOfJellyfish = mJellyfishVO.getNumberOfJellyfish();
        float uptimeMillis = (((float) SystemClock.uptimeMillis()) - mStartShakingTime) * 0.001f;
        if (uptimeMillis > 1.0f) {
            mIsAfterShaking = true;
        }
        for (int i = 0; i < numberOfJellyfish; i++) {
            if (uptimeMillis < (i * 0.6f) + 5.0f) {
                updateWhenShakingByIndex(i, force, uptimeMillis);
            } else {
                changeDestinationByIndex(i);
                updateForGoingToMoveByIndex(i);
            }
        }
        if (uptimeMillis > (((float) numberOfJellyfish) * 0.6f) + 5.0f) {
            mIsShaking = false;
            mIsAfterShaking = false;
        }
    }

    private void updateWhenShakingByIndex(int index, float force, float elapsed) {
        JellyfishVO vo = mJellyfishVO.getVOByIndex(index);
        float positionX = vo.getPositionX();
        float positionY = vo.getPositionY();
        float positionZ = vo.getPositionZ();
        float rotate = vo.getRotate();
        float forceX = vo.getForceX();
        int indexZ = (int) positionZ;
        float newForceX = forceX + ((((indexZ + 11) * 4.0E-5f) + 0.18f) * (0.0f - forceX));
        vo.setForceX(newForceX);
        float[] minMaxXYByZ = PositionVO.getMinMaxXYByZ(positionZ);
        float newPositionX = positionX + (newForceX * 0.04f * 0.7f);
        if (mWidth < mHeight) {
            if (force > 0.0f) {
                if (newPositionX > minMaxXYByZ[1] - 0.5f) {
                    newPositionX += 0.02f * ((minMaxXYByZ[1] - 0.5f) - newPositionX);
                }
                if (newPositionX > minMaxXYByZ[1] + 0.3f) {
                    newPositionX = minMaxXYByZ[1] + 0.3f;
                }
            } else {
                if (newPositionX < minMaxXYByZ[0] + 0.5f) {
                    newPositionX += 0.02f * ((minMaxXYByZ[0] + 0.5f) - newPositionX);
                }
                if (newPositionX < minMaxXYByZ[0] - 0.3f) {
                    newPositionX = minMaxXYByZ[0] - 0.3f;
                }
            }
        } else if (force > 0.0f) {
            if (newPositionX > minMaxXYByZ[1] - (minMaxXYByZ[1] * 0.45f)) {
                newPositionX = minMaxXYByZ[1] - (minMaxXYByZ[1] * 0.45f);
            }
        } else if (newPositionX < minMaxXYByZ[0] + (minMaxXYByZ[1] * 0.45f)) {
            newPositionX = minMaxXYByZ[0] + (minMaxXYByZ[1] * 0.45f);
        }

        float forceY = vo.getForceY();
        float newForceY = forceY + ((((indexZ + 11) * 5.0E-4f) + 0.006f) * (0.0f - forceY));
        vo.setForceY(newForceY);
        float absForceY = Math.abs(newForceY);
        float value = absForceY - ((absForceY / elapsed) * 0.05f);
        if (value < 0.0f) value = 0.0f;

        float newPositionY;
        float newRotate;
        if (vo.isShakeUp()) {
            newPositionY = positionY + (0.0016f * value);
            newRotate = (value * 0.1f) + rotate;
            if (newPositionY > minMaxXYByZ[3] - 0.8f) {
                newPositionY += 0.02f * ((minMaxXYByZ[3] - 0.8f) - newPositionY);
            }
        } else {
            newPositionY = positionY - (0.0016f * value);
            newRotate = rotate - (value * 0.1f);
            if (newPositionY < minMaxXYByZ[2] + 0.8f) {
                newPositionY += 0.02f * ((minMaxXYByZ[2] + 0.8f) - newPositionY);
            }
        }
        vo.setPosition(newPositionX, newPositionY, positionZ);
        vo.setRotate(newRotate);
    }

    private void startZMove() {
        float randomRotate = MathHelper.getRandomFloat(-360.0f, 360.0f);
        JellyfishVO voDown = mJellyfishVO.getRandomVOByZDown();
        JellyfishVO voUp = mJellyfishVO.getRandomVOByZUp();

        float destinationX;
        float destinationY;
        float destinationZ;
        JellyfishVO first;
        if (voDown == null) {
            JellyfishVO up2 = mJellyfishVO.getRandomVOByZUp();
            if (up2 != null) {
                destinationX = up2.getDestinationX();
                destinationY = up2.getDestinationY();
            } else {
                destinationX = 0.0f;
                destinationY = 0.0f;
            }
            destinationZ = -8.0f;
            first = up2;
        } else {
            destinationX = voDown.getDestinationX();
            destinationY = voDown.getDestinationY();
            destinationZ = voDown.getDestinationZ();
            first = voDown;
        }

        float destinationX2;
        float destinationY2;
        float destinationZ2;
        JellyfishVO second;
        if (voUp == null) {
            JellyfishVO down2 = mJellyfishVO.getRandomVOByZDown();
            if (down2 != null) {
                destinationX2 = down2.getDestinationX();
                destinationY2 = down2.getDestinationY();
            } else {
                destinationX2 = 0.0f;
                destinationY2 = 0.0f;
            }
            destinationZ2 = -4.0f;
            second = down2;
        } else {
            destinationX2 = voUp.getDestinationX();
            second = voUp;
            destinationY2 = voUp.getDestinationY();
            destinationZ2 = voUp.getDestinationZ();
        }
        if (first == null || second == null) return;
        if (!first.isMovingZ() && !second.isMovingZ()) {
            updateMovingZInfoByVO(second, destinationX, destinationY, destinationZ, randomRotate);
            updateMovingZInfoByVO(first, destinationX2, destinationY2, destinationZ2, randomRotate);
        }
    }

    private void updateMovingZInfoByVO(
            JellyfishVO vo, float x, float y, float z, float randomRotate) {
        vo.setIsMovingZ(true);
        vo.setMovingZStartPosition(vo.getPositionX(), vo.getPositionY(), vo.getPositionZ());
        vo.setMovingZDestination(x, y, z);
        vo.setDestinationRotate(destinationRotation(
                vo.getMovingZStartX(),
                vo.getMovingZStartY(),
                vo.getMovingZDestinationX(),
                vo.getMovingZDestinationY()));
    }

    /** 与 GLES 版相同的朝向角：atan2 换成角度后减 120。 */
    private static float destinationRotation(float fromX, float fromY, float toX, float toY) {
        return (((float) Math.atan2(toY - fromY, toX - fromX)) * 180.0f) / 3.1415927f - 120.0f;
    }

    private void checkBlur() {
        int numberOfJellyfish = mJellyfishVO.getNumberOfJellyfish();
        for (int i = 0; i < numberOfJellyfish; i++) {
            mJellyfishVO.getVOByIndex(i).setHasBlur(false);
        }
        int random = (int) (Math.random() * numberOfJellyfish);
        JellyfishVO first = mJellyfishVO.getVOByIndex((int) (Math.random() * numberOfJellyfish));
        JellyfishVO second = mJellyfishVO.getVOByIndex(random);
        if (!first.isRemoving() && !first.isFinishedRemoving()) {
            first.setHasBlur(true);
        }
        if (second.isRemoving() || second.isFinishedRemoving()) {
            return;
        }
        second.setHasBlur(true);
    }

    private void checkAppearing() {
        mIsAllFinishedAppearing = mJellyfishVO.isAllFinishedAppearing();
        if (mAppearingIndex < mJellyfishVO.getNumberOfJellyfish()) {
            JellyfishVO vo = mJellyfishVO.getVOByIndex(mAppearingIndex);
            if (!vo.isRemoving() && !vo.isFinishedRemoving()) {
                vo.setIsAppearing(true);
            }
            mAppearingIndex++;
        }
    }

    // ─────────────────────────── 粒子数据生成 ───────────────────────────

    private void generateParticleData() {
        setSeaDropsData(mSeaDrops, mSeaDropsBackup, DROPS_COUNT, 3.0f);
        setSeaDrops2Data();
        setSeaDrops3Data();
        setJellyDropsData(mJellyDropsA, JELLY_DROPS_A_COUNT);
        setJellyDropsData(mJellyDropsB, JELLY_DROPS_B_COUNT);
    }

    /** SeaWaterDrops.setParticleData：位置、移动与生命参数。 */
    private void setSeaDropsData(float[] data, float[] backup, int count, float spread) {
        for (int i = 0; i < count; i++) {
            int offset = i * DROP_FLOATS;
            float x = (mRandom.nextFloat() - 0.5f) * spread;
            float y = (mRandom.nextFloat() - 0.5f) * 5.0f;
            if (x < 1.0f && x > -1.0f && y < 1.5f && y > -1.5f) {
                if (x <= 0.0f && x >= -1.0f) {
                    x -= 1.0f;
                } else if (x <= 1.0f && x >= 0.0f) {
                    x += 1.0f;
                }
            }
            data[offset] = x;
            data[offset + 1] = y;
            data[offset + 2] = mRandom.nextFloat() - 0.5f;
            data[offset + 3] = (mRandom.nextFloat() - 0.5f) * spread;
            data[offset + 4] = (mRandom.nextFloat() - 0.5f) * 5.0f;
            data[offset + 5] = mRandom.nextFloat() - 0.5f;
            data[offset + 6] = MathHelper.getRandomFloat(0.5f, 1.0f);
            data[offset + 7] = MathHelper.getRandomFloat(0.01f, 0.1f);
            data[offset + 8] = MathHelper.getRandomFloat(10.0f, 20.0f);
            data[offset + 9] = MathHelper.getRandomFloat(20.0f, 40.0f);
            data[offset + 10] = mRandom.nextFloat();
            data[offset + 11] = mRandom.nextFloat();
            data[offset + 12] = mRandom.nextFloat() - 0.5f;
            for (int k = 0; k < DROP_FLOATS; k++) {
                backup[offset + k] = data[offset + k];
            }
        }
    }

    /** SeaWaterDrops2.setParticleData：6 个粒子，没有发射点属性。 */
    private void setSeaDrops2Data() {
        for (int i = 0; i < DROPS2_COUNT; i++) {
            int offset = i * DROP2_FLOATS;
            float x = mRandom.nextFloat() - 0.5f;
            float y = mRandom.nextFloat();
            mSeaDrops2[offset] = x;
            mSeaDrops2[offset + 1] = (y - 0.5f) - 1.0f;
            mSeaDrops2[offset + 2] = mRandom.nextFloat() - 0.5f;
            mSeaDrops2[offset + 3] = x;
            mSeaDrops2[offset + 4] = 3.0f;
            mSeaDrops2[offset + 5] = mRandom.nextFloat() - 0.5f;
            mSeaDrops2[offset + 6] = MathHelper.getRandomFloat(0.5f, 1.0f);
            mSeaDrops2[offset + 7] = MathHelper.getRandomFloat(0.01f, 0.1f);
            mSeaDrops2[offset + 8] = MathHelper.getRandomFloat(10.0f, 20.0f);
            mSeaDrops2[offset + 9] = MathHelper.getRandomFloat(20.0f, 40.0f);
            for (int k = 0; k < DROP2_FLOATS; k++) {
                mSeaDrops2Backup[offset + k] = mSeaDrops2[offset + k];
            }
        }
    }

    /** SeaWaterDrops3.setParticleData：120 个粒子。 */
    private void setSeaDrops3Data() {
        for (int i = 0; i < DROPS3_COUNT; i++) {
            int offset = i * DROP2_FLOATS;
            float x = mRandom.nextFloat() - 0.5f;
            mSeaDrops3[offset] = x;
            mSeaDrops3[offset + 1] = -1.0f;
            mSeaDrops3[offset + 2] = mRandom.nextFloat() - 0.5f;
            mSeaDrops3[offset + 3] = x;
            mSeaDrops3[offset + 4] = 3.0f;
            mSeaDrops3[offset + 5] = mRandom.nextFloat() - 0.5f;
            mSeaDrops3[offset + 6] = MathHelper.getRandomFloat(0.04f, 0.4f);
            mSeaDrops3[offset + 7] = MathHelper.getRandomFloat(0.02f, 0.03f);
            mSeaDrops3[offset + 8] = MathHelper.getRandomFloat(10.0f, 24.0f);
            mSeaDrops3[offset + 9] = MathHelper.getRandomFloat(20.0f, 40.0f);
            for (int k = 0; k < DROP2_FLOATS; k++) {
                mSeaDrops3Backup[offset + k] = mSeaDrops3[offset + k];
            }
        }
    }

    /** JellyfishWaterDrops.setParticleData：以原点为中心的一圈水滴。 */
    private void setJellyDropsData(float[] data, int count) {
        float increment = 0.1f / count;
        float radius = 0.2f;
        for (int i = 0; i < count; i++) {
            radius += increment;
            int angle = (int) (mRandom.nextFloat() * 360.0f);
            int offset = i * DROP_FLOATS;
            data[offset] = 0.0f;
            data[offset + 1] = 0.0f;
            data[offset + 2] = 0.0f;
            data[offset + 3] = (float) (Math.cos(Math.toRadians(angle)) * radius);
            data[offset + 4] = (float) (Math.sin(Math.toRadians(angle)) * radius);
            data[offset + 5] = mRandom.nextFloat() - 0.5f;
            data[offset + 6] = MathHelper.getRandomFloat(0.8f, 1.0f);
            data[offset + 7] = MathHelper.getRandomFloat(0.005f, 0.02f);
            data[offset + 8] = MathHelper.getRandomFloat(20.0f, 40.0f);
            data[offset + 9] = MathHelper.getRandomFloat(10.0f, 20.0f);
            data[offset + 10] = 0.0f;
            data[offset + 11] = 0.0f;
            data[offset + 12] = mRandom.nextFloat() - 0.5f;
        }
    }

    /** SeaWaterDrops.changeData / SeaWaterDrops2、3 的对应实现。 */
    private void changeSeaDropsData(int width, int height) {
        // 第一组：位置与移动都按屏幕比例重取随机值
        float widthFactor = width > 720 ? width / 720.0f : 1.0f;
        float heightFactor = height > 1184 ? height / 1184.0f : 1.0f;
        for (int i = 0; i < DROPS_COUNT; i++) {
            int offset = i * DROP_FLOATS;
            if (widthFactor == 1.0f) {
                mSeaDrops[offset] = mSeaDropsBackup[offset];
                mSeaDrops[offset + 3] = mSeaDropsBackup[offset + 3];
            } else {
                float x = (mRandom.nextFloat() - 0.5f) * widthFactor * 3.0f;
                mSeaDrops[offset] = x;
                mSeaDrops[offset + 3] = x;
            }
            if (heightFactor == 1.0f) {
                mSeaDrops[offset + 1] = mSeaDropsBackup[offset + 1];
                mSeaDrops[offset + 4] = mSeaDropsBackup[offset + 4];
            } else {
                // 与 GLES 相同：这里会把备份值也乘一次比例（每次改变尺寸都会累积）
                float y = mSeaDropsBackup[offset + 1] * heightFactor;
                mSeaDropsBackup[offset + 1] = y;
                mSeaDrops[offset + 1] = y;
                float moveY = mSeaDropsBackup[offset + 4] * heightFactor;
                mSeaDropsBackup[offset + 4] = moveY;
                mSeaDrops[offset + 4] = moveY;
            }
        }
        changeSeaDropsData2(mSeaDrops2, mSeaDrops2Backup, DROPS2_COUNT, widthFactor, heightFactor);
        changeSeaDropsData2(mSeaDrops3, mSeaDrops3Backup, DROPS3_COUNT, widthFactor, heightFactor);
    }

    private void changeSeaDropsData2(
            float[] data, float[] backup, int count, float widthFactor, float heightFactor) {
        for (int i = 0; i < count; i++) {
            int offset = i * DROP2_FLOATS;
            if (widthFactor == 1.0f) {
                data[offset] = backup[offset];
                data[offset + 3] = backup[offset + 3];
            } else {
                float x = (mRandom.nextFloat() - 0.5f) * widthFactor;
                data[offset] = x;
                data[offset + 3] = x;
            }
            if (heightFactor == 1.0f) {
                data[offset + 1] = backup[offset + 1];
                data[offset + 4] = backup[offset + 4];
            } else {
                data[offset + 1] = backup[offset + 1] * heightFactor;
                data[offset + 4] = backup[offset + 4] * heightFactor;
            }
        }
    }

    private void computeMvp(float[] model, float[] out) {
        Matrix.multiplyMM(mTmpMvp, 0, mViewMatrix, 0, model, 0);
        Matrix.multiplyMM(out, 0, mProjectionMatrix, 0, mTmpMvp, 0);
    }
}
