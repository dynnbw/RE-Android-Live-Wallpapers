package com.reandroid.wallpaper.earth;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;

import com.reandroid.plugin.VkPass;
import com.reandroid.plugin.VkPassFrame;
import com.reandroid.plugin.VkPassRendererDelegate;
import com.reandroid.plugin.VkPassScene;
import com.reandroid.utils.AssetLoader;
import com.reandroid.utils.Mat4;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Calendar;
import java.util.TimeZone;

/**
 * Earth 壁纸的 Vulkan 渲染器 —— 壁纸与预览共用。
 *
 * <p>逐层对应 {@link EarthGL#drawFrame}：
 * <ol>
 *   <li>星空球（不测深度、不写深度、不混合）</li>
 *   <li>地球（不透明，测深度 + 写深度）</li>
 *   <li>云层 / 高光（加色，测深度、不写深度）</li>
 *   <li>月球（不透明，测深度 + 写深度）</li>
 *   <li>大气光晕（屏幕空间四边形，加色，不测深度）</li>
 * </ol>
 *
 * <p>GLES 版用索引缓冲画球；通用 pass 层没有索引缓冲，所以球体网格在 CPU 上展开成
 * 三角形列表。所有球体共用这一份顶点缓冲，每帧只改推送常量里的旋转/缩放/平移。
 *
 * <p>推送常量上限 128 字节，装不下 proj/view/model 三个矩阵。所有球体的模型矩阵都只是
 * 「绕 Y 旋转 × 等比缩放 × 平移」，于是推送 proj·view 与四个变换参数，在顶点着色器里
 * 重建世界坐标 —— 光照仍在世界空间算，晨昏线位置与 GLES 版一致。
 */
final class EarthVkDelegate extends VkPassRendererDelegate {

    private static final String TAG = "EarthVK";

    private static final String KEY_SPIN = "earth_spin_speed";
    private static final String KEY_CLOUDS = "earth_clouds";
    private static final String KEY_HALO = "earth_halo";
    private static final String KEY_SKY = "earth_sky";

    // ---- 与 EarthGL 相同的常量 ----
    private static final float FOV_DEG = 30.0f;
    private static final float NEAR = 0.01f;
    private static final float FAR = 1000.0f;
    private static final float SKY_SCALE = 500.0f;
    private static final float LIGHT_X = 0.0f;
    private static final float LIGHT_Y = 3.0f;
    private static final float LIGHT_Z = 10.0f;
    private static final float AMBIENT = 0.2f;
    private static final float NIGHT_GAIN = 3.0f;
    private static final float[] CLOSEUP_DELTA = {
        -49.0f / 255.0f, 11.0f / 255.0f, 38.0f / 255.0f
    };
    private static final float SPIN_MAX_DEG_PER_SEC = 30.0f;
    private static final float HALO_RADIUS_SCALE = 1.0f / 0.672f;
    private static final long CLOCK_REFRESH_MS = 60000L;
    private static final float TAP_SLOP_PX = 24.0f;
    private static final long TAP_MAX_MS = 400L;
    private static final long TAP_DEBOUNCE_MS = 350L;

    // ---- 顶点布局：x,y,z, nx,ny,nz, u,v ----
    private static final int ATTRS = EarthGlobe.FLOATS_PER_VERTEX; // 8
    private static final int STRIDE = ATTRS * 4;                   // 32
    private static final int[] GLOBE_ATTRIBS = {3, 3, 2};

    private static final int HALO_STRIDE = 16;
    private static final int[] HALO_ATTRIBS = {2, 2};
    private static final int HALO_VERTICES = 4;

    // ---- 推送常量 ----
    /** mat4 + angle/scale/tx/tz + 光源 3 + delta 3 + ambient/nightGain/useNight。 */
    private static final int PLANET_PUSH_FLOATS = 16 + 4 + 3 + 3 + 3;
    private static final int PLANET_PUSH_BYTES = PLANET_PUSH_FLOATS * 4; // 116
    /** mat4 + angle + scale。 */
    private static final int SKY_PUSH_FLOATS = 16 + 2;
    private static final int SKY_PUSH_BYTES = SKY_PUSH_FLOATS * 4;       // 72
    /** mat4。 */
    private static final int HALO_PUSH_FLOATS = 16;
    private static final int HALO_PUSH_BYTES = HALO_PUSH_FLOATS * 4;     // 64

