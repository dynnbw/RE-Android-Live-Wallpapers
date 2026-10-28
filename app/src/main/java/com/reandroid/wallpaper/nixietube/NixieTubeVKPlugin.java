package com.reandroid.wallpaper.nixietube;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;

import com.reandroid.plugin.VkPassNative;
import com.reandroid.plugin.VkRendererEngine;
import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;
import com.reandroid.vulkan.VKSurfaceView;

public class NixieTubeVKPlugin implements WallpaperPlugin {

    @Override
    public String getId() {
        return "nixietube_vk";
    }

    @Override
    public WallpaperEngine createEngine(Context ctx, WallpaperPluginHost host) {
        NixieTubeVkDelegate delegate =
                new NixieTubeVkDelegate(ctx, host.getSharedPreferences(), false);
        return new VkRendererEngine(ctx, host, delegate);
    }

    @Override
    public View createVulkanPreview(Context context, SharedPreferences prefs) {
        if (!VkPassNative.nIsSupported()) return null;
        NixieTubeVkDelegate delegate = new NixieTubeVkDelegate(context, prefs, true);
        // 预览里点一下触发随机数字：触摸转发在 VKSurfaceView 里统一做了。
        return new VKSurfaceView(context, delegate);
    }

    @Override
    public void release() {}
}
