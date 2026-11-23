package com.reandroid.wallpaper.dandelion;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.BatteryManager;
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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Random;

/**
 * 蒲公英的 VK 渲染器 —— 壁纸与预览共用。
 *
 * <p>场景逻辑与 GLES 版是同一份 {@link DandelionScene}：构图、摆动、阵风、枯荣都在
 * 那边，这里只负责把每帧的精灵四边形写成顶点，并按 GLES 的绘制顺序分成几个 pass：
 * 天空 → 背景浮尘 → 孢子 → 植株。每个 pass 一次绑定它这一组的全部候选贴图，
 * 顶点里的贴图下标在片元着色器里选 —— 否则 14 张贴图会超过单个 pass 的 8 张上限，
 * 而通用 pass 的绑定在声明之后是静态的。
 *
 * <p>贴图下标沿用 GLES 的 {@code TEX_*} 编号（0..13），组内着色器减去基数后
 * 映射到自己 binding 0..n；顺序不变，所以每个四边形的叠加关系与 GLES 一致。
 *
 * <p>混合：天空不透明；精灵是预乘 alpha 素材，用 {@code ONE / ONE_MINUS_SRC_ALPHA}，
 * 与 GLES 的 {@code glBlendFunc} 相同。VK 的上传接口收的是未预乘的 ARGB，所以上传前
 * 补一次预乘，对齐 GLES 的 {@code inPremultiplied = true} 解码。
 */
final class DandelionVkDelegate extends VkPassRendererDelegate {

    private static final String TAG = "DandelionVK";

    /** 与 DandelionGL 相同的四边形上限。 */
    private static final int MAX_QUADS = 512;

    private static final int VERTICES_PER_QUAD = 6;

    private static final int MAX_VERTICES = MAX_QUADS * VERTICES_PER_QUAD;

    /** 植株 pass 最多 10 株、每株一个花头一个茎。 */
    private static final int FLOWER_MAX_VERTICES =
            DandelionScene.MAX_FLOWERS * 2 * VERTICES_PER_QUAD;

    /** 精灵顶点：x, y, u, v, alpha, 贴图下标。 */
    private static final int SPRITE_FLOATS = 6;

    private static final int SPRITE_STRIDE = SPRITE_FLOATS * 4;

    /** 天空顶点：x, y, 设计稿纵坐标。 */
    private static final int SKY_FLOATS = 3;

    private static final int SKY_STRIDE = SKY_FLOATS * 4;

    /** 只有 mat4。 */
    private static final int PUSH_FLOATS = 16;

    private static final int PUSH_BYTES = PUSH_FLOATS * 4;

    private static final String SKY_VERT = "shaders/dandelion_vk_sky.vert.spv";
    private static final String SKY_FRAG = "shaders/dandelion_vk_sky.frag.spv";
    private static final String SPRITE_VERT = "shaders/dandelion_vk_sprite.vert.spv";
    private static final String DUST_FRAG = "shaders/dandelion_vk_dust.frag.spv";
    private static final String SPORE_FRAG = "shaders/dandelion_vk_spore.frag.spv";
    private static final String FLOWER_FRAG = "shaders/dandelion_vk_flower.frag.spv";

    private static final int[] SKY_ATTRS = {2, 1};
    private static final int[] SPRITE_ATTRS = {2, 2, 1, 1};

    /** 与 GLES 的 TEX_* 编号一致。 */
    private static final int TEX_DUST_SHEET = 0;

    private static final int TEX_DUST_SMALL = 1;
    private static final int TEX_DUST_LARGE = 2;
    private static final int TEX_HEAD = 3;
    /** stem_a/b/c 依次是 4/5/6，花头用 3。 */
    private static final int TEX_STEM_A = 4;

    private static final int TEX_SPORE_FIRST = 7;

    private static final String[] TEXTURE_NAMES = {
        "dustSheet",
        "dustSmall",
        "dustLarge",
        "flowerHead",
        "stemA",
        "stemB",
        "stemC",
        "spore0",
        "spore1",
        "spore2",
        "spore3",
        "spore4",
        "spore5",
        "spore6",
    };

