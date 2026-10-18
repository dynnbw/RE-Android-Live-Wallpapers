package com.reandroid.wallpaper.luminousdots;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.BatteryManager;
import android.util.Log;

import com.reandroid.plugin.VkPass;
import com.reandroid.plugin.VkPassFrame;
import com.reandroid.plugin.VkPassRendererDelegate;
import com.reandroid.plugin.VkPassScene;
import com.reandroid.utils.AssetLoader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;

/**
 * LuminousDots 的 Vulkan 渲染器 —— 壁纸与预览共用。
 *
 * <p>GLES 版每个角一次 drawElements，共 8 次，再加 10 次 glow。这里把它压成
 * 8 个网格 pass + 1 个 glow pass：网格 pass 与 GLES 的 8 次 pass 一一对应
 * （顺序、旋转、位移、alpha 全部照抄），顶点是索引展开后的 6144 个；10 个 glow 的
 * MVP 完全相同（{@code buildMVPGlow} 不读单个 glow 的数据），所以合并进一个顶点缓冲、
 * 一次 draw。
 *
 * <p>贴图选择（shape/color/scale）在原版是每帧 bindTexture；通用 pass 的绑定是静态的，
 * 所以每个 pass 一次绑定全部候选贴图（网格 6 张、glow 3 张，均在 kMaxTextures=8 内），
 * 由 push constant 里的下标在片元着色器里选。
 */
final class LuminousDotsVkDelegate extends VkPassRendererDelegate {

    private static final String TAG = "LuminousDotsVK";

    private static final int ATTRS = LuminousDotsScene.ATTRS; // 12 floats
    private static final int STRIDE = LuminousDotsScene.STRIDE; // 48 bytes
    private static final int QUADS = LuminousDotsScene.QUADS; // 1024
    private static final int CORNERS_PER_QUAD = 6; // 两个三角形
    /** 与 Scene.buildGrid() 写入的索引顺序一致：0,1,2 与 2,1,3。 */
    private static final int[] QUAD_CORNERS = {0, 1, 2, 2, 1, 3};

    private static final int EXPANDED_VERTICES = QUADS * CORNERS_PER_QUAD;

    private static final int MAX_GLOW_QUADS = LuminousDotsScene.GLOW_POS.length;
    private static final int MAX_GLOW_VERTICES = MAX_GLOW_QUADS * CORNERS_PER_QUAD;

    private static final int OBJECT_PASSES = 8;
    private static final int OBJECT_TEXTURES = 6;
    private static final int GLOW_TEXTURES = 3;

    /** mat4 + vec4 + 7 floats（alpha/battery/4 个渐变参数/贴图下标）。 */
    private static final int PUSH_FLOATS = 16 + 4 + 7;

    private static final int PUSH_BYTES = PUSH_FLOATS * 4;

    private static final long BATTERY_INTERVAL_MS = 5000L;

    private static final String BG_VERT = "shaders/luminousdots_vk_bg.vert.spv";
    private static final String BG_FRAG = "shaders/luminousdots_vk_bg.frag.spv";
    private static final String DOT_VERT = "shaders/luminousdots_vk_dot.vert.spv";
    private static final String DOT_FRAG = "shaders/luminousdots_vk_dot.frag.spv";

    /** location 0..3：a_position(3), a_texCoord(2), a_alpha(1), a_color(4)。 */
    private static final int[] ATTRIB_SIZES = {3, 2, 1, 4};

    /** 与 GLES 的 mObjTexId* 一一对应，下标即 push constant 里的 uTexIndex。 */
    private static final String[] OBJECT_ASSETS = {
        "luminousdots/drawable/box0_128.png",
        "luminousdots/drawable/box0_1_128.png",
        "luminousdots/drawable/box0_64.png",
        "luminousdots/drawable/box0_1_64.png",
        "luminousdots/drawable/round0.png",
        "luminousdots/drawable/dot0.png",
    };

    /** 与 GLES 的 mGlowTexId* 一一对应：shape 0/1/2 → glowbox/glowdot/glowround。 */
    private static final String[] GLOW_ASSETS = {
        "luminousdots/drawable/glowbox.png",
        "luminousdots/drawable/glowdot.png",
        "luminousdots/drawable/glowround.png",
    };

    private final boolean mPreview;
    private SharedPreferences mPrefs;
    private LuminousDotsScene mScene;
    private VkPassScene mPassScene;

