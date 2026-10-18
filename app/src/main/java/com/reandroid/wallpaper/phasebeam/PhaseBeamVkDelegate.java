package com.reandroid.wallpaper.phasebeam;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;

import com.reandroid.plugin.VkPass;
import com.reandroid.plugin.VkPassFrame;
import com.reandroid.plugin.VkPassRendererDelegate;
import com.reandroid.plugin.VkPassScene;
import com.reandroid.utils.AssetLoader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * PhaseBeam 的 VK 渲染器 —— 壁纸与预览共用。
 *
 * <p>GLES 版用 {@code gl.POINTS} 画光束与光点。通用 VK pass 渲染器没有启用
 * {@code VkPhysicalDeviceFeatures::largePoints}，而未启用时 Vulkan 只保证点尺寸 1.0，
 * 所以这里把每个点展开成两个三角形；尺寸、偏移、alpha 的公式照抄 GLES 顶点着色器。
 *
 * <p>绘制顺序与 GLES 一致：背景 → 光束 → 光点，三者都用 SRC_ALPHA/ONE 加法混合，
 * 清屏色为不透明黑。
 */
final class PhaseBeamVkDelegate extends VkPassRendererDelegate {

    private static final int PUSH_BYTES = 16 * 4;

    /** 背景：position(3) + offsetX(1) + realColor(4) + adjust(3)。 */
    private static final int BG_FLOATS_PER_VERTEX = 11;
    private static final int BG_STRIDE_BYTES = BG_FLOATS_PER_VERTEX * 4;
    /** 三个主题的背景网格都是 102 个顶点，留一点余量。 */
    private static final int BG_MAX_VERTICES = 128;

    /** 光点：NDC position(2) + uv(2) + alpha(1) + adjust(3)。 */
    private static final int POINT_FLOATS_PER_VERTEX = 8;
    private static final int POINT_STRIDE_BYTES = POINT_FLOATS_PER_VERTEX * 4;
    private static final int VERTICES_PER_POINT = 6;
    /** 场景把 dot_count 夹在 10..60。 */
    private static final int MAX_POINTS = 64;
    private static final int MAX_POINT_VERTICES = MAX_POINTS * VERTICES_PER_POINT;

    private final SharedPreferences mPrefs;
    private PhaseBeamScene mScene;
    private VkPassScene mPassScene;

    private VkPass mBgPass;
    private VkPass mBeamPass;
    private VkPass mDotPass;

    private ByteBuffer mBgBuffer;
    private ByteBuffer mBeamBuffer;
    private ByteBuffer mDotBuffer;
    private FloatBuffer mBgFloats;
    private FloatBuffer mBeamFloats;
    private FloatBuffer mDotFloats;

    private final float[] mBgVertices = new float[BG_MAX_VERTICES * BG_FLOATS_PER_VERTEX];
    private final float[] mBeamVertices = new float[MAX_POINT_VERTICES * POINT_FLOATS_PER_VERTEX];
    private final float[] mDotVertices = new float[MAX_POINT_VERTICES * POINT_FLOATS_PER_VERTEX];

    private final float[] mPush = new float[16];
    private int mWidth;
    private int mHeight;
    private boolean mInitialized;
    /** 桌面是否随屏滚动；NaN = 还没收到 onOffsetsChanged。 */
    private float mScrollStep = Float.NaN;

    PhaseBeamVkDelegate(Context context, SharedPreferences prefs) {
        super(context);
        mPrefs = prefs;
    }