    private static final String[] TEXTURE_ASSETS = {
        "dandelion/drawable/dust_sheet.png",
        "dandelion/drawable/dust_small.png",
        "dandelion/drawable/dust_large.png",
        "dandelion/drawable/head_sheet.png",
        "dandelion/drawable/stem_a.png",
        "dandelion/drawable/stem_b.png",
        "dandelion/drawable/stem_c.png",
        "dandelion/drawable/spore_1.png",
        "dandelion/drawable/spore_2.png",
        "dandelion/drawable/spore_3.png",
        "dandelion/drawable/spore_4.png",
        "dandelion/drawable/spore_5.png",
        "dandelion/drawable/spore_6.png",
        "dandelion/drawable/spore_7.png",
    };

    private static final int DUST_TEXTURE_COUNT = 3;
    private static final int SPORE_TEXTURE_COUNT = 7;
    private static final int FLOWER_TEXTURE_COUNT = 4;

    private static final long BATTERY_INTERVAL_MS = 5000L;

    /** 与 DandelionGL 相同的手势常量。 */
    private static final float GUST_DISTANCE_PX = 90f;

    private static final long GUST_COOLDOWN_MS = 700L;
    private static final float SCROLL_GUST_PX = 120f;

    private static final float DESIGN_HALF_WIDTH = DandelionScene.DESIGN_WIDTH * 0.5f;
    private static final float DESIGN_HALF_HEIGHT = DandelionScene.DESIGN_HEIGHT * 0.5f;

    /** 场景、顶点缓冲与设置会被主线程（触摸/设置）和渲染线程同时读写。 */
    private final Object mLock = new Object();

    private final boolean mPreview;
    private SharedPreferences mPrefs;

    private DandelionScene mScene;
    private VkPassScene mPassScene;

    private VkPass mSkyPass;
    private VkPass mDustPass;
    private VkPass mSporePass;
    private VkPass mFlowerPass;

    private ByteBuffer mSkyBuffer;
    private ByteBuffer mDustBuffer;
    private ByteBuffer mSporeBuffer;
    private ByteBuffer mFlowerBuffer;

    /** 当前正在写的精灵缓冲与它的容量，以及已写顶点数。 */
    private ByteBuffer mSpriteOut;

    private int mSpriteCapacity;
    private int mSpriteVertices;

    private boolean mTexturesUploaded;
    private long mLastBatteryCheck;

    private int mWidth;
    private int mHeight;

    private final float[] mProj = new float[16];
    private final float[] mVkMvp = new float[16];
    private final float[] mPush = new float[PUSH_FLOATS];
    private final float[] mScreen = new float[6];

    private boolean mOffsetSeeded;
    private int mLastOffsetPixels;
    private long mLastGustMs;
    private float mDownX;
    private float mDownY;
    private float mGestureDistance;
    private boolean mGestureGusted;

    DandelionVkDelegate(Context context, SharedPreferences prefs, boolean preview) {
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
        synchronized (mLock) {
            mWidth = width;
            mHeight = height;
            if (mScene == null) {
                mScene = new DandelionScene(new Random());
                if (mPrefs != null) mScene.setPluginPrefs(mPrefs);
            }
            // 与 DandelionGL.ensureSceneSize 相同的投影：y=0 在顶部
            Mat4.orthoM(mProj, 0.0f, width, height, 0.0f, -1.0f, 1.0f);
            // GL clip → Vulkan clip：y 取反，z 从 [-w, w] 映射到 [0, w]。
            Mat4.multiplyMM(mVkMvp, CLIP_CORRECTION, mProj);
            mScene.resize(width, height);
        }
    }

    @Override
    public void onPluginPrefsChanged(SharedPreferences prefs) {
        mPrefs = prefs;
        synchronized (mLock) {
            if (mScene != null) mScene.setPluginPrefs(prefs);
        }
    }

    /** 桌面滚动了足够远就吹一阵风，与 GLES 的 setOffset 一致。 */
    @Override
    public void onSceneOffset(float xOffset, int xPixels) {
        synchronized (mLock) {
            if (mScene == null) return;
            if (!mOffsetSeeded) {
                mOffsetSeeded = true;
                mLastOffsetPixels = xPixels;
                return;
            }
            int delta = Math.abs(xPixels - mLastOffsetPixels);
            mLastOffsetPixels = xPixels;
            if (delta >= SCROLL_GUST_PX) {
                applyGustLocked();
                mScene.puffAll();
            }
        }
    }