    private static final String PLANET_VERT = "shaders/earth_planet_vk.vert.spv";
    private static final String PLANET_FRAG = "shaders/earth_planet_vk.frag.spv";
    private static final String SKY_VERT = "shaders/earth_sky_vk.vert.spv";
    private static final String SKY_FRAG = "shaders/earth_sky_vk.frag.spv";
    private static final String HALO_VERT = "shaders/earth_halo_vk.vert.spv";
    private static final String HALO_FRAG = "shaders/earth_halo_vk.frag.spv";

    private static final String GLOBE_BUFFER = "globe";
    private static final String HALO_BUFFER = "halo";

    private static final String TEX_DAY = "earthDay";
    private static final String TEX_NIGHT = "earthNight";
    private static final String TEX_CLOUDS = "clouds";
    private static final String TEX_MOON = "moon";
    private static final String TEX_SPECULAR = "specular";
    private static final String TEX_HALO = "halo";
    private static final String TEX_HALO_CLOSEUP = "haloCloseup";
    private static final String TEX_SKY = "sky";

    private static final String ASSET_DAY = "earth/drawable/earth_day.jpg";
    private static final String ASSET_NIGHT = "earth/drawable/earth_night.jpg";
    private static final String ASSET_CLOUDS = "earth/drawable/clouds.png";
    private static final String ASSET_MOON = "earth/drawable/moon.jpg";
    private static final String ASSET_SPECULAR = "earth/drawable/specular.png";
    private static final String ASSET_HALO = "earth/drawable/halo.png";
    private static final String ASSET_HALO_CLOSEUP = "earth/drawable/halo_closeup.png";
    private static final String ASSET_SKY = "earth/drawable/sky.jpg";

    private volatile SharedPreferences mPrefs;
    private volatile EarthScene mScene;
    private volatile VkPassScene mPassScene;

    private volatile int mWidth;
    private volatile int mHeight;

    // ---- 设置（与 EarthGL 一样每帧读取，设置页改动立即生效） ----
    private volatile int mPrefSpin = 20;
    private volatile boolean mPrefClouds = true;
    private volatile boolean mPrefHalo = true;
    private volatile boolean mPrefSky = true;

    // ---- pass ----
    private VkPass mSkyPass;
    private VkPass mEarthPass;
    private VkPass mCloudsPass;
    private VkPass mSpecularPass;
    private VkPass mMoonPass;
    private VkPass mHaloPass;
    private VkPass mHaloCloseupPass;

    // ---- 网格 ----
    private EarthGlobe mGlobe;
    private boolean mGlobeFailed;
    private boolean mGlobeUploaded;
    private ByteBuffer mGlobeExpanded;
    private int mGlobeVertexCount;

    // ---- 纹理 ----
    private boolean mTexturesUploaded;
    private boolean mHasDay;
    private boolean mHasNight;
    private boolean mHasClouds;
    private boolean mHasMoon;
    private boolean mHasSpecular;
    private boolean mHasSky;
    private boolean mHasHalo;
    private boolean mHasHaloCloseup;

    // ---- 每帧矩阵与推送数据 ----
    private final float[] mProj = new float[16];
    private final float[] mView = new float[16];
    private final float[] mSkyView = new float[16];
    private final float[] mGlViewProj = new float[16];
    private final float[] mVkViewProj = new float[16];
    private final float[] mGlSkyViewProj = new float[16];
    private final float[] mVkSkyViewProj = new float[16];
    private final float[] mOrtho = new float[16];
    private final float[] mVkOrtho = new float[16];

