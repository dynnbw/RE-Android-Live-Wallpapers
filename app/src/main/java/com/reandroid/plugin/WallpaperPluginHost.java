package com.reandroid.plugin;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Services provided by the host (ProxyWallpaperService) to plugins.
 */
public interface WallpaperPluginHost {

    /** Plugin-isolated SharedPreferences. Storage is per-pluginId. */
    SharedPreferences getSharedPreferences();

    /** Host application context. */
    Context getContext();

    /** Request the host to schedule a render frame. */
    void requestRender();
}
