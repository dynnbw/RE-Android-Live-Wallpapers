package com.reandroid.wallpaper.galaxy;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.Surface;

import com.reandroid.plugin.VkRendererDelegate;
import com.reandroid.settings.WallpaperSettings;

/**
 * Galaxy 的 VK 渲染器 —— 壁纸与预览共用这一份。
 *
 * <p>原来写了两遍（{@code GalaxyVKPluginEngine} 喂壁纸、{@code GalaxyVKSurfaceView} 喂预览），
 * 两边的 native 调用逐一对应。宿主（线程 / 配速 / surface 生命周期）现在由共享的
 * {@code VkRendererEngine} 与 {@code VKSurfaceView} 提供。
 */
final class GalaxyVkDelegate implements VkRendererDelegate {

    /** light2 贴图的热切换是每秒查一次设置，不是每帧。 */
    private static final long SETTINGS_SYNC_INTERVAL_MS = 1000L;

    private final Context mContext;
    private final SharedPreferences mPrefs;

    private GalaxyScene mScene;
    private long mRendererHandle;
    private boolean mUseLight2;
    private long mLastLightSyncCheckMs;

    GalaxyVkDelegate(Context context, SharedPreferences prefs) {
        mContext = context.getApplicationContext();
        mPrefs = prefs;
    }

    @Override
    public String logTag() {
        return "GalaxyVK";
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
        if (mScene == null) {
            mScene = new GalaxyScene(width, height, mContext);
            if (mPrefs != null) mScene.setPluginPrefs(mPrefs);
        } else {
            mScene.resize(width, height);
        }
    }

    @Override
    public long createRenderer() {
        if (mRendererHandle != 0L) return mRendererHandle;
        mRendererHandle = GalaxyVKNative.nCreateRenderer(mContext.getAssets());
        if (mRendererHandle != 0L) {
            GalaxyVKNative.uploadBackgroundTexture(mContext, mRendererHandle);
            GalaxyVKNative.uploadLightTexture(mContext, mRendererHandle);
            mUseLight2 = WallpaperSettings.isGalaxyLight2Enabled(false);
        }
        return mRendererHandle;
    }

    @Override
    public void destroyRenderer() {
        if (mRendererHandle != 0L) {
            GalaxyVKNative.nDestroyRenderer(mRendererHandle);
            mRendererHandle = 0L;
        }
    }

    @Override
    public void onSurfaceCreated(Surface surface, int width, int height) {
        GalaxyVKNative.nOnSurfaceCreated(mRendererHandle, surface, width, height);
    }

    @Override
    public void onSurfaceChanged(Surface surface, int width, int height) {
        GalaxyVKNative.nOnSurfaceChanged(mRendererHandle, surface, width, height);
    }

    @Override
    public void onSurfaceDestroyed() {
        GalaxyVKNative.nOnSurfaceDestroyed(mRendererHandle);
    }

    /** 光晕贴图（light1 / light2）切换后要重传。 */
    @Override
    public void syncTexturesIfNeeded() {
        if (mRendererHandle == 0L) return;
        long now = SystemClock.uptimeMillis();
        if (now - mLastLightSyncCheckMs < SETTINGS_SYNC_INTERVAL_MS) return;
        mLastLightSyncCheckMs = now;
        boolean desired = WallpaperSettings.isGalaxyLight2Enabled(false);
        if (desired == mUseLight2) return;
        GalaxyVKNative.uploadLightTexture(mContext, mRendererHandle);
        mUseLight2 = desired;
    }

    @Override
    public void renderFrame() {
        long now = SystemClock.uptimeMillis();
        mScene.update(now);
        GalaxyScene.SceneData sceneData = mScene.getSceneData();
        boolean colorsDirty = mScene.consumeParticleBufferRebuildRequested();
        GalaxyVKNative.nRenderFrame(
                mRendererHandle,
                sceneData.getMvpMatrix(),
                sceneData.getParticlePositions(),
                colorsDirty ? sceneData.getParticleColors() : null,
                sceneData.getParticleCount(),
                sceneData.getParticleAlphaMultiplier(),
                sceneData.getTwist());
    }

    @Override
    public void onPluginPrefsChanged(SharedPreferences prefs) {
        if (mScene != null) mScene.setPluginPrefs(prefs);
    }

    @Override
    public void onSceneOffset(float xOffset) {
        if (mScene != null) mScene.setOffset(xOffset);
    }

    // Galaxy 没有触摸交互 —— onSceneTouch / onTouchEvent 用默认实现即可
}
