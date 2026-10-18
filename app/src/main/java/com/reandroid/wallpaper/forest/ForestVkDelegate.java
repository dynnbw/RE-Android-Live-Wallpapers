package com.reandroid.wallpaper.forest;

import android.content.Context;
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
 * Forest 的 VK 渲染器 —— 壁纸与预览共用。
 *
 * GLES 版是一个程序、每株草和每个粒子各一次 draw。这里保留完全相同的绘制顺序：
 * 背景 → 叠加层 → 6 株草 → 粒子。
 *
 * 草的本地顶点只有一份，逐株的模型矩阵走 push constant；粒子把 translate/scale
 * 烘进顶点后合成一个 pass。两者都不会改变混合顺序：草仍按 0..5 逐株提交，粒子仍按
 * 场景数组顺序提交，和 GLES 的逐次 glDrawArrays 一致。
 */
final class ForestVkDelegate extends VkPassRendererDelegate {

    /** 顶点属性：位置(2) + UV(2) + alpha(1)。 */
    private static final int FLOATS_PER_VERTEX = 5;
    private static final int VERTICES_PER_QUAD = 6; // 两个三角形
    private static final int STRIDE_BYTES = FLOATS_PER_VERTEX * 4;
    /** push constant：4x4 矩阵。 */
    private static final int PUSH_BYTES = 16 * 4;

    private static final float SCREEN_W = ForestScene.SCREEN_W;
    private static final float SCREEN_H = ForestScene.SCREEN_H;
    private static final float STEM_W = ForestScene.STEM_WIDTH;
    private static final float STEM_H = ForestScene.STEM_HEIGHT;

    private static final int MAX_STEMS = ForestScene.MAX_STEM;
    private static final int MAX_PARTICLES = ForestScene.MAX_PARTICLES;
    private static final int MAX_PARTICLE_VERTICES = MAX_PARTICLES * VERTICES_PER_QUAD;

    private static final String VS_ASSET = "shaders/forest_vk.vert.spv";
    private static final String FS_ASSET = "shaders/forest_vk.frag.spv";

    private static final String TEX_BG = "forest_tex_bg";
    private static final String TEX_OVERLAY = "forest_tex_overlay";
    private static final String TEX_PARTICLE = "forest_tex_particle";
    private static final String[] STEM_TEXTURES = {
            "forest_tex_stem_a",
            "forest_tex_stem_b1",
            "forest_tex_stem_c",
            "forest_tex_stem_b2",
    };

    /** 纹理名与 GLES 的 mTexIndex 顺序对齐（0=bg, 1=overlay, 3=particle, 4..7=stems）。 */
    private static final String[] TEXTURE_NAMES = {
            TEX_BG,
            TEX_OVERLAY,
            TEX_PARTICLE,
            STEM_TEXTURES[0],
            STEM_TEXTURES[1],
            STEM_TEXTURES[2],
            STEM_TEXTURES[3],
    };
    private static final String[] TEXTURE_ASSETS = {
            "forest/drawable/forest_bg_16_16.png",
            "forest/drawable/forest_bg_overlay_256_512.png",
            "forest/drawable/forest_particle_32_32.png",
            "forest/drawable/forest_stem_a_64_512.png",
            "forest/drawable/forest_stem_b_01_64_512.png",
            "forest/drawable/forest_stem_c_64_512.png",
            "forest/drawable/forest_stem_b_02_64_512.png",
    };

    /** 与 ForestScene.initStems() 的 texIndex 一致：4,5,6,7,4,6。 */
    private static final int[] STEM_TEXTURE_INDEX = {3, 4, 5, 6, 3, 5};

    private static final String BUF_BG = "forest_buf_bg";
    private static final String BUF_OVERLAY = "forest_buf_overlay";
    private static final String BUF_STEM = "forest_buf_stem";
    private static final String BUF_PARTICLE = "forest_buf_particle";

    private ForestScene mScene;
    private int mWidth;
    private int mHeight;
    /** GLES 的 ForestGL 在预览里把滚动偏移固定为 0（见 ForestScene.setOffset）。 */
    private boolean mPreview;

    private VkPass mBgPass;
    private VkPass mOverlayPass;
    private final VkPass[] mStemPasses = new VkPass[MAX_STEMS];
    private VkPass mParticlePass;

    private ByteBuffer mBgVertices;
    private ByteBuffer mOverlayVertices;
    private final ByteBuffer mStemVertices = allocateVertices(VERTICES_PER_QUAD);
    private ByteBuffer mParticleVertices;
    private int mParticleCount;

    private final float[] mStemPush = new float[16];
    private final float[] mModelMatrix = new float[16];
    private final float[] mTempMatrix = new float[16];

