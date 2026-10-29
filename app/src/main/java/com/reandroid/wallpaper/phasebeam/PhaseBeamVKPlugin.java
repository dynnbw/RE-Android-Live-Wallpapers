package com.reandroid.wallpaper.phasebeam;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;

import com.reandroid.plugin.VkPassNative;
import com.reandroid.plugin.VkRendererEngine;
import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;
import com.reandroid.vulkan.VKSurfaceView;

public class PhaseBeamVKPlugin implements WallpaperPlugin {

    @Override
    public String getId() {
        return "phasebeam_vk";
    }

    @Override
    public WallpaperEngine createEngine(Context ctx, WallpaperPluginHost host) {
        // 页间距（xStep）现在由 VkRendererEngine 直接转给 delegate。
        return new VkRendererEngine(
                ctx, host, new PhaseBeamVkDelegate(ctx, host.getSharedPreferences()));
    }

    @Override
    public View createVulkanPreview(Context context, SharedPreferences prefs) {
        if (!VkPassNative.nIsSupported()) return null;
        return new VKSurfaceView(context, new PhaseBeamVkDelegate(context, prefs));
    }

    @Override
    public void release() {}
}
