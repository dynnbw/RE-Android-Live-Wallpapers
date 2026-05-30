package com.reandroid.wallpaper.fall;

import android.content.Context;
import android.content.SharedPreferences;

import com.reandroid.gles.GLESScene;
import com.reandroid.plugin.BasePluginEngine;
import com.reandroid.plugin.WallpaperPluginHost;

public class FallEngine extends BasePluginEngine {

    private final WallpaperPluginHost mHost;

    public FallEngine(Context context, WallpaperPluginHost host) {
        super(context, host);
        mHost = host;
    }

    @Override
    protected GLESScene createScene(int width, int height, Context context) {
        return new FallGL(context, width, height);
    }

    @Override
    protected void tryInjectPrefs(GLESScene scene) {
        if (mHost != null && scene instanceof FallGL) {
            ((FallGL) scene).setPluginPrefs(mHost.getSharedPreferences());
        }
    }
}
