package com.reandroid.wallpaper.nixietube;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;

import com.reandroid.plugin.VkPass;
import com.reandroid.plugin.VkPassFrame;
import com.reandroid.plugin.VkPassRendererDelegate;
import com.reandroid.plugin.VkPassScene;
import com.reandroid.utils.AssetLoader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * NixieTube 的 VK 渲染器 —— 壁纸与预览共用。
 *
 * <p>GLES 版每根管子一次 draw（TRIANGLE_FAN，4 顶点）。这里把 8 根管子按 GLES 的
 * 绘制顺序写进同一个顶点批：同一张图集、同一种混合，一次 draw 即可。UV 在 CPU 侧
 * 按帧号展开，和 GLES 的 buildTubeBuffer 逐字段一致。
 */
final class NixieTubeVkDelegate extends VkPassRendererDelegate {

    private static final String TAG = "NixieTubeVK";

    private static final String ATLAS_ASSET = "nixietube/drawable/nixie_atlas.png";
    private static final String ATLAS_TEXTURE = "atlas";
    private static final String VERTEX_BUFFER = "tubes";

    // ---- Atlas grid（与 NixieTubeGL 一致）----
    private static final int ATLAS_COLS = 7;
    private static final int ATLAS_ROWS = 2;
    private static final float UV_CELL_W = 1.0f / ATLAS_COLS;
    private static final float UV_CELL_H = 1.0f / ATLAS_ROWS;

    // ---- Layout（与 NixieTubeGL 一致）----
    private static final int TUBE_COUNT = 8;
    private static final float TUBE_W = 0.11f;
    private static final float TUBE_H = 0.28f;
    private static final float GAP = 0.0f;
    private static final float TOTAL_W = TUBE_COUNT * (TUBE_W + GAP) - GAP;

    private static final int FLOATS_PER_VERTEX = 4; // x, y, u, v
    private static final int VERTICES_PER_TUBE = 6; // 两个三角形
    private static final int VERTEX_COUNT = TUBE_COUNT * VERTICES_PER_TUBE;
    private static final int STRIDE_BYTES = FLOATS_PER_VERTEX * 4;
    private static final int PUSH_BYTES = 16 * 4;

    private final SharedPreferences mPrefs;
    /** 预览路径没有宿主可见性回调，触摸只在这条路径上接管。 */
    private final boolean mPreview;

    private NixieTubeScene mScene;
    private VkPassScene mPassScene;
    private VkPass mPass;
    private ByteBuffer mVertexBuffer;
    private FloatBuffer mVertexFloats;
    private final float[] mVertexScratch = new float[VERTEX_COUNT * FLOATS_PER_VERTEX];
    private final float[] mPush = new float[16];
    private boolean mAtlasUploaded;

    /** 可见性状态；预览路径始终为 true。 */
    private boolean mAudioRequested = true;

    /** native 渲染器建好之前不采音频，避免 VK 初始化失败后一直录音。 */
    private boolean mRendererBuilt;

    private int mWidth;
    private int mHeight;
    private float mScale = 1.0f;

    NixieTubeVkDelegate(Context context, SharedPreferences prefs, boolean preview) {
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
        mWidth = width;
        mHeight = height;
        mScale = computeScale(width, height);
        if (mScene == null) {
            mScene = new NixieTubeScene();
            applyPrefs(mPrefs);
        }
    }

    @Override
    public void onPluginPrefsChanged(SharedPreferences prefs) {
        if (mScene != null) applyPrefs(prefs);
    }

    /** 设置里换音源/开关音频时，Scene 会先停再起；不可见时保持停采集。 */
    private void applyPrefs(SharedPreferences prefs) {
        mScene.setPluginPrefs(prefs);
        if (!mAudioRequested || !mRendererBuilt) mScene.stopAudio();
    }

    void startAudio() {
        mAudioRequested = true;
        if (mScene != null && mRendererBuilt) mScene.startAudio();
    }

    void stopAudio() {
        mAudioRequested = false;
        if (mScene != null) mScene.stopAudio();
    }

    /** 壁纸不可见时直接回到时钟，和 GLES 版 resetModeToTime 一致。 */
    void resetModeToTime() {
        if (mScene != null) mScene.stop();
    }

    @Override
    public void onTouchEvent(MotionEvent event) {
        if (mPreview && event != null && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            tap();
        }
    }

    @Override
    public void onCommand(String action, int x, int y, int z, Bundle extras) {
        // 桌面路径：系统发送 tap 命令，和 GLES 版一致。
        if ("android.wallpaper.tap".equals(action)) tap();
    }

    private void tap() {
        if (mScene != null) mScene.onTap();
    }

