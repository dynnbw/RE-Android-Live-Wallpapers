package com.reandroid.wallpaper.weatherwallpapers.ocean;

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
 * Ocean Weather 的 Vulkan 入口。壁纸与设置页预览共用 {@link OceanWeatherVkDelegate}。
 */
public class OceanWeatherVKPlugin implements WallpaperPlugin {

    @Override
    public String getId() {
        return "ocean_vk";
    }

    @Override
    public WallpaperEngine createEngine(Context ctx, WallpaperPluginHost host) {
        return new VkRendererEngine(
                ctx, host, new OceanWeatherVkDelegate(ctx, false));
    }

    @Override
    public View createVulkanPreview(Context context, SharedPreferences prefs) {
        if (!VkPassNative.nIsSupported()) return null;
        return new VKSurfaceView(context, new OceanWeatherVkDelegate(context, true));
    }

    @Override
    public void release() {}
}
