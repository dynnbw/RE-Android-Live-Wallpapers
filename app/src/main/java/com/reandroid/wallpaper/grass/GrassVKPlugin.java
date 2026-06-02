package com.reandroid.wallpaper.grass;

import android.content.Context;
import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;

public class GrassVKPlugin implements WallpaperPlugin {
    @Override public String getId() { return "grass_vk"; }
    @Override public String getDisplayName(Context c) { return "Grass (Vulkan)"; }
    @Override public WallpaperEngine createEngine(Context c, WallpaperPluginHost host) {
        return new GrassVKPluginEngine(c, host);
    }
    @Override public void release() {}
}