    private final float[] mPlanetPush = new float[PLANET_PUSH_FLOATS];
    private final float[] mSkyPush = new float[SKY_PUSH_FLOATS];
    private final float[] mHaloPush = new float[HALO_PUSH_FLOATS];

    private final ByteBuffer mHaloVertices =
            ByteBuffer.allocateDirect(HALO_VERTICES * HALO_STRIDE)
                    .order(ByteOrder.nativeOrder());

    // ---- 时钟与帧间隔 ----
    private long mLastFrameMs;
    private long mLastClockMs = -1L;

    // ---- 触摸 ----
    private boolean mDragging;
    private float mLastTouchX;
    private long mGestureStartMs;
    private float mGestureTravel;
    private long mLastTapHandledMs;

    EarthVkDelegate(Context context, SharedPreferences prefs) {
        super(context);
        mPrefs = prefs;
        loadPrefs();
    }

    @Override
    public String logTag() {
        return TAG;
    }

    @Override
    public Object getScene() {
        return mScene;
    }

    @Override
    public boolean isReady() {
        return mScene != null;
    }

    @Override
    public void ensureScene(int width, int height) {
        if (width <= 0 || height <= 0) return;
        mWidth = width;
        mHeight = height;
        if (mScene == null) {
            mScene = new EarthScene();
        }
    }

    @Override
    public void onPluginPrefsChanged(SharedPreferences prefs) {
        mPrefs = prefs;
        loadPrefs();
    }

    private void loadPrefs() {
        SharedPreferences prefs = mPrefs;
        if (prefs == null) return;
        mPrefSpin = prefs.getInt(KEY_SPIN, 20);
        mPrefClouds = prefs.getBoolean(KEY_CLOUDS, true);
        mPrefHalo = prefs.getBoolean(KEY_HALO, true);
        mPrefSky = prefs.getBoolean(KEY_SKY, true);
    }

    /**
     * native 销毁后句柄就是野指针，设置回调拿旧场景引用去碰 native 会崩 ——
     * 在基类动手之前先把引用清掉。
     */
    @Override
    public void destroyRenderer() {
        mPassScene = null;
        super.destroyRenderer();
    }

    // ─────────────────────────── 场景声明 ───────────────────────────

    @Override
    protected void buildScene(VkPassScene scene) {
        mPassScene = scene;
        mTexturesUploaded = false;
        mGlobeUploaded = false;
        scene.setClearColor(0.0f, 0.0f, 0.0f, 1.0f);

        loadGlobe();
        int globeCapacity = mGlobeVertexCount > 0 ? mGlobeVertexCount : 1;
        scene.createBuffer(GLOBE_BUFFER, STRIDE, globeCapacity, true);
        scene.createBuffer(HALO_BUFFER, HALO_STRIDE, HALO_VERTICES, true);

        String[] textures = {
            TEX_DAY, TEX_NIGHT, TEX_CLOUDS, TEX_MOON,
            TEX_SPECULAR, TEX_HALO, TEX_HALO_CLOSEUP, TEX_SKY
        };
        for (String name : textures) scene.createTexture(name);

        // ① 星空：不测深度、不写深度、不混合，后面的球体才能盖住它。
        mSkyPass = addPass(scene, SKY_VERT, SKY_FRAG, GLOBE_ATTRIBS, STRIDE,
                VkPass.BLEND_NONE, SKY_PUSH_BYTES, false, false, TEX_SKY);

        // ② 地球：不透明，测深度 + 写深度。
        mEarthPass = addPass(scene, PLANET_VERT, PLANET_FRAG, GLOBE_ATTRIBS, STRIDE,
                VkPass.BLEND_NONE, PLANET_PUSH_BYTES, true, true, TEX_DAY, TEX_NIGHT);

        // ③ 云层 / 高光：glBlendFunc(SRC_ALPHA, ONE)，测深度但不写深度。
        mCloudsPass = addAdditivePass(scene, GLOBE_ATTRIBS, STRIDE, PLANET_PUSH_BYTES,
                true, false, TEX_CLOUDS, TEX_CLOUDS);
        mSpecularPass = addAdditivePass(scene, GLOBE_ATTRIBS, STRIDE, PLANET_PUSH_BYTES,
                true, false, TEX_SPECULAR, TEX_SPECULAR);

        // ④ 月球：不透明，测深度 + 写深度。
        mMoonPass = addPass(scene, PLANET_VERT, PLANET_FRAG, GLOBE_ATTRIBS, STRIDE,
                VkPass.BLEND_NONE, PLANET_PUSH_BYTES, true, true, TEX_MOON, TEX_MOON);

        // ⑤ 光晕：屏幕空间四边形，加色，不测深度。远近两份贴图各一个 pass，
        //    每帧把不用的那个顶点数置 0 —— pass 的纹理绑定是静态的，不能逐帧换。
        mHaloPass = addHaloPass(scene, TEX_HALO);
        mHaloCloseupPass = addHaloPass(scene, TEX_HALO_CLOSEUP);
    }

