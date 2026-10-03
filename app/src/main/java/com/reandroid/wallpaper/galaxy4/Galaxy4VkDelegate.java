package com.reandroid.wallpaper.galaxy4;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.Surface;

import com.reandroid.plugin.VkRendererDelegate;

/**
 * Galaxy4 的 VK 渲染器 —— 壁纸与预览共用这一份。
 *
 * <p>原来这件事写了两遍：{@code Galaxy4VKPluginEngine} 喂壁纸、{@code Galaxy4VKSurfaceView}
 * 喂预览，两边的 native 调用逐一对应。现在宿主（线程 / 配速 / surface 生命周期）是共享的
 * {@code VkRendererEngine} 与 {@code VKSurfaceView}，这里只留"galaxy4 怎么画"。
 */
final class Galaxy4VkDelegate implements VkRendererDelegate {

    private final Context mContext;
    /** 场景一建出来就要有设置，所以构造时就收下（之后的变化走 onPluginPrefsChanged）。 */
    private final SharedPreferences mPrefs;
    private Galaxy4Scene mScene;
    private long mRendererHandle;

    Galaxy4VkDelegate(Context context, SharedPreferences prefs) {
        mContext = context.getApplicationContext();
        mPrefs = prefs;
    }

    @Override
    public String logTag() {
        return "Galaxy4VK";
    }

    @Override
    public Object getScene() {
        return mScene;
    }

    @Override
    public boolean isReady() {
        return mScene != null;
    }

    /**
     * galaxy4 的 native 侧自己会重建 swapchain，所以 surface 变化时**不要**销毁重建渲染器
     * （基类默认那套是给需要重建的实现用的）。
     */
    @Override
    public boolean needsFullRecreateOnSurfaceChange() {
        return false;
    }

    @Override
    public void ensureScene(int width, int height) {
        if (width <= 0 || height <= 0) return;
        if (mScene == null) {
            mScene = new Galaxy4Scene(width, height, mContext);
            if (mPrefs != null) mScene.setPluginPrefs(mPrefs);
        } else {
            mScene.resize(width, height);
        }
    }

    @Override
    public long createRenderer() {
        if (mRendererHandle != 0L) return mRendererHandle;
        mRendererHandle = Galaxy4VKNative.nCreateRenderer(mContext.getAssets());
        if (mRendererHandle != 0L) {
            // 纹理一次上传，之后由 native 侧持有
            Galaxy4VKNative.uploadTextures(mContext, mRendererHandle);
        }
        return mRendererHandle;
    }

    @Override
    public void destroyRenderer() {
        if (mRendererHandle != 0L) {
            Galaxy4VKNative.nDestroyRenderer(mRendererHandle);
            mRendererHandle = 0L;
        }
    }

    @Override
    public void onSurfaceCreated(Surface surface, int width, int height) {
        Galaxy4VKNative.nOnSurfaceCreated(mRendererHandle, surface, width, height);
    }

    @Override
    public void onSurfaceChanged(Surface surface, int width, int height) {
        Galaxy4VKNative.nOnSurfaceChanged(mRendererHandle, surface, width, height);
    }

    @Override
    public void onSurfaceDestroyed() {
        Galaxy4VKNative.nOnSurfaceDestroyed(mRendererHandle);
    }

    /** galaxy4 的纹理只在建渲染器时上传一次，没有需要热切换的。 */
    @Override
    public void syncTexturesIfNeeded() {}

    @Override
    public void renderFrame() {
        long now = SystemClock.uptimeMillis();
        mScene.update(now);
        Galaxy4Scene.SceneData data = mScene.getSceneData();
        Galaxy4VKNative.nRenderFrame(
                mRendererHandle,
                data.getMvpMatrix(),
                data.getSpaceClouds(),
                data.getBgStars(),
                data.getStaticStars(),
                data.getSpaceCloudCount(),
                data.getBgStarCount(),
                data.getTimeSeconds(),
                data.getParticleSize(),
                data.getParticleOpacity());
    }

    @Override
    public void onPluginPrefsChanged(SharedPreferences prefs) {
        if (mScene != null) mScene.setPluginPrefs(prefs);
    }
}
