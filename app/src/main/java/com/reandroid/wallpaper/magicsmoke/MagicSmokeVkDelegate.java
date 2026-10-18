package com.reandroid.wallpaper.magicsmoke;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;

import com.reandroid.plugin.VkPass;
import com.reandroid.plugin.VkPassFrame;
import com.reandroid.plugin.VkPassRendererDelegate;
import com.reandroid.plugin.VkPassScene;
import com.reandroid.utils.AssetLoader;

/**
 * Magic Smoke 的 VK 渲染器 —— 壁纸与预览共用。
 *
 * <p>GLES 版每帧只画一个全屏四边形，两个程序按纹理遮罩二选一：
 * 5 层（mask 0x1f）与 4 层（mask 0x0f）。这里用两个 pass 对应这两个程序，
 * 每帧把未选中的那个顶点数设为 0，保证只画一次，且与 GLES 的
 * {@code glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)} 几何完全一致。
 *
 * <p>纹理坐标在顶点着色器里由旋转/缩放/位移算出来，片元做
 * {@code mix(color, tex, tex.a)}；GLES 的 {@code glBlendFunc(ONE, ZERO)} 就是
 * 不混合，所以两个 pass 都用 {@link VkPass#BLEND_NONE}。
 */
final class MagicSmokeVkDelegate extends VkPassRendererDelegate {

    private static final int TEXTURE_COUNT = 5;
    private static final int VERTEX_COUNT = 4;
    /** 每层一个 vec4：sin(rotation), cos(rotation), scale * 0.35, xshift。 */
    private static final float LAYER_SCALE = 0.35f;

    /** vec4 layers + vec2 pan + vec2 aspect + vec4 clear。 */
    private static final int PUSH_FLOATS_5TEX = 5 * 4 + 2 + 2 + 4; // 28 floats = 112 bytes
    private static final int PUSH_FLOATS_4TEX = 4 * 4 + 2 + 2 + 4; // 24 floats = 96 bytes
    private static final int PUSH_BYTES_5TEX = PUSH_FLOATS_5TEX * 4;
    private static final int PUSH_BYTES_4TEX = PUSH_FLOATS_4TEX * 4;

    private static final String NOISE_ASSET = "magicsmoke/drawable/noise";

    private final float[] mPush5 = new float[PUSH_FLOATS_5TEX];
    private final float[] mPush4 = new float[PUSH_FLOATS_4TEX];

    private SharedPreferences mPrefs;
    private MagicSmokeScene mScene;
    private VkPassScene mPassScene;
    private VkPass mPass5;
    private VkPass mPass4;

    private boolean mTexturesDirty = true;
    private int mWidth;
    private int mHeight;

    MagicSmokeVkDelegate(Context context, SharedPreferences prefs) {
        super(context);
        mPrefs = prefs;
    }

