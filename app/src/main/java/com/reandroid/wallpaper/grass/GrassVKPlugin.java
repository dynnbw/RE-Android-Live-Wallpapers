package com.reandroid.wallpaper.grass;

import android.content.Context;

import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;

public class GrassVKPlugin implements WallpaperPlugin {
    @Override
    public String getId() {
        return "grass_vk";
    }

    @Override
    public WallpaperEngine createEngine(Context c, WallpaperPluginHost host) {
        return new GrassVKPluginEngine(c, host);
    }

    /**
     * 设置页的 VK 预览。设备不支持 Vulkan 就返回 null，让预览退回 OpenGL ES。
     */
    @Override
    public android.view.View createVulkanPreview(Context context) {
        if (!GrassVKNative.nIsVulkanSupported()) return null;
        return new GrassVKSurfaceView(context);
    }

    @Override
    public void release() {}
}