    private static VkPass addHaloPass(VkPassScene scene, String texture) {
        VkPass pass = new VkPass(
                        HALO_VERT,
                        HALO_FRAG,
                        HALO_ATTRIBS,
                        HALO_STRIDE,
                        VkPass.TOPOLOGY_TRIANGLE_FAN,
                        VkPass.BLEND_ADDITIVE)
                .buffer(HALO_BUFFER, 0)
                .pushConstants(HALO_PUSH_BYTES)
                .depthTest(false)
                .depthWrite(false)
                .blendFactors(
                        VkPass.FACTOR_SRC_ALPHA, VkPass.FACTOR_ONE,
                        VkPass.FACTOR_SRC_ALPHA, VkPass.FACTOR_ONE)
                .texture(0, texture);
        scene.add(pass);
        return pass;
    }

    private static VkPass addPass(
            VkPassScene scene,
            String vert,
            String frag,
            int[] attribSizes,
            int stride,
            int blendMode,
            int pushBytes,
            boolean depthTest,
            boolean depthWrite,
            String... textures) {
        VkPass pass = new VkPass(
                        vert, frag, attribSizes, stride, VkPass.TOPOLOGY_TRIANGLE_LIST,
                        blendMode)
                .buffer(GLOBE_BUFFER, 0)
                .pushConstants(pushBytes)
                .depthTest(depthTest)
                .depthWrite(depthWrite);
        for (int i = 0; i < textures.length; i++) pass.texture(i, textures[i]);
        scene.add(pass);
        return pass;
    }

    /** 加色 pass：与 GLES 的 glBlendFunc(SRC_ALPHA, ONE) 一致（颜色和 alpha 两路都覆盖）。 */
    private static VkPass addAdditivePass(
            VkPassScene scene,
            int[] attribSizes,
            int stride,
            int pushBytes,
            boolean depthTest,
            boolean depthWrite,
            String... textures) {
        VkPass pass = new VkPass(
                        PLANET_VERT,
                        PLANET_FRAG,
                        attribSizes,
                        stride,
                        VkPass.TOPOLOGY_TRIANGLE_LIST,
                        VkPass.BLEND_ADDITIVE)
                .buffer(GLOBE_BUFFER, 0)
                .pushConstants(pushBytes)
                .depthTest(depthTest)
                .depthWrite(depthWrite)
                .blendFactors(
                        VkPass.FACTOR_SRC_ALPHA, VkPass.FACTOR_ONE,
                        VkPass.FACTOR_SRC_ALPHA, VkPass.FACTOR_ONE);
        for (int i = 0; i < textures.length; i++) pass.texture(i, textures[i]);
        scene.add(pass);
        return pass;
    }

