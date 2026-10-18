package com.reandroid.wallpaper.aurora2;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;

import com.reandroid.plugin.VkPassNative;
import com.reandroid.plugin.VkRendererEngine;
import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;
import com.reandroid.vulkan.VKSurfaceView;

/** Aurora 2 的 VK 入口：壁纸与设置页预览共用同一个 delegate。 */
public class Aurora2VKPlugin implements WallpaperPlugin {

    @Override
    public String getId() {
        return "aurora2_vk";
    }

    @Override
    public WallpaperEngine createEngine(Context ctx, WallpaperPluginHost host) {
        return new VkRendererEngine(
                ctx, host, new Aurora2VkDelegate(ctx, host.getSharedPreferences(), false));
    }

    @Override
    public View createVulkanPreview(Context context, SharedPreferences prefs) {
        if (!VkPassNative.nIsSupported()) return null;
        return new VKSurfaceView(context, new Aurora2VkDelegate(context, prefs, true));
    }

    @Override
    public void release() {}
}
