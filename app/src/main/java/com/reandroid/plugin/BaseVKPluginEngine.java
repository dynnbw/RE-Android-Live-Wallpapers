package com.reandroid.plugin;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.reandroid.settings.WallpaperSettings;
import com.reandroid.vulkan.FrameRateManager;

public abstract class BaseVKPluginEngine implements WallpaperEngine, Runnable {
    protected final Context mContext;
    protected final WallpaperPluginHost mHost;
    protected volatile long mRendererHandle;
    protected int mWidth = 256, mHeight = 256;
    protected volatile boolean mRunning;
    protected boolean mVisible;
    protected boolean mPreview;
    protected boolean mSurfaceCreated;
    protected Surface mCurrentSurface;
    protected SurfaceHolder mHolder;
    protected volatile Thread mThread;
    private FrameRateManager mFrameRate;

    private final SharedPreferences.OnSharedPreferenceChangeListener mPrefsListener =
            (prefs, key) -> onPluginPrefsChanged();

    public BaseVKPluginEngine(Context context, WallpaperPluginHost host) {
        mContext = context;
        mHost = host;
    }

    /** Lazy-accessed to avoid calling the overridable abstract {@link #getLogTag()}
     *  from the field initializer (constructor trap). */
    private FrameRateManager frameRate() {
        if (mFrameRate == null) mFrameRate = new FrameRateManager(getLogTag());
        return mFrameRate;
    }

    // --- Abstract methods each VK wallpaper implements ---
    protected abstract String getLogTag();
    protected abstract void ensureScene();
    protected abstract void ensureOrResizeScene();
    protected abstract long createRenderer();
    protected abstract void destroyRenderer();
    protected abstract void onSurfaceCreatedNative(Surface surface, int w, int h);
    protected abstract void onSurfaceChangedNative(Surface surface, int w, int h);
    protected abstract void onSurfaceDestroyedNative();
    protected abstract void renderFrame();
    protected abstract void syncTexturesIfNeeded();
    protected abstract void onSceneOffset(float xOffset);
    protected abstract void onSceneTouch(float x, float y);

    /** 插件设置变更时调用（主线程）。子类重写以把新 prefs 推给场景/渲染器。 */
    protected void onPluginPrefsChanged() {}

    // --- WallpaperEngine lifecycle ---
    @Override public void onCreate(SurfaceHolder holder) {
        mHolder = holder;
        WallpaperSettings.setSharedPreferences(mHost.getSharedPreferences());
        try {
            mHost.getSharedPreferences().registerOnSharedPreferenceChangeListener(mPrefsListener);
        } catch (Exception e) {
            Log.w(getLogTag(), "Failed to register prefs change listener", e);
        }
    }
    @Override public void onDestroy() {
        try {
            mHost.getSharedPreferences().unregisterOnSharedPreferenceChangeListener(mPrefsListener);
        } catch (Exception e) {
            Log.w(getLogTag(), "Failed to unregister prefs change listener", e);
        }
        stopRenderer(); releaseNative(); mSurfaceCreated = false;
        WallpaperSettings.clearSharedPreferences();
    }
    @Override public void onVisibilityChanged(boolean visible) {
        mVisible = visible; if (visible) startRenderer(); else stopRenderer();
    }
    @Override public void setPreview(boolean isPreview) { mPreview = isPreview; }
    protected boolean isPreview() { return mPreview; }
    @Override public void onOffsetsChanged(float xo, float yo, float xs, float ys, int xp, int yp) {
        onSceneOffset(xo);
    }
    @Override public void onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) onSceneTouch(e.getX(), e.getY());
    }
    @Override public void onCommand(String action, int x, int y, int z, Bundle extras) {}
    @Override public void drawFrame(long timeMs) {} // no-op: VK renders on its own thread
    @Override public void release() { stopRenderer(); releaseNative(); }

    @Override
    public void onSurfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        Log.d(getLogTag(), "onSurfaceChanged: " + w + "x" + h
                + " surfCreated=" + mSurfaceCreated + " oldSize=" + mWidth + "x" + mHeight
                + " wasRunning=" + (mThread != null));

        Surface surface = holder.getSurface();
        if (surface == null || !surface.isValid()) return;

        // Skip if nothing actually changed (now includes Surface identity —
        // a new Surface at the same dimensions still requires a full recreate).
        if (mSurfaceCreated && mCurrentSurface == surface && mWidth == w && mHeight == h) {
            Log.d(getLogTag(), "onSurfaceChanged: skipped (nothing changed)");
            return;
        }

        // Full destroy + recreate to avoid native swapchain-recreation bugs
        boolean wasRunning = (mThread != null);
        if (wasRunning) stopRenderer();
        if (mSurfaceCreated) {
            releaseNative();
            mSurfaceCreated = false;
        }

        mHolder = holder; mWidth = w; mHeight = h;
        ensureOrResizeScene(); ensureRenderer();
        mSurfaceCreated = true;
        mCurrentSurface = surface;
        onSurfaceCreatedNative(surface, w, h);
        // startRenderer 内部会检查 mVisible / mThread，可安全无条件调用；
        // 依赖 wasRunning 判断会导致线程异常退出后（mThread 已清理）无法重启渲染。
        startRenderer();
    }

    // --- Render thread ---
    protected void startRenderer() {
        if (mThread != null || !mVisible) return;
        Surface s = mHolder != null ? mHolder.getSurface() : null;
        if (s == null || !s.isValid()) return;
        mRunning = true;
        mThread = new Thread(this, getLogTag() + "Thread");
        mThread.start();
    }
    protected void stopRenderer() {
        mRunning = false;
        // 捕获局部引用：渲染线程退出时会把 mThread 置 null，
        // 若直接读字段，join 期间线程退出会导致 mThread.isAlive() NPE。
        Thread thread = mThread;
        if (thread != null) {
            long deadline = System.currentTimeMillis() + 2000L;
            while (thread.isAlive() && System.currentTimeMillis() < deadline) {
                try {
                    thread.join(Math.max(1L, deadline - System.currentTimeMillis()));
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            if (thread.isAlive()) Log.e(getLogTag(), "Render thread did not exit within 2s");
            // 不在这里置 null：由渲染线程退出时自行清理，避免旧线程未结束时又启动新线程导致并发渲染
        }
    }
    @Override public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);
        try {
            while (mRunning) {
                long now = SystemClock.uptimeMillis();
                frameRate().syncPerfSettingsIfNeeded(now);
                try {
                    syncTexturesIfNeeded();
                    renderFrame();
                } catch (Exception e) {
                    // 单帧异常不能杀死渲染线程，否则壁纸会永久冻结
                    Log.e(getLogTag(), "renderFrame failed", e);
                }
                long cost = SystemClock.uptimeMillis() - now;
                frameRate().recordFrameCost(cost);
                long sleep = Math.max(1, frameRate().getTargetFrameMs() - cost);
                try { Thread.sleep(sleep); } catch (InterruptedException ignored) {}
            }
        } finally {
            // 线程退出时释放槽位，使 startRenderer 可以重新启动（异常/中断退出也能恢复）
            if (mThread == Thread.currentThread()) {
                mThread = null;
                mRunning = false;
            }
        }
    }

    // --- Native lifecycle ---
    protected void ensureRenderer() {
        if (mRendererHandle == 0L) {
            ensureScene();
            mRendererHandle = createRenderer();
        }
    }
    protected void releaseNative() {
        if (mRendererHandle != 0L) {
            onSurfaceDestroyedNative();
            destroyRenderer();
            mRendererHandle = 0L;
        }
    }
}
