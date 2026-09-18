package com.reandroid.plugin;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Services provided by the host (ProxyWallpaperService) to plugins.
 */
public interface WallpaperPluginHost {

    /** Plugin-isolated SharedPreferences. Storage is per-pluginId. */
    SharedPreferences getSharedPreferences();

    /**
     * Plugin-isolated SharedPreferences for an arbitrary plugin id.
     * Only the compositing wallpapers need this (see {@link PluginPrefsProvider}).
     */
    SharedPreferences getSharedPreferences(String pluginId);

    /** Host application context. */
    Context getContext();
}
