package com.reandroid.wallpaper.fall;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;

import com.reandroid.plugin.VkRendererEngine;
import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;
import com.reandroid.vulkan.VKSurfaceView;

public class FallVKPlugin implements WallpaperPlugin {
    @Override
    public String getId() {
        return "fall_vk";
    }

    @Override
    public WallpaperEngine createEngine(Context ctx, WallpaperPluginHost host) {
        return new VkRendererEngine(
                ctx, host, new FallVkDelegate(ctx, host.getSharedPreferences()));
    }

    /**
     * 设置页的 VK 预览。设备不支持 Vulkan 就返回 null，让预览退回 OpenGL ES。
     *
     * <p>用的是与壁纸路径同一个 delegate 类 —— native 调用只此一份。
     */
    @Override
    public View createVulkanPreview(Context context, SharedPreferences prefs) {
        if (!FallVKNative.nIsVulkanSupported()) return null;
        return new VKSurfaceView(context, new FallVkDelegate(context, prefs));
    }

    @Override
    public void release() {}
}
