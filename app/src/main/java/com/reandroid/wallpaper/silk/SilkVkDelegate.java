package com.reandroid.wallpaper.silk;

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

/**
 * Silk 的 VK 渲染器 —— 壁纸与预览共用。
 *
 * <p>与 GLES 版相同的两次绘制：先全屏背景（主题 background 贴图，不混合），再三条丝带
 * （主题 silk 贴图，{@code GL_ONE / ONE_MINUS_SRC_ALPHA} 预乘混合）。丝的几何、颜色和
 * 动画全部来自 {@link SilkScene}，这里只负责把它每帧算出的顶点数组搬进 GPU 缓冲。
 *
 * <p>GLES 的索引表把 130 个顶点连成 64 段四边形；通用 pass 渲染器没有索引缓冲，这里按
 * 三角形条带的顺序（A0, B0, A1, B1 ... A64, B64）重排顶点，条带划出的三角形与索引表
 * 完全一致。每个顶点是位置 2 + 颜色 1 + 贴图坐标 2。
 */
final class SilkVkDelegate extends VkPassRendererDelegate {

    private static final String BG_BUFFER = "bg";
    private static final String BG_TEXTURE = "bgTex";
    private static final String SILK_TEXTURE = "silkTex";
    private static final String RIBBON_BUFFER = "ribbon";

    private static final int[] RIBBON_ATTRIB_SIZES = {2, 1, 2};
    private static final int FLOATS_PER_VERTEX = 5;
    private static final int RIBBON_STRIDE_BYTES = FLOATS_PER_VERTEX * 4;

    /** mat4 + 4 个 vec4，正好 128 字节的推送常量上限。 */
    private static final int RIBBON_PUSH_BYTES = 32 * 4;

    private static final int BG_PUSH_BYTES = 16 * 4;

    private static final int BG_VERTICES = 4;
    private static final int BG_STRIDE_BYTES = 4 * 4;

    private final SharedPreferences mPrefs;
    private SilkScene mScene;
    private VkPassScene mPassScene;

    private VkPass mBgPass;
    private VkPass[] mRibbonPasses;
    private ByteBuffer mBgBuffer;
    private ByteBuffer[] mRibbonBuffers;

    /** GLES 的 UV 表是静态的，按原始顶点下标取（A 行 0..64、B 行 65..129）。 */
    private final float[] mUv = SilkScene.buildUv();

    private final float[] mRibbonPush = new float[32];
    private final float[] mBgPush = new float[16];

    private long mLastFrameMs;
    private String mLoadedTheme;

    SilkVkDelegate(Context context, SharedPreferences prefs) {
        super(context);
        mPrefs = prefs;
    }

    @Override
    public String logTag() {
        return "SilkVK";
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
        if (mScene != null) return;
        mScene = new SilkScene();
        if (mPrefs != null) mScene.setPluginPrefs(mPrefs);
    }

    @Override
    public void onPluginPrefsChanged(SharedPreferences prefs) {
        if (mScene == null || prefs == null) return;
        // 主题对应的两张贴图由 updateFrame 按 mLoadedTheme 在渲染线程上换。
        mScene.setPluginPrefs(prefs);
    }

    @Override
    protected void buildScene(VkPassScene scene) {
        mPassScene = scene;
        scene.setClearColor(0f, 0f, 0f, 0f);

        // 与 GLES 的 glTexParameteri 逐项一致：背景 MIN/MAG 都是 NEAREST，
        // 丝带是 MIN_NEAREST / MAG_LINEAR。
        scene.createTexture(
                BG_TEXTURE, VkPassScene.FILTER_NEAREST, VkPassScene.ADDRESS_CLAMP_TO_EDGE);
        scene.createTexture(
                SILK_TEXTURE,
                VkPassScene.FILTER_NEAREST,
                VkPassScene.FILTER_LINEAR,
                VkPassScene.ADDRESS_CLAMP_TO_EDGE);

        uploadThemeTextures(mScene != null ? mScene.mTheme : SilkScene.THEME_SILK);

        scene.createBuffer(BG_BUFFER, BG_STRIDE_BYTES, BG_VERTICES, false);
        mBgBuffer = buildBackgroundBuffer();

        mBgPass = scene.add(new VkPass(
                        "shaders/silk_vk_bg.vert.spv",
                        "shaders/silk_vk_bg.frag.spv",
                        new int[] {2, 2},
                        BG_STRIDE_BYTES,
                        VkPass.TOPOLOGY_TRIANGLE_STRIP,
                        VkPass.BLEND_NONE))
                .buffer(BG_BUFFER, BG_VERTICES)
                .pushConstants(BG_PUSH_BYTES);
        mBgPass.texture(0, BG_TEXTURE);

        mRibbonPasses = new VkPass[SilkScene.RIBBON_COUNT];
        mRibbonBuffers = new ByteBuffer[SilkScene.RIBBON_COUNT];
        for (int r = 0; r < SilkScene.RIBBON_COUNT; r++) {
            String buffer = RIBBON_BUFFER + r;
            scene.createBuffer(buffer, RIBBON_STRIDE_BYTES, SilkScene.VERTICES, true);
            VkPass pass = scene.add(new VkPass(
                            "shaders/silk_vk_ribbon.vert.spv",
                            "shaders/silk_vk_ribbon.frag.spv",
                            RIBBON_ATTRIB_SIZES,
                            RIBBON_STRIDE_BYTES,
                            VkPass.TOPOLOGY_TRIANGLE_STRIP,
                            VkPass.BLEND_PREMULTIPLIED))
                    .buffer(buffer, SilkScene.VERTICES)
                    .pushConstants(RIBBON_PUSH_BYTES);
            pass.texture(0, SILK_TEXTURE);
            mRibbonPasses[r] = pass;
            mRibbonBuffers[r] = ByteBuffer.allocateDirect(SilkScene.VERTICES * RIBBON_STRIDE_BYTES)
                    .order(ByteOrder.nativeOrder());
        }

        System.arraycopy(CLIP_CORRECTION, 0, mBgPush, 0, 16);
    }

