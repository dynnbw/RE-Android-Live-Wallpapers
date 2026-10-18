package com.reandroid.wallpaper.wildworld;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;

import com.reandroid.plugin.VkPass;
import com.reandroid.plugin.VkPassFrame;
import com.reandroid.plugin.VkPassRendererDelegate;
import com.reandroid.plugin.VkPassScene;
import com.reandroid.utils.AssetLoader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * WildWorld 的 VK 渲染器 —— 壁纸与预览共用。
 *
 * <p>GLES 版每画一个矩形就一次 draw。这里按贴图分批：同一张贴图的所有矩形写进同一个
 * 顶点批（顶点里带 alpha），一次 draw 画完。pass 的声明顺序与 GLES 的 draw 顺序严格
 * 一致，只有昼夜背景会按状态把对应 pass 的顶点数置 0。
 *
 * <p>恐龙贴图在 GLES 里分两次画（上层在 layer4 之后、下层在 layer3 之后），因此它在
 * 这里也占两个 pass，中间隔着 layer3，避免合批打乱叠放顺序。
 */
final class WildWorldVkDelegate extends VkPassRendererDelegate {

    // ---- 顶点格式 ----

    private static final int FLOATS_PER_VERTEX = 5; // x, y, u, v, alpha
    private static final int VERTEX_STRIDE_BYTES = FLOATS_PER_VERTEX * 4;
    private static final int VERTICES_PER_SPRITE = 6; // 两个三角形
    private static final int PUSH_BYTES = 16 * 4;

    // ---- 纹理下标（与 TEXTURE_ASSETS 一一对应）----

    private static final int TEX_BG_DAY_UP = 0;
    private static final int TEX_BG_DAY_DOWN = 1;
    private static final int TEX_BG_NIGHT_UP = 2;
    private static final int TEX_BG_NIGHT_DOWN = 3;
    private static final int TEX_FIREBALL = 4;
    private static final int TEX_LAYER5 = 5;
    private static final int TEX_LAYER51 = 6;
    private static final int TEX_PTEROSAUR = 7;
    private static final int TEX_LAYER4 = 8;
    private static final int TEX_LAYER41 = 9;
    private static final int TEX_DINOSAUR = 10;
    private static final int TEX_LAYER3 = 11;
    private static final int TEX_LAYER31 = 12;
    private static final int TEX_LAYER2 = 13;
    private static final int TEX_LAYER21 = 14;
    private static final int TEX_LAYER1 = 15;
    private static final int TEX_LAYER11 = 16;

    private static final String[] TEXTURE_ASSETS = {
        "wildworld/drawable/ww_bgday1.jpg",
        "wildworld/drawable/ww_bgday.png",
        "wildworld/drawable/ww_bgnight1.jpg",
        "wildworld/drawable/ww_bgnight.png",
        "wildworld/drawable/ww_fireball.png",
        "wildworld/drawable/ww_layer5.png",
        "wildworld/drawable/ww_layer51.png",
        "wildworld/drawable/ww_pterosaur.png",
        "wildworld/drawable/ww_layer4.png",
        "wildworld/drawable/ww_layer41.png",
        "wildworld/drawable/ww_dinosaur.png",
        "wildworld/drawable/ww_layer3.png",
        "wildworld/drawable/ww_layer31.png",
        "wildworld/drawable/ww_layer2.png",
        "wildworld/drawable/ww_layer21.png",
        "wildworld/drawable/ww_layer1.png",
        "wildworld/drawable/ww_layer11.png",
    };

    // ---- pass 下标（声明顺序 = GLES 的绘制顺序）----

    private static final int PASS_BG_DAY_UP = 0;
    private static final int PASS_BG_DAY_DOWN = 1;
    private static final int PASS_BG_NIGHT_UP = 2;
    private static final int PASS_BG_NIGHT_DOWN = 3;
    private static final int PASS_FIREBALL = 4;
    private static final int PASS_LAYER5 = 5;
    private static final int PASS_LAYER51 = 6;
    private static final int PASS_PTEROSAUR = 7;
    private static final int PASS_LAYER4 = 8;
    private static final int PASS_LAYER41 = 9;
    private static final int PASS_DINO_UP = 10;
    private static final int PASS_LAYER3 = 11;
    private static final int PASS_LAYER31 = 12;
    private static final int PASS_DINO_DOWN = 13;
    private static final int PASS_LAYER2 = 14;
    private static final int PASS_LAYER21 = 15;
    private static final int PASS_LAYER1 = 16;
    private static final int PASS_LAYER11 = 17;

    private static final int PASS_COUNT = 18;

