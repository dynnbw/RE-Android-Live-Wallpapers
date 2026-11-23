package com.reandroid.wallpaper.geeklog;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.opengl.GLES30;
import android.opengl.GLUtils;
import android.os.BatteryManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import com.reandroid.gles.GLESScene;
import com.reandroid.utils.AssetLoader;

import java.nio.FloatBuffer;
import java.util.Locale;

/**
 * GeekLog GLES2 渲染层。
 * 字符图集（Bitmap+Paint 生成一次）+ 每帧 quad 批量绘制 + 行级着色 + 顶部渐隐 + 闪烁光标。
 *
 * <p>顶点数据由 {@link GeekLogVertexBuilder} 统一构建，与 Vulkan 后端共用。
 */
public class GeekLogGL extends GLESScene {
    private static final String TAG = "GeekLogGL";

    private final Context mContext;
    private final GeekLogScene mScene = new GeekLogScene();
    private final GeekLogVertexBuilder mVertexBuilder = new GeekLogVertexBuilder();
    private SharedPreferences mPluginPrefs;

    private int mProgram;
    private int mPosLoc, mUVLoc, mColorLoc, mTexLoc;
    private int mGlyphTexture;

    private boolean mInitialized;
    private boolean mLoggedShaderFail; // mProgram==0 持久失败态只记一次 logcat

    // 渲染统计窗口（15s）：帧率均值 + 帧耗时均值 + 顶点数
    private static final long STATS_INTERVAL_MS = 1500;
    private long mFpsWindowStartMs;
    private int mFpsFrameCount;
    private long mFpsFrameTimeAccumUs;
    private long mLastFrameTimeMs;
    private int mLastReportedVerts;

    // 系统状态轮询（5s）
    private static final long SYS_INTERVAL_MS = 5000;
    private long mLastSysLogMs;

    public GeekLogGL(int width, int height, Context context) {
        super(width, height);
        mContext = context.getApplicationContext();
    }

    /** 反射注入（BasePluginEngine.tryInjectPrefs），在 onCreate 之前调用。注入后立即全量重读配置。 */
    public void setPluginPrefs(SharedPreferences prefs) {
        mPluginPrefs = prefs;
        readPrefs();
        mScene.onPrefsChanged(null);
    }

    /** 记录 engine 生命周期事件（createScene 时调用，无需 GL）。 */
    public void logInfo(String msg) {
        mScene.log(GeekLogScene.LEVEL_INFO, msg);
    }

    // ---- 事件转发（Engine 调用）----

    public void onVisibility(boolean visible) {
        mScene.onVisibility(visible);
    }

    public void logOffsetsChanged() {
        mScene.onOffsetsChanged();
    }

    public void logTap(int x, int y) {
        mScene.onTap(x, y);
    }

    public void logDrag(int x, int y) {
        mScene.onDrag(x, y);
    }

    // ---- GL 生命周期 ----

    @Override
    protected void onCreate() {
        if (mInitialized) return;
        mInitialized = true;
        mScene.log(GeekLogScene.LEVEL_INFO, "render: initialized (" + mWidth + "x" + mHeight + ")");
        try {
            GLES30.glEnable(GLES30.GL_BLEND);
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
            readPrefs();
            createProgram();
            createGlyphTexture();
            mVertexBuilder.layoutMetrics(mWidth, mHeight);
            logStartupInfo();
        } catch (Exception e) {
            Log.e(TAG, "init failed", e);
            mScene.log(GeekLogScene.LEVEL_ERROR, "render: init failed");
            mInitialized = false;
        }
    }

    @Override
    public void release() {
        if (mProgram != 0) {
            GLES30.glDeleteProgram(mProgram);
            mProgram = 0;
        }
        if (mGlyphTexture != 0) {
            int[] tex = {mGlyphTexture};
            GLES30.glDeleteTextures(1, tex, 0);
            mGlyphTexture = 0;
        }
        mInitialized = false;
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        mVertexBuilder.layoutMetrics(width, height);
    }

    // ---- 帧渲染 ----

