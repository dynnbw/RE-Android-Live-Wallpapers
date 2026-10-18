package com.reandroid.plugin;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.Surface;

/**
 * 通用 pass 渲染器的 Java 基类。
 *
 * <p>每壁纸的 VK 版只实现两个方法：{@link #buildScene} 声明 pass/资源，
 * {@link #updateFrame} 每帧填顶点与 push constant。帧循环、pipeline、descriptor、
 * 纹理上传都在 native 通用层里。
 */
public abstract class VkPassRendererDelegate implements VkRendererDelegate {

    protected final Context context;

    private long handle;
    private VkPassScene scene;
    private VkPassFrame frame;

    protected VkPassRendererDelegate(Context context) {
        this.context = context.getApplicationContext();
    }

    /** 声明 pass、buffer、texture。设备就绪后只跑一次。 */
    protected abstract void buildScene(VkPassScene scene);

    /** 每帧更新动态 buffer 与 push constant。 */
    protected abstract void updateFrame(VkPassFrame frame, long timeMs);

    @Override
    public long createRenderer() {
        if (handle != 0) return handle;
        handle = VkPassNative.nCreate(context.getAssets());
        if (handle == 0) return 0;
        scene = new VkPassScene(handle);
        frame = new VkPassFrame(handle);
        buildScene(scene);
        scene.commit();
        return handle;
    }

    @Override
    public void destroyRenderer() {
        if (handle != 0) {
            VkPassNative.nDestroy(handle);
            handle = 0;
            scene = null;
            frame = null;
        }
    }

    @Override
    public void onSurfaceCreated(Surface surface, int width, int height) {
        VkPassNative.nSurfaceCreated(handle, surface, width, height);
    }

    @Override
    public void onSurfaceChanged(Surface surface, int width, int height) {
        VkPassNative.nSurfaceChanged(handle, surface, width, height);
    }

    @Override
    public void onSurfaceDestroyed() {
        VkPassNative.nSurfaceDestroyed(handle);
    }

    @Override
    public void syncTexturesIfNeeded() {}

    @Override
    public void renderFrame() {
        if (frame == null || handle == 0) return;
        updateFrame(frame, SystemClock.uptimeMillis());
        VkPassNative.nRenderFrame(handle);
    }

    @Override
    public void onPluginPrefsChanged(SharedPreferences prefs) {}

    @Override
    public void ensureScene(int width, int height) {}

    @Override
    public boolean isReady() {
        return handle != 0;
    }

    /** native 自己会重建 swapchain，surface 变化时不需要整块销毁。 */
    @Override
    public boolean needsFullRecreateOnSurfaceChange() {
        return false;
    }
}
