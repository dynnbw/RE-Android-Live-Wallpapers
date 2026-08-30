package com.reandroid.wallpaper.cosmicflow;

import android.content.Context;

import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;

/**
 * Cosmic Flow 壁纸(Sony Xperia 经典动态壁纸)插件。
 * 纯 Java GLES 2.0 移植:GPU 噪声流场 + 加性混合 + 8 色主题。
 */
public class CosmicPlugin implements WallpaperPlugin {

    @Override
    public String getId() {
        return "cosmicflow";
    }

    @Override
    public String getDisplayName(Context context) {
        return "Cosmic Flow";
    }

    @Override
    public WallpaperEngine createEngine(Context context, WallpaperPluginHost host) {
        return new CosmicEngine(context, host);
    }

    @Override
    public void release() {
        // No global state to release
    }
}
