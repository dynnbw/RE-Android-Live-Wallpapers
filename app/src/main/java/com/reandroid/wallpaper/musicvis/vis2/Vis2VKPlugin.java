package com.reandroid.wallpaper.musicvis.vis2;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;

import com.reandroid.plugin.VkPassNative;
import com.reandroid.plugin.VkRendererEngine;
import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;
import com.reandroid.vulkan.VKSurfaceView;
import com.reandroid.wallpaper.musicvis.MusicVisWaveVkDelegate;
import com.reandroid.wallpaper.musicvis.WaveScene;

/**
 * vis2（PCM 波形）的 Vulkan 入口。壁纸与预览共用同一个 {@link MusicVisWaveVkDelegate}。
 */
public class Vis2VKPlugin implements WallpaperPlugin {

    private static final String LINE_TEXTURE = "musicvis/drawable/musicvis_fire.png";

    @Override
    public String getId() {
        return "vis2_vk";
    }

    @Override
    public WallpaperEngine createEngine(Context ctx, WallpaperPluginHost host) {
        return new VkRendererEngine(
                ctx,
                host,
                new MusicVisWaveVkDelegate(
                        ctx, host.getSharedPreferences(), WaveScene.Mode.PCM, LINE_TEXTURE));
    }

    @Override
    public View createVulkanPreview(Context context, SharedPreferences prefs) {
        if (!VkPassNative.nIsSupported()) return null;
        return new VKSurfaceView(
                context,
                new MusicVisWaveVkDelegate(context, prefs, WaveScene.Mode.PCM, LINE_TEXTURE));
    }

    @Override
    public void release() {}
}
