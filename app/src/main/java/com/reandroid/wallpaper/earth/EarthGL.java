package com.reandroid.wallpaper.earth;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.SystemClock;
import android.view.MotionEvent;

import com.reandroid.gles.GLESScene;
import com.reandroid.utils.AssetLoader;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.Calendar;
import java.util.TimeZone;

/**
 * Sony Earth 壁纸的 GLES2 渲染层。
 *
 * <p>原版是 OpenGL ES 1.1 固定管线（无着色器），这里是整条管线的重写：
 * <ul>
 *   <li>{@code glLightfv} 的默认材质光照 → {@code earth_planet_fs.glsl} 里的
 *       {@code 纹理 × (0.2 + 0.8·N·L)}，光源是无衰减点光源 (0,3,10)</li>
 *   <li>{@code glDrawTexfOES} 的屏幕空间光晕 → 正交投影下的四边形</li>
 *   <li>相机变换被放在投影矩阵里的写法 → 显式合成为 view / view·model 两个矩阵</li>
 * </ul>
 *
 * <p><b>天空用的是另一个视图矩阵</b>（只去掉相机平移）：星空在无穷远，是真实天空，
 * 相机转向哪就该看见哪片星；留着相机旋转才有这个观感。若连旋转也去掉，星空就变成
 * 贴在屏幕上的一张画，拖拽时地球在转、星星纹丝不动（曾如此）。
 */
public class EarthGL extends GLESScene {

    private static final String KEY_SPIN = "earth_spin_speed";
    private static final String KEY_CLOUDS = "earth_clouds";
    private static final String KEY_HALO = "earth_halo";
    private static final String KEY_SKY = "earth_sky";

    private static final float FOV_DEG = 30.0f;
    private static final float NEAR = 0.01f;
    private static final float FAR = 1000.0f;
    /** 天空球半径。far 是 1000，留一倍余量。 */
    private static final float SKY_SCALE = 500.0f;
    /**
     * 光源在世界空间的位置（原版 glLightfv 的 position，w=1 即点光源）。
     *
     * <p>必须在世界空间而不是相机空间：原版在 modelview 为单位矩阵时调用 glLightfv，
     * 而它的相机变换放在投影矩阵里，所以那个位置不含相机。若当成相机空间，
     * 光会永远在相机背后，晨昏线与城市灯光整个失效。
     */
    private static final float LIGHT_X = 0.0f;
    private static final float LIGHT_Y = 3.0f;
    private static final float LIGHT_Z = 10.0f;
    /** 原版默认材质的 ambient 分量。 */
    private static final float AMBIENT = 0.2f;
    /** 城市灯光的增益（灯光图本身很暗，均值 RGB 只有 14,15,29）。 */
    private static final float NIGHT_GAIN = 3.0f;
    /** 近景地球的通道偏移（原版 applyColorFilter 的纯加法，{0,-49,11,38}）。 */
    private static final float[] CLOSEUP_DELTA = { -49.0f / 255.0f, 11.0f / 255.0f, 38.0f / 255.0f };
    /** 自转速度设置项的最大角度（度/秒）。默认值 20 → 恰好等于原版的 6°/s。 */
    private static final float SPIN_MAX_DEG_PER_SEC = 30.0f;
    /** 光晕四边形半宽 = 球体投影半径 × 该系数。实测光晕峰值在 0.672×半宽，取 1/0.672。 */
    private static final float HALO_RADIUS_SCALE = 1.0f / 0.672f;
    /** 时钟刷新间隔：原版每帧读 Calendar，这里按分钟级缓存。 */
    private static final long CLOCK_REFRESH_MS = 60000L;
    /** 点击判定的阈值。 */
    private static final float TAP_SLOP_PX = 24.0f;
    private static final long TAP_MAX_MS = 400L;
    /** onTouchEvent 与 onCommand 可能都报点击，用它去重。 */
    private static final long TAP_DEBOUNCE_MS = 350L;

