package com.reandroid.wallpaper.nexus;

import android.content.Context;

import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;

public class NexusPlugin implements WallpaperPlugin {
    @Override
    public String getId() {
        return "nexus";
    }

    @Override
    public String getDisplayName(Context context) {
        return "Nexus";
    }

    @Override
    public WallpaperEngine createEngine(Context context, WallpaperPluginHost host) {
        return new NexusEngine(context, host);
    }

    @Override
    public void release() {
    }
}
