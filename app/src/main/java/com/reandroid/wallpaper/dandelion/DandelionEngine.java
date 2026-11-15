package com.reandroid.wallpaper.dandelion;

import android.content.Context;
import android.os.BatteryManager;

import com.reandroid.gles.GLESScene;
import com.reandroid.plugin.BasePluginEngine;
import com.reandroid.plugin.WallpaperPluginHost;

/**
 * 花头的枯荣跟着电池电量走（参考实现的行为），所以每 5 秒读一次电量 ——
 * 电量变化本来就很慢，没必要挂广播接收器。
 */
public class DandelionEngine extends BasePluginEngine {

    private static final long BATTERY_INTERVAL_MS = 5000L;

    private long mLastBatteryCheck;

    public DandelionEngine(Context context, WallpaperPluginHost host) {
        super(context, host);
    }

    @Override
    protected GLESScene createScene(int width, int height, Context context) {
        return new DandelionGL(width, height, context);
    }

    @Override
    public void drawFrame(long timeMs) {
        if (mScene instanceof DandelionGL && timeMs - mLastBatteryCheck > BATTERY_INTERVAL_MS) {
            mLastBatteryCheck = timeMs;
            BatteryManager bm = (BatteryManager) mContext.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                int level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                ((DandelionGL) mScene).setBatteryLevel(level);
            }
        }
        super.drawFrame(timeMs);
    }
}