    @Override
    public String logTag() {
        return "MagicSmokeVK";
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
            mScene = new MagicSmokeScene();
            if (mPrefs != null) mScene.setPluginPrefs(mPrefs);
            mScene.init();
            mTexturesDirty = true;
        }
    }

    @Override
    public void onPluginPrefsChanged(SharedPreferences prefs) {
        mPrefs = prefs;
        if (mScene != null) mScene.setPluginPrefs(prefs);
    }

    @Override
    public void onSceneOffset(float xOffset) {
        if (mScene != null) {
            // VkRendererDelegate 只转发 xOffset；mYOffset 保持 0。
            mScene.mXOffset = xOffset;
        }
    }

    @Override
    protected void buildScene(VkPassScene scene) {
        mPassScene = scene;
        scene.setClearColor(0.0f, 0.0f, 0.0f, 1.0f);

        // 5 个纹理槽 = 5 个采样器位置。textureSwap 只改变槽里放哪张处理后的图，
        // 由 uploadPresetTextures() 决定，descriptor 绑定本身是固定的。
        for (int i = 0; i < TEXTURE_COUNT; i++) {
            scene.createTexture("tex" + i);
        }

        // 全屏四边形由 gl_VertexIndex 生成（与 GLES 的顶点顺序相同），
        // 因此不需要顶点缓冲，也没有逐帧顶点上传。
        mPass5 = scene.add(new VkPass(
                "shaders/magicsmoke_vk_5tex.vert.spv",
                "shaders/magicsmoke_vk_5tex.frag.spv",
                new int[] {}, 0,
                VkPass.TOPOLOGY_TRIANGLE_STRIP, VkPass.BLEND_NONE))
                .buffer("", VERTEX_COUNT)
                .pushConstants(PUSH_BYTES_5TEX);
        for (int i = 0; i < TEXTURE_COUNT; i++) {
            mPass5.texture(i, "tex" + i);
        }

        mPass4 = scene.add(new VkPass(
                "shaders/magicsmoke_vk_4tex.vert.spv",
                "shaders/magicsmoke_vk_4tex.frag.spv",
                new int[] {}, 0,
                VkPass.TOPOLOGY_TRIANGLE_STRIP, VkPass.BLEND_NONE))
                .buffer("", VERTEX_COUNT)
                .pushConstants(PUSH_BYTES_4TEX);
        for (int i = 0; i < 4; i++) {
            mPass4.texture(i, "tex" + i);
        }

        mTexturesDirty = true;
    }

    @Override
    protected void updateFrame(VkPassFrame frame, long timeMs) {
        if (mScene == null || mPassScene == null) {
            return;
        }

        // 与 GLES drawFrame() 的顺序一致：先检查预设（变了就重传纹理），再推进动画。
        if (mScene.updatePresetIfNeeded()) {
            mTexturesDirty = true;
        }
        if (mTexturesDirty) {
            uploadPresetTextures();
            mTexturesDirty = false;
        }
        mScene.updateAnimation(timeMs);

        MagicSmokeScene.Preset preset = MagicSmokeScene.PRESETS[mScene.mCurrentPreset];
        boolean use5 = mScene.countTextures(preset.textureMask) == 5;
        frame.setVertexCount(mPass5, use5 ? VERTEX_COUNT : 0);
        frame.setVertexCount(mPass4, use5 ? 0 : VERTEX_COUNT);

        float aspectScaleX = aspectScaleX();
        buildPush5(aspectScaleX);
        buildPush4(aspectScaleX);
        frame.pushConstants(mPass5, mPush5);
        frame.pushConstants(mPass4, mPush4);
    }

    /**
     * 复刻 GLES 的 loadTextures()：5 张噪声图各自按 alphaFactor 处理，
     * 然后按 textureSwap 决定第 0 槽取第 0 张还是第 4 张。
     */
    private void uploadPresetTextures() {
        MagicSmokeScene.Preset preset = MagicSmokeScene.PRESETS[mScene.mCurrentPreset];

        // GLES 在 loadTextures() 里顺带刷新 clear color。
        mScene.mClearColor[0] = ((preset.backColor >> 16) & 0xff) / 255.0f;
        mScene.mClearColor[1] = ((preset.backColor >> 8) & 0xff) / 255.0f;
        mScene.mClearColor[2] = (preset.backColor & 0xff) / 255.0f;
        mScene.mClearColor[3] = 1.0f;
        mPassScene.setClearColor(
                mScene.mClearColor[0], mScene.mClearColor[1],
                mScene.mClearColor[2], mScene.mClearColor[3]);

        /*
         * GLES 把处理结果写进一个标记为 premultiplied 的 Bitmap；Bitmap.setPixels()
         * 的输入按非预乘解释，会再乘一次 alpha。为了让 VK 上传的字节与 GLES 相同，
         * 这里复刻同一次乘法（只在 preMul 或 mode 0 的预设上发生）。
         */
        boolean premultiplyAgain = preset.preMul || preset.processTextureMode == 0;

        float alphaFactor = 1.0f;
        for (int i = 0; i < TEXTURE_COUNT; i++) {
            Bitmap source = AssetLoader.decodeBitmap(
                    context, NOISE_ASSET + (i + 1) + ".png");
            if (source != null) {
                int width = source.getWidth();
                int height = source.getHeight();
                int[] argb = new int[width * height];
                source.getPixels(argb, 0, width, 0, 0, width, height);
                source.recycle();

                mScene.processPixels(argb, preset, alphaFactor);
                if (premultiplyAgain) {
                    premultiplyRgb(argb);
                }

                // 与 GLES bindTextures*() 的绑定顺序一致：textureSwap 时第 0 槽取第 4 张，
                // 第 4 张同时留在第 4 槽（5 层程序里两个采样器指向同一张）。
                if (i == 0) {
                    if (!preset.textureSwap) {
                        mPassScene.setTexture("tex0", argb, width, height);
                    }
                } else {
                    mPassScene.setTexture("tex" + i, argb, width, height);
                    if (i == 4 && preset.textureSwap) {
                        mPassScene.setTexture("tex0", argb, width, height);
                    }
                }
            }
            // 与 GLES 一样，无论这张是否处理成功，因子都继续累乘。
            alphaFactor *= preset.alphaMul;
        }
    }

    /**
     * 与 Android 的 Bitmap.setPixels() 在 premultiplied 位图上做的事一致：
     * 把非预乘的 ARGB 写进预乘存储，RGB 各乘一次 alpha（Skia 的
     * SkMulDiv255Round 取整，而不是截断）。
     */
    private static void premultiplyRgb(int[] pixels) {
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            int alpha = (pixel >>> 24) & 0xff;
            int r = mulDiv255Round((pixel >> 16) & 0xff, alpha);
            int g = mulDiv255Round((pixel >> 8) & 0xff, alpha);
            int b = mulDiv255Round(pixel & 0xff, alpha);
            pixels[i] = (alpha << 24) | (r << 16) | (g << 8) | b;
        }
    }

    private static int mulDiv255Round(int color, int alpha) {
        int product = color * alpha + 128;
        return (product + (product >> 8)) >> 8;
    }

    private float aspectScaleX() {
        // 与 GLES drawWith4Textures/drawWith5Textures 的算法一致。
        float aspect = (float) mWidth / Math.max(1.0f, (float) mHeight);
        return aspect / MagicSmokeScene.REF_ASPECT;
    }

    private void buildPush5(float aspectScaleX) {
        fillLayers(mPush5, 5);
        int o = 5 * 4;
        mPush5[o] = mScene.mXOffset;
        mPush5[o + 1] = -mScene.mYOffset;
        mPush5[o + 2] = aspectScaleX;
        mPush5[o + 3] = 1.0f;
        o += 4;
        System.arraycopy(mScene.mClearColor, 0, mPush5, o, 4);
    }

    private void buildPush4(float aspectScaleX) {
        fillLayers(mPush4, 4);
        int o = 4 * 4;
        mPush4[o] = mScene.mXOffset;
        mPush4[o + 1] = -mScene.mYOffset;
        mPush4[o + 2] = aspectScaleX;
        mPush4[o + 3] = 1.0f;
        o += 4;
        System.arraycopy(mScene.mClearColor, 0, mPush4, o, 4);
    }

    /** 与 GLES setLayerUniform() 相同：glUniform4f(sin, cos, scale * 0.35, xshift)。 */
    private void fillLayers(float[] push, int count) {
        for (int i = 0; i < count; i++) {
            float radians = (float) Math.toRadians(mScene.mRotation[i]);
            int o = i * 4;
            push[o] = (float) Math.sin(radians);
            push[o + 1] = (float) Math.cos(radians);
            push[o + 2] = mScene.mScale[i] * LAYER_SCALE;
            push[o + 3] = mScene.mXShift[i];
        }
    }
}
