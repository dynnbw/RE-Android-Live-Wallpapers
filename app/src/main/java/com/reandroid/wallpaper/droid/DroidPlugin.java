package com.reandroid.wallpaper.droid;

import android.content.Context;

import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;

/**
 * 坠落的安卓机器人(Shake Them All)插件。
 * 纯 Java 移植:自实现 2D 刚体物理(顺序冲量求解),无 native 依赖。
 */
public class DroidPlugin implements WallpaperPlugin {

    @Override
    public String getId() {
        return "droid";
    }

    @Override
    public String getDisplayName(Context context) {
        return "Shake Them All";
    }

    @Override
    public WallpaperEngine createEngine(Context context, WallpaperPluginHost host) {
        return new DroidEngine(context, host);
    }

    @Override
    public void release() {
        // 无全局状态
    }
}
