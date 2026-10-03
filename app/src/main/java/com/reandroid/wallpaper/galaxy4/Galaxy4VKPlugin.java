package com.reandroid.wallpaper.galaxy4;

import android.content.Context;

import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;

public class Galaxy4VKPlugin implements WallpaperPlugin {
    static {
        android.util.Log.e("Galaxy4VKPlugin", "*** PLUGIN STATIC INIT ***");
    }

    @Override
    public String getId() {
        return "galaxy4_vk";
    }

    @Override
    public WallpaperEngine createEngine(Context ctx, WallpaperPluginHost host) {
        return new Galaxy4VKPluginEngine(ctx, host);
    }

    /**
     * 设置页的 VK 预览。设备不支持 Vulkan 就返回 null，让预览退回 OpenGL ES。
     */
    @Override
    public android.view.View createVulkanPreview(Context context) {
        if (!Galaxy4VKNative.nIsVulkanSupported()) return null;
        return new Galaxy4VKSurfaceView(context);
    }

    @Override
    public void release() {}
}