    @Override
    protected void updateFrame(VkPassFrame frame, long timeMs) {
        SilkScene scene = mScene;
        if (scene == null || mRibbonPasses == null) {
            clearPasses(frame);
            return;
        }

        float dt = mLastFrameMs == 0 ? 0.016f : (timeMs - mLastFrameMs) / 1000f;
        mLastFrameMs = timeMs;
        // 只钳制上限；速度倍率由 Scene 自己乘，与 GLES 版一致。
        scene.update(Math.min(dt, 0.1f));

        // 设置里换了主题，两张贴图要跟着换；放在渲染线程上做，免得和出帧抢锁。
        String theme = scene.mTheme;
        if (!theme.equals(mLoadedTheme)) {
            uploadThemeTextures(theme);
        }

        mBgBuffer.position(0);
        frame.upload(BG_BUFFER, mBgBuffer, BG_VERTICES * BG_STRIDE_BYTES);
        frame.setVertexCount(mBgPass, BG_VERTICES);
        frame.pushConstants(mBgPass, mBgPush);

        for (int r = 0; r < SilkScene.RIBBON_COUNT; r++) {
            SilkScene.RibbonConfig config = SilkScene.RIBBONS[r];
            buildRibbonVertices(r, scene.mPositions[r], scene.mColors[r]);

            System.arraycopy(CLIP_CORRECTION, 0, mRibbonPush, 0, 16);
            // uRotateFlash = (sin(rot), cos(rot), flash, 未用)
            mRibbonPush[16] = scene.mRotSin[r];
            mRibbonPush[17] = scene.mRotCos[r];
            mRibbonPush[18] = scene.mFlash[r];
            mRibbonPush[19] = 0f;
            // uNewPos = (newPosX, newPosY, 0, 0)，与原版 uniform4f 一致
            mRibbonPush[20] = config.newPosX;
            mRibbonPush[21] = config.newPosY;
            mRibbonPush[22] = 0f;
            mRibbonPush[23] = 0f;
            // uOriginColor = (主题色, 1)；GLES 侧同样把 a 固定为 1
            float[] origin = SilkScene.themeOriginColor(theme);
            mRibbonPush[24] = origin[0];
            mRibbonPush[25] = origin[1];
            mRibbonPush[26] = origin[2];
            mRibbonPush[27] = 1f;
            // uParams = (originAlpha, decayLen, divFactor, decay)
            mRibbonPush[28] = config.originAlpha;
            mRibbonPush[29] = config.decayLen;
            mRibbonPush[30] = config.divFactor;
            mRibbonPush[31] = config.decay ? 1f : 0f;

            ByteBuffer buffer = mRibbonBuffers[r];
            buffer.position(0);
            frame.upload(RIBBON_BUFFER + r, buffer, SilkScene.VERTICES * RIBBON_STRIDE_BYTES);
            frame.setVertexCount(mRibbonPasses[r], SilkScene.VERTICES);
            frame.pushConstants(mRibbonPasses[r], mRibbonPush);
        }
    }

    private void clearPasses(VkPassFrame frame) {
        if (mBgPass != null) frame.setVertexCount(mBgPass, 0);
        if (mRibbonPasses == null) return;
        for (VkPass pass : mRibbonPasses) {
            if (pass != null) frame.setVertexCount(pass, 0);
        }
    }

    /** 按条带顺序写 A_i、B_i 两个顶点，颜色和 UV 都从原始下标取。 */
    private void buildRibbonVertices(int ribbon, float[] positions, float[] colors) {
        ByteBuffer buffer = mRibbonBuffers[ribbon];
        buffer.clear();
        for (int i = 0; i < SilkScene.COLUMNS; i++) {
            putVertex(buffer, positions, colors, i);
            putVertex(buffer, positions, colors, i + SilkScene.COLUMNS);
        }
        buffer.position(0);
    }

    private void putVertex(ByteBuffer buffer, float[] positions, float[] colors, int index) {
        buffer.putFloat(positions[index * 2]);
        buffer.putFloat(positions[index * 2 + 1]);
        buffer.putFloat(colors[index]);
        buffer.putFloat(mUv[index * 2]);
        buffer.putFloat(mUv[index * 2 + 1]);
    }

    private void uploadThemeTextures(String theme) {
        mLoadedTheme = theme;
        if (mPassScene == null) return;
        uploadTexture(BG_TEXTURE, SilkScene.themeBackgroundAsset(theme));
        uploadTexture(SILK_TEXTURE, SilkScene.themeSilkAsset(theme));
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

    /** GLES 的 TRIANGLE_FAN（左上、右上、右下、左下）换成等价的三角形条带。 */
    private static ByteBuffer buildBackgroundBuffer() {
        float[] vertices = {
            -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f, -1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f,
        };
        ByteBuffer buffer =
                ByteBuffer.allocateDirect(vertices.length * 4).order(ByteOrder.nativeOrder());
        buffer.asFloatBuffer().put(vertices);
        buffer.position(0);
        return buffer;
    }

    private static final float[] CLIP_CORRECTION = {
        1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 0.5f, 0, 0, 0, 0.5f, 1,
    };
}