    private void loadGlobe() {
        if (mGlobeFailed) return;
        if (mGlobe == null) {
            try (InputStream in = context.getAssets().open("earth/data/globe.obj")) {
                mGlobe = EarthGlobe.load(in);
            } catch (Exception e) {
                Log.e(TAG, "球体网格加载失败，本次只渲染光晕", e);
                mGlobeFailed = true;
                return;
            }
        }
        if (mGlobeExpanded != null) return;

        short[] indices = mGlobe.indices;
        float[] vertices = mGlobe.vertices;
        mGlobeVertexCount = indices.length;
        mGlobeExpanded =
                ByteBuffer.allocateDirect(mGlobeVertexCount * STRIDE)
                        .order(ByteOrder.nativeOrder());
        for (int i = 0; i < indices.length; i++) {
            int base = (indices[i] & 0xFFFF) * ATTRS;
            for (int a = 0; a < ATTRS; a++) {
                mGlobeExpanded.putFloat(vertices[base + a]);
            }
        }
        mGlobeExpanded.position(0);
    }

    // ─────────────────────────── 每帧 ───────────────────────────

    @Override
    protected void updateFrame(VkPassFrame frame, long timeMs) {
        EarthScene scene = mScene;
        if (scene == null || mWidth <= 0 || mHeight <= 0 || mPassScene == null) {
            zeroAll(frame);
            return;
        }

        ensureTextures();
        uploadGlobeOnce(frame);

        float dt = 0.0f;
        if (mLastFrameMs != 0L) {
            dt = (timeMs - mLastFrameMs) / 1000.0f;
            if (dt < 0.0f) dt = 0.0f;
            if (dt > 0.1f) dt = 0.1f;
        }
        mLastFrameMs = timeMs;

        refreshClock(scene);
        scene.setSpinDegPerSec(mPrefSpin / 100.0f * SPIN_MAX_DEG_PER_SEC);
        scene.update(dt);

        buildMatrices(scene);

        EarthCamera cam = scene.activeCamera();
        boolean closeup = cam.id == EarthScene.CAMERA_CLOSEUP;
        boolean globeReady = !mGlobeFailed && mGlobeVertexCount > 0;

        drawSky(frame, scene, globeReady);
        drawEarth(frame, scene, closeup, globeReady);
        drawClouds(frame, scene, globeReady);
        drawSpecular(frame, closeup, globeReady);
        drawMoon(frame, scene, globeReady);
        drawHalo(frame, closeup);
    }

    private void uploadGlobeOnce(VkPassFrame frame) {
        if (mGlobeUploaded) return;
        mGlobeUploaded = true;
        if (mGlobeFailed || mGlobeExpanded == null) return;
        frame.upload(GLOBE_BUFFER, mGlobeExpanded, mGlobeVertexCount * STRIDE);
    }

    private void drawSky(VkPassFrame frame, EarthScene scene, boolean globeReady) {
        if (!globeReady || !mPrefSky || !mHasSky) {
            frame.setVertexCount(mSkyPass, 0);
            return;
        }
        System.arraycopy(mVkSkyViewProj, 0, mSkyPush, 0, 16);
        mSkyPush[16] = (float) Math.toRadians(scene.skyAngleY());
        mSkyPush[17] = SKY_SCALE;
        frame.pushConstants(mSkyPass, mSkyPush);
        frame.setVertexCount(mSkyPass, mGlobeVertexCount);
    }

    private void drawEarth(
            VkPassFrame frame, EarthScene scene, boolean closeup, boolean globeReady) {
        if (!globeReady || !mHasDay || !mHasNight) {
            frame.setVertexCount(mEarthPass, 0);
            return;
        }
        fillPlanetPush(
                mVkViewProj,
                scene.earthAngleY(),
                EarthScene.EARTH_SCALE,
                0.0f,
                0.0f,
                closeup ? CLOSEUP_DELTA : null,
                true);
        frame.pushConstants(mEarthPass, mPlanetPush);
        frame.setVertexCount(mEarthPass, mGlobeVertexCount);
    }