    private final VkPass[] mObjectPasses = new VkPass[OBJECT_PASSES];
    private VkPass mGlowPass;

    private final float[] mProj = new float[16];
    private final float[] mGlMvp = new float[16];
    private final float[] mVkMvp = new float[16];
    private final float[] mPush = new float[PUSH_FLOATS];
    private final float[] mColor = new float[4];
    private final float[] mPassAlpha = new float[OBJECT_PASSES];
    private final int[] mPassRot = new int[OBJECT_PASSES];
    private final float[] mPassTx = new float[OBJECT_PASSES];
    private final float[] mPassTy = new float[OBJECT_PASSES];

    private ByteBuffer mUpExpanded;
    private ByteBuffer mDownExpanded;
    private ByteBuffer mGlowExpanded;
    private boolean mTexturesUploaded;

    private int mHeight;
    private long mLastBatteryCheck;

    LuminousDotsVkDelegate(Context context, SharedPreferences prefs, boolean preview) {
        super(context);
        mPrefs = prefs;
        mPreview = preview;
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
        mHeight = height;
        if (mScene == null) {
            mScene = new LuminousDotsScene(width, height);
            mScene.setPluginPrefs(mPrefs);
        }
        // 与 GLES initGL()/resize() 相同的参数：xMaxOffset/yMaxOffset = 画布尺寸。
        mScene.resize(width, height, width, height);

        float aspect = (float) width / (float) height;
        float diag = Math.max(width, height);
        LuminousDotsScene.frustumM(mProj, -aspect, aspect, -1f, 1f, 1.4f, diag + 100f);
    }

    @Override
    public void onPluginPrefsChanged(SharedPreferences prefs) {
        mPrefs = prefs;
        if (mScene != null) mScene.setPluginPrefs(prefs);
    }

    @Override
    public void onSceneOffset(float xOffset) {
        // GLES 版同样忽略滚动偏移（LuminousDotsGL.setOffset 为空实现）。
    }

    @Override
    protected void buildScene(VkPassScene scene) {
        mPassScene = scene;
        // native 渲染器可能被销毁后重建：新场景里的纹理是空的，必须重新上传。
        mTexturesUploaded = false;
        scene.setClearColor(0.0f, 0.0f, 0.0f, 1.0f);

        scene.createBuffer("up", STRIDE, EXPANDED_VERTICES, true);
        scene.createBuffer("down", STRIDE, EXPANDED_VERTICES, true);
        scene.createBuffer("glow", STRIDE, MAX_GLOW_VERTICES, true);
        for (int i = 0; i < OBJECT_TEXTURES; i++) scene.createTexture("objTex" + i);
        for (int i = 0; i < GLOW_TEXTURES; i++) scene.createTexture("glowTex" + i);

        for (int i = 0; i < OBJECT_PASSES; i++) {
            // pass 0,1 = Left-Up；2,3 = Right-Up；4,5 = Left-Down；6,7 = Right-Down。
            // Up 用 mVerticesUp，Down 用 mVerticesDown（与 GLES 的 verticesUp/Down 一致）。
            String buffer = (i == 0 || i == 1 || i == 6 || i == 7) ? "up" : "down";
            VkPass pass = new VkPass(
                            BG_VERT,
                            BG_FRAG,
                            ATTRIB_SIZES,
                            STRIDE,
                            VkPass.TOPOLOGY_TRIANGLE_LIST,
                            VkPass.BLEND_ALPHA)
                    .buffer(buffer, 0)
                    .pushConstants(PUSH_BYTES)
                    // 精确对应 GLES 的 glBlendFunc(SRC_ALPHA, ONE_MINUS_SRC_ALPHA)：
                    // 颜色与 alpha 两个通道都用 SRC_ALPHA。
                    .blendFactors(
                            VkPass.FACTOR_SRC_ALPHA, VkPass.FACTOR_ONE_MINUS_SRC_ALPHA,
                            VkPass.FACTOR_SRC_ALPHA, VkPass.FACTOR_ONE_MINUS_SRC_ALPHA);
            for (int t = 0; t < OBJECT_TEXTURES; t++) {
                pass.texture(t, "objTex" + t);
            }
            scene.add(pass);
            mObjectPasses[i] = pass;
        }

        mGlowPass = new VkPass(
                        DOT_VERT,
                        DOT_FRAG,
                        ATTRIB_SIZES,
                        STRIDE,
                        VkPass.TOPOLOGY_TRIANGLE_LIST,
                        VkPass.BLEND_ALPHA)
                .buffer("glow", 0)
                .pushConstants(PUSH_BYTES)
                .blendFactors(
                        VkPass.FACTOR_SRC_ALPHA, VkPass.FACTOR_ONE_MINUS_SRC_ALPHA,
                        VkPass.FACTOR_SRC_ALPHA, VkPass.FACTOR_ONE_MINUS_SRC_ALPHA);
        for (int t = 0; t < GLOW_TEXTURES; t++) {
            mGlowPass.texture(t, "glowTex" + t);
        }
        scene.add(mGlowPass);
    }

