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

        /**
         * 平台报过不可见、但之后仍有回调（偏移 / surface / 触摸 / 指令）在驱动我们。
         *
         * <p>MIUI 的可见性事件是成串来的：实测打开一次应用，0.56 秒内回调 26 次，
         * 甚至出现同一毫秒内 true/false 成对。只要最后一个事件是 false 而壁纸其实
         * 正在显示，渲染循环就会永久停在 {@code mLock.wait()} 上 —— 表现就是
         * "壁纸卡住，进一次应用再退出来才动"。而其它回调说明系统正在用我们，那就该画。
         */
        private volatile boolean mDriven;

        /** 仅用于状态变化时打一行日志，别每帧刷。 */
        private boolean mRendering;

        /** 停泊的上限：就算没有任何事件，也会醒来重新判断一次，不会无限期卡住。 */
        private static final long PARK_TIMEOUT_MS = 2000L;

        /** destroyEngine 等渲染线程退出的上限（跑在主线程上，所以取短）。 */
        private static final long JOIN_TIMEOUT_MS = 500L;

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
                    // 画布当前的 frame 才是权威，拿不到再退回上次记录的尺寸
                    int initW = mLastWidth, initH = mLastHeight;
                    boolean fromFrame = false;
                    android.graphics.Rect frame = getSurfaceHolder().getSurfaceFrame();
                    if (frame.width() > 0 && frame.height() > 0) {
                        initW = frame.width();
                        initH = frame.height();
                        fromFrame = true;
                    }
                    Log.d(TAG, "createEngine onSurfaceChanged: " + initW + "x" + initH
                            + " (fromFrame=" + fromFrame + ")");
                    if (initW > 0 && initH > 0) {
                        mEngine.onSurfaceChanged(getSurfaceHolder(), mLastFormat, initW, initH);
                    } else {
                        // 引擎自己有兜底（BasePluginEngine 会用保存的 holder 补发），别静默跳过
                        Log.w(TAG, "createEngine: 拿不到有效的画布尺寸，交给引擎自愈");
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
                /*
                 * 上限取得短：这个 join 跑在**主线程**上（可见性/surface 回调都是），
                 * 而实测主线程在这条路径上被按住过 500-668ms。线程没退干净不算致命 ——
                 * 下面的注释说明了它会自行清理；把主线程卡住才是更糟的那个。
                 */
                long deadline = System.currentTimeMillis() + JOIN_TIMEOUT_MS;
                while (mRenderThread.isAlive() && System.currentTimeMillis() < deadline) {
                    try {
                        mRenderThread.join(Math.max(1L, deadline - System.currentTimeMillis()));
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (mRenderThread.isAlive()) {
                    Log.w(TAG, "渲染线程 " + JOIN_TIMEOUT_MS + "ms 内未退出，交给它自行清理");
                }
                mRenderThread = null;
            }
            /*
             * 不在这里清零 mLastWidth/mLastHeight：切换壁纸时 surface 根本没换，
             * 系统不会再发一次 onSurfaceChanged，而 createEngine() 靠这两个值决定
             * 要不要通知新引擎尺寸。清掉之后若 getSurfaceFrame() 也拿不到有效尺寸
             * （切换瞬间很常见），新引擎就永远收不到尺寸通知，EGL 建不起来，画面会
             * 一直停在上一个壁纸的最后一帧。保留旧值最多让新引擎先用错一次尺寸，
             * 随后系统回调会纠正；拿不到尺寸则完全画不出来，代价不对称。
             */
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
            if (!visible) {
                // 一次"权威的隐藏"清掉驱动标记，下次重新累积。
                mDriven = false;
            }
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
            // 系统在给我们配 surface，等于说这块画面在用（见 mDriven）。
            mDriven = true;
            synchronized (mLock) {
                mLock.notifyAll();
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
            // 桌面在滚动就是在用它 —— 顺带把可能停泊住的渲染循环叫醒（见 mDriven）。
            mDriven = true;
            synchronized (mLock) {
                mLock.notifyAll();
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
            // 摸得到就说明它在屏幕上（见 mDriven）。
            mDriven = true;
            synchronized (mLock) {
                mLock.notifyAll();
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
                        if (mVisible || mDriven) {
                            if (!mRendering) {
                                mRendering = true;
                                Log.d(TAG, "渲染恢复: visible=" + mVisible + " driven=" + mDriven);
                            }
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
                            if (mRendering) {
                                mRendering = false;
                                Log.d(TAG, "渲染停泊: 平台报不可见且无其它回调");
                            }
                            synchronized (mLock) {
                                try {
                                    mLock.wait(PARK_TIMEOUT_MS);
                                } catch (InterruptedException ignored) {
                                }
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
            return getSharedPreferences(mPluginId);
        }

        @Override
        public SharedPreferences getSharedPreferences(String pluginId) {
            return mContext.getSharedPreferences("plugin_" + pluginId, Context.MODE_PRIVATE);
        }

        @Override
        public Context getContext() {
            return mContext;
        }

    }
}
