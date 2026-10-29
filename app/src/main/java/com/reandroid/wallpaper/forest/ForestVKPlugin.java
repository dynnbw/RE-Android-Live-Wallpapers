package com.reandroid.wallpaper.forest;

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
 * Forest 的 Vulkan 插件入口。
 *
 * 壁纸与预览共用同一个 delegate；预览额外转发触摸，因为戳中草会生成粒子，
 * 与 GLES 版的交互一致。
 */
public class ForestVKPlugin implements WallpaperPlugin {

    @Override
    public String getId() {
        return "forest_vk";
    }

    @Override
    public WallpaperEngine createEngine(Context ctx, WallpaperPluginHost host) {
        // 预览状态与触摸转发都在 VkRendererEngine / VKSurfaceView 里统一做了。
        return new VkRendererEngine(ctx, host, new ForestVkDelegate(ctx));
    }

    @Override
    public View createVulkanPreview(Context context, SharedPreferences prefs) {
        if (!VkPassNative.nIsSupported()) return null;
        return new VKSurfaceView(context, new ForestVkDelegate(context));
    }

    @Override
    public void release() {}
}
