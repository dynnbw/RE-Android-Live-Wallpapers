package com.reandroid.wallpaper.musicvis.vis6;

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
 * vis6（环形频谱）的 Vulkan 入口。壁纸与预览共用同一个 {@link Vis6VkDelegate}。
 */
public class Vis6VKPlugin implements WallpaperPlugin {

    @Override
    public String getId() {
        return "vis6_vk";
    }

    @Override
    public WallpaperEngine createEngine(Context ctx, WallpaperPluginHost host) {
        return new VkRendererEngine(
                ctx, host, new Vis6VkDelegate(ctx, host.getSharedPreferences()));
    }

    @Override
    public View createVulkanPreview(Context context, SharedPreferences prefs) {
        if (!VkPassNative.nIsSupported()) return null;
        return new VKSurfaceView(context, new Vis6VkDelegate(context, prefs));
    }

    @Override
    public void release() {}
}