    /** GL ortho(0,240,0,320,-10,1000)，列主序。 */
    private static final float[] PROJECTION = {
            1.0f / 120.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f / 160.0f, 0.0f, 0.0f,
            0.0f, 0.0f, -2.0f / 1010.0f, 0.0f,
            -1.0f, -1.0f, -990.0f / 1010.0f, 1.0f,
    };

    /** GL NDC → Vulkan 裁剪空间（y 翻转、z 到 0..1）。 */
    private static final float[] CLIP_CORRECTION = {
            1, 0, 0, 0,
            0, -1, 0, 0,
            0, 0, 0.5f, 0,
            0, 0, 0.5f, 1,
    };

    ForestVkDelegate(Context context) {
        super(context);
    }

    @Override
    public String logTag() {
        return "ForestVK";
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
        // 与 GLES 一致：场景只在首次拿到尺寸时 init，旋转不重置粒子与草。
        if (mScene == null) {
            mScene = new ForestScene();
            mScene.init(width, height);
        }
    }

    /** 通用 VK 引擎没有 delegate 侧的预览钩子，由插件在 setPreview 时转发。 */
    void setPreview(boolean preview) {
        mPreview = preview;
    }

    @Override
    public void onSceneOffset(float xOffset) {
        if (mScene != null) mScene.setOffset(xOffset, mPreview);
    }