    @Override
    public String logTag() {
        return "PhaseBeamVK";
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
            mScene = new PhaseBeamScene();
            if (mPrefs != null) mScene.setPluginPrefs(mPrefs);
            mScene.init(context.getResources());
            if (!Float.isNaN(mScrollStep)) mScene.setScrollStep(mScrollStep);
            mScene.loadBackgroundMesh(context, meshAsset());
        }
    }

    @Override
    public void onPluginPrefsChanged(SharedPreferences prefs) {
        if (mScene == null) return;
        if (prefs != null) mScene.setPluginPrefs(prefs);
        // 主题/重上色也是 prefs 的一部分，必须整份重读 —— 与 PhaseBeamGL.setPluginPrefs 一致。
        mScene.reloadPreferences(context.getResources());
        mScene.loadBackgroundMesh(context, meshAsset());
    }

    @Override
    public void onSceneOffset(float xOffset) {
        if (mScene != null) mScene.setOffset(xOffset);
    }

    /**
     * 桌面报告的滚动步长。{@link com.reandroid.plugin.VkRendererDelegate} 没有这个钩子，
     * 由插件在引擎层转发过来；场景据此区分"停泊在首页"和"本来就不滚动"。
     */
    void setScrollStep(float xStep) {
        mScrollStep = xStep;
        if (mScene != null) mScene.setScrollStep(xStep);
    }

    @Override
    protected void buildScene(VkPassScene scene) {
        mPassScene = scene;
        scene.setClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        // 渲染器是新建的：贴图槽是空的，下一帧必须重传一次。
        if (mScene != null) mScene.mDirtyTexture = true;

        scene.createBuffer("bg", BG_STRIDE_BYTES, BG_MAX_VERTICES, true);
        scene.createBuffer("beam", POINT_STRIDE_BYTES, MAX_POINT_VERTICES, true);
        scene.createBuffer("dot", POINT_STRIDE_BYTES, MAX_POINT_VERTICES, true);
        scene.createTexture("beamTex");
        scene.createTexture("dotTex");

        mBgPass = scene.add(new VkPass(
                "shaders/phasebeam_vk_bg.vert.spv",
                "shaders/phasebeam_vk_bg.frag.spv",
                new int[] {3, 1, 4, 3}, BG_STRIDE_BYTES,
                VkPass.TOPOLOGY_TRIANGLE_LIST, VkPass.BLEND_ADDITIVE))
                .buffer("bg", 0)
                .pushConstants(PUSH_BYTES);

        mBeamPass = scene.add(new VkPass(
                "shaders/phasebeam_vk_dot.vert.spv",
                "shaders/phasebeam_vk_dot.frag.spv",
                new int[] {2, 2, 1, 3}, POINT_STRIDE_BYTES,
                VkPass.TOPOLOGY_TRIANGLE_LIST, VkPass.BLEND_ADDITIVE))
                .buffer("beam", 0)
                .pushConstants(PUSH_BYTES);
        mBeamPass.texture(0, "beamTex");

        mDotPass = scene.add(new VkPass(
                "shaders/phasebeam_vk_dot.vert.spv",
                "shaders/phasebeam_vk_dot.frag.spv",
                new int[] {2, 2, 1, 3}, POINT_STRIDE_BYTES,
                VkPass.TOPOLOGY_TRIANGLE_LIST, VkPass.BLEND_ADDITIVE))
                .buffer("dot", 0)
                .pushConstants(PUSH_BYTES);
        mDotPass.texture(0, "dotTex");

        mBgBuffer = direct(BG_MAX_VERTICES * BG_STRIDE_BYTES);
        mBgFloats = mBgBuffer.asFloatBuffer();
        mBeamBuffer = direct(MAX_POINT_VERTICES * POINT_STRIDE_BYTES);
        mBeamFloats = mBeamBuffer.asFloatBuffer();
        mDotBuffer = direct(MAX_POINT_VERTICES * POINT_STRIDE_BYTES);
        mDotFloats = mDotBuffer.asFloatBuffer();

        System.arraycopy(CLIP_CORRECTION, 0, mPush, 0, 16);
    }

    @Override
    protected void updateFrame(VkPassFrame frame, long timeMs) {
        if (mScene == null) return;

        // 与 PhaseBeamGL.initGLIfNeeded 一致：首帧无条件摆放粒子；之后只在 dot_count 变化时重摆。
        if (mScene.consumeParamsDirty() || !mInitialized) {
            mScene.allocateArrays();
            mScene.positionParticles();
            mScene.updateParticleBuffers();
        }
        mInitialized = true;

        long now = timeMs;
        if (mScene.mLastTimeMs == 0L) {
            mScene.mLastTimeMs = now;
        }
        float delta = Math.max(1.0f, (now - mScene.mLastTimeMs));
        mScene.mLastTimeMs = now;

        float newOffset = mScene.mXOffset * 2.0f;
        float speedbump = (newOffset != mScene.mOldOffset) ? 0.25f : 1.0f;
        float timeScale = (delta / 66.0f) * speedbump;

        if (mScene.mDirtyTexture) {
            uploadTextures();
        }

        boolean adjustChanged = mScene.adjustChanged();
        if (mScene.mDirtyParticles || adjustChanged) {
            mScene.updateParticleAdjusts();
            mScene.mDirtyParticles = false;
        }

        mScene.updateParticles(timeScale, newOffset);

        int bgCount = Math.min(mScene.mBgVertexCount, BG_MAX_VERTICES);
        if (bgCount > 0) {
            int floats = buildBackground(bgCount);
            mBgFloats.clear();
            mBgFloats.put(mBgVertices, 0, floats);
            frame.upload("bg", mBgBuffer, floats * 4);
            frame.setVertexCount(mBgPass, bgCount);
            mScene.mDirtyBackground = false;
        } else {
            frame.setVertexCount(mBgPass, 0);
        }
        frame.pushConstants(mBgPass, mPush);

        int dots = mScene.getDotCount();
        int beamFloats = buildPoints(
                mBeamVertices, mScene.mBeamPositions, mScene.mBeamOffsets,
                mScene.mBeamAdjusts, dots);
        mBeamFloats.clear();
        mBeamFloats.put(mBeamVertices, 0, beamFloats);
        frame.upload("beam", mBeamBuffer, beamFloats * 4);
        frame.setVertexCount(mBeamPass, dots * VERTICES_PER_POINT);
        frame.pushConstants(mBeamPass, mPush);

        int dotFloats = buildPoints(
                mDotVertices, mScene.mDotPositions, mScene.mDotOffsets,
                mScene.mDotAdjusts, dots);
        mDotFloats.clear();
        mDotFloats.put(mDotVertices, 0, dotFloats);
        frame.upload("dot", mDotBuffer, dotFloats * 4);
        frame.setVertexCount(mDotPass, dots * VERTICES_PER_POINT);
        frame.pushConstants(mDotPass, mPush);

        mScene.mOldOffset = newOffset;
        System.arraycopy(mScene.mAdjust, 0, mScene.mOldAdjust, 0, mScene.mAdjust.length);
    }

    /** 与 PhaseBeamScene.updateBackgroundBuffers 相同的取值，直接写进可复用的顶点数组。 */
    private int buildBackground(int count) {
        float offset = -mScene.mXOffset / 2.0f;
        boolean recolor = mScene.mRecolorEnabled;
        float adjustX = mScene.mAdjust[0];
        float adjustY = mScene.mAdjust[1];
        float adjustZ = mScene.mAdjust[2];
        float[] out = mBgVertices;
        int o = 0;
        for (int i = 0; i < count; i++) {
            int v = i * 3;
            float r = mScene.mBgBaseColors[v];
            float g = mScene.mBgBaseColors[v + 1];
            float b = mScene.mBgBaseColors[v + 2];
            if (recolor) {
                float grey = 0.3f * r + 0.59f * g + 0.11f * b;
                r = grey;
                g = grey;
                b = grey;
            }
            out[o++] = mScene.mBgRawVertices[v];
            out[o++] = mScene.mBgRawVertices[v + 1];
            out[o++] = 0.0f;
            out[o++] = offset;
            out[o++] = r;
            out[o++] = g;
            out[o++] = b;
            out[o++] = 1.0f;
            out[o++] = adjustX;
            out[o++] = adjustY;
            out[o++] = adjustZ;
        }
        return o;
    }

    /**
     * 把点展开成两个三角形。位置/尺寸/alpha 与 GLES 顶点着色器逐项对应：
     * {@code size = z * 7}、{@code alpha = 0.5 - size / 1000}、
     * {@code x -= offsetX * size / 100}、像素尺寸为 {@code size * scaleSize}。
     */
    private int buildPoints(float[] out, float[] positions, float[] offsets,
                            float[] adjusts, int count) {
        float scale = mScene.mScaleSize;
        int o = 0;
        for (int i = 0; i < count; i++) {
            int v = i * 3;
            float z = positions[v + 2];
            float size = z * 7.0f;
            float alpha = 0.5f - size / 1000.0f;
            float cx = positions[v] - offsets[i] * size / 100.0f;
            float cy = positions[v + 1];
            float sizePx = size * scale;
            float hx = sizePx / mWidth;
            float hy = sizePx / mHeight;
            o = writeQuad(out, o, cx, cy, hx, hy, alpha,
                    adjusts[v], adjusts[v + 1], adjusts[v + 2]);
        }
        return o;
    }

    /** 顶点格式：x, y, u, v, alpha, adjust(x,y,z)。左上角 uv=(0,0)，与 gl_PointCoord 同向。 */
    private static int writeQuad(float[] out, int o, float cx, float cy,
                                 float hx, float hy, float alpha,
                                 float ax, float ay, float az) {
        float left = cx - hx;
        float right = cx + hx;
        float top = cy + hy;
        float bottom = cy - hy;
        o = writeVertex(out, o, left, top, 0.0f, 0.0f, alpha, ax, ay, az);
        o = writeVertex(out, o, right, top, 1.0f, 0.0f, alpha, ax, ay, az);
        o = writeVertex(out, o, left, bottom, 0.0f, 1.0f, alpha, ax, ay, az);
        o = writeVertex(out, o, left, bottom, 0.0f, 1.0f, alpha, ax, ay, az);
        o = writeVertex(out, o, right, top, 1.0f, 0.0f, alpha, ax, ay, az);
        return writeVertex(out, o, right, bottom, 1.0f, 1.0f, alpha, ax, ay, az);
    }

    private static int writeVertex(float[] out, int o, float x, float y,
                                   float u, float v, float alpha,
                                   float ax, float ay, float az) {
        out[o++] = x;
        out[o++] = y;
        out[o++] = u;
        out[o++] = v;
        out[o++] = alpha;
        out[o++] = ax;
        out[o++] = ay;
        out[o++] = az;
        return o;
    }

    /** 与 PhaseBeamGL.reloadTextures 相同的主题/灰色贴图选择。 */
    private void uploadTextures() {
        if (mPassScene == null) return;
        boolean useGrey = mScene.mRecolorEnabled && "phasebeam".equals(mScene.mTheme);
        String prefix = "sunbeam".equals(mScene.mTheme) ? "sunbeam"
                : "waterbeam".equals(mScene.mTheme) ? "water"
                : "phasebeam";
        String suffix = useGrey ? "_grey" : "";
        String path = "phasebeam/drawable/" + prefix;
        uploadTexture("dotTex", path + "_dot" + suffix + ".png");
        uploadTexture("beamTex", path + "_beam" + suffix + ".png");
        mScene.mDirtyTexture = false;
    }

    private void uploadTexture(String name, String asset) {
        Bitmap bitmap = AssetLoader.decodeBitmap(context, asset);
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
        mPassScene.setTexture(name, pixels, width, height);
    }

    private String meshAsset() {
        return "sunbeam".equals(mScene.mTheme)
                ? "phasebeam/data/sunbeam_bg_mesh.csv"
                : "waterbeam".equals(mScene.mTheme)
                    ? "phasebeam/data/water_bg_mesh.csv"
                    : "phasebeam/data/phasebeam_bg_mesh.csv";
    }

    private static ByteBuffer direct(int bytes) {
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
    }

    private static final float[] CLIP_CORRECTION = {
        1, 0, 0, 0,
        0, -1, 0, 0,
        0, 0, 0.5f, 0,
        0, 0, 0.5f, 1,
    };
}
