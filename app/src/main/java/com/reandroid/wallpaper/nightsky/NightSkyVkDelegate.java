package com.reandroid.wallpaper.nightsky;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.opengl.Matrix;
import android.util.Log;
import android.view.MotionEvent;

import com.reandroid.plugin.VkPass;
import com.reandroid.plugin.VkPassFrame;
import com.reandroid.plugin.VkPassRendererDelegate;
import com.reandroid.plugin.VkPassScene;
import com.reandroid.utils.AssetLoader;
import com.reandroid.utils.MathUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * NightSky 的 Vulkan 渲染器 —— 壁纸与预览共用。
 *
 * <p>三个 pass，顺序与 GLES 版逐字对应：
 * <ol>
 *   <li>银河球：UV 球面网格 + 银河贴图，不混合；</li>
 *   <li>星点：{@code TOPOLOGY_POINT_LIST} + {@code gl_PointSize}，9000 颗星一次 draw，
 *       ONE/ONE 相加；</li>
 *   <li>星轨：CPU 生成的 NDC 三角形条带（与 GLES 共用
 *       {@link NightSkyTrailBuilder}），ONE/ONE 相加。</li>
 * </ol>
 *
 * <p>星星位置用 {@code uProj × uViewRot} 预乘矩阵一次变换（viewRot 是仿射的，合并等价），
 * 裁剪空间转换在着色器里做（y 取反、z 映射到 [0, w]）。
 *
 * <p>陀螺仪/触摸/滚动都在引擎侧：这里把 {@link NightSkyScene} 的传感器控制器、
 * 触摸时间控制器与滚动页数照 GLES 版的调用次序驱动。
 */
final class NightSkyVkDelegate extends VkPassRendererDelegate {

    private static final String TAG = "NightSkyVK";

    private static final int SPHERE_LON_STEPS = 96;
    private static final int SPHERE_LAT_STEPS = 48;

    private static final int SPHERE_FLOATS_PER_VERTEX = 2;
    private static final int SPHERE_STRIDE = SPHERE_FLOATS_PER_VERTEX * 4;

    private static final int STAR_FLOATS_PER_VERTEX = 7; // 4 params + 3 colors
    private static final int STAR_STRIDE = STAR_FLOATS_PER_VERTEX * 4;
    private static final int STAR_PARAMS = 4;
    private static final int STAR_COLORS = 3;

    private static final int TRAIL_STRIDE = NightSkyTrailBuilder.STRIDE_BYTES;

    /** mat4 + uLatitudeRad + uLSTRad + uMilkyBrightness。 */
    private static final int SPHERE_PUSH_FLOATS = 16 + 3;

    private static final int SPHERE_PUSH_BYTES = SPHERE_PUSH_FLOATS * 4;

    /** mat4 + uLatitudeRad + uLSTRad + uTimeSec + uTrailMode。 */
    private static final int STAR_PUSH_FLOATS = 16 + 4;

    private static final int STAR_PUSH_BYTES = STAR_PUSH_FLOATS * 4;

    /** 与 NightSkyGL 相同的星轨回溯上限与加速时的长度增益。 */
    private static final long TRAIL_LOOKBACK_MAX_MS = 90L * 60L * 1000L;

    private static final float TRAIL_SPEED_LENGTH_BOOST_MAX = 4.0f;

    private static final String SPHERE_VERT = "shaders/nightsky_vk_sphere.vert.spv";
    private static final String SPHERE_FRAG = "shaders/nightsky_vk_sphere.frag.spv";
    private static final String STAR_VERT = "shaders/nightsky_vk_star.vert.spv";
    private static final String STAR_FRAG = "shaders/nightsky_vk_star.frag.spv";
    private static final String TRAIL_VERT = "shaders/nightsky_vk_trail.vert.spv";
    private static final String TRAIL_FRAG = "shaders/nightsky_vk_trail.frag.spv";

    private static final String MILKY_ASSET = "nightsky/drawable/nightsky_milkyway.jpg";

    /** 场景与传感器会被主线程（设置/触摸/可见性）和渲染线程同时访问。 */
    private final Object mLock = new Object();

    private SharedPreferences mPrefs;
    private volatile NightSkyScene mScene;
    private VkPassScene mPassScene;

    private VkPass mSpherePass;
    private VkPass mStarPass;
    private VkPass mTrailPass;

    private ByteBuffer mSphereBuffer;
    private ByteBuffer mStarBuffer;
    private ByteBuffer mTrailBuffer;
    private boolean mStaticUploaded;

    private int mStarCount;
    private int mSphereVertexCount;