    /** 每个 pass 绑定的纹理。恐龙两个 pass 共用一张贴图。 */
    private static final int[] PASS_TEXTURES = {
        TEX_BG_DAY_UP, TEX_BG_DAY_DOWN, TEX_BG_NIGHT_UP, TEX_BG_NIGHT_DOWN,
        TEX_FIREBALL,
        TEX_LAYER5, TEX_LAYER51,
        TEX_PTEROSAUR,
        TEX_LAYER4, TEX_LAYER41,
        TEX_DINOSAUR,
        TEX_LAYER3, TEX_LAYER31,
        TEX_DINOSAUR,
        TEX_LAYER2, TEX_LAYER21,
        TEX_LAYER1, TEX_LAYER11,
    };

    /**
     * 每个 pass 的矩形上限：背景各 1 个；火球 6 个（WildWorldScene.FIREBALL_COUNT）；
     * 滚动层上下各 2 个；翼龙 1 个；恐龙每个 pass 1 个。
     */
    private static final int[] PASS_MAX_SPRITES = {
        1, 1, 1, 1,
        6,
        2, 2,
        1,
        2, 2,
        1,
        2, 2,
        1,
        2, 2,
        2, 2,
    };

    private final boolean mPreview;
    private WildWorldScene mScene;
    private int mWidth;
    private int mHeight;

    private VkPass[] mPasses;
    private String[] mBufferNames;
    private ByteBuffer[] mVertexBuffers;
    private final int[] mSpriteCounts = new int[PASS_COUNT];
    private final float[] mPush = new float[16];

    WildWorldVkDelegate(Context context, SharedPreferences prefs, boolean preview) {
        super(context);
        mPreview = preview;
    }

