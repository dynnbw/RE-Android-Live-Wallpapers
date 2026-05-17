package com.reandroid.wallpaper.grass;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Surface;

import com.reandroid.vulkan.VKSurfaceView;

class GrassVKSurfaceView extends VKSurfaceView<GrassScene> {
    private static final int VERTEX_STRIDE = 8;

    private short[] mCachedIndices = new short[0];

    GrassVKSurfaceView(Context context) {
        super(context);
    }

    GrassVKSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void ensureScene() {
        if (mScene == null && mWidth > 0 && mHeight > 0) {
            mScene = new GrassScene(mWidth, mHeight);
            mScene.init(true);
        }
    }

    @Override
    protected void onSceneResize(int width, int height) {
        if (mScene != null) mScene.resize(width, height);
    }

    @Override
    protected void ensureRenderer() {
        if (mRendererHandle == 0L && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            mRendererHandle = GrassVKNative.nCreateRenderer(getContext().getAssets());
            GrassVKNative.uploadSkyTextures(getContext(), mRendererHandle);
            GrassVKNative.uploadAATexture(mRendererHandle);
            GrassVKNative.uploadSpriteTextures(getContext(), mRendererHandle);
        }
    }

    @Override
    protected void destroyRenderer() {
        GrassVKNative.nDestroyRenderer(mRendererHandle);
    }

    @Override
    protected void onSurfaceCreatedNative(Surface surface) {
        GrassVKNative.nOnSurfaceCreated(mRendererHandle, surface, mWidth, mHeight);
    }

    @Override
    protected void onSurfaceChangedNative(Surface surface) {
        GrassVKNative.nOnSurfaceChanged(mRendererHandle, surface, mWidth, mHeight);
    }

    @Override
    protected void onSurfaceDestroyedNative() {
        GrassVKNative.nOnSurfaceDestroyed(mRendererHandle);
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
        float[] sunVerts = mScene.mRenderDataBuilder.buildSunSpriteVertices(sd);
        float[] dandelionVerts = mScene.mRenderDataBuilder.buildDandelionSpriteVertices(sd);
        float[] fireflyVerts = mScene.mRenderDataBuilder.buildFireflySpriteVertices(sd);
        float[] fireflyFlareVerts = mScene.mRenderDataBuilder.buildFireflyFlareSpriteVertices(sd);
        float[] moonVerts = mScene.mRenderDataBuilder.buildMoonSpriteVertices(sd);
        float[] moonParams = mScene.mRenderDataBuilder.buildMoonParams(sd);

        GrassVKNative.nRenderFrame(mRendererHandle,
                sky, sd.projectionMatrix,
                verts, vertCount,
                mCachedIndices, mCachedIndices.length,
                sunVerts, mScene.mRenderDataBuilder.getSunVertexCount(),
                dandelionVerts, mScene.mRenderDataBuilder.getDandelionVertexCount(),
                fireflyVerts, mScene.mRenderDataBuilder.getFireflyVertexCount(),
                fireflyFlareVerts, mScene.mRenderDataBuilder.getFireflyFlareVertexCount(),
                moonVerts, mScene.mRenderDataBuilder.getMoonVertexCount(),
                moonParams);
    }

    @Override
    protected String getThreadName() { return "GrassVKPreviewThread"; }

    @Override
    protected String getLogTag() { return "GrassVKSurfaceView"; }
}
