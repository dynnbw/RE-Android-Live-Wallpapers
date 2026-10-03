package com.reandroid.vulkan;

import android.content.Context;
import android.os.Process;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.reandroid.plugin.VkRendererDelegate;
import com.reandroid.plugin.WallpaperPreview;

/**
 * Vulkan 预览 SurfaceView 共享基类，封装线程管理、Surface 生命周期和帧率诊断。
 * 子类通过模板方法注入壁纸特定的 Scene 创建、纹理上传和渲染调用。
 *
 * @param <T> Scene 类型
 */
public class VKSurfaceView extends SurfaceView
        implements SurfaceHolder.Callback, Runnable, WallpaperPreview {

    /** 壁纸特有的那部分；本类只管线程、surface 生命周期与配速。 */
    private final VkRendererDelegate mDelegate;

    protected volatile Thread mThread;
    protected volatile boolean mRunning;
    /** 渲染器是否已建 —— createRenderer() 归 delegate，这里只记它成没成功。 */
    protected boolean mRendererCreated;

    protected final Object mSceneLock = new Object();
    protected boolean mNativeSurfaceAlive;
    protected int mWidth, mHeight;

    /*
     * 惰性建：不能写在字段初始化里 —— getLogTag() 已经转给 mDelegate，而字段初始化发生在
     * 构造器体之前，那时 mDelegate 还是 null。
     */
    private FrameRateManager mFrameRate;

    private FrameRateManager frameRate() {
        if (mFrameRate == null) mFrameRate = new FrameRateManager(getLogTag());
        return mFrameRate;
    }

    // ---- 构造器 ----

    public VKSurfaceView(Context context, VkRendererDelegate delegate) {
        super(context);
        mDelegate = delegate;
        init();
    }

    public VKSurfaceView(Context context, AttributeSet attrs, VkRendererDelegate delegate) {
        super(context, attrs);
        mDelegate = delegate;
        init();
    }

    private void init() {
        getHolder().addCallback(this);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    // ---- SurfaceHolder.Callback ----

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        ensureScene();
        ensureRenderer();
        Surface surface = holder.getSurface();
        if (surface != null && surface.isValid() && mWidth > 0 && mHeight > 0) {
            mNativeSurfaceAlive = true;
            onSurfaceCreatedNative(surface);
        }
        startRenderer();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        mWidth = width;
        mHeight = height;
        ensureScene();
        synchronized (mSceneLock) {
            onSceneResize(width, height);
        }
        ensureRenderer();
        Surface surface = holder.getSurface();
        if (surface != null && surface.isValid()) {
            mNativeSurfaceAlive = true;
            onSurfaceChangedNative(surface);
        }
        startRenderer();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stopRenderer();
        if (mNativeSurfaceAlive && mRendererCreated) {
            onSurfaceDestroyedNative();
            mNativeSurfaceAlive = false;
        }
    }

    // ---- 公共生命周期 ----

    public void resumeRenderer() {
        startRenderer();
    }

    public void pauseRenderer() {
        stopRenderer();
    }

    public void releaseRenderer() {
        stopRenderer();
        if (mRendererCreated) {
            if (mNativeSurfaceAlive) {
                onSurfaceDestroyedNative();
                mNativeSurfaceAlive = false;
            }
            destroyRenderer();
            mRendererCreated = false;
        }
    }

    // ---- 渲染线程 ----

    @Override
    public void run() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        } catch (Throwable ignored) {
        }

        try {
            while (mRunning) {
                long frameStart = SystemClock.uptimeMillis();
                frameRate().syncPerfSettingsIfNeeded(frameStart);

                try {
                    if (mRendererCreated && mDelegate.isReady()) {
                        synchronized (mSceneLock) {
                            syncTexturesIfNeeded();
                            renderFrame();
                        }
                    }
                } catch (Exception e) {
                    // 单帧异常不能杀死渲染线程，否则壁纸会永久冻结
                    android.util.Log.e(getLogTag(), "renderFrame failed", e);
                }

                long frameCost = SystemClock.uptimeMillis() - frameStart;
                frameRate().recordFrameCost(frameCost);

                try {
                    Thread.sleep(frameRate().pacingSleepMs(frameCost));
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        } finally {
            // 线程退出时释放槽位，使 startRenderer 可以重新启动（异常/中断退出也能恢复）
            if (mThread == Thread.currentThread()) {
                mThread = null;
                mRunning = false;
            }
        }
    }

    protected void startRenderer() {
        if (mRunning || mWidth <= 0 || mHeight <= 0) return;
        mRunning = true;
        mThread = new Thread(this, getThreadName());
        mThread.start();
    }

    /** public 是为了满足 {@link WallpaperPreview}；pauseRenderer() 本来就公开这一件事。 */
    @Override
    public void stopRenderer() {
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
            if (thread.isAlive()) {
                android.util.Log.e(getLogTag(), "Render thread did not exit within 2s");
                // 保留引用：由渲染线程退出时自行清理，避免旧线程未结束时又启动新线程导致并发渲染
            }
        }
    }

    // ---- 模板方法：全部转给 delegate ----

    @Override
    public Object getScene() {
        return mDelegate.getScene();
    }

    protected void ensureScene() {
        mDelegate.ensureScene(mWidth, mHeight);
    }

    protected void onSceneResize(int width, int height) {
        mDelegate.ensureScene(width, height);
    }

    protected void ensureRenderer() {
        if (mRendererCreated) return;
        mRendererCreated = mDelegate.createRenderer() != 0L;
    }

    protected void destroyRenderer() {
        mDelegate.destroyRenderer();
    }

    protected void onSurfaceCreatedNative(Surface surface) {
        mDelegate.onSurfaceCreated(surface, mWidth, mHeight);
    }

    protected void onSurfaceChangedNative(Surface surface) {
        mDelegate.onSurfaceChanged(surface, mWidth, mHeight);
    }

    protected void onSurfaceDestroyedNative() {
        mDelegate.onSurfaceDestroyed();
    }

    protected void syncTexturesIfNeeded() {
        mDelegate.syncTexturesIfNeeded();
    }

    protected void renderFrame() {
        mDelegate.renderFrame();
    }

    protected String getThreadName() {
        return mDelegate.logTag() + "PreviewThread";
    }

    protected String getLogTag() {
        return mDelegate.logTag();
    }
}