    @Override
    protected void updateFrame(VkPassFrame frame, long timeMs) {
        if (mScene == null) return;
        ensureTextures();

        /*
         * Scene 内部用 System.currentTimeMillis()：构造时的 mSystemTimer 与 buildGrid()
         * 里的 mEventTimer 都是墙钟。通用 VK 基类传进来的是 uptimeMillis，直接混用会
         * 让 getDefaultAlpha 的循环相位错掉，所以这里按 GLES 壁纸/预览的取时方式走墙钟。
         */
        long now = System.currentTimeMillis();
        // LuminousDotsEngine 在 super.drawFrame() 之前轮询电量，这里保持同样的顺序；
        // 预览路径（GLESPreviewView）从不注入电量，所以预览也不轮询。
        if (!mPreview) pollBattery(now);

        if (!mScene.mGridBuilt) {
            mScene.readSettings();
            mScene.buildGrid();
            if (!mScene.mGridBuilt) return;
            buildExpandedBuffers();
        }

        mScene.readSettings();
        mScene.update(now);
        LuminousDotsScene.SceneData data = mScene.getSceneData();

        // --- 每帧只改 alpha（attr 9）；其余顶点数据在 buildGrid 之后不再变化 ---
        patchObjectAlpha(mScene.mVerticesUp, mUpExpanded);
        patchObjectAlpha(mScene.mVerticesDown, mDownExpanded);
        int glowCount = patchGlowAlpha();

        frame.upload("up", mUpExpanded, EXPANDED_VERTICES * STRIDE);
        frame.upload("down", mDownExpanded, EXPANDED_VERTICES * STRIDE);
        if (glowCount > 0) {
            frame.upload("glow", mGlowExpanded, glowCount * CORNERS_PER_QUAD * STRIDE);
        }

        float f2 = data.halfLayoutSize * data.mScale;
        colorFor(data.colorState, mColor);
        int objectTex = objectTextureIndex(data);

        // 顺序与 LuminousDotsGL.renderPass 的调用顺序完全一致：
        // 0,1 = Left-Up；2,3 = Right-Up；4,5 = Left-Down；6,7 = Right-Down。
        mPassAlpha[0] = data.alphaLU;
        mPassAlpha[1] = 1f - data.alphaLU;
        mPassAlpha[2] = data.alphaRU;
        mPassAlpha[3] = 1f - data.alphaRU;
        mPassAlpha[4] = data.alphaLD;
        mPassAlpha[5] = 1f - data.alphaLD;
        mPassAlpha[6] = data.alphaRD;
        mPassAlpha[7] = 1f - data.alphaRD;

        mPassRot[0] = data.rotIdxLU0;
        mPassRot[1] = data.rotIdxLU1;
        mPassRot[2] = data.rotIdxRU0;
        mPassRot[3] = data.rotIdxRU1;
        mPassRot[4] = data.rotIdxLD0;
        mPassRot[5] = data.rotIdxLD1;
        mPassRot[6] = data.rotIdxRD0;
        mPassRot[7] = data.rotIdxRD1;

        mPassTx[0] = -f2 / 2f;
        mPassTx[1] = -f2 / 2f;
        mPassTx[2] = f2 / 2f;
        mPassTx[3] = f2 / 2f;
        mPassTx[4] = -f2 / 2f;
        mPassTx[5] = -f2 / 2f;
        mPassTx[6] = f2 / 2f;
        mPassTx[7] = f2 / 2f;

        mPassTy[0] = f2;
        mPassTy[1] = f2;
        mPassTy[2] = f2;
        mPassTy[3] = f2;
        mPassTy[4] = -f2;
        mPassTy[5] = -f2;
        mPassTy[6] = -f2;
        mPassTy[7] = -f2;

        for (int i = 0; i < OBJECT_PASSES; i++) {
            VkPass pass = mObjectPasses[i];
            float alpha = mPassAlpha[i];
            if (!(alpha > 0f)) {
                frame.setVertexCount(pass, 0);
                continue;
            }
            float[] rot =
                    LuminousDotsScene.ROTATE_TYPE[LuminousDotsScene.ROTATE_IDX[mPassRot[i]][0]];
            LuminousDotsScene.buildMVP(
                    mGlMvp,
                    mProj,
                    mHeight,
                    mPassTx[i],
                    mPassTy[i],
                    0f,
                    data.mScale,
                    rot,
                    data.camPx,
                    data.camPy);
            // GL clip → Vulkan clip：y 取反，z 从 [-w,w] 映射到 [0,w]。
            LuminousDotsScene.multiplyMM(mVkMvp, CLIP_CORRECTION, mGlMvp);
            fillPush(mVkMvp, alpha, objectTex, data);
            frame.pushConstants(pass, mPush);
            frame.setVertexCount(pass, EXPANDED_VERTICES);
        }

        // --- glow：10 个 quad 共用一个 MVP 与一张贴图 ---
        if (glowCount > 0) {
            float[] rot =
                    LuminousDotsScene.ROTATE_TYPE[LuminousDotsScene.ROTATE_IDX[data.rotIdxGlow][0]];
            LuminousDotsScene.buildMVPGlow(
                    mGlMvp, mProj, mHeight, data.mScale, rot, data.camPx, data.camPy);
            LuminousDotsScene.multiplyMM(mVkMvp, CLIP_CORRECTION, mGlMvp);
            fillPush(mVkMvp, data.glowAlpha, glowTextureIndex(data.shape), data);
            frame.pushConstants(mGlowPass, mPush);
        }
        frame.setVertexCount(mGlowPass, glowCount * CORNERS_PER_QUAD);
    }

