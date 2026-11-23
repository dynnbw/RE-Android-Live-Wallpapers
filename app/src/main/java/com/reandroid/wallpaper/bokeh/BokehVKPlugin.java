package com.reandroid.wallpaper.bokeh;

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
 * 散景彩虹的 Vulkan 入口。壁纸与设置页预览共用同一个 delegate。
 */
public class BokehVKPlugin implements WallpaperPlugin {

    @Override
    public String getId() {
        return "bokeh_vk";
    }

    @Override
    public WallpaperEngine createEngine(Context context, WallpaperPluginHost host) {
        return new VkRendererEngine(context, host, new BokehVkDelegate(context));
    }

    @Override
    public View createVulkanPreview(Context context, SharedPreferences prefs) {
        if (!VkPassNative.nIsSupported()) return null;
        return new VKSurfaceView(context, new BokehVkDelegate(context));
    }

    @Override
    public void release() {}
}