    private final EarthScene mScene = new EarthScene();
    private final Context mContext;

    private boolean mGLReady;
    private boolean mGlobeFailed;
    /**
     * 设备是否支持 NPOT 贴图的 mipmap。
     *
     * <p>GLES2 规范里 NPOT 贴图只能用 CLAMP_TO_EDGE + NEAREST/LINEAR，**不能生成 mipmap**；
     * 而我们的地球/星空是 3072 宽（POT 但非 2 的幂）。没有 mipmap 时，月球那种 2048 宽贴图
     * 缩到 330px（约 6:1）会出现明显走样 —— 实测是一层菱形织纹。
     * {@code GL_OES_texture_npot} 普遍支持，支持时就能给 NPOT 也上 mipmap。
     */
    private boolean mNpotMipmaps;

    // ---- programs ----
    private int mPlanetProgram;
    private int mPlanetProj, mPlanetView, mPlanetModel, mPlanetPos, mPlanetNormal, mPlanetTex;
    private int mPlanetDay, mPlanetNight, mPlanetLightPos, mPlanetDelta, mPlanetAmbient,
            mPlanetNightGain, mPlanetUseNight;

    private int mSkyProgram;
    private int mSkyProj, mSkyModelView, mSkyPos, mSkyTex, mSkySampler;

    private int mOverlayProgram;
    private int mOverlayOrtho, mOverlayPos, mOverlayTex, mOverlaySampler;

    // ---- textures ----
    private int mTexEarthDay;
    private int mTexEarthNight;
    private int mTexClouds;
    private int mTexMoon;
    private int mTexSpecular;
    private int mTexHalo;
    private int mTexHaloCloseup;
    private int mTexSky;

    // ---- globe ----
    private FloatBuffer mGlobeVerts;
    private ShortBuffer mGlobeIndices;
    private int mGlobeTriangles;
    private float mGlobeRadius = EarthGlobe.NORMALIZED_RADIUS;

    // ---- overlay quad ----
    private FloatBuffer mOverlayBuf;

    // ---- matrices ----
    private final float[] mProj = new float[16];
    private final float[] mView = new float[16];
    private final float[] mSkyView = new float[16];
    private final float[] mModel = new float[16];
    private final float[] mSkyMV = new float[16];
    private final float[] mOrtho = new float[16];

    // ---- settings ----
    private android.content.SharedPreferences mPluginPrefs;
    private volatile int mPrefSpin = 20;
    private volatile boolean mPrefClouds = true;
    private volatile boolean mPrefHalo = true;
    private volatile boolean mPrefSky = true;

    // ---- touch ----
    private boolean mDragging;
    private float mLastTouchX;
    private long mGestureStartMs;
    private float mGestureTravel;
    private long mLastTapHandledMs;

    // ---- 状态 ----
    private long mLastFrameMs;
    private long mLastClockMs = -1L;

    public EarthGL(int width, int height, Context context) {
        super(width, height);
        mContext = context;
    }

    /** 由引擎经反射注入插件隔离的偏好。 */
    public void setPluginPrefs(SharedPreferences prefs) {
        mPluginPrefs = prefs;
        if (prefs != null) {
            mPrefSpin = prefs.getInt(KEY_SPIN, 20);
            mPrefClouds = prefs.getBoolean(KEY_CLOUDS, true);
            mPrefHalo = prefs.getBoolean(KEY_HALO, true);
            mPrefSky = prefs.getBoolean(KEY_SKY, true);
        }
    }

    @Override
    protected void onCreate() {
        // GL 资源在首帧延迟创建（同项目其它壁纸），避免构造期没有 GL 上下文
    }

