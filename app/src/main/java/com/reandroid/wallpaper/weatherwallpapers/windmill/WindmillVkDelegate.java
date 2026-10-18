package com.reandroid.wallpaper.weatherwallpapers.windmill;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.opengl.Matrix;

import com.reandroid.plugin.VkPass;
import com.reandroid.plugin.VkPassFrame;
import com.reandroid.plugin.VkPassRendererDelegate;
import com.reandroid.plugin.VkPassScene;
import com.reandroid.utils.AssetLoader;
import com.reandroid.wallpaper.weatherwallpapers.CloudRenderer;
import com.reandroid.wallpaper.weatherwallpapers.FogIceRenderer;
import com.reandroid.wallpaper.weatherwallpapers.PrecipitationRenderer;
import com.reandroid.wallpaper.weatherwallpapers.SkyRenderer;
import com.reandroid.wallpaper.weatherwallpapers.ThunderRenderer;
import com.reandroid.wallpaper.weatherwallpapers.WeatherStateManager;
import com.reandroid.weather.WeatherCondition;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Windmill 的 VK 渲染器 —— 壁纸与预览共用这一份。
 *
 * <p>GLES 版把场景拆给一组共享渲染器（天空、云、风车、地面、雾冰、降水、雷），每个渲染器
 * 通过 {@code Drawer} 吐出一串精灵。这里原样复用那套逻辑与状态机，只把 {@code Drawer}
 * 换成按「绘制顺序」分组的顶点批：
 *
 * <ul>
 *   <li>每个 pass 对应 GLES 里一段连续的、同一张贴图的绘制。pass 的声明顺序就是 GLES 的
 *       绘制顺序，因此 alpha 混合的前后关系不变。</li>
 *   <li>一张贴图在一帧里出现在多段（例如风车分三档距离、云分 A/B 两段）时，就为每一段
 *       各建一个 pass，而不是把整张图合成一批。</li>
 *   <li>少数一格里会换贴图（白/夜天空、四种雨、三种闪电、云隙光、地面昼夜），
 *       该 pass 一次绑 8 个采样器，顶点里带本地贴图号，片元里分支取样。</li>
 * </ul>
 *
 * <p>顶点变换与 GLES 完全一致：CPU 侧用 {@code android.opengl.Matrix} 复刻
 * {@code SpriteDrawer} 的 translate → rotateZ → scale，再把 GL 投影矩阵乘上 Vulkan
 * 裁剪修正后作为 push constant。混合是 ONE / ONE_MINUS_SRC_ALPHA，贴图按预乘上传。
 */