    private void drawClouds(VkPassFrame frame, EarthScene scene, boolean globeReady) {
        if (!globeReady || !mPrefClouds || !mHasClouds) {
            frame.setVertexCount(mCloudsPass, 0);
            return;
        }
        fillPlanetPush(
                mVkViewProj,
                scene.cloudAngleY(),
                EarthScene.CLOUDS_SCALE,
                0.0f,
                0.0f,
                null,
                false);
        frame.pushConstants(mCloudsPass, mPlanetPush);
        frame.setVertexCount(mCloudsPass, mGlobeVertexCount);
    }

    private void drawSpecular(VkPassFrame frame, boolean closeup, boolean globeReady) {
        if (!globeReady || closeup || !mHasSpecular) {
            frame.setVertexCount(mSpecularPass, 0);
            return;
        }
        fillPlanetPush(
                mVkViewProj,
                EarthScene.SPECULAR_ANGLE_Y,
                EarthScene.SPECULAR_SCALE,
                0.0f,
                0.0f,
                null,
                false);
        frame.pushConstants(mSpecularPass, mPlanetPush);
        frame.setVertexCount(mSpecularPass, mGlobeVertexCount);
    }

    private void drawMoon(VkPassFrame frame, EarthScene scene, boolean globeReady) {
        if (!globeReady || !mHasMoon) {
            frame.setVertexCount(mMoonPass, 0);
            return;
        }
        fillPlanetPush(
                mVkViewProj,
                scene.moonAngleY(),
                EarthScene.MOON_SCALE,
                scene.moonX(),
                scene.moonZ(),
                null,
                false);
        frame.pushConstants(mMoonPass, mPlanetPush);
        frame.setVertexCount(mMoonPass, mGlobeVertexCount);
    }

    /**
     * 光晕：与 {@link EarthGL#drawHalo} 相同的屏幕坐标与半径计算。
     * 远近两份贴图各一个 pass，不用的那个顶点数置 0。
     */
    private void drawHalo(VkPassFrame frame, boolean closeup) {
        // 与 GLES 的 drawHalo 相同：近景需要 closeup 贴图，缺了就不画（不退回远景贴图）。
        boolean draw = mPrefHalo && mHasHalo
                && (!closeup || mHasHaloCloseup);
        if (!draw || !buildHaloGeometry()) {
            frame.setVertexCount(mHaloPass, 0);
            frame.setVertexCount(mHaloCloseupPass, 0);
            return;
        }

        frame.upload(HALO_BUFFER, mHaloVertices, HALO_VERTICES * HALO_STRIDE);
        System.arraycopy(mVkOrtho, 0, mHaloPush, 0, 16);

        VkPass pass = closeup ? mHaloCloseupPass : mHaloPass;
        VkPass other = closeup ? mHaloPass : mHaloCloseupPass;
        frame.pushConstants(pass, mHaloPush);
        frame.setVertexCount(pass, HALO_VERTICES);
        frame.setVertexCount(other, 0);
    }

    /** 返回 false 表示这一帧没有可画的光晕（相机贴到地心或半径非正）。 */
    private boolean buildHaloGeometry() {
        // 视图矩阵只含旋转与平移，地心在视图空间的位置就是平移列
        float vx = mView[12];
        float vy = mView[13];
        float vz = mView[14];
        float d = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (d < 1.0E-3f) return false;

        // 世界原点的裁剪坐标 → 屏幕像素
        float cx = mProj[0] * vx + mProj[4] * vy + mProj[8] * vz + mProj[12];
        float cy = mProj[1] * vx + mProj[5] * vy + mProj[9] * vz + mProj[13];
        float cw = mProj[3] * vx + mProj[7] * vy + mProj[11] * vz + mProj[15];
        if (cw <= 0.0f) return false;
        float sx = (cx / cw * 0.5f + 0.5f) * mWidth;
        float sy = (0.5f - cy / cw * 0.5f) * mHeight;

        // 球体的角半径 → 屏幕半径。mProj[5] = 1/tan(垂直半视场角)
        float worldR = EarthScene.EARTH_SCALE * EarthGlobe.NORMALIZED_RADIUS;
        float angR = (float) Math.asin(Math.min(1.0f, worldR / d));
        float screenR = (mHeight * 0.5f) * mProj[5] * (float) Math.tan(angR)
                * HALO_RADIUS_SCALE;
        if (screenR <= 0.0f) return false;

        mHaloVertices.clear();
        mHaloVertices.putFloat(sx - screenR).putFloat(sy - screenR).putFloat(0.0f).putFloat(0.0f);
        mHaloVertices.putFloat(sx - screenR).putFloat(sy + screenR).putFloat(0.0f).putFloat(1.0f);
        mHaloVertices.putFloat(sx + screenR).putFloat(sy + screenR).putFloat(1.0f).putFloat(1.0f);
        mHaloVertices.putFloat(sx + screenR).putFloat(sy - screenR).putFloat(1.0f).putFloat(0.0f);
        mHaloVertices.position(0);
        return true;
    }

