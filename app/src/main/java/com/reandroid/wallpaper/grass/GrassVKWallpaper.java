package com.reandroid.wallpaper.grass;

import android.os.Build;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.view.Surface;

import com.reandroid.vulkan.VKWallpaperEngine;

public class GrassVKWallpaper extends WallpaperService {
    @Override
    public Engine onCreateEngine() {
        return new GrassVKEngine();
    }

    private final class GrassVKEngine extends VKWallpaperEngine<GrassScene> {
        private short[] mCachedIndices = new short[0];

        GrassVKEngine() {
            super(GrassVKWallpaper.this);
        }

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
        protected void onSceneOffset(float xOffset) {
            mScene.setOffset(xOffset);
        }

        @Override
        protected void ensureRenderer() {
            if (mRendererHandle == 0L && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mRendererHandle = GrassVKNative.nCreateRenderer(getAssets());
                GrassVKNative.uploadSkyTextures(GrassVKWallpaper.this, mRendererHandle);
                GrassVKNative.uploadAATexture(mRendererHandle);
                GrassVKNative.uploadSpriteTextures(GrassVKWallpaper.this, mRendererHandle);
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
        protected String getThreadName() { return "GrassVKWallpaperThread"; }

        @Override
        protected String getLogTag() { return "GrassVKWallpaper"; }
    }
}