    @Override
    public void onTouchEvent(MotionEvent event) {
        if (mScene == null || event == null || mWidth <= 0 || mHeight <= 0) return;
        int action;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: action = ForestScene.ACTION_DOWN; break;
            case MotionEvent.ACTION_UP:   action = ForestScene.ACTION_UP; break;
            case MotionEvent.ACTION_MOVE: action = ForestScene.ACTION_MOVE; break;
            default: return;
        }
        float sx = SCREEN_W / mWidth;
        float sy = SCREEN_H / mHeight;
        mScene.onTouchEvent(action, event.getX() * sx, event.getY() * sy);
    }

    @Override
    protected void buildScene(VkPassScene scene) {
        scene.setClearColor(0.0f, 0.0f, 0.0f, 1.0f);

        for (int i = 0; i < TEXTURE_NAMES.length; i++) {
            scene.createTexture(TEXTURE_NAMES[i]);
            uploadTexture(scene, TEXTURE_NAMES[i], TEXTURE_ASSETS[i]);
        }

        mBgVertices = allocateVertices(VERTICES_PER_QUAD);
        mOverlayVertices = allocateVertices(VERTICES_PER_QUAD);
        mParticleVertices = allocateVertices(MAX_PARTICLE_VERTICES);

        scene.createBuffer(BUF_BG, STRIDE_BYTES, VERTICES_PER_QUAD, true);
        scene.createBuffer(BUF_OVERLAY, STRIDE_BYTES, VERTICES_PER_QUAD, true);
        scene.createBuffer(BUF_STEM, STRIDE_BYTES, VERTICES_PER_QUAD, true);
        scene.createBuffer(BUF_PARTICLE, STRIDE_BYTES, MAX_PARTICLE_VERTICES, true);

        // 草的本地 quad：位置保持世界坐标，逐株变换由 push constant 完成。
        writeQuad(mStemVertices, 0, 0.0f, 0.0f, STEM_W, STEM_H, 1.0f);

        int[] attribs = {2, 2, 1};

        // 背景：GLES 里关掉混合。
        mBgPass = scene.add(new VkPass(
                VS_ASSET, FS_ASSET, attribs, STRIDE_BYTES,
                VkPass.TOPOLOGY_TRIANGLE_LIST, VkPass.BLEND_NONE))
                .buffer(BUF_BG, 0)
                .pushConstants(PUSH_BYTES);
        mBgPass.texture(0, TEX_BG);

        // 叠加层：glBlendFunc(SRC_ALPHA, ONE)。四个因子都显式给全，alpha 通道也与 GLES 一致。
        mOverlayPass = scene.add(new VkPass(
                VS_ASSET, FS_ASSET, attribs, STRIDE_BYTES,
                VkPass.TOPOLOGY_TRIANGLE_LIST, VkPass.BLEND_ADDITIVE))
                .buffer(BUF_OVERLAY, 0)
                .pushConstants(PUSH_BYTES)
                .blendFactors(
                        VkPass.FACTOR_SRC_ALPHA, VkPass.FACTOR_ONE,
                        VkPass.FACTOR_SRC_ALPHA, VkPass.FACTOR_ONE);
        mOverlayPass.texture(0, TEX_OVERLAY);

        // 草：SRC_ALPHA / ONE_MINUS_SRC_ALPHA，按场景顺序逐株一个 pass。
        for (int i = 0; i < MAX_STEMS; i++) {
            VkPass pass = scene.add(new VkPass(
                    VS_ASSET, FS_ASSET, attribs, STRIDE_BYTES,
                    VkPass.TOPOLOGY_TRIANGLE_LIST, VkPass.BLEND_ALPHA))
                    .buffer(BUF_STEM, 0)
                    .pushConstants(PUSH_BYTES)
                    .blendFactors(
                            VkPass.FACTOR_SRC_ALPHA, VkPass.FACTOR_ONE_MINUS_SRC_ALPHA,
                            VkPass.FACTOR_SRC_ALPHA, VkPass.FACTOR_ONE_MINUS_SRC_ALPHA);
            pass.texture(0, TEXTURE_NAMES[STEM_TEXTURE_INDEX[i]]);
            mStemPasses[i] = pass;
        }

        // 粒子：同样是非预乘 alpha，一次 pass 画完。
        mParticlePass = scene.add(new VkPass(
                VS_ASSET, FS_ASSET, attribs, STRIDE_BYTES,
                VkPass.TOPOLOGY_TRIANGLE_LIST, VkPass.BLEND_ALPHA))
                .buffer(BUF_PARTICLE, 0)
                .pushConstants(PUSH_BYTES)
                .blendFactors(
                        VkPass.FACTOR_SRC_ALPHA, VkPass.FACTOR_ONE_MINUS_SRC_ALPHA,
                        VkPass.FACTOR_SRC_ALPHA, VkPass.FACTOR_ONE_MINUS_SRC_ALPHA);
        mParticlePass.texture(0, TEX_PARTICLE);
    }

    @Override
    protected void updateFrame(VkPassFrame frame, long timeMs) {
        if (mScene == null) return;
        mScene.update();

        // 背景
        writeScreenQuad(mBgVertices, 0.0f, mScene.mLandscapeY, 1.0f);
        frame.upload(BUF_BG, mBgVertices, VERTICES_PER_QUAD * STRIDE_BYTES);
        frame.setVertexCount(mBgPass, VERTICES_PER_QUAD);
        frame.pushConstants(mBgPass, CLIP_CORRECTION);

        // 叠加层：平移 +30，并随桌面滚动。
        writeScreenQuad(mOverlayVertices, 30.0f + mScene.mXOffset,
                mScene.mLandscapeY, mScene.mOverlayOutTime);
        frame.upload(BUF_OVERLAY, mOverlayVertices, VERTICES_PER_QUAD * STRIDE_BYTES);
        frame.setVertexCount(mOverlayPass, VERTICES_PER_QUAD);
        frame.pushConstants(mOverlayPass, CLIP_CORRECTION);

        // 草：本地顶点共用一份，每株只换 push constant。
        frame.upload(BUF_STEM, mStemVertices, VERTICES_PER_QUAD * STRIDE_BYTES);
        for (int i = 0; i < MAX_STEMS; i++) {
            buildStemMvp(mStemPush, mScene.mStems[i]);
            frame.setVertexCount(mStemPasses[i], VERTICES_PER_QUAD);
            frame.pushConstants(mStemPasses[i], mStemPush);
        }

        // 粒子
        mParticleCount = 0;
        for (int i = 0; i < MAX_PARTICLES; i++) {
            ForestScene.Particle particle = mScene.mParticles[i];
            if (particle.active != 1) continue;
            appendParticle(particle);
        }
        int particleVertices = mParticleCount * VERTICES_PER_QUAD;
        frame.setVertexCount(mParticlePass, particleVertices);
        if (particleVertices > 0) {
            frame.upload(BUF_PARTICLE, mParticleVertices, particleVertices * STRIDE_BYTES);
        }
        frame.pushConstants(mParticlePass, CLIP_CORRECTION);
    }

    /** 屏幕四边形：world 坐标经 GL ortho 变成 NDC，y 翻转交给 CLIP_CORRECTION。 */
    private static void writeScreenQuad(ByteBuffer buffer, float worldLeft,
                                        float worldBottom, float alpha) {
        float left = worldLeft / (SCREEN_W * 0.5f) - 1.0f;
        float right = (worldLeft + SCREEN_W) / (SCREEN_W * 0.5f) - 1.0f;
        float bottom = worldBottom / (SCREEN_H * 0.5f) - 1.0f;
        float top = (worldBottom + SCREEN_H) / (SCREEN_H * 0.5f) - 1.0f;
        writeQuad(buffer, 0, left, bottom, right, top, alpha);
    }

    private void appendParticle(ForestScene.Particle particle) {
        if (mParticleCount >= MAX_PARTICLES) return;
        // GLES: translate(p) * translate(move) * scale(scaleWeight) * translate(-gap)
        float centerX = particle.x + particle.moveX;
        float centerY = particle.y + particle.moveY;
        float halfX = ForestScene.PARTICLE_GAP_X * particle.scaleWeight;
        float halfY = ForestScene.PARTICLE_GAP_Y * particle.scaleWeight;

        float left = (centerX - halfX) / (SCREEN_W * 0.5f) - 1.0f;
        float right = (centerX + halfX) / (SCREEN_W * 0.5f) - 1.0f;
        float bottom = (centerY - halfY) / (SCREEN_H * 0.5f) - 1.0f;
        float top = (centerY + halfY) / (SCREEN_H * 0.5f) - 1.0f;

        int floatIndex = mParticleCount * VERTICES_PER_QUAD * FLOATS_PER_VERTEX;
        writeQuad(mParticleVertices, floatIndex, left, bottom, right, top,
                particle.outTime);
        mParticleCount++;
    }

    /** GLES: translate(x,y) * translate(STEM_W,STEM_H) * rotateZ(angle) * translate(-STEM_W,-STEM_H)。 */
    private void buildStemMvp(float[] out, ForestScene.Stem stem) {
        float radians = (float) Math.toRadians(stem.angle);
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);
        float originX = mScene.mXOffset + stem.x;
        float originY = mScene.mLandscapeY;

        float tx = originX + STEM_W - STEM_W * cos + STEM_H * sin;
        float ty = originY + STEM_H - STEM_W * sin - STEM_H * cos;

        mModelMatrix[0] = cos;
        mModelMatrix[1] = sin;
        mModelMatrix[2] = 0.0f;
        mModelMatrix[3] = 0.0f;
        mModelMatrix[4] = -sin;
        mModelMatrix[5] = cos;
        mModelMatrix[6] = 0.0f;
        mModelMatrix[7] = 0.0f;
        mModelMatrix[8] = 0.0f;
        mModelMatrix[9] = 0.0f;
        mModelMatrix[10] = 1.0f;
        mModelMatrix[11] = 0.0f;
        mModelMatrix[12] = tx;
        mModelMatrix[13] = ty;
        mModelMatrix[14] = 0.0f;
        mModelMatrix[15] = 1.0f;

        multiply(mTempMatrix, PROJECTION, mModelMatrix);
        multiply(out, CLIP_CORRECTION, mTempMatrix);
    }

    /** 列主序 4x4 乘法；out 不能与 a/b 相同。 */
    private static void multiply(float[] out, float[] a, float[] b) {
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                out[column * 4 + row] =
                        a[row] * b[column * 4]
                        + a[4 + row] * b[column * 4 + 1]
                        + a[8 + row] * b[column * 4 + 2]
                        + a[12 + row] * b[column * 4 + 3];
            }
        }
    }

    /** 两个三角形 0,1,2 与 2,1,3；UV 与 GLES 的 (0,1)(1,1)(0,0)(1,0) 一致。 */
    private static void writeQuad(ByteBuffer buffer, int floatIndex,
                                  float left, float bottom, float right, float top,
                                  float alpha) {
        floatIndex = putVertex(buffer, floatIndex, left, bottom, 0.0f, 1.0f, alpha);
        floatIndex = putVertex(buffer, floatIndex, right, bottom, 1.0f, 1.0f, alpha);
        floatIndex = putVertex(buffer, floatIndex, left, top, 0.0f, 0.0f, alpha);
        floatIndex = putVertex(buffer, floatIndex, left, top, 0.0f, 0.0f, alpha);
        floatIndex = putVertex(buffer, floatIndex, right, bottom, 1.0f, 1.0f, alpha);
        putVertex(buffer, floatIndex, right, top, 1.0f, 0.0f, alpha);
    }

    private static int putVertex(ByteBuffer buffer, int floatIndex,
                                 float x, float y, float u, float v, float alpha) {
        buffer.putFloat(floatIndex * 4, x);
        buffer.putFloat((floatIndex + 1) * 4, y);
        buffer.putFloat((floatIndex + 2) * 4, u);
        buffer.putFloat((floatIndex + 3) * 4, v);
        buffer.putFloat((floatIndex + 4) * 4, alpha);
        return floatIndex + FLOATS_PER_VERTEX;
    }

    private static ByteBuffer allocateVertices(int vertexCount) {
        return ByteBuffer.allocateDirect(vertexCount * FLOATS_PER_VERTEX * 4)
                .order(ByteOrder.nativeOrder());
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
}
