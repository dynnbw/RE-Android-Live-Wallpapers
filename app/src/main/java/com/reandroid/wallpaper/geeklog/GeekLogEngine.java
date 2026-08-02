package com.reandroid.wallpaper.geeklog;

import android.content.Context;
import android.os.Bundle;
import android.view.MotionEvent;

import com.reandroid.gles.GLESScene;
import com.reandroid.plugin.BasePluginEngine;
import com.reandroid.plugin.WallpaperPluginHost;

/**
 * GeekLog 引擎：事件源接线。
 * 所有覆写先调用 super（保持 start/stop/转发行为），再记录真实事件日志。
 */
public class GeekLogEngine extends BasePluginEngine {

    public GeekLogEngine(Context context, WallpaperPluginHost host) {
        super(context, host);
    }

    @Override
    protected GLESScene createScene(int width, int height, Context context) {
        GeekLogGL gl = new GeekLogGL(width, height, context);
        gl.logInfo("engine: created");
        return gl;
    }

    @Override
    public void onVisibilityChanged(boolean visible) {
        super.onVisibilityChanged(visible);
        if (mScene instanceof GeekLogGL) {
            ((GeekLogGL) mScene).onVisibility(visible);
        }
    }

    @Override
    public void onOffsetsChanged(float xOffset, float yOffset, float xStep, float yStep,
                                 int xPixels, int yPixels) {
        super.onOffsetsChanged(xOffset, yOffset, xStep, yStep, xPixels, yPixels);
        if (mScene instanceof GeekLogGL) {
            ((GeekLogGL) mScene).logOffsetsChanged();
        }
    }

    @Override
    public void onTouchEvent(MotionEvent event) {
        super.onTouchEvent(event);
        if (mScene instanceof GeekLogGL) {
            GeekLogGL gl = (GeekLogGL) mScene;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                gl.logTap((int) event.getX(), (int) event.getY());
            } else if (action == MotionEvent.ACTION_MOVE) {
                gl.logDrag((int) event.getX(), (int) event.getY());
            }
        }
    }

    @Override
    public void onCommand(String action, int x, int y, int z, Bundle extras) {
        super.onCommand(action, x, y, z, extras);
        if ("android.wallpaper.tap".equals(action) && mScene instanceof GeekLogGL) {
            ((GeekLogGL) mScene).logTap(x, y);
        }
    }

    @Override
    public void onDestroy() {
        if (mScene instanceof GeekLogGL) {
            ((GeekLogGL) mScene).logInfo("engine: destroyed");
        }
        super.onDestroy();
    }
}
