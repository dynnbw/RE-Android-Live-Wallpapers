package com.reandroid.wallpaper.geeklog;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;

import com.reandroid.plugin.VkPass;
import com.reandroid.plugin.VkPassFrame;
import com.reandroid.plugin.VkPassRendererDelegate;
import com.reandroid.plugin.VkPassScene;
import com.reandroid.utils.AssetLoader;

import java.util.Locale;

/**
 * GeekLog 的 Vulkan 渲染器 —— 壁纸与预览共用。
 *
 * <p>整个画面只有一次 draw：一张字符图集贴图 + 每帧重建的 quad 批量（TRIANGLES）。
 * 顶点数据由 {@link GeekLogVertexBuilder} 构建，与 GLES 版共用同一份逻辑；位置直接是
 * NDC，所以顶点着色器只做一次 y 取反（GL 与 Vulkan 的裁剪空间方向相反）。
 * 混合取 {@code SRC_ALPHA / ONE_MINUS_SRC_ALPHA}，颜色与 alpha 两路都按这个来，
 * 与 GLES 的 {@code glBlendFunc} 相同。
 *
 * <p>终端内容来自 {@link GeekLogScene}：启动信息、周期系统状态、帧率统计与触摸/滚动事件。
 * 这些日志的添加顺序与 GLES 版一致，先记账再取快照，所以同一时刻两边的行数和内容相同。
 */
final class GeekLogVkDelegate extends VkPassRendererDelegate {

    private static final String TAG = "GeekLogVK";

    private static final String VERT_SHADER = "shaders/geeklog_vk.vert.spv";
    private static final String FRAG_SHADER = "shaders/geeklog_vk.frag.spv";

    private static final String BUFFER_NAME = "glyphs";
    private static final String TEXTURE_NAME = "glyph";
    private static final String GLYPH_ASSET = "geeklog/drawable/glyph_00.png";

    /** 位置 2 + 图集坐标 2 + 颜色 3，与 GeekLogVertexBuilder 的布局一致。 */
    private static final int[] ATTRIB_SIZES = {2, 2, 3};

    // 渲染统计窗口（1.5s）：帧率均值 + 帧耗时均值 + 顶点数
    private static final long STATS_INTERVAL_MS = 1500;
    private long mFpsWindowStartMs;
    private int mFpsFrameCount;
    private long mFpsFrameTimeAccumUs;
    private long mLastFrameTimeMs;
    private int mLastReportedVerts;

    // 系统状态轮询（5s）
    private static final long SYS_INTERVAL_MS = 5000;
    private long mLastSysLogMs;

    /** 场景与顶点布局会被主线程（尺寸/设置/事件）和渲染线程同时读写。 */
    private final Object mLock = new Object();

    private SharedPreferences mPrefs;
    private final GeekLogScene mScene = new GeekLogScene();
    private final GeekLogVertexBuilder mVertexBuilder = new GeekLogVertexBuilder();

    private VkPassScene mPassScene;
    private VkPass mPass;

    private int mWidth;
    private int mHeight;
    private boolean mInitialized;
    private boolean mTextureUploaded;

    GeekLogVkDelegate(Context context, SharedPreferences prefs) {
        super(context);
        mPrefs = prefs;
    }

    @Override
    public String logTag() {
        return TAG;
    }

    /**
     * 设置页按方法名反射 {@code setPluginPrefs(SharedPreferences)} 把新设置推给预览。
     *
     * <p>{@link GeekLogScene} 是纯逻辑层、没有这个方法（GLES 版的设置也保存在
     * {@link GeekLogGL} 里），所以这里返回 delegate 自身；返回场景对象的话，
     * 设置页每改一次都得整块重建预览。
     */
    @Override
    public Object getScene() {
        return this;
    }

    @Override
    public boolean isReady() {
        return mWidth > 0 && mHeight > 0;
    }

    @Override
    public void ensureScene(int width, int height) {
        if (width <= 0 || height <= 0) return;
        synchronized (mLock) {
            mWidth = width;
            mHeight = height;
            mVertexBuilder.layoutMetrics(width, height);
            if (mInitialized) return;
            mInitialized = true;
            readPrefsLocked();
            // 与 GLES 版的 createScene/onCreate 相同：先 engine，再 render。
            mScene.log(GeekLogScene.LEVEL_INFO, "engine: created");
            mScene.log(
                    GeekLogScene.LEVEL_INFO, "render: initialized (" + width + "x" + height + ")");
            logStartupInfo();
        }
    }

