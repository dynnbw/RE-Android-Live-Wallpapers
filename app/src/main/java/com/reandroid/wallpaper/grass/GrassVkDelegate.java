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

    private final Context mContext;
    private final SharedPreferences mPrefs;
    private final boolean mPreview;

    private GrassScene mScene;
    private long mRendererHandle;
    private int mWidth, mHeight;
    private short[] mCachedIndices = new short[0];
    private final NightStarsLayer mNightStars = new NightStarsLayer();
    private final GrassRenderDataBuilder.StarBatches mStarBatches =
            new GrassRenderDataBuilder.StarBatches();

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
        return mScene != null;
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
        GrassVKNative.nOnSurfaceCreated(mRendererHandle, surface, width, height);
    }

    @Override
    public void onSurfaceChanged(Surface surface, int width, int height) {
        mWidth = width;
        mHeight = height;
        GrassVKNative.nOnSurfaceChanged(mRendererHandle, surface, width, height);
    }

    @Override
    public void onSurfaceDestroyed() {
        GrassVKNative.nOnSurfaceDestroyed(mRendererHandle);
    }

    /** Grass 没有需要热切换的贴图。 */
    @Override
    public void syncTexturesIfNeeded() {}

    @Override
    public void renderFrame() {
        long now = SystemClock.uptimeMillis();
        mScene.update(now);
        SceneData sd = mScene.getSceneData();

        if (sd.bladeIndexRebuildNeeded || mCachedIndices.length == 0) {
            mCachedIndices = mScene.mRenderDataBuilder.buildGrassIndexArray();
        }

        float[] sky = mScene.mRenderDataBuilder.computeSkyParams(sd);
        float[] verts = mScene.mRenderDataBuilder.buildGrassVertexArray(sd);
        int vertCount = mScene.mRenderDataBuilder.getGrassVertexCount();

        GrassRenderDataBuilder.StarBatches stars =
                mScene.mRenderDataBuilder.buildStarBatches(
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
                mScene.mRenderDataBuilder.buildFireflySpriteVertices(sd),
                mScene.mRenderDataBuilder.getFireflyVertexCount(),
                mScene.mRenderDataBuilder.buildFireflyFlareSpriteVertices(sd),
                mScene.mRenderDataBuilder.getFireflyFlareVertexCount(),
                mScene.mRenderDataBuilder.buildMoonSpriteVertices(sd),
                mScene.mRenderDataBuilder.getMoonVertexCount(),
                mScene.mRenderDataBuilder.buildMoonParams(sd));
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

