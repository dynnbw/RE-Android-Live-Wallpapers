package com.reandroid.wallpaper.fall;

import android.content.Context;
import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;

public class FallVKPlugin implements WallpaperPlugin {
    @Override public String getId() { return "fall_vk"; }
    @Override public String getDisplayName(Context c) { return "Water Leaves (Vulkan)"; }
    @Override public WallpaperEngine createEngine(Context c, WallpaperPluginHost host) {
        return new FallVKPluginEngine(c, host);
    }
    @Override public void release() {}
}
