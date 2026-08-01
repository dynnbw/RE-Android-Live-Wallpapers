package com.reandroid.plugin;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Process;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;

import com.reandroid.settings.WallpaperSettings;
import com.reandroid.utils.IoUtils;
import com.reandroid.vulkan.FrameRateManager;
import org.json.JSONObject;

import java.io.InputStream;

/**
 * Single-entry WallpaperService that dispatches to the currently selected plugin.
 * Replaces the ~30 individual service declarations in the manifest.
 */
public class ProxyWallpaperService extends WallpaperService {

    private static final String TAG = "ProxyWallpaper";
    private static final String PREFS_NAME = "proxy_wallpaper";
    private static final String KEY_PLUGIN_ID = "current_plugin_id";

    /** Set the active wallpaper plugin. Call this from the app UI before applying. */
    public static void setActivePlugin(Context context, String pluginId) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PLUGIN_ID, pluginId)
                .apply();
    }

    public static String getActivePlugin(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_PLUGIN_ID, null);
    }

    @Override
    public Engine onCreateEngine() {
        return new ProxyEngine();
    }

    private class ProxyEngine extends Engine {

        private WallpaperPlugin mPlugin;
        private volatile WallpaperEngine mEngine;
        private PluginHostImpl mHost;
        private Thread mRenderThread;
        private volatile boolean mRunning;
        private volatile boolean mVisible;
        private String mCurrentPluginId;
        private int mLastFormat;
        private int mLastWidth;
        private int mLastHeight;
        private final Object mLock = new Object();
        private final FrameRateManager mFrameRate = new FrameRateManager(TAG);

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            setTouchEventsEnabled(true);
            createEngine(getActivePlugin(ProxyWallpaperService.this));
        }

        private void createEngine(String pluginId) {
            if (pluginId == null) {
                Log.e(TAG, "No plugin configured");
                return;
            }
            Log.d(TAG, "createEngine pluginId=" + pluginId);
            mCurrentPluginId = pluginId;

            try {
                mPlugin = loadPlugin(pluginId);
                if (mPlugin == null) {
                    Log.e(TAG, "Failed to load plugin: " + pluginId);
                    return;
                }
                mHost = new PluginHostImpl(ProxyWallpaperService.this, pluginId);
                mEngine = mPlugin.createEngine(ProxyWallpaperService.this, mHost);
                if (mEngine != null) {
                    mEngine.onCreate(getSurfaceHolder());
                    mEngine.setPreview(isPreview());
                    int initW = mLastWidth, initH = mLastHeight;
                    boolean fromFrame = false;
                    if (initW <= 0 || initH <= 0) {
                        android.graphics.Rect frame = getSurfaceHolder().getSurfaceFrame();
                        initW = frame.width();
                        initH = frame.height();
                        fromFrame = true;
                    }
                    Log.d(TAG, "createEngine onSurfaceChanged: " + initW + "x" + initH
                            + " (fromCache=" + !fromFrame + ")");
                    if (initW > 0 && initH > 0) {
                        mEngine.onSurfaceChanged(getSurfaceHolder(), mLastFormat, initW, initH);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Plugin init failed", e);
            }
        }

        private void destroyEngine() {
            Log.d(TAG, "destroyEngine: lastSize=" + mLastWidth + "x" + mLastHeight);
            mRunning = false;
            if (mRenderThread != null) {
                synchronized (mLock) { mLock.notifyAll(); }
                mRenderThread.interrupt();
                long deadline = System.currentTimeMillis() + 2000L;
                while (mRenderThread.isAlive() && System.currentTimeMillis() < deadline) {
                    try {
                        mRenderThread.join(Math.max(1L, deadline - System.currentTimeMillis()));
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (mRenderThread.isAlive()) {
                    Log.w(TAG, "Render thread did not exit within 2s");
                }
                mRenderThread = null;
            }
            mLastWidth = mLastHeight = 0;
            if (mEngine != null) {
                mEngine.onDestroy();
                mEngine.release();
                mEngine = null;
            }
            if (mPlugin != null) {
                mPlugin.release();
                mPlugin = null;
            }
            WallpaperSettings.clearSharedPreferences();
        }

        @Override
        public void onDestroy() {
            destroyEngine();
            super.onDestroy();
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            Log.d(TAG, "onVisibilityChanged visible=" + visible + " engine=" + (mEngine != null));
            mVisible = visible;
            if (visible) {
                synchronized (mLock) { mLock.notifyAll(); }
                // Detect plugin ID change (user switched to a different wallpaper
                // via settings while this engine was running): recreate the engine.
                String activeId = getActivePlugin(ProxyWallpaperService.this);
                if (activeId != null && !activeId.equals(mCurrentPluginId)) {
                    Log.i(TAG, "Plugin changed: " + mCurrentPluginId + " -> " + activeId);
                    destroyEngine();
                    createEngine(activeId);
                }
            }
            if (mEngine != null) mEngine.onVisibilityChanged(visible);
            if (visible) ensureRenderThread();
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            // Recreate the engine after surface destruction — without this override,
            // a surface-recreate (rotation, multi-window) leaves the wallpaper black
            // because onSurfaceDestroyed nulled mEngine with no rebuild path.
            if (mEngine == null) {
                String activeId = getActivePlugin(ProxyWallpaperService.this);
                if (activeId != null) {
                    createEngine(activeId);
                }
            }
        }

        // ---- Engine callbacks forwarded under mLock (mirrors GLESWallpaper.mSceneLock) ----
        // mLock serializes the render thread (drawFrame) with these mutating callbacks,
        // preventing concurrent EGL-context/scene access when BasePluginEngine rebuilds
        // EGL in onSurfaceChanged on the callback thread while drawFrame runs on the
        // render thread.  Plugin exceptions are caught to avoid crashing the host.

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            Log.d(TAG, "ProxyEngine.onSurfaceChanged: " + width + "x" + height
                    + " engine=" + (mEngine != null));
            mLastFormat = format; mLastWidth = width; mLastHeight = height;
            synchronized (mLock) {
                if (mEngine != null) {
                    try {
                        mEngine.setPreview(isPreview());
                        mEngine.onSurfaceChanged(holder, format, width, height);
                    } catch (Exception e) {
                        Log.e(TAG, "Plugin onSurfaceChanged crashed", e);
                    }
                }
            }
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            destroyEngine();
            super.onSurfaceDestroyed(holder);
        }

        @Override
        public void onOffsetsChanged(float xOffset, float yOffset,
                                     float xOffsetStep, float yOffsetStep,
                                     int xPixelOffset, int yPixelOffset) {
            synchronized (mLock) {
                if (mEngine != null) {
                    try {
                        mEngine.onOffsetsChanged(xOffset, yOffset, xOffsetStep, yOffsetStep,
                                xPixelOffset, yPixelOffset);
                    } catch (Exception e) {
                        Log.e(TAG, "Plugin onOffsetsChanged crashed", e);
                    }
                }
            }
        }

        @Override
        public void onTouchEvent(MotionEvent event) {
            synchronized (mLock) {
                if (mEngine != null) {
                    try {
                        mEngine.onTouchEvent(event);
                    } catch (Exception e) {
                        Log.e(TAG, "Plugin onTouchEvent crashed", e);
                    }
                }
            }
        }

        @Override
        public Bundle onCommand(String action, int x, int y, int z,
                                Bundle extras, boolean resultRequested) {
            synchronized (mLock) {
                if (mEngine != null) {
                    try {
                        mEngine.onCommand(action, x, y, z, extras);
                    } catch (Exception e) {
                        Log.e(TAG, "Plugin onCommand crashed", e);
                    }
                }
            }
            return null;
        }

        private void ensureRenderThread() {
            if (mRenderThread != null) return;
            mRunning = true;
            mRenderThread = new Thread("ProxyEngineRenderer") {
                @Override
                public void run() {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);
                    Log.d(TAG, "Render thread started, engine=" + (mEngine != null));
                    int frameCount = 0;
                    while (mRunning) {
                        if (mVisible) {
                            long frameStart = System.currentTimeMillis();
                            mFrameRate.syncPerfSettingsIfNeeded(frameStart);
                            synchronized (mLock) {
                                if (mEngine != null) {
                                    try {
                                        mEngine.drawFrame(frameStart);
                                        if (++frameCount == 60) {
                                            Log.d(TAG, "Rendered 60 frames OK");
                                            frameCount = 0;
                                        }
                                    } catch (Exception e) {
                                        // 单帧异常不应杀死渲染线程，否则壁纸会永久冻结；
                                        // 继续渲染，帧率节流仍在循环内生效。
                                        Log.e(TAG, "drawFrame crashed", e);
                                    }
                                }
                            }
                            long frameCost = System.currentTimeMillis() - frameStart;
                            mFrameRate.recordFrameCost(frameCost);
                            long sleepMs = Math.max(1L, mFrameRate.getTargetFrameMs() - frameCost);
                            try { Thread.sleep(sleepMs); } catch (InterruptedException ignored) {}
                        } else {
                            synchronized (mLock) {
                                try { mLock.wait(); } catch (InterruptedException ignored) {}
                            }
                        }
                    }
                }
            };
            mRenderThread.start();
        }

        private WallpaperPlugin loadPlugin(String pluginId) throws Exception {
            // Read info.json for plugin class name
            String className = null;
            String pluginVk = null;
            try (InputStream is = getAssets().open(pluginId + "/info.json")) {
                JSONObject json = new JSONObject(new String(IoUtils.readAllBytes(is), "UTF-8"));
                className = json.optString("plugin", null);
                pluginVk = json.optString("pluginVk", null);
            } catch (Exception e) {
                Log.w(TAG, "No info.json for " + pluginId + ", trying convention");
            }

            // Check for Vulkan renderer preference
            if (pluginVk != null) {
                SharedPreferences prefs = getSharedPreferences("plugin_" + pluginId, Context.MODE_PRIVATE);
                if (prefs.getBoolean("use_vulkan", false)) {
                    className = pluginVk;
                }
            }

            if (className == null) {
                // Fallback naming convention
                String capId = pluginId.substring(0, 1).toUpperCase() + pluginId.substring(1);
                className = "com.reandroid.wallpaper." + pluginId + "." + capId + "Plugin";
            }

            Class<?> clazz = Class.forName(className);
            return (WallpaperPlugin) clazz.getDeclaredConstructor().newInstance();
        }
    }

    private static class PluginHostImpl implements WallpaperPluginHost {
        private final Context mContext;
        private final String mPluginId;

        PluginHostImpl(Context context, String pluginId) {
            mContext = context.getApplicationContext();
            mPluginId = pluginId;
        }

        @Override
        public SharedPreferences getSharedPreferences() {
            return mContext.getSharedPreferences("plugin_" + mPluginId, Context.MODE_PRIVATE);
        }

        @Override
        public Context getContext() {
            return mContext;
        }

    }
}