    @Override
    public void onTouchEvent(MotionEvent event) {
        if (event == null) return;
        synchronized (mLock) {
            if (mScene == null) return;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mDownX = event.getX();
                    mDownY = event.getY();
                    mGestureDistance = 0f;
                    mGestureGusted = false;
                    handleTapLocked(event.getX(), event.getY());
                    break;
                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getX() - mDownX;
                    float dy = event.getY() - mDownY;
                    mGestureDistance = Math.max(mGestureDistance, (float) Math.hypot(dx, dy));
                    if (!mGestureGusted && mGestureDistance >= GUST_DISTANCE_PX) {
                        mGestureGusted = true;
                        applyGustLocked();
                        mScene.puffAll();
                    }
                    break;
                }
                default:
                    break;
            }
        }
    }

    @Override
    public void onCommand(String action, int x, int y, int z, Bundle extras) {
        if (!"android.wallpaper.tap".equals(action)) return;
        synchronized (mLock) {
            if (mScene != null) handleTapLocked(x, y);
        }
    }

    private void handleTapLocked(float x, float y) {
        int flower = mScene.flowerAt(x, y);
        if (flower >= 0) {
            mScene.puff(flower);
            applyGustLocked();
        }
    }

    /** 阵风按 uptime 去抖，与 GLES 的 applyGust 同一套节奏。 */
    private void applyGustLocked() {
        long now = SystemClock.uptimeMillis();
        if (now - mLastGustMs < GUST_COOLDOWN_MS) return;
        mLastGustMs = now;
        mScene.gust();
    }

    /**
     * native 销毁后句柄就是野指针（{@code asRenderer} 是裸 cast），
     * 在基类动手之前把场景引用清掉，避免设置回调拿旧句柄去调用。
     */
    @Override
    public void destroyRenderer() {
        synchronized (mLock) {
            mPassScene = null;
        }
        super.destroyRenderer();
    }

    @Override
    protected void buildScene(VkPassScene scene) {
        mPassScene = scene;
        // native 渲染器可能被销毁后重建：新场景里的纹理是空的，必须重新上传。
        mTexturesUploaded = false;
        // 与 GLES 每帧 glClearColor 的颜色一致；天空四边形不透明，实际看不见清屏色。
        scene.setClearColor(0.29f, 0.49f, 1.0f, 1.0f);

        scene.createBuffer("sky", SKY_STRIDE, VERTICES_PER_QUAD, false);
        scene.createBuffer("dust", SPRITE_STRIDE, MAX_VERTICES, true);
        scene.createBuffer("spores", SPRITE_STRIDE, MAX_VERTICES, true);
        scene.createBuffer("flowers", SPRITE_STRIDE, FLOWER_MAX_VERTICES, true);

        mSkyBuffer = allocate(SKY_STRIDE, VERTICES_PER_QUAD);
        mDustBuffer = allocate(SPRITE_STRIDE, MAX_VERTICES);
        mSporeBuffer = allocate(SPRITE_STRIDE, MAX_VERTICES);
        mFlowerBuffer = allocate(SPRITE_STRIDE, FLOWER_MAX_VERTICES);

        for (String name : TEXTURE_NAMES) {
            scene.createTexture(name);
        }

        mSkyPass = new VkPass(
                        SKY_VERT,
                        SKY_FRAG,
                        SKY_ATTRS,
                        SKY_STRIDE,
                        VkPass.TOPOLOGY_TRIANGLE_LIST,
                        VkPass.BLEND_NONE)
                .buffer("sky", 0)
                .pushConstants(PUSH_BYTES);
        scene.add(mSkyPass);

        mDustPass = addSpritePass(scene, "dust", DUST_FRAG, TEX_DUST_SHEET, DUST_TEXTURE_COUNT);
        mSporePass =
                addSpritePass(scene, "spores", SPORE_FRAG, TEX_SPORE_FIRST, SPORE_TEXTURE_COUNT);
        mFlowerPass = addSpritePass(scene, "flowers", FLOWER_FRAG, TEX_HEAD, FLOWER_TEXTURE_COUNT);
    }

    /**
     * 一组精灵 pass。贴图按全局编号从 {@code firstTexture} 起连续绑定到 binding 0..n，
     * 片元着色器再把顶点里的全局下标减回去。
     */
    private static VkPass addSpritePass(
            VkPassScene scene, String buffer, String frag, int firstTexture, int textureCount) {
        VkPass pass = new VkPass(
                        SPRITE_VERT,
                        frag,
                        SPRITE_ATTRS,
                        SPRITE_STRIDE,
                        VkPass.TOPOLOGY_TRIANGLE_LIST,
                        VkPass.BLEND_PREMULTIPLIED)
                .buffer(buffer, 0)
                .pushConstants(PUSH_BYTES);
        for (int i = 0; i < textureCount; i++) {
            pass.texture(i, TEXTURE_NAMES[firstTexture + i]);
        }
        scene.add(pass);
        return pass;
    }

    @Override
    protected void updateFrame(VkPassFrame frame, long timeMs) {
        synchronized (mLock) {
            if (mScene == null || mWidth <= 0 || mHeight <= 0) {
                zeroAllPasses(frame);
                return;
            }
            ensureTextures();

            /*
             * 与 GLES 壁纸/预览同一条时间线：两边都用 System.currentTimeMillis()。
             * 通用 VK 基类传进来的是 uptimeMillis，混用会让浮尘旋转与摆动相位偏掉。
             */
            long now = System.currentTimeMillis();
            // DandelionEngine 在 super.drawFrame() 之前轮询电量，这里保持同样的顺序；
            // 应用内预览（preview = true）不轮询，花头固定停在最完整的一帧。
            if (!mPreview) pollBattery(now);

            mScene.update(now);

            System.arraycopy(mVkMvp, 0, mPush, 0, 16);

            drawSky(frame);
            drawDust(frame, now);
            drawSpores(frame, now);
            drawFlowers(frame);
        }
    }

    // ─────────────────────────── 天空 ───────────────────────────

    private void drawSky(VkPassFrame frame) {
        float scale = mScene.scale();
        if (scale <= 0f) {
            frame.setVertexCount(mSkyPass, 0);
            return;
        }
        float designTop = -mScene.offsetY() / scale;

        mSkyBuffer.clear();
        putSkyVertex(0f, 0f, designTop);
        putSkyVertex(mWidth, 0f, designTop);
        putSkyVertex(mWidth, mHeight, DandelionScene.DESIGN_HEIGHT);
        putSkyVertex(0f, 0f, designTop);
        putSkyVertex(mWidth, mHeight, DandelionScene.DESIGN_HEIGHT);
        putSkyVertex(0f, mHeight, DandelionScene.DESIGN_HEIGHT);

        frame.upload("sky", mSkyBuffer, VERTICES_PER_QUAD * SKY_STRIDE);
        frame.pushConstants(mSkyPass, mPush);
        frame.setVertexCount(mSkyPass, VERTICES_PER_QUAD);
    }

    private void putSkyVertex(float x, float y, float designY) {
        mSkyBuffer.putFloat(x).putFloat(y).putFloat(designY);
    }

    // ─────────────────────────── 浮尘 ───────────────────────────

    /** 两层整片旋转的点点贴图，加上大小两种浮尘粒子。 */
    private void drawDust(VkPassFrame frame, long now) {
        beginBatch(mDustBuffer, MAX_VERTICES);
        designToScreen();

        for (int layer = 0; layer < 2; layer++) {
            float[] m = concat(
                    mScreen,
                    translate(mScene.dustLayerOffsetX(layer), mScene.dustLayerOffsetY(layer)));
            m = concat(
                    m,
                    rotateAbout(
                            mScene.dustLayerRotation(layer, now),
                            DESIGN_HALF_WIDTH,
                            DESIGN_HALF_HEIGHT));
            addQuad(
                    m,
                    0f,
                    0f,
                    DandelionScene.DESIGN_WIDTH,
                    DandelionScene.DESIGN_HEIGHT,
                    0f,
                    0f,
                    1f,
                    1f,
                    1f,
                    TEX_DUST_SHEET);
        }

        List<DandelionParticle> particles = mScene.dust();
        for (int i = 0; i < particles.size(); i++) {
            DandelionParticle p = particles.get(i);
            float half = visibleHalf(p, now);
            if (half <= 0f) continue;
            int texture = p.sprite == 0 ? TEX_DUST_SMALL : TEX_DUST_LARGE;
            addQuad(
                    concat(mScreen, translate(p.x, p.y)),
                    -half,
                    -half,
                    half,
                    half,
                    0f,
                    0f,
                    1f,
                    1f,
                    p.alpha(now),
                    texture);
        }

        uploadSprite(frame, "dust", mDustPass, mDustBuffer);
    }

    // ─────────────────────────── 孢子 ───────────────────────────

    private void drawSpores(VkPassFrame frame, long now) {
        beginBatch(mSporeBuffer, MAX_VERTICES);

        List<DandelionParticle> particles = mScene.spores();
        for (int i = 0; i < particles.size(); i++) {
            DandelionParticle p = particles.get(i);
            float half = visibleHalf(p, now);
            if (half <= 0f) continue;
            addQuad(
                    concat(mScreen, translate(p.x, p.y)),
                    -half,
                    -half,
                    half,
                    half,
                    0f,
                    0f,
                    1f,
                    1f,
                    p.alpha(now),
                    TEX_SPORE_FIRST + p.sprite);
        }

        uploadSprite(frame, "spores", mSporePass, mSporeBuffer);
    }

    /**
     * GLES 里浮尘/孢子共用的可见性与尺寸判断：alpha 或尺寸为 0 的不画，
     * 完全移出设计稿外扩 40 的也不画。返回半边长度，不可见时返回 0。
     */
    private static float visibleHalf(DandelionParticle p, long now) {
        if (p.alpha(now) <= 0f) return 0f;
        float half = p.size * p.sizeFactor(now) * 0.5f;
        if (half <= 0.01f) return 0f;
        if (p.x + half < -40f
                || p.x - half > DandelionScene.DESIGN_WIDTH + 40f
                || p.y + half < -40f
                || p.y - half > DandelionScene.DESIGN_HEIGHT + 40f) {
            return 0f;
        }
        return half;
    }

    // ─────────────────────────── 植株 ───────────────────────────

    /** 绘制顺序与 DandelionGL.drawFlowers 一致：先画背景小株，再按参考实现的顺序画主花。 */
    private void drawFlowers(VkPassFrame frame) {
        beginBatch(mFlowerBuffer, FLOWER_MAX_VERTICES);

        int headFrame = mScene.headFrame();
        float frameV0 = headFrame / (float) DandelionScene.HEAD_FRAMES;
        float frameV1 = (headFrame + 1) / (float) DandelionScene.HEAD_FRAMES;

        for (int index = DandelionScene.MAX_FLOWERS - 1;
                index >= DandelionScene.EMITTING_FLOWERS;
                index--) {
            drawFlower(index, frameV0, frameV1);
        }
        for (int index : DandelionScene.DRAW_ORDER) {
            drawFlower(index, frameV0, frameV1);
        }

        uploadSprite(frame, "flowers", mFlowerPass, mFlowerBuffer);
    }

    private void drawFlower(int index, float frameV0, float frameV1) {
        if (!mScene.isFlowerVisible(index)) return;
        DandelionScene.Flower f = DandelionScene.flower(index);
        float scale = f.scale;

        float[] stem = concat(mScreen, translate(f.rootX, f.rootY));
        stem = concat(stem, rotate(mScene.swayDegrees(index)));
        stem = concat(stem, translate(0f, -f.pivotY * scale));

        float[] head = concat(stem, rotate(f.headRotation));
        addQuad(
                head,
                f.headX * scale,
                f.headY * scale,
                (f.headX + DandelionScene.HEAD_WIDTH) * scale,
                (f.headY + DandelionScene.HEAD_HEIGHT) * scale,
                0f,
                frameV0,
                1f,
                frameV1,
                1f,
                TEX_HEAD);

        addQuad(
                stem,
                0f,
                0f,
                f.stemWidth * scale,
                f.stemHeight * scale,
                0f,
                0f,
                1f,
                1f,
                1f,
                TEX_STEM_A + f.stem);
    }

    // ─────────────────────────── 顶点写入 ───────────────────────────

    private void beginBatch(ByteBuffer buffer, int capacityVertices) {
        buffer.clear();
        mSpriteOut = buffer;
        mSpriteCapacity = capacityVertices;
        mSpriteVertices = 0;
    }

    /**
     * 六个顶点写一个四边形，顺序与 DandelionGL.addQuad 相同。
     * 容量按场景上限给足，这里的越界检查只是防御。
     */
    private void addQuad(
            float[] m,
            float x0,
            float y0,
            float x1,
            float y1,
            float u0,
            float v0,
            float u1,
            float v1,
            float alpha,
            int texture) {
        if (mSpriteVertices + VERTICES_PER_QUAD > mSpriteCapacity) return;
        putSpriteVertex(m, x0, y0, u0, v0, alpha, texture);
        putSpriteVertex(m, x1, y0, u1, v0, alpha, texture);
        putSpriteVertex(m, x1, y1, u1, v1, alpha, texture);
        putSpriteVertex(m, x0, y0, u0, v0, alpha, texture);
        putSpriteVertex(m, x1, y1, u1, v1, alpha, texture);
        putSpriteVertex(m, x0, y1, u0, v1, alpha, texture);
        mSpriteVertices += VERTICES_PER_QUAD;
    }

    private void putSpriteVertex(
            float[] m, float x, float y, float u, float v, float alpha, int texture) {
        mSpriteOut.putFloat(m[0] * x + m[2] * y + m[4]);
        mSpriteOut.putFloat(m[1] * x + m[3] * y + m[5]);
        mSpriteOut.putFloat(u);
        mSpriteOut.putFloat(v);
        mSpriteOut.putFloat(alpha);
        mSpriteOut.putFloat(texture);
    }

    private void uploadSprite(
            VkPassFrame frame, String bufferName, VkPass pass, ByteBuffer buffer) {
        if (mSpriteVertices == 0) {
            frame.setVertexCount(pass, 0);
            return;
        }
        frame.upload(bufferName, buffer, mSpriteVertices * SPRITE_STRIDE);
        frame.pushConstants(pass, mPush);
        frame.setVertexCount(pass, mSpriteVertices);
    }

    private void zeroAllPasses(VkPassFrame frame) {
        frame.setVertexCount(mSkyPass, 0);
        frame.setVertexCount(mDustPass, 0);
        frame.setVertexCount(mSporePass, 0);
        frame.setVertexCount(mFlowerPass, 0);
    }

    // ─────────────────────────── 2D 仿射 ───────────────────────────

    /** 设计稿 → 屏幕：等比缩放再平移到构图位置，与 DandelionGL.designToScreen 一致。 */
    private void designToScreen() {
        float s = mScene.scale();
        mScreen[0] = s;
        mScreen[1] = 0f;
        mScreen[2] = 0f;
        mScreen[3] = s;
        mScreen[4] = mScene.offsetX();
        mScreen[5] = mScene.offsetY();
    }

    private static float[] translate(float tx, float ty) {
        return new float[] {1f, 0f, 0f, 1f, tx, ty};
    }

    private static float[] rotate(float degrees) {
        double rad = Math.toRadians(degrees);
        float c = (float) Math.cos(rad);
        float s = (float) Math.sin(rad);
        return new float[] {c, s, -s, c, 0f, 0f};
    }

    private static float[] rotateAbout(float degrees, float cx, float cy) {
        return concat(concat(translate(cx, cy), rotate(degrees)), translate(-cx, -cy));
    }

    /** 先作用 b，再作用 a。 */
    private static float[] concat(float[] a, float[] b) {
        float[] r = new float[6];
        r[0] = a[0] * b[0] + a[2] * b[1];
        r[1] = a[1] * b[0] + a[3] * b[1];
        r[2] = a[0] * b[2] + a[2] * b[3];
        r[3] = a[1] * b[2] + a[3] * b[3];
        r[4] = a[0] * b[4] + a[2] * b[5] + a[4];
        r[5] = a[1] * b[4] + a[3] * b[5] + a[5];
        return r;
    }

    // ─────────────────────────── 资源 ───────────────────────────

    private static ByteBuffer allocate(int strideBytes, int vertexCount) {
        return ByteBuffer.allocateDirect(strideBytes * vertexCount).order(ByteOrder.nativeOrder());
    }

    private void ensureTextures() {
        if (mTexturesUploaded || mPassScene == null) return;
        mTexturesUploaded = true;
        for (int i = 0; i < TEXTURE_NAMES.length; i++) {
            uploadTexture(TEXTURE_NAMES[i], TEXTURE_ASSETS[i]);
        }
    }

    /**
     * GLES 用 {@code inPremultiplied = true} 解码后直接上传；VK 的上传接口收的是
     * {@code getPixels()} 的直通 ARGB，所以这里补上同一次预乘。
     */
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
        if (argb == null) return;

        int width = argb.getWidth();
        int height = argb.getHeight();
        int[] pixels = new int[width * height];
        argb.getPixels(pixels, 0, width, 0, 0, width, height);
        argb.recycle();

        premultiply(pixels);
        mPassScene.setTexture(name, pixels, width, height);
    }

    private static void premultiply(int[] pixels) {
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            int alpha = (pixel >>> 24) & 0xff;
            if (alpha == 0xff) continue;
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

    /** 与 DandelionEngine 相同：每 5 秒读一次电量，驱动花头的枯荣帧。 */
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
     * GL clip → Vulkan clip：y 取反，z 从 [-w, w] 映射到 [0, w]。
     * 与其他 VK delegate 使用同一份矩阵。
     */
    private static final float[] CLIP_CORRECTION = {
        1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 0.5f, 0, 0, 0, 0.5f, 1,
    };
}
