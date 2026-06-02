package com.reandroid.wallpaper.grass;

import android.os.Build;
import android.os.SystemClock;
import android.view.Surface;

import com.reandroid.plugin.BaseVKPluginEngine;
import com.reandroid.plugin.WallpaperPluginHost;

public class GrassVKPluginEngine extends BaseVKPluginEngine {
    private GrassScene mScene;
    private short[] mCachedIndices = new short[0];

    public GrassVKPluginEngine(android.content.Context context, WallpaperPluginHost host) {
        super(context, host);
    }

    @Override
    protected String getLogTag() { return "GrassVK"; }

    @Override
    protected void ensureScene() {
        if (mScene == null && mWidth > 0 && mHeight > 0) {
            mScene = new GrassScene(mWidth, mHeight);
            mScene.init(isPreview());
        }
    }

    @Override
    protected void ensureOrResizeScene() {
        if (mScene == null) {
            mScene = new GrassScene(mWidth, mHeight);
            mScene.init(isPreview());
        } else {
            mScene.resize(mWidth, mHeight);
        }
    }

    @Override
    protected long createRenderer() {
        long handle = GrassVKNative.nCreateRenderer(mContext.getAssets());
        if (handle != 0L) {
            GrassVKNative.uploadSkyTextures(mContext, handle);
            GrassVKNative.uploadAATexture(handle);
            GrassVKNative.uploadSpriteTextures(mContext, handle);
        }
        return handle;
    }

    @Override
    protected void destroyRenderer() {
        GrassVKNative.nDestroyRenderer(mRendererHandle);
    }

    @Override
    protected void onSurfaceCreatedNative(Surface surface, int w, int h) {
        GrassVKNative.nOnSurfaceCreated(mRendererHandle, surface, w, h);
    }

    @Override
    protected void onSurfaceChangedNative(Surface surface, int w, int h) {
        GrassVKNative.nOnSurfaceChanged(mRendererHandle, surface, w, h);
    }

    @Override
    protected void onSurfaceDestroyedNative() {
        GrassVKNative.nOnSurfaceDestroyed(mRendererHandle);
    }

    @Override
    protected void syncTexturesIfNeeded() {
        // Grass has no runtime texture switching
    }

    @Override
    protected void renderFrame() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;

        long now = SystemClock.uptimeMillis();
        mScene.update(now);
        SceneData sd = mScene.getSceneData();

        if (sd.bladeIndexRebuildNeeded || mCachedIndices.length == 0) {
            mCachedIndices = mScene.mRenderDataBuilder.buildGrassIndexArray();
        }

        float[] sky = mScene.mRenderDataBuilder.computeSkyParams(sd);
        float[] verts = mScene.mRenderDataBuilder.buildGrassVertexArray(sd);
        int vertCount = mScene.mRenderDataBuilder.getGrassVertexCount();

        GrassVKNative.nRenderFrame(mRendererHandle,
                sky, sd.projectionMatrix,
                verts, vertCount,
                mCachedIndices, mCachedIndices.length,
                mScene.mRenderDataBuilder.buildSunSpriteVertices(sd),
                mScene.mRenderDataBuilder.getSunVertexCount(),
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
    protected void onSceneOffset(float xOffset) {
        if (mScene != null) mScene.setOffset(xOffset);
    }

    @Override
    protected void onSceneTouch(float x, float y) {
        // Grass wallpaper has no touch interaction
    }
}
