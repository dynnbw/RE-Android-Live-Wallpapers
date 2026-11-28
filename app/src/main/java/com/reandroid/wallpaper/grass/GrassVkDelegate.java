package com.reandroid.wallpaper.grass;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.Surface;

import com.reandroid.plugin.VkRendererDelegate;

/**
 * Grass 的 VK 渲染器 —— 壁纸与预览共用这一份。
 *
 * <p>原来写了两遍（{@code GrassVKPluginEngine} 喂壁纸、{@code GrassVKSurfaceView} 喂预览）。
 * 它是唯一需要知道"自己是不是在预览里"的壁纸（场景初始化不同），所以构造时收一个标记。
 */
final class GrassVkDelegate implements VkRendererDelegate {

    /** {@link #buildGrassLightParams} 的长度，与 grassvk_jni.cpp 的偏移一一对应。 */
    private static final int GRASS_LIGHT_PARAM_COUNT = 22;

    private final Context mContext;
    private final SharedPreferences mPrefs;
    private final boolean mPreview;

    private GrassScene mScene;
    private long mRendererHandle;
    /**
     * native 侧 Vulkan 初始化是否成功。没有 Vulkan 物理设备时为 false
     * （Stack G11 / Android 9 实测就是这样），这时不能进 native 画帧。
     */
    private boolean mSurfaceReady;

    private int mWidth, mHeight;
    private short[] mCachedIndices = new short[0];
    private final NightStarsLayer mNightStars = new NightStarsLayer();
    private final GrassRenderDataBuilder.StarBatches mStarBatches =
            new GrassRenderDataBuilder.StarBatches();
    /** 光源与逐叶萤火虫遮挡，与 GLES 路径共用同一份逻辑。 */
    private final GrassFireflyLighting mFireflyLighting = new GrassFireflyLighting();
    /** 草叶光照的每帧参数，见 {@link #buildGrassLightParams}。 */
    private final float[] mGrassLightParams = new float[GRASS_LIGHT_PARAM_COUNT];
    /** 辉光的每帧参数，见 {@link #buildGlowParams}。 */
    private final float[] mGlowParams = new float[6];

    GrassVkDelegate(Context context, SharedPreferences prefs, boolean preview) {
        mContext = context.getApplicationContext();
        mPrefs = prefs;
        mPreview = preview;
    }

    @Override
    public String logTag() {
        return "GrassVK";
    }

    @Override
    public Object getScene() {
        return mScene;
    }

    @Override
    public boolean isReady() {
        return mScene != null && mSurfaceReady;
    }

    @Override
    public void ensureScene(int width, int height) {
        if (width <= 0 || height <= 0) return;
        mWidth = width;
        mHeight = height;
        if (mScene == null) {
            mScene = new GrassScene(width, height);
            if (mPrefs != null) {
                mScene.setPluginPrefs(mPrefs);
                mNightStars.setPluginPrefs(mPrefs);
            }
            mScene.init(mPreview);
        } else {
            mScene.resize(width, height);
        }
    }

    @Override
    public long createRenderer() {
        if (mRendererHandle != 0L) return mRendererHandle;
        mRendererHandle = GrassVKNative.nCreateRenderer(mContext.getAssets());
        if (mRendererHandle != 0L) {
            GrassVKNative.uploadSkyTextures(mContext, mRendererHandle);
            GrassVKNative.uploadAATexture(mRendererHandle);
            GrassVKNative.uploadSpriteTextures(mContext, mRendererHandle);
        }
        return mRendererHandle;
    }

    @Override
    public void destroyRenderer() {
        if (mRendererHandle != 0L) {
            GrassVKNative.nDestroyRenderer(mRendererHandle);
            mRendererHandle = 0L;
        }
    }

    @Override
    public void onSurfaceCreated(Surface surface, int width, int height) {
        mWidth = width;
        mHeight = height;
        mSurfaceReady = GrassVKNative.nOnSurfaceCreated(mRendererHandle, surface, width, height);
    }

    @Override
    public void onSurfaceChanged(Surface surface, int width, int height) {
        mWidth = width;
        mHeight = height;
        GrassVKNative.nOnSurfaceChanged(mRendererHandle, surface, width, height);
    }

    @Override
    public void onSurfaceDestroyed() {
        mSurfaceReady = false;
        GrassVKNative.nOnSurfaceDestroyed(mRendererHandle);
    }

    /** Grass 没有需要热切换的贴图。 */
    @Override
    public void syncTexturesIfNeeded() {}