    // ─────────────────────────── 顶点缓冲 ───────────────────────────

    /**
     * GLES 用 glDrawElements(6 个索引 / quad) 画网格。通用 pass 不支持索引缓冲，
     * 所以在 buildGrid 之后按索引顺序把每个 quad 展开成 6 个顶点。
     * 之后每一帧只改 attr 9（color alpha），与 Scene.updateMotionAlpha 的写入位置一致。
     */
    private void buildExpandedBuffers() {
        mUpExpanded = ByteBuffer.allocateDirect(EXPANDED_VERTICES * STRIDE)
                .order(ByteOrder.nativeOrder());
        mDownExpanded = ByteBuffer.allocateDirect(EXPANDED_VERTICES * STRIDE)
                .order(ByteOrder.nativeOrder());
        mGlowExpanded = ByteBuffer.allocateDirect(MAX_GLOW_VERTICES * STRIDE)
                .order(ByteOrder.nativeOrder());

        expand(mScene.mVerticesUp, mUpExpanded, QUADS);
        expand(mScene.mVerticesDown, mDownExpanded, QUADS);
        List<LuminousDotsScene.GlowData> glows = mScene.mGlowList;
        for (int i = 0; i < glows.size() && i < MAX_GLOW_QUADS; i++) {
            expandQuad(glows.get(i).vertices, mGlowExpanded, 0, i);
        }
    }

    private static void expand(FloatBuffer src, ByteBuffer dst, int quadCount) {
        for (int q = 0; q < quadCount; q++) {
            expandQuad(src, dst, q, q);
        }
    }

    private static void expandQuad(FloatBuffer src, ByteBuffer dst, int srcQuad, int dstQuad) {
        for (int k = 0; k < CORNERS_PER_QUAD; k++) {
            int srcBase = srcQuad * 4 * ATTRS + QUAD_CORNERS[k] * ATTRS;
            int dstBase = (dstQuad * CORNERS_PER_QUAD + k) * ATTRS;
            for (int a = 0; a < ATTRS; a++) {
                dst.putFloat((dstBase + a) * 4, src.get(srcBase + a));
            }
        }
    }

    private static void patchObjectAlpha(FloatBuffer src, ByteBuffer dst) {
        for (int q = 0; q < QUADS; q++) {
            patchQuadAlpha(src, q, dst, q);
        }
    }

