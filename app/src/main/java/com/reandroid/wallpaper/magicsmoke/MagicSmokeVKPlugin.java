package com.reandroid.wallpaper.magicsmoke;

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
 * Magic Smoke 的 Vulkan 插件入口。
 *
 * <p>壁纸与设置页预览共用同一个 {@link MagicSmokeVkDelegate}，native 调用只有一份。
 */
public class MagicSmokeVKPlugin implements WallpaperPlugin {

    @Override
    public String getId() {
        return "magicsmoke_vk";
    }

    @Override
    public WallpaperEngine createEngine(Context ctx, WallpaperPluginHost host) {
        return new VkRendererEngine(
                ctx, host, new MagicSmokeVkDelegate(ctx, host.getSharedPreferences()));
    }

    @Override
    public View createVulkanPreview(Context context, SharedPreferences prefs) {
        if (!VkPassNative.nIsSupported()) return null;
        return new VKSurfaceView(context, new MagicSmokeVkDelegate(context, prefs));
    }

    @Override
    public void release() {}
}