    @Override
    public void drawFrame(long timeMs) {
        if (!mInitialized) {
            return;
        }
        if (mProgram == 0) {
            if (!mLoggedShaderFail) {
                mLoggedShaderFail = true;
                Log.w(TAG, "drawFrame skipped: shader program not ready");
            }
            return;
        }

        // 渲染统计（15s 窗口）：帧率/帧耗时均值，低帧率记 WARN，否则记 INFO
        long now = System.currentTimeMillis();
        if (mLastFrameTimeMs != 0) {
            mFpsFrameTimeAccumUs += Math.min(now - mLastFrameTimeMs, 200L) * 1000L;
        }
        mLastFrameTimeMs = now;
        mFpsFrameCount++;
        if (mFpsWindowStartMs == 0) mFpsWindowStartMs = now;
        long elapsed = now - mFpsWindowStartMs;
        if (elapsed >= STATS_INTERVAL_MS) {
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

        // 周期系统状态（60s）
        if (now - mLastSysLogMs >= SYS_INTERVAL_MS) {
            mLastSysLogMs = now;
            logSystemState();
        }

        GLES30.glClearColor(0f, 0f, 0f, 1f);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);

        mVertexBuilder.buildVertices(mScene, timeMs);
        int vertexCount = mVertexBuilder.vertexCount();
        mLastReportedVerts = vertexCount;

        if (vertexCount == 0) return;
        GLES30.glUseProgram(mProgram);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mGlyphTexture);
        GLES30.glUniform1i(mTexLoc, 0);

        GLES30.glEnableVertexAttribArray(mPosLoc);
        GLES30.glEnableVertexAttribArray(mUVLoc);
        GLES30.glEnableVertexAttribArray(mColorLoc);

        FloatBuffer vertices = mVertexBuilder.floatBuffer();
        vertices.position(0);
        GLES30.glVertexAttribPointer(
                mPosLoc, 2, GLES30.GL_FLOAT, false, GeekLogVertexBuilder.STRIDE_BYTES, vertices);
        vertices.position(2);
        GLES30.glVertexAttribPointer(
                mUVLoc, 2, GLES30.GL_FLOAT, false, GeekLogVertexBuilder.STRIDE_BYTES, vertices);
        vertices.position(4);
        GLES30.glVertexAttribPointer(
                mColorLoc, 3, GLES30.GL_FLOAT, false, GeekLogVertexBuilder.STRIDE_BYTES, vertices);
        vertices.position(0);

        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, vertexCount);

        GLES30.glDisableVertexAttribArray(mPosLoc);
        GLES30.glDisableVertexAttribArray(mUVLoc);
        GLES30.glDisableVertexAttribArray(mColorLoc);
    }

    // ---- 初始化辅助 ----

    /** 只返回注入的设置；调用点已判空（见 readPrefs）。 */
    private SharedPreferences prefs() {
        return mPluginPrefs;
    }

    private void readPrefs() {
        SharedPreferences p = prefs();
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

    /** 周期系统状态（60s）：电量、内存、充电状态。静态读取，无需权限。 */
    private void logSystemState() {
        try {
            int battery = -1;
            boolean charging = false;
            BatteryManager bm = (BatteryManager) mContext.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    charging = bm.isCharging();
                }
            }
            long memFreeMb = -1;
            ActivityManager am =
                    (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
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

    /** 启动系统信息（一次）：开机时长、屏幕、密度、系统版本。 */
    private void logStartupInfo() {
        try {
            long uptimeSec = SystemClock.elapsedRealtime() / 1000;
            String uptime =
                    String.format(Locale.US, "%dh%02dm", uptimeSec / 3600, (uptimeSec % 3600) / 60);
            float density = mResources != null ? mResources.getDisplayMetrics().density : 1f;
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

    private void createProgram() {
        String vs = AssetLoader.readText(mContext, "geeklog/shaders/GLES/terminal_vs.glsl");
        String fs = AssetLoader.readText(mContext, "geeklog/shaders/GLES/terminal_fs.glsl");
        mProgram = createProgram(vs, fs);
        if (mProgram == 0) {
            Log.e(TAG, "Shader program creation failed");
            mScene.log(GeekLogScene.LEVEL_ERROR, "render: shader compile failed");
            return;
        }
        mPosLoc = GLES30.glGetAttribLocation(mProgram, "aPosition");
        mUVLoc = GLES30.glGetAttribLocation(mProgram, "aUV");
        mColorLoc = GLES30.glGetAttribLocation(mProgram, "aColor");
        mTexLoc = GLES30.glGetUniformLocation(mProgram, "uTexture");
    }

    /** 加载预烘焙字符图集（像素风格，槽位索引 = 字符码）。 */
    private void createGlyphTexture() {
        Bitmap bmp = AssetLoader.decodeBitmap(mContext, "geeklog/drawable/glyph_00.png");
        int[] tex = new int[1];
        GLES30.glGenTextures(1, tex, 0);
        mGlyphTexture = tex[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mGlyphTexture);
        // LINEAR：无 NEAREST 的槽边界硬切问题（部分设备 NEAREST+收缩 UV 会黑屏）
        GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0);
        bmp.recycle();
    }
}
