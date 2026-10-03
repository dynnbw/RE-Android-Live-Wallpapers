package com.reandroid.wallpaper.fall;

import android.content.Context;

import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;

public class FallVKPlugin implements WallpaperPlugin {
    @Override
    public String getId() {
        return "fall_vk";
    }

    @Override
    public WallpaperEngine createEngine(Context c, WallpaperPluginHost host) {
        return new FallVKPluginEngine(c, host);
    }

    /**
     * 设置页的 VK 预览。设备不支持 Vulkan 就返回 null，让预览退回 OpenGL ES。
     */
    @Override
    public android.view.View createVulkanPreview(Context context) {
        if (!FallVKNative.nIsVulkanSupported()) return null;
        return new FallVKSurfaceView(context);
    }

    @Override
    public void release() {}
}
