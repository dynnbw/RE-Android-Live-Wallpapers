package com.reandroid.wallpaper.luminousdots;

import android.content.Context;
import android.os.BatteryManager;

import com.reandroid.gles.GLESScene;
import com.reandroid.plugin.BasePluginEngine;
import com.reandroid.plugin.WallpaperPluginHost;

public class LuminousDotsEngine extends BasePluginEngine {

    private long mLastBatteryCheck;
    private static final long BATTERY_INTERVAL_MS = 5000;

    public LuminousDotsEngine(Context context, WallpaperPluginHost host) {
        super(context, host);
    }

    @Override
    protected GLESScene createScene(int width, int height, Context context) {
        return new LuminousDotsGL(width, height, context);
    }

    @Override
    public void drawFrame(long timeMs) {
        if (timeMs - mLastBatteryCheck > BATTERY_INTERVAL_MS) {
            if (mScene instanceof LuminousDotsGL) {
                BatteryManager bm = (BatteryManager) mContext.getSystemService(Context.BATTERY_SERVICE);
                if (bm != null) {
                    int level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                    ((LuminousDotsGL) mScene).setBatteryLevel(level);
                }
            }
            mLastBatteryCheck = timeMs;
        }
        super.drawFrame(timeMs);
    }
}
