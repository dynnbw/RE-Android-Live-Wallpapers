package com.reandroid.wallpaper.earth;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;

import com.reandroid.plugin.VkPassNative;
import com.reandroid.plugin.VkRendererEngine;
import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;
import com.reandroid.vulkan.VKSurfaceView;

/** Earth 壁纸的 Vulkan 入口；壁纸与设置页预览共用同一个 delegate。 */
public class EarthVKPlugin implements WallpaperPlugin {

    @Override
    public String getId() {
        return "earth_vk";
    }

    @Override
    public WallpaperEngine createEngine(Context context, WallpaperPluginHost host) {
        return new VkRendererEngine(
                context, host, new EarthVkDelegate(context, host.getSharedPreferences()));
    }

    @Override
    public View createVulkanPreview(Context context, SharedPreferences prefs) {
        if (!VkPassNative.nIsSupported()) return null;
        return new VKSurfaceView(context, new EarthVkDelegate(context, prefs));
    }

    @Override
    public void release() {}
}
