package com.reandroid.wallpaper.holospiral;

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
 * Holo Spiral 的 Vulkan 插件入口。
 *
 * <p>壁纸与预览共用同一个 delegate。设备不支持 Vulkan 时预览返回 null，
 * 让设置页退回 OpenGL ES。
 */
public class HoloSpiralVKPlugin implements WallpaperPlugin {

    @Override
    public String getId() {
        return "holospiral_vk";
    }

    @Override
    public WallpaperEngine createEngine(Context ctx, WallpaperPluginHost host) {
        return new VkRendererEngine(
                ctx, host, new HoloSpiralVkDelegate(ctx, host.getSharedPreferences()));
    }

    @Override
    public View createVulkanPreview(Context context, SharedPreferences prefs) {
        if (!VkPassNative.nIsSupported()) return null;
        return new VKSurfaceView(context, new HoloSpiralVkDelegate(context, prefs));
    }

    @Override
    public void release() {}
}