    @Override
    public String logTag() {
        return "WildWorldVK";
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
            mScene = new WildWorldScene();
            mScene.initState(width, height);
            if (mPreview) {
                // 与 WildWorldGL.onCreate() 一致：预览没有桌面滚动，按 0.5 的偏移初始化。
                mScene.mXOffsetPixels = 0.5f;
            }
        } else {
            // GLES 的 resize() 也会重新 initState，保持同样的重置行为。
            mScene.initState(width, height);
        }
    }

    @Override
    public void onSceneOffset(float xOffset) {
        // 通用 VK 引擎传的是 0..1 的比例，GLES 引擎传的是滚动像素；
        // WildWorldScene 按像素消费，这里换算回与 GLES 相同的量纲。
        if (mScene != null) {
            mScene.mXOffsetPixels = xOffset * mWidth;
        }
    }

    @Override
    public void onCommand(String action, int x, int y, int z, Bundle extras) {
        if (mScene == null) return;
        if ("android.wallpaper.tap".equals(action)) {
            mScene.mTouchX = x;
            mScene.mTouchY = y;
            mScene.mTouchPending = true;
        }
    }

    @Override
    protected void buildScene(VkPassScene scene) {
        // 与 GLES 的 glClearColor(0, 0, 0, 1) 保持一致。
        scene.setClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        System.arraycopy(CLIP_CORRECTION, 0, mPush, 0, 16);

        mPasses = new VkPass[PASS_COUNT];
        mBufferNames = new String[PASS_COUNT];
        mVertexBuffers = new ByteBuffer[PASS_COUNT];

        // 每张纹理只上传一次；恐龙的两个 pass 共用同一个纹理名。
        for (int i = 0; i < TEXTURE_ASSETS.length; i++) {
            String textureName = textureName(i);
            scene.createTexture(textureName);
            uploadTexture(scene, textureName, TEXTURE_ASSETS[i]);
        }

        for (int i = 0; i < PASS_COUNT; i++) {
            String bufferName = "ww_batch" + i;
            mBufferNames[i] = bufferName;
            int maxVertices = PASS_MAX_SPRITES[i] * VERTICES_PER_SPRITE;
            scene.createBuffer(bufferName, VERTEX_STRIDE_BYTES, maxVertices, true);
            mVertexBuffers[i] = ByteBuffer
                    .allocateDirect(maxVertices * VERTEX_STRIDE_BYTES)
                    .order(ByteOrder.nativeOrder());

            // GLES：glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)，
            // RGB 与 alpha 使用同一组因子，因此四个因子都显式给出。
            VkPass pass = scene.add(new VkPass(
                    "shaders/wildworld_vk.vert.spv",
                    "shaders/wildworld_vk.frag.spv",
                    new int[] {2, 2, 1}, VERTEX_STRIDE_BYTES,
                    VkPass.TOPOLOGY_TRIANGLE_LIST, VkPass.BLEND_ALPHA))
                    .buffer(bufferName, 0)
                    .pushConstants(PUSH_BYTES)
                    .blendFactors(
                            VkPass.FACTOR_SRC_ALPHA,
                            VkPass.FACTOR_ONE_MINUS_SRC_ALPHA,
                            VkPass.FACTOR_SRC_ALPHA,
                            VkPass.FACTOR_ONE_MINUS_SRC_ALPHA);
            pass.texture(0, textureName(PASS_TEXTURES[i]));
            mPasses[i] = pass;
        }
    }

    @Override
    protected void updateFrame(VkPassFrame frame, long timeMs) {
        if (mScene == null) return;

        // GLES 侧引擎给 drawFrame 的时间是 System.currentTimeMillis()，而
        // WildWorldScene 内部的翼龙/恐龙/火球时间戳用 SystemClock.uptimeMillis()。
        // 这里沿用 GLES 的时钟，保证两侧的动画节奏与特效触发时机完全一致。
        mScene.updateFrame(System.currentTimeMillis());

        for (int i = 0; i < PASS_COUNT; i++) {
            mSpriteCounts[i] = 0;
        }

        addBackground();
        addFireballs();
        addSeamlessLayers(PASS_LAYER5, PASS_LAYER51, mScene.mVcnLayer);
        addPterosaur();
        addSeamlessLayers(PASS_LAYER4, PASS_LAYER41, mScene.mLayer4);
        addDinosaur(PASS_DINO_UP, WildWorldScene.UP);
        addSeamlessLayers(PASS_LAYER3, PASS_LAYER31, mScene.mLayer3);
        addDinosaur(PASS_DINO_DOWN, WildWorldScene.DOWN);
        addSeamlessLayers(PASS_LAYER2, PASS_LAYER21, mScene.mLayer2);
        addSeamlessLayers(PASS_LAYER1, PASS_LAYER11, mScene.mLayer1);

        for (int i = 0; i < PASS_COUNT; i++) {
            int sprites = mSpriteCounts[i];
            int vertexCount = sprites * VERTICES_PER_SPRITE;
            frame.setVertexCount(mPasses[i], vertexCount);
            if (vertexCount > 0) {
                frame.upload(mBufferNames[i], mVertexBuffers[i],
                        vertexCount * VERTEX_STRIDE_BYTES);
            }
            frame.pushConstants(mPasses[i], mPush);
        }
    }

    /** WildWorldGL.drawDayAndNightLayer()：pass 顺序固定为 0..3，未激活的置 0。 */
    private void addBackground() {
        boolean drawDay;
        boolean drawNight;
        if (mScene.mDayNight > 0) {
            drawDay = true;
            drawNight = mScene.mAnimation == 0;
        } else {
            drawDay = mScene.mAnimation != 0;
            drawNight = true;
        }

        if (drawDay) {
            addLayer(PASS_BG_DAY_UP, mScene.mDay[WildWorldScene.UP]);
            addLayer(PASS_BG_DAY_DOWN, mScene.mDay[WildWorldScene.DOWN]);
        }
        if (drawNight) {
            addLayer(PASS_BG_NIGHT_UP, mScene.mNight[WildWorldScene.UP]);
            addLayer(PASS_BG_NIGHT_DOWN, mScene.mNight[WildWorldScene.DOWN]);
        }
    }

    private void addLayer(int pass, WildWorldScene.Layer layer) {
        addQuad(pass, layer.x, layer.y, layer.x + layer.w, layer.y + layer.h);
    }

    /** WildWorldGL.drawLayers() 里的火球段。 */
    private void addFireballs() {
        if (mScene.mFireballsShow == 0) return;
        for (int i = 0; i < WildWorldScene.FIREBALL_COUNT; i++) {
            WildWorldScene.Fireball f = mScene.mFireballs[i];
            if (mScene.mCurTime > f.startTime && f.steps > 0) {
                float offX = f.x + mScene.mXOffset;
                addQuad(PASS_FIREBALL, offX, f.y, offX + f.w, f.y + f.h);
            }
        }
    }

    /** WildWorldGL.drawPterosaur()。 */
    private void addPterosaur() {
        WildWorldScene.Pterosaur p = mScene.mPterosaur;
        if (p.alive == 0) return;
        float offX = p.x + mScene.mXOffset;
        if (offX + p.w > 0 && offX < mScene.mScreenWidth) {
            addQuad(PASS_PTEROSAUR, offX, p.y, offX + p.w, p.y + p.h);
        }
    }

    /** WildWorldGL.drawDinosaur()。 */
    private void addDinosaur(int pass, int ud) {
        WildWorldScene.Dinosaur d = mScene.mDinosaur[ud];
        if (d.alive == 0) return;
        float offX = d.x + mScene.mXOffset;
        if (offX + d.w > 0 && offX < mScene.mScreenWidth) {
            addQuad(pass, offX, d.y - d.stepY,
                    offX + d.w, d.y + d.h - d.stepY);
        }
    }

    /** WildWorldGL.drawLayerSeamless()：上下两层各自画「当前 + 左侧补片」两遍。 */
    private float addSeamlessLayers(
            int passUp, int passDown, WildWorldScene.Layer[] layers) {
        WildWorldScene.Layer up = layers[WildWorldScene.UP];
        WildWorldScene.Layer down = layers[WildWorldScene.DOWN];

        float offX = up.x + mScene.mXOffset;
        while (offX < 0) offX += up.w;

        addQuad(passUp, offX, up.y, offX + up.w, up.y + up.h);
        addQuad(passUp, offX - up.w, up.y, offX, up.y + up.h);
        // 与 GLES 一样：第一片用 down.w，第二片用 up.w（两者都是屏宽）。
        addQuad(passDown, offX, down.y, offX + down.w, down.y + down.h);
        addQuad(passDown, offX - up.w, down.y, offX, down.y + down.h);
        return offX;
    }

    /**
     * 追加一个矩形。顶点顺序与 GLES 的 TRIANGLE_FAN 一致：
     * 左上、左下、右下、右上，展开成两个三角形 (0,1,2) 与 (0,2,3)。
     */
    private void addQuad(int pass, float x0, float y0, float x1, float y1) {
        int sprites = mSpriteCounts[pass];
        if (sprites >= PASS_MAX_SPRITES[pass]) return;

        ByteBuffer buffer = mVertexBuffers[pass];
        int offset = sprites * VERTICES_PER_SPRITE * FLOATS_PER_VERTEX;

        // GL 的 ortho(0, w, h, 0)：x 线性映射，y 翻转；再交给 CLIP_CORRECTION。
        float ndcLeft = x0 / mWidth * 2.0f - 1.0f;
        float ndcRight = x1 / mWidth * 2.0f - 1.0f;
        float ndcTop = 1.0f - y0 / mHeight * 2.0f;
        float ndcBottom = 1.0f - y1 / mHeight * 2.0f;

        // 上边 v=0、下边 v=1，与 WildWorldGL.QUAD_TEMPLATE 相同；
        // VK 纹理按行序上传（行 0 → v=0），所以这里不能再翻转。
        offset = putVertex(buffer, offset, ndcLeft, ndcTop, 0.0f, 0.0f);
        offset = putVertex(buffer, offset, ndcLeft, ndcBottom, 0.0f, 1.0f);
        offset = putVertex(buffer, offset, ndcRight, ndcBottom, 1.0f, 1.0f);

        offset = putVertex(buffer, offset, ndcLeft, ndcTop, 0.0f, 0.0f);
        offset = putVertex(buffer, offset, ndcRight, ndcBottom, 1.0f, 1.0f);
        putVertex(buffer, offset, ndcRight, ndcTop, 1.0f, 0.0f);

        mSpriteCounts[pass] = sprites + 1;
    }

    private static int putVertex(ByteBuffer buffer, int floatIndex,
                                 float x, float y, float u, float v) {
        buffer.putFloat(floatIndex * 4, x);
        buffer.putFloat((floatIndex + 1) * 4, y);
        buffer.putFloat((floatIndex + 2) * 4, u);
        buffer.putFloat((floatIndex + 3) * 4, v);
        // GLES 每帧都把 uAlpha 设为 1.0。
        buffer.putFloat((floatIndex + 4) * 4, 1.0f);
        return floatIndex + FLOATS_PER_VERTEX;
    }

    private static String textureName(int index) {
        return "ww_tex" + index;
    }

    private void uploadTexture(VkPassScene scene, String name, String asset) {
        Bitmap bitmap = AssetLoader.decodeBitmap(context, asset);
        if (bitmap == null) return;
        Bitmap argb = bitmap.getConfig() == Bitmap.Config.ARGB_8888
                ? bitmap
                : bitmap.copy(Bitmap.Config.ARGB_8888, false);
        if (argb != bitmap) bitmap.recycle();
        if (argb == null) return;

        int width = argb.getWidth();
        int height = argb.getHeight();
        int[] pixels = new int[width * height];
        argb.getPixels(pixels, 0, width, 0, 0, width, height);
        argb.recycle();
        scene.setTexture(name, pixels, width, height);
    }

    /** GL NDC → Vulkan 裁剪空间（y 翻转、z 到 0..1）。与其它 VK 壁纸保持一致。 */
    private static final float[] CLIP_CORRECTION = {
        1, 0, 0, 0,
        0, -1, 0, 0,
        0, 0, 0.5f, 0,
        0, 0, 0.5f, 1,
    };
}