    private void fillPlanetPush(
            float[] viewProj,
            float angleDeg,
            float scale,
            float tx,
            float tz,
            float[] delta,
            boolean useNight) {
        System.arraycopy(viewProj, 0, mPlanetPush, 0, 16);
        mPlanetPush[16] = (float) Math.toRadians(angleDeg);
        mPlanetPush[17] = scale;
        mPlanetPush[18] = tx;
        mPlanetPush[19] = tz;
        mPlanetPush[20] = LIGHT_X;
        mPlanetPush[21] = LIGHT_Y;
        mPlanetPush[22] = LIGHT_Z;
        mPlanetPush[23] = delta != null ? delta[0] : 0.0f;
        mPlanetPush[24] = delta != null ? delta[1] : 0.0f;
        mPlanetPush[25] = delta != null ? delta[2] : 0.0f;
        mPlanetPush[26] = AMBIENT;
        mPlanetPush[27] = NIGHT_GAIN;
        mPlanetPush[28] = useNight ? 1.0f : 0.0f;
    }

    private void buildMatrices(EarthScene scene) {
        float size = NEAR * (float) Math.tan(Math.toRadians(FOV_DEG / 2.0));
        float ratio = mHeight > 0 ? (float) mWidth / mHeight : 1.0f;
        Mat4.frustumM(mProj, -size, size, -size / ratio, size / ratio, NEAR, FAR);

        EarthCamera cam = scene.activeCamera();
        EarthView.buildCamera(mView, cam);
        EarthView.buildSky(mSkyView, cam);

        // GL clip → Vulkan clip：y 取反，z 从 [-w, w] 映射到 [0, w]。
        Mat4.multiplyMM(mGlViewProj, mProj, mView);
        Mat4.multiplyMM(mVkViewProj, CLIP_CORRECTION, mGlViewProj);
        Mat4.multiplyMM(mGlSkyViewProj, mProj, mSkyView);
        Mat4.multiplyMM(mVkSkyViewProj, CLIP_CORRECTION, mGlSkyViewProj);

        Mat4.orthoM(mOrtho, 0.0f, mWidth, mHeight, 0.0f, -1.0f, 1.0f);
        Mat4.multiplyMM(mVkOrtho, CLIP_CORRECTION, mOrtho);
    }

    private void zeroAll(VkPassFrame frame) {
        for (VkPass pass : new VkPass[] {
                mSkyPass, mEarthPass, mCloudsPass, mSpecularPass,
                mMoonPass, mHaloPass, mHaloCloseupPass}) {
            if (pass != null) frame.setVertexCount(pass, 0);
        }
    }

    // ─────────────────────────── 时钟 ───────────────────────────

    /** 每分钟从 Calendar 取一次时间；与 GLES 版的取法与换算完全相同。 */
    private void refreshClock(EarthScene scene) {
        long now = System.currentTimeMillis();
        if (mLastClockMs >= 0 && now - mLastClockMs < CLOCK_REFRESH_MS) return;
        mLastClockMs = now;

        Calendar cal = Calendar.getInstance();
        TimeZone tz = cal.getTimeZone();
        long msSinceNoon = (((cal.get(Calendar.HOUR_OF_DAY) - 12L) * 3600L
                + cal.get(Calendar.MINUTE) * 60L
                + cal.get(Calendar.SECOND)) * 1000L)
                + cal.get(Calendar.MILLISECOND);
        double daysSinceEpoch = System.currentTimeMillis() / 86400000.0;
        scene.setClock(msSinceNoon, tz.getRawOffset(), cal.get(Calendar.DAY_OF_YEAR),
                daysSinceEpoch);
    }

