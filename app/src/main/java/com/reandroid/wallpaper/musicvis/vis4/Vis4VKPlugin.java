package com.reandroid.wallpaper.musicvis.vis4;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;

import com.reandroid.plugin.VkPassNative;
import com.reandroid.plugin.VkRendererEngine;
import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;
import com.reandroid.vulkan.VKSurfaceView;
import com.reandroid.wallpaper.musicvis.MusicVisVuVkDelegate;

/**
 * vis4（VU 表）的 Vulkan 入口。壁纸与预览共用同一个 {@link MusicVisVuVkDelegate}。
 */
public class Vis4VKPlugin implements WallpaperPlugin {

    @Override
    public String getId() {
        return "vis4_vk";
    }

    @Override
    public WallpaperEngine createEngine(Context ctx, WallpaperPluginHost host) {
        return new VkRendererEngine(
                ctx, host, new MusicVisVuVkDelegate(ctx, host.getSharedPreferences()));
    }

    @Override
    public View createVulkanPreview(Context context, SharedPreferences prefs) {
        if (!VkPassNative.nIsSupported()) return null;
        return new VKSurfaceView(context, new MusicVisVuVkDelegate(context, prefs));
    }

    @Override
    public void release() {}
}