    @Override
    public void release() {
        int[] programs = { mPlanetProgram, mSkyProgram, mOverlayProgram };
        for (int p : programs) {
            if (p != 0) GLES20.glDeleteProgram(p);
        }
        mPlanetProgram = 0;
        mSkyProgram = 0;
        mOverlayProgram = 0;

        int[] textures = { mTexEarthDay, mTexEarthNight, mTexClouds, mTexMoon,
                mTexSpecular, mTexHalo, mTexHaloCloseup, mTexSky };
        for (int t : textures) {
            if (t != 0) GLES20.glDeleteTextures(1, new int[] { t }, 0);
        }
        mTexEarthDay = 0;
        mTexEarthNight = 0;
        mTexClouds = 0;
        mTexMoon = 0;
        mTexSpecular = 0;
        mTexHalo = 0;
        mTexHaloCloseup = 0;
        mTexSky = 0;

        mGLReady = false;
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        if (mGLReady) {
            GLES20.glViewport(0, 0, mWidth, mHeight);
            updateProjection();
        }
    }

    // ------------------------------------------------------------------
    // 触摸：拖拽转镜头的 angleY；轻点（位移很小、时间很短）切镜头
    // ------------------------------------------------------------------

    @Override
    public void onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                beginGesture(event.getX(), event.getEventTime());
                break;
            case MotionEvent.ACTION_MOVE:
                if (!mDragging) {
                    // launcher 可能吞掉 DOWN（翻页/抽屉），把首个 MOVE 当起点，避免跳变
                    beginGesture(event.getX(), event.getEventTime());
                } else {
                    float dx = event.getX() - mLastTouchX;
                    mLastTouchX = event.getX();
                    mGestureTravel += Math.abs(dx);
                    mScene.drag(dx);
                }
                break;
            case MotionEvent.ACTION_UP:
                mDragging = false;
                if (mGestureTravel <= TAP_SLOP_PX
                        && event.getEventTime() - mGestureStartMs <= TAP_MAX_MS) {
                    handleTap();
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                mDragging = false;
                break;
            default:
                break;
        }
    }

    @Override
    public void onCommand(String action, int x, int y, int z) {
        // 桌面路径：系统只发 tap 命令；本方法在触摸线程，mScene 的字段都是简单的数值更新
        if ("android.wallpaper.tap".equals(action)) {
            handleTap();
        }
    }

    private void beginGesture(float x, long eventTimeMs) {
        mDragging = true;
        mLastTouchX = x;
        mGestureStartMs = eventTimeMs;
        mGestureTravel = 0.0f;
    }

    private void handleTap() {
        long now = SystemClock.uptimeMillis();
        if (now - mLastTapHandledMs < TAP_DEBOUNCE_MS) return;   // onTouchEvent 与 onCommand 去重
        mLastTapHandledMs = now;
        mScene.onTap();
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    @Override
    public void drawFrame(long timeMs) {
        if (!mGLReady) {
            if (mContext == null) return;
            initGL();
            if (!mGLReady) return;
        }

        float dt = 0.0f;
        if (mLastFrameMs != 0L) {
            dt = (timeMs - mLastFrameMs) / 1000.0f;
            if (dt < 0.0f) dt = 0.0f;
            if (dt > 0.1f) dt = 0.1f;   // 切后台回来不跳变
        }
        mLastFrameMs = timeMs;

        refreshClock();
        applyPrefs();
        mScene.update(dt);

        // 深度写入必须在清屏**之前**打开：glClear(GL_DEPTH_BUFFER_BIT) 受 glDepthMask 影响，
        // 上一帧末尾光晕把深度写入关了，若不在清屏前恢复，深度清除会变成空操作，
        // 深度值一直留着上一帧的，结果所有球体都被深度测试拒绝 —— 整屏黑。
        GLES20.glDepthMask(true);
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        buildView();

        // ① 天空：从内侧看，只当背景 —— 不测深度、也不写深度，后面的球体才能盖住它
        if (mPrefSky && mTexSky != 0 && !mGlobeFailed) {
            GLES20.glDisable(GLES20.GL_DEPTH_TEST);
            GLES20.glDepthMask(false);
            GLES20.glDisable(GLES20.GL_BLEND);
            Matrix.setIdentityM(mSkyModel, 0);
            Matrix.rotateM(mSkyModel, 0, mScene.skyAngleY(), 0.0f, 1.0f, 0.0f);
            Matrix.scaleM(mSkyModel, 0, SKY_SCALE, SKY_SCALE, SKY_SCALE);
            Matrix.multiplyMM(mSkyMV, 0, mSkyView, 0, mSkyModel, 0);
            drawGlobe(mSkyProgram, mSkyProj, mSkyModelView, mSkyPos, mSkyTex, mTexSky, false);
        }

        // ② 球体层
        //
        // 这里**不开背面剔除**，只靠深度测试。原因是曾经踩过的坑：原先项目的 EGL 配置没有申请
        // 深度缓冲（三个配置里都没有 EGL_DEPTH_SIZE → 驱动给 depth=0），于是
        // ① 月球画在地球前面（后画的直接盖住先画的）
        // ② 地球正面能看到背面的云（背面没被任何机制拦住）
        // 深度缓冲已在 EGL 配置里补上；靠它遮挡与绕序无关，比依赖 glFrontFace 判断更稳。
        // （网格实测 100% 逆时针朝外，剔除方向其实是对的，但那属于可后置的优化。）
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthFunc(GLES20.GL_LEQUAL);
        GLES20.glDepthMask(true);
        GLES20.glDisable(GLES20.GL_CULL_FACE);

        EarthCamera cam = mScene.activeCamera();
        boolean closeup = cam.id == EarthScene.CAMERA_CLOSEUP;
        float[] delta = closeup ? CLOSEUP_DELTA : null;

        // 地球（不透明）
        GLES20.glDisable(GLES20.GL_BLEND);
        drawSphere(mTexEarthDay, mTexEarthNight, EarthScene.EARTH_SCALE,
                mScene.earthAngleY(), true, delta, true);

        // 云层 / 高光（加色）
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE);
        if (mPrefClouds) {
            drawSphere(mTexClouds, mTexClouds, EarthScene.CLOUDS_SCALE,
                    mScene.cloudAngleY(), false, null, true);
        }
        if (!closeup && mTexSpecular != 0) {
            drawSphere(mTexSpecular, mTexSpecular, EarthScene.SPECULAR_SCALE,
                    EarthScene.SPECULAR_ANGLE_Y, false, null, true);
        }
        GLES20.glDisable(GLES20.GL_BLEND);

        // 月球（不透明）
        drawSphere(mTexMoon, mTexMoon, EarthScene.MOON_SCALE,
                mScene.moonAngleY(), false, null, true, mScene.moonX(), mScene.moonZ());

        // ③ 光晕：屏幕空间叠加，画在最后
        if (mPrefHalo) {
            drawHalo(cam, closeup);
        }
    }

    /** 画一层球体。 */
    private void drawSphere(int dayTex, int nightTex, float scale, float angleY,
                            boolean useNight, float[] channelDelta, boolean hasNormals) {
        drawSphere(dayTex, nightTex, scale, angleY, useNight, channelDelta, hasNormals, 0.0f, 0.0f);
    }

    private void drawSphere(int dayTex, int nightTex, float scale, float angleY,
                            boolean useNight, float[] channelDelta, boolean hasNormals,
                            float posX, float posZ) {
        if (mGlobeFailed || dayTex == 0) return;

        Matrix.setIdentityM(mModel, 0);
        if (posX != 0.0f || posZ != 0.0f) {
            Matrix.translateM(mModel, 0, posX, 0.0f, posZ);
        }
        Matrix.rotateM(mModel, 0, angleY, 0.0f, 1.0f, 0.0f);
        Matrix.scaleM(mModel, 0, scale, scale, scale);
        GLES20.glUseProgram(mPlanetProgram);
        GLES20.glUniformMatrix4fv(mPlanetProj, 1, false, mProj, 0);
        GLES20.glUniformMatrix4fv(mPlanetView, 1, false, mView, 0);
        GLES20.glUniformMatrix4fv(mPlanetModel, 1, false, mModel, 0);
        GLES20.glUniform3f(mPlanetLightPos, LIGHT_X, LIGHT_Y, LIGHT_Z);
        GLES20.glUniform1f(mPlanetAmbient, AMBIENT);
        GLES20.glUniform1f(mPlanetNightGain, NIGHT_GAIN);
        GLES20.glUniform1f(mPlanetUseNight, useNight ? 1.0f : 0.0f);
        if (channelDelta != null) {
            GLES20.glUniform3f(mPlanetDelta, channelDelta[0], channelDelta[1], channelDelta[2]);
        } else {
            GLES20.glUniform3f(mPlanetDelta, 0.0f, 0.0f, 0.0f);
        }

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, dayTex);
        GLES20.glUniform1i(mPlanetDay, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, nightTex);
        GLES20.glUniform1i(mPlanetNight, 1);

        int stride = EarthGlobe.FLOATS_PER_VERTEX * 4;
        mGlobeVerts.position(0);
        GLES20.glEnableVertexAttribArray(mPlanetPos);
        GLES20.glVertexAttribPointer(mPlanetPos, 3, GLES20.GL_FLOAT, false, stride, mGlobeVerts);
        mGlobeVerts.position(3);
        GLES20.glEnableVertexAttribArray(mPlanetNormal);
        GLES20.glVertexAttribPointer(mPlanetNormal, 3, GLES20.GL_FLOAT, false, stride, mGlobeVerts);
        mGlobeVerts.position(6);
        GLES20.glEnableVertexAttribArray(mPlanetTex);
        GLES20.glVertexAttribPointer(mPlanetTex, 2, GLES20.GL_FLOAT, false, stride, mGlobeVerts);

        mGlobeIndices.position(0);
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, mGlobeTriangles * 3,
                GLES20.GL_UNSIGNED_SHORT, mGlobeIndices);

        GLES20.glDisableVertexAttribArray(mPlanetPos);
        GLES20.glDisableVertexAttribArray(mPlanetNormal);
        GLES20.glDisableVertexAttribArray(mPlanetTex);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    }

    /** 画天空球（只用位置与 UV）。 */
    private void drawGlobe(int program, int uProj, int uModelView, int aPos, int aTex,
                           int texture, boolean unused) {
        GLES20.glUseProgram(program);
        GLES20.glUniformMatrix4fv(uProj, 1, false, mProj, 0);
        GLES20.glUniformMatrix4fv(uModelView, 1, false, mSkyMV, 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glUniform1i(mSkySampler, 0);

        int stride = EarthGlobe.FLOATS_PER_VERTEX * 4;
        mGlobeVerts.position(0);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, stride, mGlobeVerts);
        mGlobeVerts.position(6);
        GLES20.glEnableVertexAttribArray(aTex);
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, stride, mGlobeVerts);

        mGlobeIndices.position(0);
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, mGlobeTriangles * 3,
                GLES20.GL_UNSIGNED_SHORT, mGlobeIndices);

        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisableVertexAttribArray(aTex);
    }

    /**
     * 大气光晕：屏幕空间四边形。
     *
     * <p>原版用 {@code GLU.gluProject} 求地心屏幕坐标、按球体投影尺寸算半径，
     * 再用 {@code glDrawTexfOES}（窗口坐标直接贴图）画。GLES2 没有那个扩展，
     * 这里等价地算好像素坐标后走一次正交投影。
     *
     * <p>半径按"光晕峰值落在地球边缘"来定：实测峰值在贴图半宽的 0.672 处，
     * 所以四边形半宽取球体投影半径的 1/0.672 倍。
     */
    private void drawHalo(EarthCamera cam, boolean closeup) {
        int tex = closeup ? mTexHaloCloseup : mTexHalo;
        if (tex == 0 || mTexHalo == 0) return;

        // 相机到地心的距离（视图矩阵只含旋转与平移，地心在视图空间的位置就是平移列）
        float vx = mView[12];
        float vy = mView[13];
        float vz = mView[14];
        float d = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (d < 1.0E-3f) return;

        // 世界原点的裁剪坐标 → 屏幕像素
        float cx = mProj[0] * vx + mProj[4] * vy + mProj[8] * vz + mProj[12];
        float cy = mProj[1] * vx + mProj[5] * vy + mProj[9] * vz + mProj[13];
        float cw = mProj[3] * vx + mProj[7] * vy + mProj[11] * vz + mProj[15];
        if (cw <= 0.0f) return;
        float sx = (cx / cw * 0.5f + 0.5f) * mWidth;
        float sy = (0.5f - cy / cw * 0.5f) * mHeight;

        // 球体的角半径 → 屏幕半径。mProj[5] = 1/tan(垂直半视场角)
        float worldR = EarthScene.EARTH_SCALE * mGlobeRadius;
        float angR = (float) Math.asin(Math.min(1.0f, worldR / d));
        float screenR = (mHeight * 0.5f) * mProj[5] * (float) Math.tan(angR) * HALO_RADIUS_SCALE;
        if (screenR <= 0.0f) return;

        // 光晕贴图上传时原版设了负高度的 crop rect（垂直翻转），UV 跟着翻
        mOverlayBuf.clear();
        mOverlayBuf.put(sx - screenR).put(sy - screenR).put(0.0f).put(0.0f);
        mOverlayBuf.put(sx - screenR).put(sy + screenR).put(0.0f).put(1.0f);
        mOverlayBuf.put(sx + screenR).put(sy + screenR).put(1.0f).put(1.0f);
        mOverlayBuf.put(sx + screenR).put(sy - screenR).put(1.0f).put(0.0f);
        mOverlayBuf.position(0);

        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthMask(false);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE);

        GLES20.glUseProgram(mOverlayProgram);
        GLES20.glUniformMatrix4fv(mOverlayOrtho, 1, false, mOrtho, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
        GLES20.glUniform1i(mOverlaySampler, 0);

        mOverlayBuf.position(0);
        GLES20.glEnableVertexAttribArray(mOverlayPos);
        GLES20.glVertexAttribPointer(mOverlayPos, 2, GLES20.GL_FLOAT, false, 16, mOverlayBuf);
        mOverlayBuf.position(2);
        GLES20.glEnableVertexAttribArray(mOverlayTex);
        GLES20.glVertexAttribPointer(mOverlayTex, 2, GLES20.GL_FLOAT, false, 16, mOverlayBuf);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4);

        GLES20.glDisableVertexAttribArray(mOverlayPos);
        GLES20.glDisableVertexAttribArray(mOverlayTex);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    // ------------------------------------------------------------------
    // 矩阵与时钟
    // ------------------------------------------------------------------

    private void updateProjection() {
        float size = NEAR * (float) Math.tan(Math.toRadians(FOV_DEG / 2.0));
        float ratio = mHeight > 0 ? (float) mWidth / mHeight : 1.0f;
        Matrix.frustumM(mProj, 0, -size, size, -size / ratio, size / ratio, NEAR, FAR);
        Matrix.orthoM(mOrtho, 0, 0.0f, mWidth, mHeight, 0.0f, -1.0f, 1.0f);
    }

    /** 视图矩阵（相机 / 天空）。两者的差别只在相机平移，见 {@link EarthView}。 */
    private void buildView() {
        EarthCamera cam = mScene.activeCamera();
        EarthView.buildCamera(mView, cam);
        EarthView.buildSky(mSkyView, cam);
    }

    private final float[] mSkyModel = new float[16];

    private static boolean isPowerOfTwo(int v) {
        return v > 0 && (v & (v - 1)) == 0;
    }

    /** 每分钟从 Calendar 取一次时间；原版是每帧取，代价高且无必要。 */
    private void refreshClock() {
        long now = System.currentTimeMillis();
        if (mLastClockMs >= 0 && now - mLastClockMs < CLOCK_REFRESH_MS) return;
        mLastClockMs = now;

        Calendar cal = Calendar.getInstance();
        TimeZone tz = cal.getTimeZone();
        // 原版算的是"相对 12:00 的毫秒数"
        long msSinceNoon = (((cal.get(Calendar.HOUR_OF_DAY) - 12L) * 3600L
                + cal.get(Calendar.MINUTE) * 60L
                + cal.get(Calendar.SECOND)) * 1000L)
                + cal.get(Calendar.MILLISECOND);
        // 恒星日漂移需要一个连续的天数（不能只用 dayOfYear，那样跨年会跳变）
        double daysSinceEpoch = System.currentTimeMillis() / 86400000.0;
        mScene.setClock(msSinceNoon, tz.getRawOffset(), cal.get(Calendar.DAY_OF_YEAR), daysSinceEpoch);
    }

    private void applyPrefs() {
        mScene.setSpinDegPerSec(mPrefSpin / 100.0f * SPIN_MAX_DEG_PER_SEC);
    }

    // ------------------------------------------------------------------
    // 资源
    // ------------------------------------------------------------------

    private void initGL() {
        updateProjection();
        mOverlayBuf = ByteBuffer.allocateDirect(4 * 4 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();

        // 网格（解析失败则只画天空，不崩）
        try {
            InputStream in = mContext.getAssets().open("earth/data/globe.obj");
            EarthGlobe globe;
            try {
                globe = EarthGlobe.load(in);
            } finally {
                in.close();
            }
            mGlobeVerts = ByteBuffer.allocateDirect(globe.vertices.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            mGlobeVerts.put(globe.vertices).position(0);
            mGlobeIndices = ByteBuffer.allocateDirect(globe.indices.length * 2)
                    .order(ByteOrder.nativeOrder()).asShortBuffer();
            mGlobeIndices.put(globe.indices).position(0);
            mGlobeTriangles = globe.triangleCount;
            mGlobeRadius = globe.radius();
        } catch (Exception e) {
            android.util.Log.e("EarthGL", "球体网格加载失败，本次只渲染天空", e);
            mGlobeFailed = true;
        }

        buildPlanetProgram();
        buildSkyProgram();
        buildOverlayProgram();

        if (!mGlobeFailed) {
            mTexEarthDay = loadTexture("earth/drawable/earth_day.jpg");
            mTexEarthNight = loadTexture("earth/drawable/earth_night.jpg");
            mTexClouds = loadTexture("earth/drawable/clouds.png");
            mTexMoon = loadTexture("earth/drawable/moon.jpg");
            mTexSpecular = loadTexture("earth/drawable/specular.png");
        }
        mTexHalo = loadTexture("earth/drawable/halo.png");
        mTexHaloCloseup = loadTexture("earth/drawable/halo_closeup.png");
        mTexSky = loadTexture("earth/drawable/sky.jpg");

        String extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS);
        mNpotMipmaps = extensions != null && extensions.contains("GL_OES_texture_npot");

        GLES20.glDisable(GLES20.GL_DITHER);
        mGLReady = true;
    }

    private void buildPlanetProgram() {
        String vs = AssetLoader.readText(mContext, "earth/shaders/GLES/earth_planet_vs.glsl");
        String fs = AssetLoader.readText(mContext, "earth/shaders/GLES/earth_planet_fs.glsl");
        mPlanetProgram = createProgram(vs, fs);
        mPlanetProj = GLES20.glGetUniformLocation(mPlanetProgram, "uProjection");
        mPlanetView = GLES20.glGetUniformLocation(mPlanetProgram, "uView");
        mPlanetModel = GLES20.glGetUniformLocation(mPlanetProgram, "uModel");
        mPlanetLightPos = GLES20.glGetUniformLocation(mPlanetProgram, "uLightPosWorld");
        mPlanetDelta = GLES20.glGetUniformLocation(mPlanetProgram, "uChannelDelta");
        mPlanetAmbient = GLES20.glGetUniformLocation(mPlanetProgram, "uAmbient");
        mPlanetNightGain = GLES20.glGetUniformLocation(mPlanetProgram, "uNightGain");
        mPlanetUseNight = GLES20.glGetUniformLocation(mPlanetProgram, "uUseNight");
        mPlanetDay = GLES20.glGetUniformLocation(mPlanetProgram, "uDay");
        mPlanetNight = GLES20.glGetUniformLocation(mPlanetProgram, "uNight");
        mPlanetPos = GLES20.glGetAttribLocation(mPlanetProgram, "aPosition");
        mPlanetNormal = GLES20.glGetAttribLocation(mPlanetProgram, "aNormal");
        mPlanetTex = GLES20.glGetAttribLocation(mPlanetProgram, "aTexCoord");
    }

    private void buildSkyProgram() {
        String vs = AssetLoader.readText(mContext, "earth/shaders/GLES/earth_sky_vs.glsl");
        String fs = AssetLoader.readText(mContext, "earth/shaders/GLES/earth_sky_fs.glsl");
        mSkyProgram = createProgram(vs, fs);
        mSkyProj = GLES20.glGetUniformLocation(mSkyProgram, "uProjection");
        mSkyModelView = GLES20.glGetUniformLocation(mSkyProgram, "uModelView");
        mSkySampler = GLES20.glGetUniformLocation(mSkyProgram, "uSampler");
        mSkyPos = GLES20.glGetAttribLocation(mSkyProgram, "aPosition");
        mSkyTex = GLES20.glGetAttribLocation(mSkyProgram, "aTexCoord");
    }

    private void buildOverlayProgram() {
        String vs = AssetLoader.readText(mContext, "earth/shaders/GLES/earth_overlay_vs.glsl");
        String fs = AssetLoader.readText(mContext, "earth/shaders/GLES/earth_overlay_fs.glsl");
        mOverlayProgram = createProgram(vs, fs);
        mOverlayOrtho = GLES20.glGetUniformLocation(mOverlayProgram, "uOrtho");
        mOverlaySampler = GLES20.glGetUniformLocation(mOverlayProgram, "uSampler");
        mOverlayPos = GLES20.glGetAttribLocation(mOverlayProgram, "aPosition");
        mOverlayTex = GLES20.glGetAttribLocation(mOverlayProgram, "aTexCoord");
    }

    /**
     * 从 assets 加载纹理。贴图都是 NPOT（3072 宽等），
     * GLES2 下 NPOT 只支持 CLAMP_TO_EDGE + LINEAR，且不能生成 mipmap ——
     * 正好与原版一致（原版设的就是 CLAMP_TO_EDGE）。
     */
    private int loadTexture(String assetPath) {
        Bitmap bmp = AssetLoader.decodeBitmap(mContext, assetPath);
        if (bmp == null) {
            android.util.Log.w("EarthGL", "纹理缺失: " + assetPath);
            return 0;
        }
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        // 2 的幂贴图一定可以生成 mipmap；非 2 的幂要看 GL_OES_texture_npot
        boolean mipmaps = mNpotMipmaps || (isPowerOfTwo(bmp.getWidth()) && isPowerOfTwo(bmp.getHeight()));
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                mipmaps ? GLES20.GL_LINEAR_MIPMAP_LINEAR : GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
        if (mipmaps) {
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
        }
        bmp.recycle();
        return tex[0];
    }
}