    // ─────────────────────────── 纹理 ───────────────────────────

    private void ensureTextures() {
        if (mTexturesUploaded || mPassScene == null) return;
        mTexturesUploaded = true;
        if (!mGlobeFailed) {
            mHasDay = uploadTexture(TEX_DAY, ASSET_DAY);
            mHasNight = uploadTexture(TEX_NIGHT, ASSET_NIGHT);
            mHasClouds = uploadTexture(TEX_CLOUDS, ASSET_CLOUDS);
            mHasMoon = uploadTexture(TEX_MOON, ASSET_MOON);
            mHasSpecular = uploadTexture(TEX_SPECULAR, ASSET_SPECULAR);
        }
        mHasHalo = uploadTexture(TEX_HALO, ASSET_HALO);
        mHasHaloCloseup = uploadTexture(TEX_HALO_CLOSEUP, ASSET_HALO_CLOSEUP);
        mHasSky = uploadTexture(TEX_SKY, ASSET_SKY);
    }

    private boolean uploadTexture(String name, String asset) {
        Bitmap bitmap;
        try {
            bitmap = AssetLoader.decodeBitmap(context, asset);
        } catch (RuntimeException e) {
            Log.e(TAG, "decode failed: " + asset, e);
            return false;
        }
        if (bitmap == null) {
            Log.w(TAG, "decode returned null: " + asset);
            return false;
        }

        Bitmap argb = bitmap.getConfig() == Bitmap.Config.ARGB_8888
                ? bitmap
                : bitmap.copy(Bitmap.Config.ARGB_8888, false);
        if (argb != bitmap) bitmap.recycle();
        int width = argb.getWidth();
        int height = argb.getHeight();
        int[] pixels = new int[width * height];
        argb.getPixels(pixels, 0, width, 0, 0, width, height);
        argb.recycle();
        mPassScene.setTexture(name, pixels, width, height);
        return true;
    }

    // ─────────────────────────── 触摸 ───────────────────────────

    @Override
    public void onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                beginGesture(event.getX(), event.getEventTime());
                break;
            case MotionEvent.ACTION_MOVE:
                if (!mDragging) {
                    // launcher 可能吞掉 DOWN，把首个 MOVE 当起点，避免跳变
                    beginGesture(event.getX(), event.getEventTime());
                } else {
                    float dx = event.getX() - mLastTouchX;
                    mLastTouchX = event.getX();
                    mGestureTravel += Math.abs(dx);
                    EarthScene scene = mScene;
                    if (scene != null) scene.drag(dx);
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
    public void onCommand(String action, int x, int y, int z, Bundle extras) {
        if ("android.wallpaper.tap".equals(action)) handleTap();
    }

    private void beginGesture(float x, long eventTimeMs) {
        mDragging = true;
        mLastTouchX = x;
        mGestureStartMs = eventTimeMs;
        mGestureTravel = 0.0f;
    }

    private void handleTap() {
        long now = SystemClock.uptimeMillis();
        if (now - mLastTapHandledMs < TAP_DEBOUNCE_MS) return; // onTouchEvent 与 onCommand 去重
        mLastTapHandledMs = now;
        EarthScene scene = mScene;
        if (scene != null) scene.onTap();
    }

    /**
     * GL clip → Vulkan clip：y 取反，z 从 [-w, w] 映射到 [0, w]。
     * 与其余 VK delegate 使用同一份矩阵。
     */
    private static final float[] CLIP_CORRECTION = {
        1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 0.5f, 0, 0, 0, 0.5f, 1,
    };
}
