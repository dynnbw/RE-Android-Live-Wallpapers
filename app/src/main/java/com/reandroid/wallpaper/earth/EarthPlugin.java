package com.reandroid.wallpaper.earth;

import android.content.Context;

import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;

/**
 * Sony Ericsson Earth 壁纸（Xperia 时代预装，"Cosmic Flow" 的姊妹作）。
 */
public class EarthPlugin implements WallpaperPlugin {

    @Override
    public String getId() {
        return "earth";
    }

    @Override
    public String getDisplayName(Context context) {
        return "Earth";
    }

    @Override
    public WallpaperEngine createEngine(Context context, WallpaperPluginHost host) {
        return new EarthEngine(context, host);
    }

    @Override
    public void release() {
        // 无全局状态
    }
}
