package com.reandroid.wallpaper.deepsea;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;

import com.reandroid.plugin.VkPassNative;
import com.reandroid.plugin.VkRendererEngine;
import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;
import com.reandroid.vulkan.VKSurfaceView;

/** DeepSea 的 Vulkan 插件入口，壁纸与预览共用同一个 delegate。 */
public class DeepSeaVKPlugin implements WallpaperPlugin {

    @Override
    public String getId() {
        return "deepsea_vk";
    }

    @Override
    public WallpaperEngine createEngine(Context ctx, WallpaperPluginHost host) {
        return new VkRendererEngine(
                ctx, host, new DeepSeaVkDelegate(ctx, host.getSharedPreferences()));
    }

    @Override
    public View createVulkanPreview(Context context, SharedPreferences prefs) {
        if (!VkPassNative.nIsSupported()) return null;
        return new VKSurfaceView(context, new DeepSeaVkDelegate(context, prefs));
    }

    @Override
    public void release() {}
}
