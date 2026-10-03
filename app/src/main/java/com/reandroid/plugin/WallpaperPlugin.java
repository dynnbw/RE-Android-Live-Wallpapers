package com.reandroid.plugin;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;

/**
 * Entry point for a wallpaper plugin.
 * Each wallpaper provides one implementation of this interface.
 */
public interface WallpaperPlugin {

    /** Unique identifier (matches assets/{id}/ directory name). */
    String getId();

    /** Create the rendering engine for this wallpaper. */
    WallpaperEngine createEngine(Context context, WallpaperPluginHost host);

    /**
     * 这个插件的 Vulkan 预览视图；没有就返回 null。
     *
     * <p>设置页的预览默认是 OpenGL ES 版，只有打开「使用 Vulkan」时才换成这里返回的视图 ——
     * 否则用户在预览里看到的和实际应用后的渲染器是两回事，VK 在设备上跑不起来也看不出来。
     *
     * <p>由插件自己给，而不是在 info.json 里再写一个类名：视图类通常不是 public，
     * 而这个方法天然能访问同包的 nIsVulkanSupported()，能不能用 Vulkan 由它自己判断。
     *
     * <p>{@code prefs} 是插件作用域的那份 —— 预览与壁纸用的是同一个 delegate，
     * 场景建出来时就得带着设置。
     */
    default View createVulkanPreview(Context context, SharedPreferences prefs) {
        return null;
    }

    /** Release plugin resources on unload. */
    void release();
}
