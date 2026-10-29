package com.reandroid.wallpaper.musicvis.vis1;

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
 * vis1（Visualizer）的 Vulkan 入口。壁纸与预览共用同一个 {@link Vis1VkDelegate}。
 */
public class Vis1VKPlugin implements WallpaperPlugin {

    @Override
    public String getId() {
        return "vis1_vk";
    }

    @Override
    public WallpaperEngine createEngine(Context ctx, WallpaperPluginHost host) {
        return new VkRendererEngine(
                ctx, host, new Vis1VkDelegate(ctx, host.getSharedPreferences()));
    }

    @Override
    public View createVulkanPreview(Context context, SharedPreferences prefs) {
        if (!VkPassNative.nIsSupported()) return null;
        return new VKSurfaceView(context, new Vis1VkDelegate(context, prefs));
    }

    @Override
    public void release() {}
}
