package com.reandroid.wallpaper.weatherwallpapers.windmill;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;

import com.reandroid.plugin.VkPassNative;
import com.reandroid.plugin.VkRendererEngine;
import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;
import com.reandroid.vulkan.VKSurfaceView;

/**
 * Windmill 的 Vulkan 插件入口。壁纸路径与设置页预览共用 {@link WindmillVkDelegate}。
 */
public class WindmillVKPlugin implements WallpaperPlugin {

    @Override
    public String getId() {
        return "windmill_vk";
    }

    @Override
    public WallpaperEngine createEngine(Context ctx, WallpaperPluginHost host) {
        return new VkRendererEngine(ctx, host,
                new WindmillVkDelegate(ctx, host.getSharedPreferences(), false));
    }

    /**
     * 设置页的 VK 预览。设备不支持 Vulkan 就返回 null，让预览退回 OpenGL ES。
     */
    @Override
    public View createVulkanPreview(Context context, SharedPreferences prefs) {
        if (!VkPassNative.nIsSupported()) return null;
        return new VKSurfaceView(context, new WindmillVkDelegate(context, prefs, true));
    }

    @Override
    public void release() {}
}
