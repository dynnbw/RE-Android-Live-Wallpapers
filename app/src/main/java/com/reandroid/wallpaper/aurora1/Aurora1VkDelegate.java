package com.reandroid.wallpaper.aurora1;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.view.MotionEvent;

import com.reandroid.plugin.VkPass;
import com.reandroid.plugin.VkPassFrame;
import com.reandroid.plugin.VkPassRendererDelegate;
import com.reandroid.plugin.VkPassScene;
import com.reandroid.utils.AssetLoader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Aurora1 的 VK 渲染器 —— 壁纸与预览共用。
 *
 * <p>GLES 版每个精灵一次 draw。这里按纹理分批：一张贴图一个 pass，所有精灵把变换烘进
 * 顶点、把颜色/流动/扰动写成顶点属性，每帧一次上传、一次 draw。
 *
 * <p>GLES 的绘制顺序是「背景 → 星星 → 极光 → 粒子」，后三者都用
 * {@code glBlendFunc(SRC_ALPHA, ONE)}。加法混合与顺序无关，所以按纹理重新分组不会改变
 * 结果；背景仍然排在最前，保持它盖在清屏色上的直通 alpha 混合。
 */
final class Aurora1VkDelegate extends VkPassRendererDelegate {

    /** 纹理 id 与 {@link Aurora1Scene} 的 TEXTURE_* 常量一一对应。 */
    private static final int TEXTURE_COUNT = 10;

    /**
     * 顶点属性：位置(2) + UV(2) + 颜色(4) + 流动(2) + 扰动(1)。
     * GLES 里颜色/流动/扰动是逐精灵 uniform，这里改放顶点属性以便同纹理合批。
     */
    private static final int FLOATS_PER_VERTEX = 11;
    private static final int VERTICES_PER_SPRITE = 6; // 两个三角形
    private static final int VERTEX_STRIDE_BYTES = FLOATS_PER_VERTEX * 4;

    /** push constant：4×4 裁剪修正矩阵 + 秒级时间。 */
    private static final int PUSH_FLOATS = 17;
    private static final int PUSH_BYTES = PUSH_FLOATS * 4;

    private static final int MAX_STARS = 30;      // Aurora1Scene.STAR_COUNT
    private static final int MAX_PARTICLES = 60;  // Aurora1Scene.MAX_PARTICLES

    private static final String[] TEXTURE_ASSETS = {
        "aurora1/drawable/aurora1_bg.png",
        "aurora1/drawable/aurora1_bg_landscape.png",
        "aurora1/drawable/aurora1_aurora_1.png",
        "aurora1/drawable/aurora1_aurora_2.png",
        "aurora1/drawable/aurora1_aurora_3.png",
        "aurora1/drawable/aurora1_star.png",
        "aurora1/drawable/aurora1_particle_p1_1.png",
        "aurora1/drawable/aurora1_particle_p1_2.png",
        "aurora1/drawable/aurora1_particle_p1_3.png",
        "aurora1/drawable/aurora1_particle_s1.png",
    };

    /** 每个纹理 pass 的精灵上限（LAYER_SPECS 的数量、STAR_COUNT、MAX_PARTICLES）。 */
    private static final int[] MAX_SPRITES = {
        1, 1, 4, 7, 3, MAX_STARS,
        MAX_PARTICLES, MAX_PARTICLES, MAX_PARTICLES, MAX_PARTICLES,
    };

    private Aurora1Scene mScene;

    private final VkPass[] mPasses = new VkPass[TEXTURE_COUNT];
    private final ByteBuffer[] mVertexBuffers = new ByteBuffer[TEXTURE_COUNT];
    private final int[] mSpriteCounts = new int[TEXTURE_COUNT];
    private final boolean[] mTextureLoaded = new boolean[TEXTURE_COUNT];
    private final String[] mBufferNames = new String[TEXTURE_COUNT];

    private final float[] mPush = new float[PUSH_FLOATS];
    private int mWidth;
    private int mHeight;
    private long mShaderStartTimeMs;

    Aurora1VkDelegate(Context context, SharedPreferences prefs) {
        super(context);
        // Aurora1 没有插件设置，prefs 只为与其它 VK delegate 保持同一构造形状。
    }