    private int mWidth;
    private int mHeight;
    /** 最近一次 onOffsetsChanged 的像素偏移；{@link Integer#MIN_VALUE} = 还没收到。 */
    private int mScrollPixels = Integer.MIN_VALUE;

    private volatile boolean mVisible = true;

    private final NightSkyTrailBuilder mTrailBuilder = new NightSkyTrailBuilder();
    private final float[] mProjViewRot = new float[16];
    private final float[] mSpherePush = new float[SPHERE_PUSH_FLOATS];
    private final float[] mStarPush = new float[STAR_PUSH_FLOATS];

    NightSkyVkDelegate(Context context, SharedPreferences prefs) {
        super(context);
        mPrefs = prefs;
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
        synchronized (mLock) {
            ensureSceneLocked(width, height);
        }
    }

    private void ensureSceneLocked(int width, int height) {
        if (width > 0 && height > 0) {
            mWidth = width;
            mHeight = height;
        }
        if (mScene == null) {
            NightSkyScene scene = new NightSkyScene();
            scene.setPluginPrefs(mPrefs);
            scene.init(context);
            scene.buildSphereGrid(SPHERE_LON_STEPS, SPHERE_LAT_STEPS);
            mScene = scene;
        } else if (mPrefs != null) {
            mScene.setPluginPrefs(mPrefs);
        }
        if (mWidth > 0 && mHeight > 0) {
            mScene.updateProjectionMatrix(mWidth, mHeight);
        }
        if (mScrollPixels != Integer.MIN_VALUE && mWidth > 0) {
            mScene.setScroll(mScrollPixels, mWidth);
        }
        // 场景可能是在不可见时建的；保证可见时传感器是开的。
        applySensorStateLocked(mVisible);
    }

    @Override
    public void onPluginPrefsChanged(SharedPreferences prefs) {
        mPrefs = prefs;
        synchronized (mLock) {
            if (mScene != null) mScene.setPluginPrefs(prefs);
        }
    }

    @Override
    public void onSceneOffset(float xOffset, int xPixels) {
        synchronized (mLock) {
            mScrollPixels = xPixels;
            if (mScene != null && mWidth > 0) {
                mScene.setScroll(xPixels, mWidth);
            }
        }
    }

    @Override
    public void onVisibilityChanged(boolean visible) {
        mVisible = visible;
        synchronized (mLock) {
            if (visible && mScene != null) {
                // 与 NightSkyGL.start() 相同：恢复时重新读一次设置与定位。
                mScene.refreshRuntimeSettingsIfNeeded(System.currentTimeMillis(), mWidth, mHeight);
                mScene.updateLocation();
            }
            applySensorStateLocked(visible);
        }
    }

    @Override
    public void onTouchEvent(MotionEvent event) {
        synchronized (mLock) {
            if (mScene != null) mScene.touchTimeController.onTouchEvent(event);
        }
    }

    /**
     * native 销毁后句柄就是野指针，需要在基类动手之前把场景引用与传感器清掉，
     * 避免设置回调或可见性回调拿旧句柄去调用。
     */
    @Override
    public void destroyRenderer() {
        synchronized (mLock) {
            if (mScene != null) mScene.sensorController.stop();
            mPassScene = null;
            mStaticUploaded = false;
            mTrailBuilder.reset();
        }
        super.destroyRenderer();
    }

    // ─────────────────────────── 场景声明 ───────────────────────────

