package com.reandroid.wallpaper.galaxy;

import android.content.Context;

import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;

public class GalaxyVKPlugin implements WallpaperPlugin {
    @Override
    public String getId() {
        return "galaxy_vk";
    }

    @Override
    public WallpaperEngine createEngine(Context c, WallpaperPluginHost host) {
        return new GalaxyVKPluginEngine(c, host);
    }

    /**
     * 设置页的 VK 预览。设备不支持 Vulkan 就返回 null，让预览退回 OpenGL ES。
     */
    @Override
    public android.view.View createVulkanPreview(Context context) {
        if (!GalaxyVKNative.nIsVulkanSupported()) return null;
        return new GalaxyVKSurfaceView(context);
    }

    @Override
    public void release() {}
}