    /** 设置页反射调用的入口；壁纸路径走 {@link #onPluginPrefsChanged}。 */
    public void setPluginPrefs(SharedPreferences prefs) {
        mPrefs = prefs;
        synchronized (mLock) {
            readPrefsLocked();
        }
        mScene.onPrefsChanged(null);
    }

    @Override
    public void onPluginPrefsChanged(SharedPreferences prefs) {
        setPluginPrefs(prefs);
    }

    // ---- 事件转发（与 GeekLogEngine 相同）----

    @Override
    public void onVisibilityChanged(boolean visible) {
        mScene.onVisibility(visible);
    }

    @Override
    public void onSceneOffset(float xOffset) {
        mScene.onOffsetsChanged();
    }

    @Override
    public void onTouchEvent(MotionEvent event) {
        if (event == null) return;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            mScene.onTap((int) event.getX(), (int) event.getY());
        } else if (action == MotionEvent.ACTION_MOVE) {
            mScene.onDrag((int) event.getX(), (int) event.getY());
        }
    }

    @Override
    public void onCommand(String action, int x, int y, int z, Bundle extras) {
        if ("android.wallpaper.tap".equals(action)) {
            mScene.onTap(x, y);
        }
    }

    @Override
    public void destroyRenderer() {
        // native 销毁后句柄就是野指针；先摘掉场景引用，避免设置回调拿旧句柄去调用。
        synchronized (mLock) {
            mPassScene = null;
            mTextureUploaded = false;
        }
        super.destroyRenderer();
    }

    @Override
    protected void buildScene(VkPassScene scene) {
        synchronized (mLock) {
            mPassScene = scene;
            // native 渲染器可能被销毁后重建：新场景里的纹理是空的，必须重新上传。
            mTextureUploaded = false;
        }
        scene.setClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        scene.createBuffer(
                BUFFER_NAME,
                GeekLogVertexBuilder.STRIDE_BYTES,
                GeekLogVertexBuilder.CAPACITY_VERTICES,
                true);
        scene.createTexture(TEXTURE_NAME);

        mPass = new VkPass(
                        VERT_SHADER,
                        FRAG_SHADER,
                        ATTRIB_SIZES,
                        GeekLogVertexBuilder.STRIDE_BYTES,
                        VkPass.TOPOLOGY_TRIANGLE_LIST,
                        VkPass.BLEND_ALPHA)
                .buffer(BUFFER_NAME, 0)
                // 精确对应 GLES 的 glBlendFunc(SRC_ALPHA, ONE_MINUS_SRC_ALPHA)：
                // 颜色与 alpha 两个通道都用 SRC_ALPHA。
                .blendFactors(
                        VkPass.FACTOR_SRC_ALPHA,
                        VkPass.FACTOR_ONE_MINUS_SRC_ALPHA,
                        VkPass.FACTOR_SRC_ALPHA,
                        VkPass.FACTOR_ONE_MINUS_SRC_ALPHA)
                .texture(0, TEXTURE_NAME);
        scene.add(mPass);
    }

    @Override
    protected void updateFrame(VkPassFrame frame, long timeMs) {
        synchronized (mLock) {
            if (mPassScene == null) return;
            ensureTextureLocked();

            // 与 GeekLogGL.drawFrame 相同的顺序：先记统计/系统状态，再取场景快照。
            long now = System.currentTimeMillis();
            logStats(now);
            if (now - mLastSysLogMs >= SYS_INTERVAL_MS) {
                mLastSysLogMs = now;
                logSystemState();
            }

            mVertexBuilder.buildVertices(mScene, timeMs);
            int vertexCount = mVertexBuilder.vertexCount();
            mLastReportedVerts = vertexCount;
            if (vertexCount > 0) {
                frame.upload(
                        BUFFER_NAME,
                        mVertexBuilder.byteBuffer(),
                        vertexCount * GeekLogVertexBuilder.STRIDE_BYTES);
            }
            frame.setVertexCount(mPass, vertexCount);
        }
    }

    // ---- 场景配置 ----

    private void readPrefsLocked() {
        SharedPreferences p = mPrefs;
        if (p == null) return;
        String color = p.getString("geeklog_color", "green");
        int idx = 0;
        if ("amber".equals(color)) idx = 1;
        else if ("cyan".equals(color)) idx = 2;
        else if ("white".equals(color)) idx = 3;
        mScene.setColorIndex(idx);
        mScene.setHighlightErrors(p.getBoolean("geeklog_highlight_errors", false));
        mScene.setMaxLines(p.getInt("geeklog_max_lines", 80));
    }

    // ---- 资源 ----

    private void ensureTextureLocked() {
        if (mTextureUploaded || mPassScene == null) return;
        mTextureUploaded = true;
        Bitmap bitmap;
        try {
            bitmap = AssetLoader.decodeBitmap(context, GLYPH_ASSET);
        } catch (RuntimeException e) {
            Log.e(TAG, "decode failed: " + GLYPH_ASSET, e);
            return;
        }
        if (bitmap == null) return;

        Bitmap argb = bitmap.getConfig() == Bitmap.Config.ARGB_8888
                ? bitmap
                : bitmap.copy(Bitmap.Config.ARGB_8888, false);
        if (argb != bitmap) bitmap.recycle();
        int width = argb.getWidth();
        int height = argb.getHeight();
        int[] pixels = new int[width * height];
        argb.getPixels(pixels, 0, width, 0, 0, width, height);
        argb.recycle();
        mPassScene.setTexture(TEXTURE_NAME, pixels, width, height);
    }

    // ---- 终端日志内容（与 GeekLogGL 相同）----

    private void logStats(long now) {
        if (mLastFrameTimeMs != 0) {
            mFpsFrameTimeAccumUs += Math.min(now - mLastFrameTimeMs, 200L) * 1000L;
        }
        mLastFrameTimeMs = now;
        mFpsFrameCount++;
        if (mFpsWindowStartMs == 0) mFpsWindowStartMs = now;
        long elapsed = now - mFpsWindowStartMs;
        if (elapsed < STATS_INTERVAL_MS) return;

        float fps = mFpsFrameCount * 1000f / Math.max(1, elapsed);
        float avgFrameMs = mFpsFrameTimeAccumUs / 1000f / Math.max(1, mFpsFrameCount);
        if (fps < 20f) {
            mScene.onFpsDrop((int) fps);
        } else {
            mScene.log(
                    GeekLogScene.LEVEL_INFO,
                    String.format(
                            Locale.US,
                            "render: %.1ffps, %.1fms, %dv",
                            fps,
                            avgFrameMs,
                            mLastReportedVerts));
        }
        mFpsFrameCount = 0;
        mFpsWindowStartMs = now;
        mFpsFrameTimeAccumUs = 0L;
    }

    /** 周期系统状态：电量、内存、充电状态。静态读取，无需权限。 */
    private void logSystemState() {
        try {
            int battery = -1;
            boolean charging = false;
            BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    charging = bm.isCharging();
                }
            }
            long memFreeMb = -1;
            ActivityManager am =
                    (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                memFreeMb = mi.availMem / (1024L * 1024L);
            }
            mScene.log(
                    GeekLogScene.LEVEL_INFO,
                    String.format(
                            Locale.US,
                            "system: bat %d%% %s, mem %dM",
                            battery,
                            charging ? "chg" : "nochg",
                            memFreeMb));
        } catch (Exception e) {
            Log.w(TAG, "system state read failed", e);
        }
    }

    /** 启动系统信息（一次）：开机时长、屏幕、系统版本。 */
    private void logStartupInfo() {
        try {
            long uptimeSec = SystemClock.elapsedRealtime() / 1000;
            String uptime =
                    String.format(Locale.US, "%dh%02dm", uptimeSec / 3600, (uptimeSec % 3600) / 60);
            mScene.log(
                    GeekLogScene.LEVEL_INFO,
                    String.format(
                            Locale.US,
                            "system: up %s, %dx%d, a%s",
                            uptime,
                            mWidth,
                            mHeight,
                            Build.VERSION.RELEASE));
        } catch (Exception e) {
            Log.w(TAG, "startup info read failed", e);
        }
    }
}