    @Override
    protected void buildScene(VkPassScene scene) {
        mPassScene = scene;
        mAtlasUploaded = false;
        mRendererBuilt = true;
        scene.setClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        scene.createBuffer(VERTEX_BUFFER, STRIDE_BYTES, VERTEX_COUNT, true);
        scene.createTexture(ATLAS_TEXTURE);

        mPass = scene.add(new VkPass(
                        "shaders/nixietube_vk.vert.spv",
                        "shaders/nixietube_vk.frag.spv",
                        new int[] {2, 2},
                        STRIDE_BYTES,
                        VkPass.TOPOLOGY_TRIANGLE_LIST,
                        VkPass.BLEND_ALPHA))
                .buffer(VERTEX_BUFFER, VERTEX_COUNT)
                .pushConstants(PUSH_BYTES)
                // glBlendFunc(SRC_ALPHA, ONE_MINUS_SRC_ALPHA) 对 alpha 通道同样生效；
                // 通用 BLEND_ALPHA 预设给 srcAlpha 用的是 ONE。
                .blendFactors(
                        VkPass.FACTOR_SRC_ALPHA,
                        VkPass.FACTOR_ONE_MINUS_SRC_ALPHA,
                        VkPass.FACTOR_SRC_ALPHA,
                        VkPass.FACTOR_ONE_MINUS_SRC_ALPHA);
        mPass.texture(0, ATLAS_TEXTURE);

        mVertexBuffer = ByteBuffer.allocateDirect(VERTEX_COUNT * STRIDE_BYTES)
                .order(ByteOrder.nativeOrder());
        mVertexFloats = mVertexBuffer.asFloatBuffer();

        System.arraycopy(CLIP_CORRECTION, 0, mPush, 0, 16);

        // surface 重建会重新走到这里；音频按可见性恢复。
        if (mAudioRequested && mScene != null) mScene.startAudio();
    }

    @Override
    protected void updateFrame(VkPassFrame frame, long timeMs) {
        if (mScene == null || mWidth <= 0 || mHeight <= 0) return;
        if (!mAtlasUploaded) uploadAtlas();

        mScene.update(timeMs);
        int[] frames = mScene.getDisplayValues();
        buildVertices(frames);

        frame.upload(VERTEX_BUFFER, mVertexBuffer, VERTEX_COUNT * STRIDE_BYTES);
        frame.setVertexCount(mPass, VERTEX_COUNT);
        frame.pushConstants(mPass, mPush);
    }

    private void buildVertices(int[] frames) {
        float[] v = mVertexScratch;
        int index = 0;

        // GLES 的 ortho 里 1 个单位 = 屏幕高度的一半；先落到像素，再转 GL NDC。
        float halfHeight = mHeight * 0.5f;
        float centerX = mWidth * 0.5f;
        float centerY = mHeight * 0.5f;
        float tubeW = TUBE_W * mScale;
        float tubeH = TUBE_H * mScale;
        float gap = GAP * mScale;
        float totalW = TOTAL_W * mScale;
        float halfTubeH = tubeH * 0.5f;

        for (int i = 0; i < TUBE_COUNT; i++) {
            float unitX0 = -totalW * 0.5f + i * (tubeW + gap);
            float x0 = centerX + unitX0 * halfHeight;
            float x1 = x0 + tubeW * halfHeight;
            float top = centerY - halfTubeH * halfHeight;
            float bottom = centerY + halfTubeH * halfHeight;

            float left = x0 / mWidth * 2.0f - 1.0f;
            float right = x1 / mWidth * 2.0f - 1.0f;
            float ndcTop = 1.0f - top / mHeight * 2.0f;
            float ndcBottom = 1.0f - bottom / mHeight * 2.0f;

            int frameIndex = frames[i];
            int col = frameIndex % ATLAS_COLS;
            int row = frameIndex / ATLAS_COLS;
            float u0 = col * UV_CELL_W;
            float v0 = row * UV_CELL_H;
            float u1 = u0 + UV_CELL_W;
            float v1 = v0 + UV_CELL_H;

            // 与 GLES 的 TRIANGLE_FAN（左上、右上、右下、左下）等价的两个三角形。
            index = putVertex(v, index, left, ndcTop, u0, v0);
            index = putVertex(v, index, right, ndcTop, u1, v0);
            index = putVertex(v, index, right, ndcBottom, u1, v1);
            index = putVertex(v, index, left, ndcTop, u0, v0);
            index = putVertex(v, index, right, ndcBottom, u1, v1);
            index = putVertex(v, index, left, ndcBottom, u0, v1);
        }

        mVertexFloats.clear();
        mVertexFloats.put(v);
    }

    private static int putVertex(float[] out, int index, float x, float y, float u, float v) {
        out[index] = x;
        out[index + 1] = y;
        out[index + 2] = u;
        out[index + 3] = v;
        return index + FLOATS_PER_VERTEX;
    }

    private void uploadAtlas() {
        // 只尝试一次：资产固定，失败后每帧重试只会刷日志。
        mAtlasUploaded = true;
        if (mPassScene == null) return;
        try {
            Bitmap bitmap = AssetLoader.decodeBitmap(context, ATLAS_ASSET);
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
            mPassScene.setTexture(ATLAS_TEXTURE, pixels, width, height);
        } catch (Exception e) {
            Log.e(TAG, "Failed to upload nixie atlas", e);
        }
    }

    private static float computeScale(int width, int height) {
        float aspect = (float) width / (float) height;
        float scale = (1.4f * aspect) / TOTAL_W;
        scale = Math.max(1.0f, scale);
        return Math.min(scale, 1.6f / TUBE_H);
    }

    @Override
    public void destroyRenderer() {
        mRendererBuilt = false;
        if (mScene != null) {
            mScene.stopAudio();
            mScene.stop();
        }
        super.destroyRenderer();
    }

    private static final float[] CLIP_CORRECTION = {
        1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 0.5f, 0, 0, 0, 0.5f, 1,
    };
}
