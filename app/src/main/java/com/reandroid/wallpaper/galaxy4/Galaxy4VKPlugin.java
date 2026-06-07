package com.reandroid.wallpaper.galaxy4;

import android.content.Context;

import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;

public class Galaxy4VKPlugin implements WallpaperPlugin {
    static { android.util.Log.e("Galaxy4VKPlugin", "*** PLUGIN STATIC INIT ***"); }
    @Override public String getId() { return "galaxy4_vk"; }
    @Override public String getDisplayName(Context ctx) { return "Galaxy4 Vulkan"; }
    @Override public WallpaperEngine createEngine(Context ctx, WallpaperPluginHost host) {
        return new Galaxy4VKPluginEngine(ctx, host);
    }
    @Override public void release() {}
}