    @Override
    protected void buildScene(VkPassScene scene) {
        synchronized (mLock) {
            if (mScene == null) {
                ensureSceneLocked(mWidth, mHeight);
            }
            mPassScene = scene;
            mStaticUploaded = false;
            mTrailBuilder.reset();

            NightSkyCatalog catalog = mScene.catalog;
            mStarCount = catalog != null ? catalog.starCount : 0;
            mSphereVertexCount = mScene.sphereVertexCount;

            // 与 GLES 的 glClearColor(0.015,0.02,0.03,1) 一致。
            scene.setClearColor(0.015f, 0.02f, 0.03f, 1.0f);

            scene.createBuffer("sphere", SPHERE_STRIDE, mSphereVertexCount, true);
            scene.createBuffer("stars", STAR_STRIDE, mStarCount, true);
            int trailMax = NightSkyTrailBuilder.maxVertexCount(mStarCount);
            scene.createBuffer("trail", TRAIL_STRIDE, trailMax, true);
            scene.createTexture("milky");
            uploadMilkyTextureLocked(scene);

            mSpherePass = scene.add(new VkPass(
                            SPHERE_VERT,
                            SPHERE_FRAG,
                            new int[] {SPHERE_FLOATS_PER_VERTEX},
                            SPHERE_STRIDE,
                            VkPass.TOPOLOGY_TRIANGLE_LIST,
                            VkPass.BLEND_NONE))
                    .buffer("sphere", 0)
                    .pushConstants(SPHERE_PUSH_BYTES);
            mSpherePass.texture(0, "milky");

            mStarPass = scene.add(new VkPass(
                            STAR_VERT,
                            STAR_FRAG,
                            new int[] {STAR_PARAMS, STAR_COLORS},
                            STAR_STRIDE,
                            VkPass.TOPOLOGY_POINT_LIST,
                            VkPass.BLEND_ADDITIVE))
                    .buffer("stars", 0)
                    .pushConstants(STAR_PUSH_BYTES)
                    // GLES 是 glBlendFunc(GL_ONE, GL_ONE)；片元输出预乘颜色。
                    .blendFactors(
                            VkPass.FACTOR_ONE,
                            VkPass.FACTOR_ONE,
                            VkPass.FACTOR_ONE,
                            VkPass.FACTOR_ONE);

            mTrailPass = scene.add(new VkPass(
                            TRAIL_VERT,
                            TRAIL_FRAG,
                            new int[] {2, 4},
                            TRAIL_STRIDE,
                            VkPass.TOPOLOGY_TRIANGLE_LIST,
                            VkPass.BLEND_ADDITIVE))
                    .buffer("trail", 0)
                    .blendFactors(
                            VkPass.FACTOR_ONE,
                            VkPass.FACTOR_ONE,
                            VkPass.FACTOR_ONE,
                            VkPass.FACTOR_ONE);

            mSphereBuffer = copyToDirect(mScene.sphereUvBuffer);
            mStarBuffer = buildStarBuffer(catalog);
            mTrailBuffer = trailMax > 0
                    ? ByteBuffer.allocateDirect(trailMax * TRAIL_STRIDE)
                            .order(ByteOrder.nativeOrder())
                    : null;
        }
    }

    // ─────────────────────────── 每帧 ───────────────────────────

    @Override
    protected void updateFrame(VkPassFrame frame, long timeMs) {
        synchronized (mLock) {
            NightSkyScene scene = mScene;
            if (scene == null) {
                zeroPasses(frame);
                return;
            }

            // GLES 的 drawFrame 时间线用墙钟；这里同样用墙钟，避免 uptime 混入。
            long nowWallMs = System.currentTimeMillis();
            scene.touchTimeController.advance();
            scene.refreshRuntimeSettingsIfNeeded(nowWallMs, mWidth, mHeight);
            applySensorStateLocked(mVisible);
            updateViewRotationLocked(scene);

            if (!mStaticUploaded) {
                uploadStaticBuffersLocked(frame);
            }

            float latRad = (float) (scene.latitudeDeg * NightSkyMath.DEG_TO_RAD);
            long astronomyMs = scene.touchTimeController.getAstronomyTimeMs();
            float lstRad = (float)
                    NightSkyMath.computeLocalSiderealTimeRad(astronomyMs, scene.longitudeDeg);
            float timeSec = astronomyMs / 1000.0f;

            // uProj × uViewRot：viewRot 是仿射的（w=1），合并后与分两步完全等价。
            Matrix.multiplyMM(mProjViewRot, 0, scene.proj, 0, scene.currentViewRot, 0);

            System.arraycopy(mProjViewRot, 0, mSpherePush, 0, 16);
            mSpherePush[16] = latRad;
            mSpherePush[17] = lstRad;
            mSpherePush[18] = NightSkyScene.MILKY_BRIGHTNESS;
            frame.pushConstants(mSpherePass, mSpherePush);
            frame.setVertexCount(mSpherePass, mSphereVertexCount);

            System.arraycopy(mProjViewRot, 0, mStarPush, 0, 16);
            mStarPush[16] = latRad;
            mStarPush[17] = lstRad;
            mStarPush[18] = timeSec;
            mStarPush[19] = 0.0f;
            frame.pushConstants(mStarPass, mStarPush);
            frame.setVertexCount(mStarPass, mStarCount);

            long baseTrailLookbackMs = scene.touchTimeController.getAcceleratingSimElapsedMs() / 6;
            float speedScale = scene.touchTimeController.getCurrentTimeScale();
            float speedNorm = MathUtils.clamp(
                    (speedScale - NightSkyScene.MIN_TIME_ACCEL_SPEED)
                            / (NightSkyScene.MAX_TIME_ACCEL_SPEED
                                    - NightSkyScene.MIN_TIME_ACCEL_SPEED),
                    0.0f,
                    1.0f);
            float trailLengthBoost = 1.0f + (TRAIL_SPEED_LENGTH_BOOST_MAX - 1.0f) * speedNorm;
            boolean accelerating = scene.touchTimeController.isAccelerating();
            if (!accelerating) {
                trailLengthBoost = 1.0f;
            }
            long trailLookbackMs = Math.min(
                    (long) (baseTrailLookbackMs * trailLengthBoost), TRAIL_LOOKBACK_MAX_MS);

            boolean trailChanged = mTrailBuilder.update(
                    scene.catalog,
                    latRad,
                    scene.longitudeDeg,
                    astronomyMs,
                    nowWallMs,
                    scene.currentViewRot,
                    scene.proj,
                    mWidth,
                    mHeight,
                    trailLookbackMs,
                    accelerating);
            if (trailChanged && mTrailBuilder.vertexCount > 0 && mTrailBuffer != null) {
                int floats = mTrailBuilder.vertexCount * NightSkyTrailBuilder.FLOATS_PER_VERTEX;
                FloatBuffer fb = mTrailBuffer.asFloatBuffer();
                fb.clear();
                fb.put(mTrailBuilder.data, 0, floats);
                fb.position(0);
                frame.upload("trail", mTrailBuffer, floats * 4);
            }
            frame.setVertexCount(mTrailPass, mTrailBuilder.vertexCount);
        }
    }

