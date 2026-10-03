package com.reandroid.wallpaper.galaxy4;

import android.content.Context;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Surface;

import com.reandroid.vulkan.VKSurfaceView;

/**
 * Galaxy4 的 Vulkan 预览视图（设置页里用）。
 *
 * <p>和 {@code Galaxy4VKPluginEngine} 走同一条 native 路径，区别只在宿主是 SurfaceView
 * 而不是 WallpaperService 的 SurfaceHolder。
 *
 * <p>galaxy4 原先没有这份预览 —— 其余三个 VK 壁纸都有。于是它明明有 VK 版，设置页里却一直
 * 只显示 OpenGL ES 版：开着「使用 Vulkan」预览也是 GL 的，VK 在设备上跑不起来同样看不出来。
 */
class Galaxy4VKSurfaceView extends VKSurfaceView<Galaxy4Scene> {

    Galaxy4VKSurfaceView(Context context) {
        super(context);
    }

    Galaxy4VKSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void ensureScene() {
        if (mScene == null && mWidth > 0 && mHeight > 0) {
            mScene = new Galaxy4Scene(mWidth, mHeight, getContext());
        }
    }

    @Override
    protected void onSceneResize(int width, int height) {
        if (mScene != null) mScene.resize(width, height);
    }

    @Override
    protected void ensureRenderer() {
        if (mRendererHandle == 0L) {
            mRendererHandle = Galaxy4VKNative.nCreateRenderer(getContext().getAssets());
            if (mRendererHandle != 0L) {
                // 纹理一次性上传；native 侧自己持有，之后每帧不再走这条
                Galaxy4VKNative.uploadTextures(getContext(), mRendererHandle);
            }
        }
    }

    @Override
    protected void destroyRenderer() {
        Galaxy4VKNative.nDestroyRenderer(mRendererHandle);
    }

    @Override
    protected void onSurfaceCreatedNative(Surface surface) {
        Galaxy4VKNative.nOnSurfaceCreated(mRendererHandle, surface, mWidth, mHeight);
    }

    @Override
    protected void onSurfaceChangedNative(Surface surface) {
        Galaxy4VKNative.nOnSurfaceChanged(mRendererHandle, surface, mWidth, mHeight);
    }

    @Override
    protected void onSurfaceDestroyedNative() {
        Galaxy4VKNative.nOnSurfaceDestroyed(mRendererHandle);
    }

    @Override
    protected void syncTexturesIfNeeded() {}

    @Override
    protected void renderFrame() {
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
    protected String getThreadName() {
        return "Galaxy4VKPreviewThread";
    }

    @Override
    protected String getLogTag() {
        return "Galaxy4VKSurfaceView";
    }
}
