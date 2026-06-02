package com.reandroid.wallpaper.galaxy;

import android.content.Context;
import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;

public class GalaxyVKPlugin implements WallpaperPlugin {
    @Override public String getId() { return "galaxy_vk"; }
    @Override public String getDisplayName(Context c) { return "Galaxy (Vulkan)"; }
    @Override public WallpaperEngine createEngine(Context c, WallpaperPluginHost host) {
        return new GalaxyVKPluginEngine(c, host);
    }
    @Override public void release() {}
}