    @Override
    public void renderFrame() {
        if (!mSurfaceReady) return;
        long now = SystemClock.uptimeMillis();
        mScene.update(now);
        SceneData sd = mScene.getSceneData();

        if (sd.bladeIndexRebuildNeeded || mCachedIndices.length == 0) {
            mCachedIndices = mScene.mRenderDataBuilder.buildGrassIndexArray();
        }

        /*
         * 萤火虫批必须建在草叶顶点**之前**：光源与逐叶遮挡都从这一批里取
         * （见 GrassFireflyLighting），而遮挡会进顶点数组。两批都只建一次 ——
         * 传统萤火虫那条路有状态，建两次 flare 计时会走双倍。
         */
        GrassRenderDataBuilder builder = mScene.mRenderDataBuilder;
        final int spriteStride = GrassRenderDataBuilder.FLOATS_PER_SPRITE_VERTEX;
        float[] fireflyBody = builder.buildFireflySpriteVertices(sd);
        int fireflyBodyCount = builder.getFireflyVertexCount();
        float[] fireflyFlare = builder.buildFireflyFlareSpriteVertices(sd);
        int fireflyFlareCount = builder.getFireflyFlareVertexCount();
        mFireflyLighting.update(
                sd,
                fireflyBody,
                fireflyBodyCount * spriteStride,
                fireflyFlare,
                fireflyFlareCount * spriteStride,
                now);

        float[] sky = mScene.mRenderDataBuilder.computeSkyParams(sd);
        float[] verts = mScene.mRenderDataBuilder.buildGrassVertexArray(sd);
        int vertCount = mScene.mRenderDataBuilder.getGrassVertexCount();

        GrassRenderDataBuilder.StarBatches stars = mScene.mRenderDataBuilder.buildStarBatches(
                mNightStars, sd, mWidth, mHeight, mStarBatches);

        GrassVKNative.nRenderFrame(
                mRendererHandle,
                sky,
                sd.projectionMatrix,
                verts,
                vertCount,
                mCachedIndices,
                mCachedIndices.length,
                mScene.mRenderDataBuilder.buildSunSpriteVertices(sd),
                mScene.mRenderDataBuilder.getSunVertexCount(),
                stars.white,
                stars.whiteCount,
                stars.warm,
                stars.warmCount,
                stars.cool,
                stars.coolCount,
                stars.yellow,
                stars.yellowCount,
                mScene.mRenderDataBuilder.buildDandelionSpriteVertices(sd),
                mScene.mRenderDataBuilder.getDandelionVertexCount(),
                fireflyBody,
                fireflyBodyCount,
                fireflyFlare,
                fireflyFlareCount,
                mScene.mRenderDataBuilder.buildMoonSpriteVertices(sd),
                mScene.mRenderDataBuilder.getMoonVertexCount(),
                mScene.mRenderDataBuilder.buildMoonParams(sd),
                buildGrassLightParams(sd),
                mFireflyLighting.lights,
                buildGlowParams(sd),
                sd.glowEnabled);
    }

    /**
     * 草叶光照的每帧参数，顺序与 grassvk_jni.cpp 的 {@code unlockGrassLight} 一一对应：
     * {@code [0..4]} 总强度 / 高光门控 / 横截面张角 / 叶根系数 / 叶尖系数，
     * {@code [5..7]} 透射色、{@code [8..10]} 冷影、{@code [11..13]} 迎光边颜色，
     * {@code [14..15]} 迎光边增益 / 阴影增益，
     * {@code [16..18]} 萤火虫增益 / 半径 / 本色还原增益，{@code [19..21]} 萤火虫光色。
     */
    private float[] buildGrassLightParams(SceneData sd) {
        float[] out = mGrassLightParams;
        out[0] = sd.lightStrength;
        out[1] = sd.lightHighlight;
        out[2] = GrassConstants.GRASS_LIGHT_CROSS_ANGLE;
        out[3] = GrassConstants.GRASS_LIGHT_HEIGHT_DIM;
        out[4] = GrassConstants.GRASS_LIGHT_HEIGHT_GAIN;
        out[5] = sd.lightTransmit[0];
        out[6] = sd.lightTransmit[1];
        out[7] = sd.lightTransmit[2];
        out[8] = sd.lightCool[0];
        out[9] = sd.lightCool[1];
        out[10] = sd.lightCool[2];
        out[11] = GrassConstants.GRASS_LIGHT_RIM[0];
        out[12] = GrassConstants.GRASS_LIGHT_RIM[1];
        out[13] = GrassConstants.GRASS_LIGHT_RIM[2];
        out[14] = GrassConstants.GRASS_LIGHT_RIM_GAIN;
        out[15] = GrassConstants.GRASS_LIGHT_SHADOW_GAIN;
        out[16] = sd.fireflyLightEnabled ? GrassConstants.FIREFLY_LIGHT_GAIN : 0.0f;
        out[17] = GrassConstants.FIREFLY_LIGHT_RADIUS;
        out[18] = mFireflyLighting.albedoBoost;
        out[19] = GrassConstants.FIREFLY_LIGHT_TINT[0];
        out[20] = GrassConstants.FIREFLY_LIGHT_TINT[1];
        out[21] = GrassConstants.FIREFLY_LIGHT_TINT[2];
        return out;
    }

    /**
     * 辉光的每帧参数：阈值 / 过渡宽度 / 模糊半径 / 叠加强度 / 黄昏影调强度 / 影调指数。
     *
     * <p>与 GLES 的 {@code GlowRenderer.endScene} + {@code setTone} 一一对应；
     * 影调**每帧都要给**，黄昏过去之后必须回到 0（见 GrassScene.duskTone）。
     */
    private float[] buildGlowParams(SceneData sd) {
        float[] out = mGlowParams;
        out[0] = GrassConstants.GLOW_THRESHOLD;
        out[1] = GrassConstants.GLOW_SOFT_KNEE;
        out[2] = GrassConstants.GLOW_RADIUS;
        out[3] = GrassConstants.GLOW_STRENGTH;
        out[4] = sd.duskTone;
        out[5] = GrassConstants.DUSK_TONE_GAMMA;
        return out;
    }

    @Override
    public void onPluginPrefsChanged(SharedPreferences prefs) {
        if (mScene != null) {
            mScene.setPluginPrefs(prefs);
            mNightStars.setPluginPrefs(prefs);
        }
    }

    @Override
    public void onSceneOffset(float xOffset) {
        if (mScene != null) mScene.setOffset(xOffset);
    }

    @Override
    public void onSceneTouch(float x, float y) {
        // 点击放出一颗粒子（移植自原版 MTK grass addTap）
        if (mScene != null) mScene.addTap(x, y);
    }
}