final class WindmillVkDelegate extends VkPassRendererDelegate
        implements WindmillRenderer.Drawer,
                GroundRenderer.Drawer,
                CloudRenderer.Drawer,
                FogIceRenderer.Drawer,
                SkyRenderer.Drawer,
                PrecipitationRenderer.Drawer,
                ThunderRenderer.Drawer {

    private static final String VS = "shaders/windmill_vk.vert.spv";
    private static final String FS = "shaders/windmill_vk.frag.spv";

    /** 每个 pass 固定绑定 8 个采样器；单贴图 pass 重复绑同一张。 */
    private static final int SLOTS = 8;
    /** x, y, z, u, v, r, g, b, a, textureSlot。 */
    private static final int FLOATS_PER_VERTEX = 10;

    private static final int STRIDE = FLOATS_PER_VERTEX * 4;
    private static final int VERTICES_PER_SPRITE = 6;
    private static final int PUSH_BYTES = 16 * 4;

    // ── 顶点形状（与 windmill/data/*.csv 完全一致）──
    private static final float[] QUAD = {-8f, 8f, 0f, -8f, -8f, 0f, 8f, 8f, 0f, 8f, -8f, 0f};
    private static final float[] RECT_ONE_TO_TWO = {
        -4f, 2f, 0f, -4f, -2f, 0f, 4f, 2f, 0f, 4f, -2f, 0f
    };
    private static final float[] RECT_ONE_TO_FOUR = {
        -4f, 1f, 0f, -4f, -1f, 0f, 4f, 1f, 0f, 4f, -1f, 0f
    };
    private static final float[] TEX_COORDS = {0f, 0f, 0f, 1f, 1f, 0f, 1f, 1f};

    private static final int QUAD_TYPE_SPRITE = 0;
    private static final int QUAD_TYPE_RECT_ONE_TO_TWO = 1;
    private static final int QUAD_TYPE_RECT_ONE_TO_FOUR = 2;

    // ── Drawer 的当前阶段（决定同样的贴图落到哪个 pass）──
    private static final int STAGE_NONE = 0;
    private static final int STAGE_SKY = 1;
    private static final int STAGE_NIGHTCOVER = 2;
    private static final int STAGE_CLOUDS = 3;
    private static final int STAGE_WINDMILL = 4;
    private static final int STAGE_FAR_LAND = 5;
    private static final int STAGE_NEAR_LAND = 6;
    private static final int STAGE_LAWN = 7;
    private static final int STAGE_SUNLIGHT = 8;
    private static final int STAGE_FOG_ICE = 9;
    private static final int STAGE_FROST = 10;
    private static final int STAGE_RAIN = 11;
    private static final int STAGE_SNOW = 12;
    private static final int STAGE_THUNDER = 13;

    // ── 贴图资产（顺序必须与 ASSET_PATHS 一致）──
    private static final int TEX_SKY_01 = 0;
    private static final int TEX_SKY_02 = 1;
    private static final int TEX_SKY_03 = 2;
    private static final int TEX_SKY_04 = 3;
    private static final int TEX_SKY_STARS = 4;
    private static final int TEX_CLOUD_A_01 = 5;
    private static final int TEX_CLOUD_A_02 = 6;
    private static final int TEX_CLOUD_A_03 = 7;
    private static final int TEX_CLOUD_B_01 = 8;
    private static final int TEX_CLOUD_B_02 = 9;
    private static final int TEX_CLOUD_B_03 = 10;
    private static final int TEX_SUN_01 = 11;
    private static final int TEX_SUN_02 = 12;
    private static final int TEX_SUN_03 = 13;
    private static final int TEX_SUN_04 = 14;
    private static final int TEX_STAR = 15;
    private static final int TEX_METEOR = 16;
    private static final int TEX_MOON = 17;
    private static final int TEX_RAIN_01 = 18;
    private static final int TEX_RAIN_02 = 19;
    private static final int TEX_RAIN_03 = 20;
    private static final int TEX_RAIN_04 = 21;
    private static final int TEX_FOG = 22;
    private static final int TEX_ICE = 23;
    private static final int TEX_WATERDROP = 24;
    private static final int TEX_FROST_E = 25;
    private static final int TEX_FROST_F = 26;
    private static final int TEX_SNOW_01 = 27;
    private static final int TEX_SNOW_02 = 28;
    private static final int TEX_SNOW_03 = 29;
    private static final int TEX_SNOW_04 = 30;
    private static final int TEX_NIGHTCOVER = 31;
    private static final int TEX_SKY_FLASH = 32;
    private static final int TEX_LIGHTNING_01 = 33;
    private static final int TEX_LIGHTNING_02 = 34;
    private static final int TEX_LIGHTNING_03 = 35;
    private static final int TEX_CLOUD_LIGHT_A_01 = 36;
    private static final int TEX_CLOUD_LIGHT_A_02 = 37;
    private static final int TEX_CLOUD_LIGHT_A_03 = 38;
    private static final int TEX_CLOUD_LIGHT_B_01 = 39;
    private static final int TEX_CLOUD_LIGHT_B_02 = 40;
    private static final int TEX_CLOUD_LIGHT_B_03 = 41;
    private static final int TEX_WING = 42;
    private static final int TEX_WING_BLUR = 43;
    private static final int TEX_CENTER = 44;
    private static final int TEX_PILLAR = 45;
    private static final int TEX_PILLAR_FLIP = 46;
    private static final int TEX_LAND_01 = 47;
    private static final int TEX_LAND_02 = 48;
    private static final int TEX_LAND_03 = 49;
    private static final int TEX_LAND_04 = 50;
    private static final int TEX_LAND_05 = 51;
    private static final int TEX_LAND_06 = 52;
    private static final int TEX_LAND_07 = 53;
    private static final int TEX_LAND_08 = 54;
    private static final int TEX_LAND_09 = 55;
    private static final int TEX_LAWN_01 = 56;
    private static final int TEX_LAWN_02 = 57;
    private static final int TEX_LAWN_03 = 58;
    private static final int TEX_LAWN_04 = 59;
    private static final int TEX_LAWN_05 = 60;
    private static final int TEX_RAINDROP_A_0 = 61;
    private static final int TEX_RAINDROP_B_0 = 86;
    private static final int TEX_COUNT = 111;

    // ── pass 下标 = 声明顺序 = GLES 绘制顺序 ──
    private static final int P_SKY_DAY = 0;
    private static final int P_SKY_NIGHT = 1;
    private static final int P_SKY_STARS = 2;
    private static final int P_SUN_01 = 3;
    private static final int P_SUN_02 = 4;
    private static final int P_SUN_03 = 5;
    private static final int P_MOON = 6;
    private static final int P_STAR = 7;
    private static final int P_METEOR = 8;
    private static final int P_NIGHTCOVER = 9;
    private static final int P_CLOUD_A_1 = 10;
    private static final int P_CLOUD_B_1 = 11;
    private static final int P_CLOUD_A_2 = 12;
    private static final int P_CLOUD_LIGHTS = 13;
    /** 远/中/近三档风车，每档每个风车一个 pass，保证一台风车内部 P→W→C 的顺序。 */
    private static final int P_WINMILL_FAR_0 = 14;

    private static final int P_FAR_LAND = 17;
    private static final int P_WINMILL_MID_0 = 18;
    private static final int P_NEAR_LAND = 21;
    private static final int P_WINMILL_NEAR_0 = 22;
    private static final int P_LAWN = 29;
    private static final int P_SUNLIGHT = 30;
    private static final int P_FOG = 31;
    private static final int P_ICE = 32;
    private static final int P_FROST = 33;
    private static final int P_RAIN = 34;
    private static final int P_WATERDROP = 35;
    private static final int P_RAINDROP_A_0 = 36;
    private static final int P_RAINDROP_B_0 = 61;
    private static final int P_SNOW_01 = 86;
    private static final int P_SNOW_02 = 87;
    private static final int P_SNOW_04 = 88;
    private static final int P_SNOW_03 = 89;
    private static final int P_THUNDER = 90;
    private static final int PASS_COUNT = 91;

    private static final int[] WINMILL_TEX = {
        TEX_PILLAR, TEX_PILLAR_FLIP, TEX_WING, TEX_WING_BLUR, TEX_CENTER
    };

    /**
     * GL 的 NDC 与 Vulkan 的裁剪空间差着 y 翻转和 z 的 [-1,1] → [0,1]。
     * 与参考实现（BlueSeaVkDelegate）同一份常量。
     */
    private static final float[] CLIP_CORRECTION = {
        1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 0.5f, 0, 0, 0, 0.5f, 1,
    };

    private static final String[] ASSET_PATHS = new String[TEX_COUNT];

    static {
        ASSET_PATHS[TEX_SKY_01] = "windmill/drawable/sky_01.jpg";
        ASSET_PATHS[TEX_SKY_02] = "windmill/drawable/sky_02.jpg";
        ASSET_PATHS[TEX_SKY_03] = "windmill/drawable/sky_03.png";
        ASSET_PATHS[TEX_SKY_04] = "windmill/drawable/sky_04.png";
        ASSET_PATHS[TEX_SKY_STARS] = "weatherwallpapers/common/drawable/d_sky_stars.png";
        ASSET_PATHS[TEX_CLOUD_A_01] = "weatherwallpapers/common/drawable/cloud_a_01.png";
        ASSET_PATHS[TEX_CLOUD_A_02] = "weatherwallpapers/common/drawable/cloud_a_02.png";
        ASSET_PATHS[TEX_CLOUD_A_03] = "weatherwallpapers/common/drawable/cloud_a_03.png";
        ASSET_PATHS[TEX_CLOUD_B_01] = "weatherwallpapers/common/drawable/cloud_b_01.png";
        ASSET_PATHS[TEX_CLOUD_B_02] = "weatherwallpapers/common/drawable/cloud_b_02.png";
        ASSET_PATHS[TEX_CLOUD_B_03] = "weatherwallpapers/common/drawable/cloud_b_03.png";
        ASSET_PATHS[TEX_SUN_01] = "weatherwallpapers/common/drawable/a_sun_01.png";
        ASSET_PATHS[TEX_SUN_02] = "weatherwallpapers/common/drawable/a_sun_02.png";
        ASSET_PATHS[TEX_SUN_03] = "weatherwallpapers/common/drawable/a_sun_03.png";
        ASSET_PATHS[TEX_SUN_04] = "windmill/drawable/a_sun_04.png";
        ASSET_PATHS[TEX_STAR] = "weatherwallpapers/common/drawable/d_star.png";
        ASSET_PATHS[TEX_METEOR] = "windmill/drawable/d_meteor.png";
        ASSET_PATHS[TEX_MOON] = "windmill/drawable/d_moon.png";
        ASSET_PATHS[TEX_RAIN_01] = "weatherwallpapers/common/drawable/c_rain_01.png";
        ASSET_PATHS[TEX_RAIN_02] = "weatherwallpapers/common/drawable/c_rain_02.png";
        ASSET_PATHS[TEX_RAIN_03] = "weatherwallpapers/common/drawable/c_rain_03.png";
        ASSET_PATHS[TEX_RAIN_04] = "weatherwallpapers/common/drawable/c_rain_04.png";
        ASSET_PATHS[TEX_FOG] = "weatherwallpapers/common/drawable/fog_02.png";
        ASSET_PATHS[TEX_ICE] = "weatherwallpapers/common/drawable/ice.png";
        ASSET_PATHS[TEX_WATERDROP] = "weatherwallpapers/common/drawable/c_waterdrop.png";
        ASSET_PATHS[TEX_FROST_E] = "weatherwallpapers/common/drawable/e_frost.png";
        ASSET_PATHS[TEX_FROST_F] = "weatherwallpapers/common/drawable/f_frost.png";
        ASSET_PATHS[TEX_SNOW_01] = "weatherwallpapers/common/drawable/e_snow_01.png";
        ASSET_PATHS[TEX_SNOW_02] = "weatherwallpapers/common/drawable/e_snow_02.png";
        ASSET_PATHS[TEX_SNOW_03] = "weatherwallpapers/common/drawable/e_snow_03.png";
        ASSET_PATHS[TEX_SNOW_04] = "weatherwallpapers/common/drawable/e_snow_04.png";
        ASSET_PATHS[TEX_NIGHTCOVER] = "weatherwallpapers/common/drawable/nightcover_01.png";
        ASSET_PATHS[TEX_SKY_FLASH] = "weatherwallpapers/common/drawable/g_sky_flash.png";
        ASSET_PATHS[TEX_LIGHTNING_01] = "weatherwallpapers/common/drawable/g_lightning_01.png";
        ASSET_PATHS[TEX_LIGHTNING_02] = "weatherwallpapers/common/drawable/g_lightning_02.png";
        ASSET_PATHS[TEX_LIGHTNING_03] = "weatherwallpapers/common/drawable/g_lightning_03.png";
        ASSET_PATHS[TEX_CLOUD_LIGHT_A_01] =
                "weatherwallpapers/common/drawable/cloud_a_light_01.png";
        ASSET_PATHS[TEX_CLOUD_LIGHT_A_02] =
                "weatherwallpapers/common/drawable/cloud_a_light_02.png";
        ASSET_PATHS[TEX_CLOUD_LIGHT_A_03] =
                "weatherwallpapers/common/drawable/cloud_a_light_03.png";
        ASSET_PATHS[TEX_CLOUD_LIGHT_B_01] =
                "weatherwallpapers/common/drawable/cloud_b_light_01.png";
        ASSET_PATHS[TEX_CLOUD_LIGHT_B_02] =
                "weatherwallpapers/common/drawable/cloud_b_light_02.png";
        ASSET_PATHS[TEX_CLOUD_LIGHT_B_03] =
                "weatherwallpapers/common/drawable/cloud_b_light_03.png";
        ASSET_PATHS[TEX_WING] = "windmill/drawable/a_windmill_wing.png";
        ASSET_PATHS[TEX_WING_BLUR] = "windmill/drawable/a_windmill_wing_blur2.png";
        ASSET_PATHS[TEX_CENTER] = "windmill/drawable/a_windmill_center_01.png";
        ASSET_PATHS[TEX_PILLAR] = "windmill/drawable/a_windmill_pillar_01.png";
        ASSET_PATHS[TEX_PILLAR_FLIP] = "windmill/drawable/a_windmill_pillar_flip_01.png";
        ASSET_PATHS[TEX_LAND_01] = "windmill/drawable/a_land_01.png";
        ASSET_PATHS[TEX_LAND_02] = "windmill/drawable/a_land_02.png";
        ASSET_PATHS[TEX_LAND_03] = "windmill/drawable/a_land_03.png";
        ASSET_PATHS[TEX_LAND_04] = "windmill/drawable/a_land_04.png";
        ASSET_PATHS[TEX_LAND_05] = "windmill/drawable/a_land_05.png";
        ASSET_PATHS[TEX_LAND_06] = "windmill/drawable/a_land_06.png";
        ASSET_PATHS[TEX_LAND_07] = "windmill/drawable/a_land_07.png";
        ASSET_PATHS[TEX_LAND_08] = "windmill/drawable/a_land_08.png";
        ASSET_PATHS[TEX_LAND_09] = "windmill/drawable/a_land_09.png";
        ASSET_PATHS[TEX_LAWN_01] = "windmill/drawable/a_lawn_01.png";
        ASSET_PATHS[TEX_LAWN_02] = "windmill/drawable/a_lawn_02.png";
        ASSET_PATHS[TEX_LAWN_03] = "windmill/drawable/a_lawn_03.png";
        ASSET_PATHS[TEX_LAWN_04] = "windmill/drawable/a_lawn_04.png";
        ASSET_PATHS[TEX_LAWN_05] = "windmill/drawable/a_lawn_05.png";
        for (int i = 0; i < 25; i++) {
            ASSET_PATHS[TEX_RAINDROP_A_0 + i] =
                    "weatherwallpapers/common/drawable/waterdrop_a_" + i + ".png";
            ASSET_PATHS[TEX_RAINDROP_B_0 + i] =
                    "weatherwallpapers/common/drawable/waterdrop_b_" + i + ".png";
        }
    }

    private final boolean mPreview;

    private WindmillScene mScene;
    private VkPassScene mPassScene;
    private SpritePass[] mSpritePasses;
    private boolean mSceneStarted;
    /** 贴图在首次被某个 pass 用到时上传，避免开局一次性解码 100 多张。 */
    private final boolean[] mTextureUploaded = new boolean[TEX_COUNT];

    private final WindmillRenderer mWindmillRenderer = new WindmillRenderer();
    private final GroundRenderer mGroundRenderer = new GroundRenderer();
    private final CloudRenderer mCloudRenderer = new CloudRenderer();
    private final FogIceRenderer mFogIceRenderer = new FogIceRenderer();
    private final PrecipitationRenderer mPrecipitationRenderer = new PrecipitationRenderer();
    private final ThunderRenderer mThunderRenderer = new ThunderRenderer();
    private final SkyRenderer mSkyRenderer = new SkyRenderer();

    private int mWidth;
    private int mHeight;
    private float mPendingOffset = Float.NaN;

    private int mStage = STAGE_NONE;
    /** 云的一格里，第 3 个 A 在「晴」分支要落到第二段；阴天分支三张 A 连续。 */
    private int mCloudACount;

    private boolean mClearCloudMode;
    /** 当前 drawByDistance 对应的风车 pass（按该距离下的绘制顺序）。 */
    private int[] mActiveMillPasses = new int[0];

    private int mMillCallCursor;

    private final float[] mPush = new float[16];
    private final float[] mModel = new float[16];
    private final float[] mIn = {0f, 0f, 0f, 1f};
    private final float[] mWorld = new float[16];

    WindmillVkDelegate(Context context, SharedPreferences prefs, boolean preview) {
        super(context);
        mPreview = preview;
    }

    @Override
    public String logTag() {
        return "WindmillVK";
    }

    @Override
    public Object getScene() {
        return mScene;
    }

    @Override
    public boolean isReady() {
        return mScene != null;
    }

    @Override
    public void ensureScene(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        mWidth = width;
        mHeight = height;
        if (mScene == null) {
            mScene = new WindmillScene();
            mWindmillRenderer.initInstances();
            mCloudRenderer.initMemory();
            mPrecipitationRenderer.initMemory(PrecipitationRenderer.Config.WINDMILL);
            mThunderRenderer.initMemory();
            mSkyRenderer.initMemory();
            mScene.onCreate(context);
            if (!Float.isNaN(mPendingOffset)) {
                mScene.mOffset = mPendingOffset;
            }
            mScene.updateProjection(width, height);
        } else {
            mScene.updateProjection(width, height);
        }
        // 预览路径的 surfaceCreated 可能先带着 0x0 尺寸到达（此时上面直接返回），
        // 真正的尺寸在随后的 surfaceChanged 才给出；而 createRenderer 已经跑过了。
        // 所以场景启动放在这里，并用 createRenderer 兜住另一种顺序。
        ensureSceneStarted();
    }

    @Override
    public long createRenderer() {
        long handle = super.createRenderer();
        if (handle != 0L) {
            ensureSceneStarted();
        }
        return handle;
    }

    private void ensureSceneStarted() {
        if (mScene != null && !mSceneStarted) {
            mScene.start(mPreview);
            mSceneStarted = true;
        }
    }

    @Override
    public void onPluginPrefsChanged(SharedPreferences prefs) {
        // Windmill 没有插件设置项；天气/昼夜由 WeatherStateManager 自己更新。
    }

    @Override
    public void onSceneOffset(float xOffset) {
        mPendingOffset = 2.5f * xOffset;
        if (mScene != null) {
            mScene.mOffset = mPendingOffset;
        }
    }

    @Override
    public void destroyRenderer() {
        if (mSceneStarted) {
            mScene.stop();
            mSceneStarted = false;
        }
        super.destroyRenderer();
        mSpritePasses = null;
        mPassScene = null;
        for (int i = 0; i < mTextureUploaded.length; i++) {
            mTextureUploaded[i] = false;
        }
    }

    // ─────────────────────────── scene build ───────────────────────────

    @Override
    protected void buildScene(VkPassScene scene) {
        mPassScene = scene;
        scene.setClearColor(0f, 0f, 0f, 0f);
        mSpritePasses = new SpritePass[PASS_COUNT];

        for (int i = 0; i < TEX_COUNT; i++) {
            scene.createTexture("t" + i);
        }

        // 声明顺序就是 GLES 的绘制顺序，见 WindmillGL.drawObjects。
        definePass(scene, P_SKY_DAY, 1, TEX_SKY_01, TEX_SKY_03, TEX_SKY_04);
        definePass(scene, P_SKY_NIGHT, 1, TEX_SKY_02);
        definePass(scene, P_SKY_STARS, 1, TEX_SKY_STARS);
        definePass(scene, P_SUN_01, 1, TEX_SUN_01);
        definePass(scene, P_SUN_02, 1, TEX_SUN_02);
        definePass(scene, P_SUN_03, 1, TEX_SUN_03);
        definePass(scene, P_MOON, 1, TEX_MOON);
        definePass(scene, P_STAR, 7, TEX_STAR);
        definePass(scene, P_METEOR, 1, TEX_METEOR);

        definePass(scene, P_NIGHTCOVER, 1, TEX_NIGHTCOVER);

        definePass(scene, P_CLOUD_A_1, 3, TEX_CLOUD_A_01, TEX_CLOUD_A_02, TEX_CLOUD_A_03);
        definePass(scene, P_CLOUD_B_1, 7, TEX_CLOUD_B_01, TEX_CLOUD_B_02, TEX_CLOUD_B_03);
        definePass(scene, P_CLOUD_A_2, 1, TEX_CLOUD_A_01, TEX_CLOUD_A_02, TEX_CLOUD_A_03);
        definePass(
                scene,
                P_CLOUD_LIGHTS,
                20,
                TEX_CLOUD_LIGHT_A_01,
                TEX_CLOUD_LIGHT_A_02,
                TEX_CLOUD_LIGHT_A_03,
                TEX_CLOUD_LIGHT_B_01,
                TEX_CLOUD_LIGHT_B_02,
                TEX_CLOUD_LIGHT_B_03);

        definePass(scene, P_WINMILL_FAR_0, 3, WINMILL_TEX);
        definePass(scene, P_WINMILL_FAR_0 + 1, 3, WINMILL_TEX);
        definePass(scene, P_WINMILL_FAR_0 + 2, 3, WINMILL_TEX);

        definePass(scene, P_FAR_LAND, 2, TEX_LAND_02, TEX_LAND_04, TEX_LAND_07, TEX_LAND_09);

        definePass(scene, P_WINMILL_MID_0, 3, WINMILL_TEX);
        definePass(scene, P_WINMILL_MID_0 + 1, 3, WINMILL_TEX);
        definePass(scene, P_WINMILL_MID_0 + 2, 3, WINMILL_TEX);

        definePass(
                scene,
                P_NEAR_LAND,
                2,
                TEX_LAND_01,
                TEX_LAND_03,
                TEX_LAND_05,
                TEX_LAND_06,
                TEX_LAND_08);

        definePass(scene, P_WINMILL_NEAR_0, 3, WINMILL_TEX);
        definePass(scene, P_WINMILL_NEAR_0 + 1, 3, WINMILL_TEX);
        definePass(scene, P_WINMILL_NEAR_0 + 2, 3, WINMILL_TEX);
        definePass(scene, P_WINMILL_NEAR_0 + 3, 3, WINMILL_TEX);
        definePass(scene, P_WINMILL_NEAR_0 + 4, 3, WINMILL_TEX);
        definePass(scene, P_WINMILL_NEAR_0 + 5, 3, WINMILL_TEX);
        definePass(scene, P_WINMILL_NEAR_0 + 6, 3, WINMILL_TEX);

        definePass(
                scene, P_LAWN, 2, TEX_LAWN_01, TEX_LAWN_02, TEX_LAWN_03, TEX_LAWN_04, TEX_LAWN_05);

        definePass(scene, P_SUNLIGHT, 1, TEX_SUN_04);
        definePass(scene, P_FOG, 1, TEX_FOG);
        definePass(scene, P_ICE, 1, TEX_ICE);

        definePass(scene, P_RAIN, 1, TEX_RAIN_01, TEX_RAIN_02, TEX_RAIN_03, TEX_RAIN_04);
        definePass(scene, P_WATERDROP, 1, TEX_WATERDROP);

        for (int i = 0; i < 25; i++) {
            definePass(scene, P_RAINDROP_A_0 + i, 8, TEX_RAINDROP_A_0 + i);
            definePass(scene, P_RAINDROP_B_0 + i, 8, TEX_RAINDROP_B_0 + i);
        }

        // 霜在 GLES 里属于 drawSnow，排在雨之后、雪之前。
        definePass(scene, P_FROST, 1, TEX_FROST_E, TEX_FROST_F);

        definePass(scene, P_SNOW_01, 5, TEX_SNOW_01);
        definePass(scene, P_SNOW_02, 100, TEX_SNOW_02);
        definePass(scene, P_SNOW_04, 1, TEX_SNOW_04);
        definePass(scene, P_SNOW_03, 200, TEX_SNOW_03);

        definePass(
                scene,
                P_THUNDER,
                80,
                TEX_SKY_FLASH,
                TEX_LIGHTNING_01,
                TEX_LIGHTNING_02,
                TEX_LIGHTNING_03);
    }

    private void definePass(VkPassScene scene, int id, int capacitySprites, int... texIds) {
        String bufferName = "b" + id;
        int vertices = capacitySprites * VERTICES_PER_SPRITE;
        scene.createBuffer(bufferName, STRIDE, vertices, true);

        VkPass pass = scene.add(new VkPass(
                        VS,
                        FS,
                        new int[] {3, 2, 4, 1},
                        STRIDE,
                        VkPass.TOPOLOGY_TRIANGLE_LIST,
                        VkPass.BLEND_PREMULTIPLIED))
                .buffer(bufferName, 0)
                .pushConstants(PUSH_BYTES);

        // 片元着色器静态引用 8 个采样器；不足 8 张时重复绑定，避免未写 descriptor。
        for (int slot = 0; slot < SLOTS; slot++) {
            int texId = texIds[Math.min(slot, texIds.length - 1)];
            pass.texture(slot, "t" + texId);
        }

        ByteBuffer buffer =
                ByteBuffer.allocateDirect(vertices * STRIDE).order(ByteOrder.nativeOrder());
        mSpritePasses[id] = new SpritePass(pass, bufferName, buffer, capacitySprites, texIds);
    }

    /**
     * 首次被某个 pass 用到时才解码/上传（GLES 也在绘制线程里 loadTextures）。
     *
     * <p>一个 pass 的 8 个采样器会重复引用它那几张图，所以调用方要把整个
     * {@link SpritePass#texIds} 都准备好 —— 未写的 descriptor 即使没被动态选中，
     * 也不该留给验证层和驱动去猜。
     */
    private void ensureTextureUploaded(int texId) {
        if (mTextureUploaded[texId] || mPassScene == null) {
            return;
        }
        mTextureUploaded[texId] = true;

        Bitmap bitmap = AssetLoader.decodeBitmap(context, ASSET_PATHS[texId]);
        if (bitmap == null) {
            return;
        }
        Bitmap argb = bitmap.getConfig() == Bitmap.Config.ARGB_8888
                ? bitmap
                : bitmap.copy(Bitmap.Config.ARGB_8888, false);
        if (argb != bitmap) {
            bitmap.recycle();
        }
        if (argb == null) {
            return;
        }
        int width = argb.getWidth();
        int height = argb.getHeight();
        int[] pixels = new int[width * height];
        argb.getPixels(pixels, 0, width, 0, 0, width, height);
        argb.recycle();

        // GLES 侧用 inPremultiplied=true 解码并直接上传；getPixels 返回非预乘，
        // 所以这里补上同一次乘法（Skia 的 SkMulDiv255Round）。
        premultiplyRgb(pixels);
        mPassScene.setTexture("t" + texId, pixels, width, height);
    }

    private static void premultiplyRgb(int[] pixels) {
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            int alpha = (pixel >>> 24) & 0xff;
            if (alpha == 0xff) {
                continue;
            }
            int r = mulDiv255Round((pixel >> 16) & 0xff, alpha);
            int g = mulDiv255Round((pixel >> 8) & 0xff, alpha);
            int b = mulDiv255Round(pixel & 0xff, alpha);
            pixels[i] = (alpha << 24) | (r << 16) | (g << 8) | b;
        }
    }

    private static int mulDiv255Round(int color, int alpha) {
        int product = color * alpha + 128;
        return (product + (product >> 8)) >> 8;
    }

    // ─────────────────────────── every frame ───────────────────────────

    @Override
    protected void updateFrame(VkPassFrame frame, long timeMs) {
        if (mScene == null || mSpritePasses == null) {
            return;
        }

        // 与 WindmillGL.drawFrame 的前半段逐行一致。
        long deltaMs = mScene.mAnimationController.updateDelta(timeMs);
        WeatherStateManager wsm = mScene.mWeatherStateManager;
        if (wsm != null) {
            wsm.update(timeMs, mPreview);
            mScene.mCondition = wsm.getCondition();
            mScene.mNightWeight = wsm.nightWeight();
        }
        float frameDuration = wsm != null && wsm.shouldFastAnimate() ? 16.666f : 20.0f;
        mScene.mAnimationController.setFrameDurationMs(frameDuration);
        mScene.mAnimationController.advanceFrame(deltaMs);
        mScene.mFrameCnt = mScene.mAnimationController.getFrameCount();
        mScene.updateWeatherFlags();

        for (int i = 0; i < mSpritePasses.length; i++) {
            SpritePass pass = mSpritePasses[i];
            if (pass != null) {
                pass.count = 0;
                pass.buffer.clear();
            }
        }

        drawObjects();

        Matrix.multiplyMM(mPush, 0, CLIP_CORRECTION, 0, mScene.mProjectionMatrix, 0);

        for (int i = 0; i < mSpritePasses.length; i++) {
            SpritePass pass = mSpritePasses[i];
            if (pass == null) {
                continue;
            }
            int sprites = pass.count;
            if (sprites == 0) {
                frame.setVertexCount(pass.pass, 0);
                continue;
            }
            pass.buffer.position(0);
            frame.upload(pass.bufferName, pass.buffer, sprites * VERTICES_PER_SPRITE * STRIDE);
            frame.setVertexCount(pass.pass, sprites * VERTICES_PER_SPRITE);
            frame.pushConstants(pass.pass, mPush);
        }
    }

    /** 对应 WindmillGL.drawObjects；顺序与参数一字不差。 */
    private void drawObjects() {
        if (mPreview) {
            mScene.mOffset = 1.25f;
        }

        GroundRenderer.GroundTextures groundTextures = mGroundRenderer.selectTextures(
                mScene.mCondition,
                TEX_LAND_01,
                TEX_LAND_02,
                TEX_LAND_03,
                TEX_LAND_04,
                TEX_LAND_05,
                TEX_LAND_06,
                TEX_LAND_07,
                TEX_LAND_08,
                TEX_LAND_09,
                TEX_LAWN_01,
                TEX_LAWN_02,
                TEX_LAWN_03,
                TEX_LAWN_04,
                TEX_LAWN_05);

        boolean clearOn = mScene.mWeatherFlagManager.isClearOn();
        boolean rainOn = mScene.mWeatherFlagManager.isRainOn();
        boolean snowOn = mScene.mWeatherFlagManager.isSnowOn();
        boolean thunderOn = mScene.mWeatherFlagManager.isThunderOn();

        mStage = STAGE_SKY;
        clearOn = mSkyRenderer.drawSkyAndCelestial(
                this,
                mScene.mCondition,
                mScene.mNightWeight,
                mScene.mFrameCnt,
                mScene.mOffset,
                mScene.mLandscape,
                mScene.mFillScaleY,
                TEX_SKY_01,
                TEX_SKY_02,
                TEX_SKY_03,
                TEX_SKY_04,
                TEX_SKY_04,
                TEX_SKY_STARS,
                TEX_SUN_01,
                TEX_SUN_02,
                TEX_SUN_03,
                TEX_MOON,
                TEX_STAR,
                TEX_METEOR,
                clearOn,
                SkyRenderer.Config.WINDMILL);

        mStage = STAGE_NIGHTCOVER;
        if (mScene.mNightWeight > 0.0f
                && (mScene.mCondition == WeatherCondition.D3_DREARY
                        || mScene.mCondition == WeatherCondition.D4_FOG
                        || mScene.mCondition == WeatherCondition.D5_RAIN_SHOWERS
                        || mScene.mCondition == WeatherCondition.D6_THUNDERSTORMS
                        || mScene.mCondition == WeatherCondition.D9_SLEET)) {
            drawSprite(
                    TEX_NIGHTCOVER,
                    (-1.5f) + ((1.5f - mScene.mOffset) * 5.0f),
                    -2.3f,
                    -29.9f,
                    2.0f * mScene.mLandscape,
                    2.0f * mScene.mFillScaleY,
                    0.0f,
                    mScene.mNightWeight);
        }

        mStage = STAGE_CLOUDS;
        mCloudACount = 0;
        mClearCloudMode = (mScene.mCondition == WeatherCondition.D1_CLEAR
                        || mScene.mCondition == WeatherCondition.D8_ICE_COLD)
                && mScene.mNightWeight < 1.0f;
        thunderOn = mCloudRenderer.drawClouds(
                this,
                mScene.mCondition,
                mScene.mNightWeight,
                mScene.mFrameCnt,
                mScene.mOffset,
                mScene.mLandscape,
                TEX_CLOUD_A_01,
                TEX_CLOUD_A_02,
                TEX_CLOUD_A_03,
                TEX_CLOUD_B_01,
                TEX_CLOUD_B_02,
                TEX_CLOUD_B_03,
                TEX_CLOUD_LIGHT_A_01,
                TEX_CLOUD_LIGHT_A_02,
                TEX_CLOUD_LIGHT_A_03,
                TEX_CLOUD_LIGHT_B_01,
                TEX_CLOUD_LIGHT_B_02,
                TEX_CLOUD_LIGHT_B_03,
                thunderOn,
                CloudRenderer.Config.WINDMILL);

        drawWindmillDistance(2);
        mStage = STAGE_FAR_LAND;
        mGroundRenderer.drawFarLand(
                this,
                groundTextures.farLand,
                groundTextures.farLandNight,
                mScene.mOffset,
                mScene.mLandscape,
                mScene.mNightWeight);

        drawWindmillDistance(1);
        mStage = STAGE_NEAR_LAND;
        mGroundRenderer.drawNearLand(
                this,
                groundTextures.nearLand,
                groundTextures.nearLandNight,
                mScene.mOffset,
                mScene.mLandscape,
                mScene.mNightWeight);

        drawWindmillDistance(0);
        mStage = STAGE_LAWN;
        mGroundRenderer.drawLawn(
                this,
                groundTextures.lawn,
                groundTextures.lawnNight,
                mScene.mOffset,
                mScene.mLandscape,
                mScene.mNightWeight);

        mStage = STAGE_SUNLIGHT;
        clearOn = mSkyRenderer.drawSunlight(
                this,
                mScene.mCondition,
                mScene.mNightWeight,
                mScene.mFrameCnt,
                mScene.mOffset,
                mScene.mLandscape,
                TEX_SUN_04,
                clearOn,
                SkyRenderer.Config.WINDMILL);

        mStage = STAGE_FOG_ICE;
        mFogIceRenderer.drawFogIce(
                this,
                mScene.mCondition,
                mScene.mNightWeight,
                mScene.mLandscape,
                TEX_FOG,
                TEX_FOG,
                TEX_ICE,
                FogIceRenderer.Config.WINDMILL);

        mStage = STAGE_RAIN;
        rainOn = mPrecipitationRenderer.drawRain(
                this,
                mScene.mCondition,
                mScene.mFrameCnt,
                mScene.mLandscape,
                mScene.mFillScaleY,
                TEX_RAIN_01,
                TEX_RAIN_02,
                TEX_RAIN_03,
                TEX_RAIN_04,
                0,
                TEX_WATERDROP,
                mRaindropA,
                mRaindropB,
                rainOn,
                PrecipitationRenderer.Config.WINDMILL);

        snowOn = drawSnow(snowOn);

        mStage = STAGE_THUNDER;
        thunderOn = mThunderRenderer.drawThunder(
                this,
                mScene.mCondition,
                mScene.mFrameCnt,
                mScene.mOffset,
                mScene.mLandscape,
                TEX_SKY_FLASH,
                TEX_LIGHTNING_01,
                TEX_LIGHTNING_02,
                TEX_LIGHTNING_03,
                thunderOn,
                ThunderRenderer.Config.WINDMILL);

        mScene.mWeatherFlagManager.setClearOn(clearOn);
        mScene.mWeatherFlagManager.setRainOn(rainOn);
        mScene.mWeatherFlagManager.setSnowOn(snowOn);
        mScene.mWeatherFlagManager.setThunderOn(thunderOn);
    }

    private static final int[] RAINDROP_A_IDS = new int[25];
    private static final int[] RAINDROP_B_IDS = new int[25];

    static {
        for (int i = 0; i < 25; i++) {
            RAINDROP_A_IDS[i] = TEX_RAINDROP_A_0 + i;
            RAINDROP_B_IDS[i] = TEX_RAINDROP_B_0 + i;
        }
    }

    // PrecipitationRenderer 只拿数组做下标，值与 GLES 的贴图下标一一对应。
    private final int[] mRaindropA = RAINDROP_A_IDS;
    private final int[] mRaindropB = RAINDROP_B_IDS;

    private boolean drawSnow(boolean snowOn) {
        if (mScene.mCondition == WeatherCondition.D7_FLURRIES_SNOW
                || mScene.mCondition == WeatherCondition.D9_SLEET) {
            int frostTex =
                    mScene.mCondition == WeatherCondition.D9_SLEET ? TEX_FROST_F : TEX_FROST_E;
            mStage = STAGE_FROST;
            if (mScene.mCondition == WeatherCondition.D9_SLEET) {
                drawSprite(
                        frostTex,
                        -0.1f,
                        -0.5f,
                        -20.3f,
                        0.65f * mScene.mLandscape,
                        1.05f,
                        0.0f,
                        1.0f);
            } else {
                drawSprite(
                        frostTex,
                        0.0f,
                        -0.5f,
                        -20.3f,
                        0.63f * mScene.mLandscape,
                        1.05f,
                        0.0f,
                        1.0f);
            }
        }
        mStage = STAGE_SNOW;
        return mPrecipitationRenderer.drawSnow(
                this,
                mScene.mCondition,
                mScene.mFrameCnt,
                mScene.mOffset,
                mScene.mLandscape,
                TEX_SNOW_01,
                TEX_SNOW_02,
                TEX_SNOW_03,
                TEX_SNOW_04,
                snowOn,
                PrecipitationRenderer.Config.WINDMILL);
    }

    private static final int[] MILL_FAR_PASSES = {
        P_WINMILL_FAR_0, P_WINMILL_FAR_0 + 1, P_WINMILL_FAR_0 + 2
    };
    private static final int[] MILL_MID_PASSES = {
        P_WINMILL_MID_0, P_WINMILL_MID_0 + 1, P_WINMILL_MID_0 + 2
    };
    private static final int[] MILL_NEAR_PASSES = {
        P_WINMILL_NEAR_0,
        P_WINMILL_NEAR_0 + 1,
        P_WINMILL_NEAR_0 + 2,
        P_WINMILL_NEAR_0 + 3,
        P_WINMILL_NEAR_0 + 4,
        P_WINMILL_NEAR_0 + 5,
        P_WINMILL_NEAR_0 + 6
    };

    private void drawWindmillDistance(int distance) {
        mStage = STAGE_WINDMILL;
        mMillCallCursor = 0;
        if (distance == 2) {
            mActiveMillPasses = MILL_FAR_PASSES;
        } else if (distance == 1) {
            mActiveMillPasses = MILL_MID_PASSES;
        } else {
            mActiveMillPasses = MILL_NEAR_PASSES;
        }
        mWindmillRenderer.drawByDistance(
                this,
                distance,
                mScene.mFrameCnt,
                mScene.mOffset,
                mScene.mLandscape,
                mScene.mNightWeight,
                TEX_WING,
                TEX_WING_BLUR,
                TEX_CENTER,
                TEX_CENTER,
                TEX_PILLAR,
                TEX_PILLAR,
                TEX_PILLAR_FLIP,
                TEX_PILLAR_FLIP);
    }

    // ─────────────────────────── Drawer ───────────────────────────

    @Override
    public void drawSprite(
            int texture,
            float x,
            float y,
            float z,
            float scaleX,
            float scaleY,
            float rotation,
            float alpha) {
        draw(
                texture,
                x,
                y,
                z,
                scaleX,
                scaleY,
                rotation,
                alpha,
                alpha,
                alpha,
                alpha,
                QUAD_TYPE_SPRITE);
    }

    @Override
    public void drawSpriteRectOneToTwo(
            int texture,
            float x,
            float y,
            float z,
            float scaleX,
            float scaleY,
            float rotation,
            float alpha) {
        draw(
                texture,
                x,
                y,
                z,
                scaleX,
                scaleY,
                rotation,
                alpha,
                alpha,
                alpha,
                alpha,
                QUAD_TYPE_RECT_ONE_TO_TWO);
    }

    @Override
    public void drawSpriteColoredRectOneToTwo(
            int texture,
            float x,
            float y,
            float z,
            float scaleX,
            float scaleY,
            float rotation,
            float r,
            float g,
            float b,
            float alpha) {
        draw(texture, x, y, z, scaleX, scaleY, rotation, r, g, b, alpha, QUAD_TYPE_RECT_ONE_TO_TWO);
    }

    @Override
    public void drawSpriteRectOneToFour(
            int texture,
            float x,
            float y,
            float z,
            float scaleX,
            float scaleY,
            float rotation,
            float alpha) {
        draw(
                texture,
                x,
                y,
                z,
                scaleX,
                scaleY,
                rotation,
                alpha,
                alpha,
                alpha,
                alpha,
                QUAD_TYPE_RECT_ONE_TO_FOUR);
    }

    @Override
    public void drawSpriteColored(
            int texture,
            float x,
            float y,
            float z,
            float scaleX,
            float scaleY,
            float rotation,
            float r,
            float g,
            float b,
            float alpha) {
        draw(texture, x, y, z, scaleX, scaleY, rotation, r, g, b, alpha, QUAD_TYPE_SPRITE);
    }

    private void draw(
            int texture,
            float x,
            float y,
            float z,
            float scaleX,
            float scaleY,
            float rotation,
            float r,
            float g,
            float b,
            float alpha,
            int quadType) {
        int passId = routePass(texture);
        if (passId < 0) {
            return;
        }
        SpritePass pass = mSpritePasses[passId];
        if (pass == null || pass.count >= pass.capacity) {
            return;
        }
        int slot = pass.localIndex(texture);
        if (slot < 0) {
            return;
        }
        for (int i = 0; i < pass.texIds.length; i++) {
            ensureTextureUploaded(pass.texIds[i]);
        }

        float[] quad;
        if (quadType == QUAD_TYPE_RECT_ONE_TO_TWO) {
            quad = RECT_ONE_TO_TWO;
        } else if (quadType == QUAD_TYPE_RECT_ONE_TO_FOUR) {
            quad = RECT_ONE_TO_FOUR;
        } else {
            quad = QUAD;
        }

        // 与 SpriteDrawer.drawSpriteColoredWithBuffer 相同的矩阵顺序。
        Matrix.setIdentityM(mModel, 0);
        Matrix.translateM(mModel, 0, x, y, z);
        Matrix.rotateM(mModel, 0, rotation, 0f, 0f, 1f);
        Matrix.scaleM(mModel, 0, scaleX, scaleY, 1f);

        for (int i = 0; i < 4; i++) {
            mIn[0] = quad[i * 3];
            mIn[1] = quad[i * 3 + 1];
            mIn[2] = quad[i * 3 + 2];
            mIn[3] = 1f;
            Matrix.multiplyMV(mWorld, i * 4, mModel, 0, mIn, 0);
        }

        // GLES 是 TRIANGLE_STRIP（0,1,2,3）；这里拆成两个三角形保持同一覆盖。
        ByteBuffer buffer = pass.buffer;
        putVertex(buffer, 0, 0, r, g, b, alpha, slot);
        putVertex(buffer, 1, 1, r, g, b, alpha, slot);
        putVertex(buffer, 2, 2, r, g, b, alpha, slot);
        putVertex(buffer, 2, 2, r, g, b, alpha, slot);
        putVertex(buffer, 1, 1, r, g, b, alpha, slot);
        putVertex(buffer, 3, 3, r, g, b, alpha, slot);
        pass.count++;
    }

    private void putVertex(
            ByteBuffer buffer,
            int vertexIndex,
            int uvIndex,
            float r,
            float g,
            float b,
            float alpha,
            int slot) {
        int base = vertexIndex * 4;
        buffer.putFloat(mWorld[base]);
        buffer.putFloat(mWorld[base + 1]);
        buffer.putFloat(mWorld[base + 2]);
        buffer.putFloat(TEX_COORDS[uvIndex * 2]);
        buffer.putFloat(TEX_COORDS[uvIndex * 2 + 1]);
        buffer.putFloat(r);
        buffer.putFloat(g);
        buffer.putFloat(b);
        buffer.putFloat(alpha);
        buffer.putFloat(slot);
    }

    private int routePass(int texture) {
        switch (mStage) {
            case STAGE_SKY:
                if (texture == TEX_SKY_01 || texture == TEX_SKY_03 || texture == TEX_SKY_04) {
                    return P_SKY_DAY;
                }
                if (texture == TEX_SKY_02) return P_SKY_NIGHT;
                if (texture == TEX_SKY_STARS) return P_SKY_STARS;
                if (texture == TEX_SUN_01) return P_SUN_01;
                if (texture == TEX_SUN_02) return P_SUN_02;
                if (texture == TEX_SUN_03) return P_SUN_03;
                if (texture == TEX_MOON) return P_MOON;
                if (texture == TEX_STAR) return P_STAR;
                if (texture == TEX_METEOR) return P_METEOR;
                return -1;

            case STAGE_NIGHTCOVER:
                return texture == TEX_NIGHTCOVER ? P_NIGHTCOVER : -1;

            case STAGE_CLOUDS:
                if (texture == TEX_CLOUD_A_01
                        || texture == TEX_CLOUD_A_02
                        || texture == TEX_CLOUD_A_03) {
                    int index = mCloudACount++;
                    if (mClearCloudMode && index >= 2) return P_CLOUD_A_2;
                    return P_CLOUD_A_1;
                }
                if (texture == TEX_CLOUD_B_01
                        || texture == TEX_CLOUD_B_02
                        || texture == TEX_CLOUD_B_03) {
                    return P_CLOUD_B_1;
                }
                if (texture >= TEX_CLOUD_LIGHT_A_01 && texture <= TEX_CLOUD_LIGHT_B_03) {
                    return P_CLOUD_LIGHTS;
                }
                return -1;

            case STAGE_WINDMILL: {
                int order = mMillCallCursor / 3;
                mMillCallCursor++;
                if (order < 0 || order >= mActiveMillPasses.length) return -1;
                return mActiveMillPasses[order];
            }

            case STAGE_FAR_LAND:
                return P_FAR_LAND;

            case STAGE_NEAR_LAND:
                return P_NEAR_LAND;

            case STAGE_LAWN:
                return P_LAWN;

            case STAGE_SUNLIGHT:
                return texture == TEX_SUN_04 ? P_SUNLIGHT : -1;

            case STAGE_FOG_ICE:
                if (texture == TEX_FOG) return P_FOG;
                if (texture == TEX_ICE) return P_ICE;
                return -1;

            case STAGE_FROST:
                return P_FROST;

            case STAGE_RAIN:
                if (texture >= TEX_RAIN_01 && texture <= TEX_RAIN_04) return P_RAIN;
                if (texture == TEX_WATERDROP) return P_WATERDROP;
                if (texture >= TEX_RAINDROP_A_0 && texture < TEX_RAINDROP_A_0 + 25) {
                    return P_RAINDROP_A_0 + (texture - TEX_RAINDROP_A_0);
                }
                if (texture >= TEX_RAINDROP_B_0 && texture < TEX_RAINDROP_B_0 + 25) {
                    return P_RAINDROP_B_0 + (texture - TEX_RAINDROP_B_0);
                }
                return -1;

            case STAGE_SNOW:
                if (texture == TEX_SNOW_01) return P_SNOW_01;
                if (texture == TEX_SNOW_02) return P_SNOW_02;
                if (texture == TEX_SNOW_03) return P_SNOW_03;
                if (texture == TEX_SNOW_04) return P_SNOW_04;
                return -1;

            case STAGE_THUNDER:
                if (texture == TEX_SKY_FLASH
                        || texture == TEX_LIGHTNING_01
                        || texture == TEX_LIGHTNING_02
                        || texture == TEX_LIGHTNING_03) {
                    return P_THUNDER;
                }
                return -1;

            default:
                return -1;
        }
    }

    private static final class SpritePass {
        final VkPass pass;
        final String bufferName;
        final ByteBuffer buffer;
        final int capacity;
        final int[] texIds;
        int count;

        SpritePass(VkPass pass, String bufferName, ByteBuffer buffer, int capacity, int[] texIds) {
            this.pass = pass;
            this.bufferName = bufferName;
            this.buffer = buffer;
            this.capacity = capacity;
            this.texIds = texIds;
        }

        int localIndex(int texture) {
            for (int i = 0; i < texIds.length; i++) {
                if (texIds[i] == texture) return i;
            }
            return -1;
        }
    }
}