    private void updateViewRotationLocked(NightSkyScene scene) {
        if (scene.sensorEnabled) {
            scene.sensorController.setDisplayRotation(NightSkyScene.getDisplayRotation(context));
            scene.sensorController.copyViewMatrix(scene.currentViewRot);
        } else {
            Matrix.setIdentityM(scene.currentViewRot, 0);
            Matrix.rotateM(scene.currentViewRot, 0, 90.0f, 1.0f, 0.0f, 0.0f);
            Matrix.rotateM(
                    scene.currentViewRot,
                    0,
                    scene.scrollPages * NightSkyScene.PAGE_ROTATION_DEG,
                    0.0f,
                    0.0f,
                    1.0f);
        }
    }

    private void uploadStaticBuffersLocked(VkPassFrame frame) {
        mStaticUploaded = true;
        if (mSphereBuffer != null && mSphereVertexCount > 0) {
            frame.upload("sphere", mSphereBuffer, mSphereVertexCount * SPHERE_STRIDE);
        }
        if (mStarBuffer != null && mStarCount > 0) {
            frame.upload("stars", mStarBuffer, mStarCount * STAR_STRIDE);
        }
    }

    private void zeroPasses(VkPassFrame frame) {
        if (mSpherePass != null) frame.setVertexCount(mSpherePass, 0);
        if (mStarPass != null) frame.setVertexCount(mStarPass, 0);
        if (mTrailPass != null) frame.setVertexCount(mTrailPass, 0);
    }

    private void applySensorStateLocked(boolean visible) {
        NightSkyScene scene = mScene;
        if (scene == null) return;
        boolean want = visible && scene.sensorEnabled;
        NightSkySensorController controller = scene.sensorController;
        if (want && !controller.isStarted()) {
            controller.start();
        } else if (!want && controller.isStarted()) {
            controller.stop();
        }
    }

    // ─────────────────────────── 资源 ───────────────────────────

    private void uploadMilkyTextureLocked(VkPassScene scene) {
        Bitmap bitmap;
        try {
            bitmap = AssetLoader.decodeBitmap(context, MILKY_ASSET);
        } catch (RuntimeException e) {
            Log.e(TAG, "decode failed: " + MILKY_ASSET, e);
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
        scene.setTexture("milky", pixels, width, height);
    }

    private static ByteBuffer copyToDirect(FloatBuffer source) {
        if (source == null || source.capacity() <= 0) return null;
        FloatBuffer copy = source.duplicate();
        copy.position(0);
        ByteBuffer bytes =
                ByteBuffer.allocateDirect(copy.remaining() * 4).order(ByteOrder.nativeOrder());
        FloatBuffer out = bytes.asFloatBuffer();
        out.put(copy);
        bytes.position(0);
        return bytes;
    }

    private static ByteBuffer buildStarBuffer(NightSkyCatalog catalog) {
        if (catalog == null || catalog.starCount <= 0) return null;
        ByteBuffer bytes = ByteBuffer.allocateDirect(catalog.starCount * STAR_STRIDE)
                .order(ByteOrder.nativeOrder());
        FloatBuffer out = bytes.asFloatBuffer();
        for (int i = 0; i < catalog.starCount; i++) {
            int p = i * STAR_PARAMS;
            int c = i * STAR_COLORS;
            out.put(catalog.starParams, p, STAR_PARAMS);
            out.put(catalog.starColors, c, STAR_COLORS);
        }
        bytes.position(0);
        return bytes;
    }
}
