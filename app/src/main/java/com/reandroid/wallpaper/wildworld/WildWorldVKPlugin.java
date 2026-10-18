package com.reandroid.wallpaper.wildworld;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;

import com.reandroid.plugin.VkPassNative;
import com.reandroid.plugin.VkRendererEngine;
import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;
import com.reandroid.vulkan.VKSurfaceView;

public class WildWorldVKPlugin implements WallpaperPlugin {

    @Override
    public String getId() {
        return "wildworld_vk";
    }

    @Override
    public WallpaperEngine createEngine(Context ctx, WallpaperPluginHost host) {
        return new VkRendererEngine(
                ctx, host,
                new WildWorldVkDelegate(ctx, host.getSharedPreferences(), false));
    }

    @Override
    public View createVulkanPreview(Context context, SharedPreferences prefs) {
        if (!VkPassNative.nIsSupported()) return null;
        return new VKSurfaceView(
                context, new WildWorldVkDelegate(context, prefs, true));
    }

    @Override
    public void release() {}
}