    @Override
    public String logTag() {
        return "Aurora1VK";
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
            mScene = new Aurora1Scene(width, height);
        } else {
            mScene.resize(width, height);
        }
    }

    @Override
    public void onPluginPrefsChanged(SharedPreferences prefs) {
        // Aurora1 没有插件设置。
    }

    @Override
    public void onSceneOffset(float xOffset) {
        if (mScene != null) mScene.setOffset(xOffset);
    }

    @Override
    public void onTouchEvent(MotionEvent event) {
        if (mScene != null) mScene.onTouchEvent(event);
    }

    @Override
    protected void buildScene(VkPassScene scene) {
        scene.setClearColor(0.01f, 0.01f, 0.04f, 1.0f);
        System.arraycopy(CLIP_CORRECTION, 0, mPush, 0, 16);

        for (int i = 0; i < TEXTURE_COUNT; i++) {
            String bufferName = "aurora1_buf" + i;
            String textureName = "aurora1_tex" + i;
            mBufferNames[i] = bufferName;

            int maxVertices = MAX_SPRITES[i] * VERTICES_PER_SPRITE;
            scene.createBuffer(bufferName, VERTEX_STRIDE_BYTES, maxVertices, true);
            scene.createTexture(textureName);
            mVertexBuffers[i] = ByteBuffer
                    .allocateDirect(maxVertices * VERTEX_STRIDE_BYTES)
                    .order(ByteOrder.nativeOrder());

            // pass 0/1 是两种背景（竖屏/横屏，实际只有一个有顶点），其余都是加法混合。
            // glBlendFunc 对 RGB 和 alpha 用同一组因子；通用预设把 alpha 的源因子写成 ONE，
            // 这里显式给全四个因子，保证逐通道都与 GLES 一致。
            VkPass pass = scene.add(new VkPass(
                    "shaders/aurora1_vk.vert.spv",
                    "shaders/aurora1_vk.frag.spv",
                    new int[] {2, 2, 4, 2, 1},
                    VERTEX_STRIDE_BYTES,
                    VkPass.TOPOLOGY_TRIANGLE_LIST,
                    i < 2 ? VkPass.BLEND_ALPHA : VkPass.BLEND_ADDITIVE))
                    .buffer(bufferName, 0)
                    .pushConstants(PUSH_BYTES)
                    .blendFactors(
                            VkPass.FACTOR_SRC_ALPHA,
                            i < 2 ? VkPass.FACTOR_ONE_MINUS_SRC_ALPHA : VkPass.FACTOR_ONE,
                            VkPass.FACTOR_SRC_ALPHA,
                            i < 2 ? VkPass.FACTOR_ONE_MINUS_SRC_ALPHA : VkPass.FACTOR_ONE);
            pass.texture(0, textureName);
            mPasses[i] = pass;

            mTextureLoaded[i] = uploadTexture(scene, textureName, TEXTURE_ASSETS[i]);
        }
    }

    @Override
    protected void updateFrame(VkPassFrame frame, long timeMs) {
        if (mScene == null) return;

        mScene.update(timeMs);
        Aurora1Scene.SceneData data = mScene.getSceneData();

        if (mShaderStartTimeMs == 0L) {
            mShaderStartTimeMs = timeMs;
        }
        float shaderTimeSec = ((timeMs - mShaderStartTimeMs) % 600000L) / 1000.0f;

        for (int i = 0; i < TEXTURE_COUNT; i++) {
            mSpriteCounts[i] = 0;
        }

        addSprite(data.background);
        for (Aurora1Scene.Sprite sprite : data.starSprites) {
            addSprite(sprite);
        }
        for (Aurora1Scene.Sprite sprite : data.auroraSprites) {
            addSprite(sprite);
        }
        for (int i = 0; i < data.activeParticleCount; i++) {
            addSprite(data.particleSprites[i]);
        }

        mPush[16] = shaderTimeSec;
        for (int i = 0; i < TEXTURE_COUNT; i++) {
            VkPass pass = mPasses[i];
            if (pass == null) continue;
            int count = mSpriteCounts[i];
            int vertexCount = count * VERTICES_PER_SPRITE;
            frame.setVertexCount(pass, vertexCount);
            if (vertexCount > 0) {
                frame.upload(mBufferNames[i], mVertexBuffers[i],
                        vertexCount * VERTEX_STRIDE_BYTES);
            }
            frame.pushConstants(pass, mPush);
        }
    }

    private void addSprite(Aurora1Scene.Sprite sprite) {
        if (sprite == null) return;
        int textureId = sprite.textureId;
        if (textureId < 0 || textureId >= TEXTURE_COUNT) return;
        if (!mTextureLoaded[textureId]) return;
        // 与 GLES 的 drawSprite 相同的跳过条件。
        if (sprite.alpha <= 0.001f || sprite.width <= 0.0f || sprite.height <= 0.0f) return;

        int count = mSpriteCounts[textureId];
        if (count >= MAX_SPRITES[textureId]) return;

        float radians = sprite.rotationDeg * (float) Math.PI / 180.0f;
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);

        ByteBuffer buffer = mVertexBuffers[textureId];
        int index = count * VERTICES_PER_SPRITE * FLOATS_PER_VERTEX;

        // 顶点的本地坐标/UV 顺序与 asset 里的 quad 一致：
        // (-0.5,-0.5)(0.5,-0.5)(-0.5,0.5)(0.5,0.5) 对应 UV (0,1)(1,1)(0,0)(1,0)。
        // 三角形列表：0,1,2 与 2,1,3（GLES 用的是同一个 quad 的 triangle strip）。
        index = putVertex(buffer, index, -0.5f, -0.5f, 0.0f, 1.0f, sprite, cos, sin);
        index = putVertex(buffer, index, 0.5f, -0.5f, 1.0f, 1.0f, sprite, cos, sin);
        index = putVertex(buffer, index, -0.5f, 0.5f, 0.0f, 0.0f, sprite, cos, sin);
        index = putVertex(buffer, index, -0.5f, 0.5f, 0.0f, 0.0f, sprite, cos, sin);
        index = putVertex(buffer, index, 0.5f, -0.5f, 1.0f, 1.0f, sprite, cos, sin);
        putVertex(buffer, index, 0.5f, 0.5f, 1.0f, 0.0f, sprite, cos, sin);

        mSpriteCounts[textureId] = count + 1;
    }

    private int putVertex(ByteBuffer buffer, int floatIndex,
                          float localX, float localY, float u, float v,
                          Aurora1Scene.Sprite sprite, float cos, float sin) {
        // GLES: translate(x,y) * rotateZ * scale(width,height)
        float scaledX = localX * sprite.width;
        float scaledY = localY * sprite.height;
        float worldX = sprite.x + scaledX * cos - scaledY * sin;
        float worldY = sprite.y + scaledX * sin + scaledY * cos;

        // GL 的 ortho(0,width,0,height) → NDC；y 翻转与 z 映射交给 CLIP_CORRECTION。
        float ndcX = worldX / mWidth * 2.0f - 1.0f;
        float ndcY = worldY / mHeight * 2.0f - 1.0f;

        buffer.putFloat(floatIndex * 4, ndcX);
        buffer.putFloat((floatIndex + 1) * 4, ndcY);
        buffer.putFloat((floatIndex + 2) * 4, u);
        buffer.putFloat((floatIndex + 3) * 4, v);
        buffer.putFloat((floatIndex + 4) * 4, sprite.red);
        buffer.putFloat((floatIndex + 5) * 4, sprite.green);
        buffer.putFloat((floatIndex + 6) * 4, sprite.blue);
        buffer.putFloat((floatIndex + 7) * 4, sprite.alpha);
        buffer.putFloat((floatIndex + 8) * 4, sprite.flowX);
        buffer.putFloat((floatIndex + 9) * 4, sprite.flowY);
        buffer.putFloat((floatIndex + 10) * 4, sprite.distort);
        return floatIndex + FLOATS_PER_VERTEX;
    }

    private boolean uploadTexture(VkPassScene scene, String name, String path) {
        Bitmap bitmap = AssetLoader.decodeBitmap(context, path);
        if (bitmap == null) return false;
        Bitmap argb = bitmap.getConfig() == Bitmap.Config.ARGB_8888
                ? bitmap
                : bitmap.copy(Bitmap.Config.ARGB_8888, false);
        if (argb != bitmap) bitmap.recycle();
        if (argb == null) return false;

        int width = argb.getWidth();
        int height = argb.getHeight();
        int[] pixels = new int[width * height];
        argb.getPixels(pixels, 0, width, 0, 0, width, height);
        argb.recycle();
        scene.setTexture(name, pixels, width, height);
        return true;
    }

    /** GL NDC → Vulkan 裁剪空间（y 翻转、z 到 0..1）。与其它 VK 壁纸保持一致。 */
    private static final float[] CLIP_CORRECTION = {
        1, 0, 0, 0,
        0, -1, 0, 0,
        0, 0, 0.5f, 0,
        0, 0, 0.5f, 1,
    };
}