    private int patchGlowAlpha() {
        List<LuminousDotsScene.GlowData> glows = mScene.mGlowList;
        int count = Math.min(glows.size(), MAX_GLOW_QUADS);
        for (int i = 0; i < count; i++) {
            patchQuadAlpha(glows.get(i).vertices, 0, mGlowExpanded, i);
        }
        return count;
    }

    /** 四个角点在 updateMotionAlpha 里被写成同一个值，所以读 vertex 0 的 attr 9 即可。 */
    private static void patchQuadAlpha(FloatBuffer src, int srcQuad, ByteBuffer dst, int dstQuad) {
        float alpha = src.get(srcQuad * 4 * ATTRS + 9);
        int base = dstQuad * CORNERS_PER_QUAD * ATTRS;
        for (int k = 0; k < CORNERS_PER_QUAD; k++) {
            dst.putFloat((base + k * ATTRS + 9) * 4, alpha);
        }
    }

    // ─────────────────────────── 每帧参数 ───────────────────────────

    private void fillPush(
            float[] mvp, float alpha, int texIndex, LuminousDotsScene.SceneData data) {
        System.arraycopy(mvp, 0, mPush, 0, 16);
        System.arraycopy(mColor, 0, mPush, 16, 4);
        mPush[20] = alpha;
        mPush[21] = data.batteryAlpha;
        mPush[22] = data.gradDownStart;
        mPush[23] = data.gradUpStart;
        mPush[24] = data.gradGapUp;
        mPush[25] = data.gradGapDown;
        mPush[26] = texIndex;
    }

    /** 与 LuminousDotsGL.drawFrame() 里的 objTex 选择完全一致。 */
    private static int objectTextureIndex(LuminousDotsScene.SceneData data) {
        switch (data.shape) {
            case 0:
                if (data.mScale >= 1.9f) {
                    return data.colorState != 0 ? 3 : 2;
                }
                return data.colorState != 0 ? 1 : 0;
            case 1:
                return 4;
            case 2:
                return 5;
            default:
                return 0;
        }
    }

    private static int glowTextureIndex(int shape) {
        switch (shape) {
            case 1:
                return 1;
            case 2:
                return 2;
            case 0:
            default:
                return 0;
        }
    }

    /** 与 GLES 的 uColor 设置一致。原版 uIsGradiatObj 恒为 0，该颜色实际不参与着色。 */
    private static void colorFor(int colorState, float[] out) {
        switch (colorState) {
            case 1:
                out[0] = 0.031f;
                out[1] = 0.07f;
                out[2] = 0.0f;
                out[3] = 1.0f;
                break;
            case 2:
                out[0] = 0.094f;
                out[1] = 0.05f;
                out[2] = 0.008f;
                out[3] = 1.0f;
                break;
            default:
                out[0] = 0.015f;
                out[1] = 0.027f;
                out[2] = 0.055f;
                out[3] = 1.0f;
                break;
        }
    }

    // ─────────────────────────── 资源 ───────────────────────────

    private void ensureTextures() {
        if (mTexturesUploaded || mPassScene == null) return;
        mTexturesUploaded = true;
        for (int i = 0; i < OBJECT_TEXTURES; i++) {
            uploadTexture("objTex" + i, OBJECT_ASSETS[i]);
        }
        for (int i = 0; i < GLOW_TEXTURES; i++) {
            uploadTexture("glowTex" + i, GLOW_ASSETS[i]);
        }
    }

    private void uploadTexture(String name, String asset) {
        Bitmap bitmap;
        try {
            bitmap = AssetLoader.decodeBitmap(context, asset);
        } catch (RuntimeException e) {
            Log.e(TAG, "decode failed: " + asset, e);
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
        mPassScene.setTexture(name, pixels, width, height);
    }

    /** 与 LuminousDotsEngine 相同：每 5 秒读一次电量，驱动 Scene 的电池 alpha。 */
    private void pollBattery(long now) {
        if (now - mLastBatteryCheck <= BATTERY_INTERVAL_MS) return;
        mLastBatteryCheck = now;
        try {
            BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                mScene.setBatteryLevel(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "battery read failed", e);
        }
    }

    /**
     * GL clip → Vulkan clip：y 取反，z 从 [-w,w] 映射到 [0,w]。
     * 与 BlueSeaVkDelegate 使用同一份矩阵。
     */
    private static final float[] CLIP_CORRECTION = {
        1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 0.5f, 0, 0, 0, 0.5f, 1,
    };
}
