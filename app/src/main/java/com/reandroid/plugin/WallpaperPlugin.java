package com.reandroid.plugin;

import android.content.Context;

/**
 * Entry point for a wallpaper plugin.
 * Each wallpaper provides one implementation of this interface.
 */
public interface WallpaperPlugin {

    /** Unique identifier (matches assets/{id}/ directory name). */
    String getId();


    /** Create the rendering engine for this wallpaper. */
    WallpaperEngine createEngine(Context context, WallpaperPluginHost host);

    /** Release plugin resources on unload. */
    void release();
}
