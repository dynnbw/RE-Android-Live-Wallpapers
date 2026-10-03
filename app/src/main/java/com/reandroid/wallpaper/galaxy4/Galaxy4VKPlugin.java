package com.reandroid.wallpaper.galaxy4;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;

import com.reandroid.plugin.VkRendererEngine;
import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;
import com.reandroid.vulkan.VKSurfaceView;

public class Galaxy4VKPlugin implements WallpaperPlugin {

    @Override
    public String getId() {
        return "galaxy4_vk";
    }

    @Override
    public WallpaperEngine createEngine(Context ctx, WallpaperPluginHost host) {
        return new VkRendererEngine(
                ctx, host, new Galaxy4VkDelegate(ctx, host.getSharedPreferences()));
    }

    /**
     * 设置页的 VK 预览。设备不支持 Vulkan 就返回 null，让预览退回 OpenGL ES。
     *
     * <p>用的 delegate 与壁纸那条路径是同一个类 —— 预览和壁纸不会再各写一份 native 调用。
     */
    @Override
    public View createVulkanPreview(Context context, SharedPreferences prefs) {
        if (!Galaxy4VKNative.nIsVulkanSupported()) return null;
        return new VKSurfaceView(context, new Galaxy4VkDelegate(context, prefs));
    }

    @Override
    public void release() {}
}
