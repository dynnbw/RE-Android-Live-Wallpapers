package com.reandroid.wallpaper.luminousdots;

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
 * LuminousDots 的 Vulkan 入口。壁纸与设置页预览共用同一个 delegate。
 */
public class LuminousDotsVKPlugin implements WallpaperPlugin {

    @Override
    public String getId() {
        return "luminousdots_vk";
    }

    @Override
    public WallpaperEngine createEngine(Context ctx, WallpaperPluginHost host) {
        return new VkRendererEngine(
                ctx, host, new LuminousDotsVkDelegate(ctx, host.getSharedPreferences(), false));
    }

    @Override
    public View createVulkanPreview(Context context, SharedPreferences prefs) {
        if (!VkPassNative.nIsSupported()) return null;
        return new VKSurfaceView(context, new LuminousDotsVkDelegate(context, prefs, true));
    }

    @Override
    public void release() {}
}
