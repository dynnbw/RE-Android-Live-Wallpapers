package com.reandroid.plugin;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.Surface;

/**
 * VK 渲染器里"这个壁纸特有"的那部分 —— 引擎和预览都只调这一组，自己一行 Vulkan 调用都没有。
 *
 * <p>为什么要这一层：同一件事原来写了两份（{@code *VKPluginEngine} 喂壁纸，
 * {@code *VKSurfaceView} 喂预览），两份的成员几乎逐一对应，只是宿主不同。
 * 加一个壁纸的 VK 版要写两遍同样的 native 调用，改动还要同时改两处。
 *
 * <p>现在加一个壁纸的 VK 版 = <b>实现这一个接口</b>，壁纸与预览两条路径同时得到支持。
 *
 * <p>宿主负责的（渲染线程、配速、surface 生命周期）不在这个接口里 —— 那是
 * {@code BaseVKPluginEngine} 与 {@code VKSurfaceView} 的事，两者共用。
 */
public interface VkRendererDelegate {

    /**
     * 让场景与这个尺寸一致：没有就建，有就调整。
     *
     * <p>尺寸可能先是 0（宿主还没拿到画布），实现里按 0 直接返回即可。
     */
    void ensureScene(int width, int height);

    /** 建 native 渲染器并自己持有句柄；失败返回 0。 */
    long createRenderer();

    /** 销毁 native 渲染器。 */
    void destroyRenderer();

    void onSurfaceCreated(Surface surface, int width, int height);

    void onSurfaceChanged(Surface surface, int width, int height);

    void onSurfaceDestroyed();

    /** 每帧调用；设置变了在这里重新上传纹理。 */
    void syncTexturesIfNeeded();

    /** 画一帧。 */
    void renderFrame();

    /** 插件设置变了（壁纸路径用；预览路径走 setPluginPrefs 反射）。 */
    void onPluginPrefsChanged(SharedPreferences prefs);

    /** 场景就绪了吗 —— 为 null 时不能进 native。 */
    default boolean isReady() {
        return true;
    }

    /**
     * surface 变化时要不要按"整个销毁重建"处理。
     *
     * <p>默认 true —— native 侧需要重建 swapchain 的实现靠它。**native 自己会重建 swapchain**
     * 的壁纸（galaxy4 就是）返回 false：那样只更新尺寸与场景，不销毁渲染器。
     */
    default boolean needsFullRecreateOnSurfaceChange() {
        return true;
    }

    /** 场景对象；设置页要按方法名反射把 prefs 推给它。 */
    Object getScene();

    /** 日志与线程名用的短名。 */
    String logTag();

    /** 桌面滚动（预览路径不会调）。 */
    default void onSceneOffset(float xOffset) {}

    /** 触摸的原点（预览路径不会调）。默认由 {@link #onTouchEvent} 转过来。 */
    default void onSceneTouch(float x, float y) {}

    /**
     * 完整触摸事件。默认只把按下转成 {@link #onSceneTouch}；需要滑动等手势的壁纸
     * （fall 的滑动波纹）覆盖它。
     */
    default void onTouchEvent(MotionEvent event) {
        if (event != null && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            onSceneTouch(event.getX(), event.getY());
        }
    }

    /** 系统指令（含 android.wallpaper.tap）。 */
    default void onCommand(String action, int x, int y, int z, Bundle extras) {}
}
