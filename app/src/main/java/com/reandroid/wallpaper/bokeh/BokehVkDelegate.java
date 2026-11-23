package com.reandroid.wallpaper.bokeh;

import android.content.Context;
import android.os.Bundle;

import com.reandroid.plugin.VkPass;
import com.reandroid.plugin.VkPassFrame;
import com.reandroid.plugin.VkPassRendererDelegate;
import com.reandroid.plugin.VkPassScene;
import com.reandroid.utils.Mat4;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * 散景彩虹的 VK 渲染器 —— 壁纸与预览共用。
 *
 * <p>场景逻辑（光斑的生命周期、漂移、消亡）完全复用 {@link BokehScene}，这里只负责把
 * 当前存活的光斑摊成顶点：每个光斑一个四边形，片元着色器按到圆心的距离同时画出填充
 * 与描边，一次 draw 画完全部光斑 —— 与 GLES 版 {@code BokehGL} 的顶点与颜色逐项一致。
 *
 * <p>顶点属性布局与 GLES 相同（12 float）：corner(2) + center(2) + radius(1) +
 * fill(4) + ring(3)。位置都在屏幕像素坐标里，投影矩阵在 GL 裁剪空间的基础上左乘
 * GL → Vulkan 的修正（y 取反、z 从 [-w, w] 映射到 [0, w]），由推送常量里的 uMvp 下发。
 *
 * <p>混合因子取 {@code SRC_ALPHA / ONE_MINUS_SRC_ALPHA}（颜色与 alpha 两个通道都如此），
 * 与 GLES 的 {@code glBlendFunc} 相同。
 */
final class BokehVkDelegate extends VkPassRendererDelegate {

    private static final String TAG = "BokehVK";

    /** corner(2) + center(2) + radius(1) + fill(4) + ring(3)。 */
    private static final int FLOATS_PER_VERTEX = 12;

    private static final int VERTICES_PER_CIRCLE = 6;

    private static final int STRIDE_BYTES = FLOATS_PER_VERTEX * 4;

    /** 与 GLES 版相同的容量：BokehScene 自己也会把数量钳在这个值。 */
    private static final int MAX_VERTICES = BokehScene.MAX_CIRCLES * VERTICES_PER_CIRCLE;

    /** mat4 + uRingPx，68 字节。 */
    private static final int PUSH_FLOATS = 16 + 1;

    private static final int PUSH_BYTES = PUSH_FLOATS * 4;

    private static final String VERT_SHADER = "shaders/bokeh_vk.vert.spv";
    private static final String FRAG_SHADER = "shaders/bokeh_vk.frag.spv";

    private static final String BUFFER = "circles";

    /** 场景会被主线程（尺寸/触摸/滚动）和渲染线程同时读写。 */
    private final Object mLock = new Object();

    private final ByteBuffer mVertices =
            ByteBuffer.allocateDirect(MAX_VERTICES * STRIDE_BYTES).order(ByteOrder.nativeOrder());
    private final float[] mProj = new float[16];
    private final float[] mVkMvp = new float[16];
    private final float[] mPush = new float[PUSH_FLOATS];

    private BokehScene mScene;
    private VkPass mPass;

    private int mWidth;
    private int mHeight;
    private boolean mPreview;

    /** 桌面滚动的像素偏移；场景还没建时先记下来。 */
    private int mOffsetX;

    private int mOffsetY;

    /** 上次喂给场景的尺寸/预览状态，变化时才 resize（与 BokehGL.ensureSceneSize 一致）。 */
    private int mSceneWidth = -1;

    private int mSceneHeight = -1;
    private boolean mScenePreview;

    BokehVkDelegate(Context context) {
        super(context);
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
                mScene = new BokehScene();
                mScene.setOffset(mOffsetX, mOffsetY);
            }
            applyResizeLocked();
        }
    }

    @Override
    public void onPreviewChanged(boolean preview) {
        synchronized (mLock) {
            mPreview = preview;
            applyResizeLocked();
        }
    }

    /**
     * 桌面滚动的像素偏移。通用 VK 委托只转 xPixels（yPixels 没进接口），
     * 而散景的竖直偏移在桌面壁纸上通常为 0，所以这里按 0 处理。
     */
    @Override
    public void onSceneOffset(float xOffset, int xPixels) {
        synchronized (mLock) {
            mOffsetX = xPixels;
            mOffsetY = 0;
            if (mScene != null) {
                mScene.setOffset(mOffsetX, mOffsetY);
            }
        }
    }

    @Override
    public void onSceneTouch(float x, float y) {
        synchronized (mLock) {
            if (mScene != null) mScene.addCircleAt(x, y);
        }
    }

    @Override
    public void onCommand(String action, int x, int y, int z, Bundle extras) {
        if ("android.wallpaper.tap".equals(action)) {
            synchronized (mLock) {
                if (mScene != null) mScene.addCircleAt(x, y);
            }
        }
    }

    @Override
    protected void buildScene(VkPassScene scene) {
        // 与 GLES 的 glClearColor(0, 0, 0, 1) 一致。
        scene.setClearColor(0f, 0f, 0f, 1f);

        scene.createBuffer(BUFFER, STRIDE_BYTES, MAX_VERTICES, true);

        mPass = new VkPass(
                        VERT_SHADER,
                        FRAG_SHADER,
                        new int[] {2, 2, 1, 4, 3},
                        STRIDE_BYTES,
                        VkPass.TOPOLOGY_TRIANGLE_LIST,
                        VkPass.BLEND_ALPHA)
                .buffer(BUFFER, 0)
                .pushConstants(PUSH_BYTES)
                // 精确对应 GLES 的 glBlendFunc(SRC_ALPHA, ONE_MINUS_SRC_ALPHA)：
                // 颜色与 alpha 两个通道都用 SRC_ALPHA。
                .blendFactors(
                        VkPass.FACTOR_SRC_ALPHA,
                        VkPass.FACTOR_ONE_MINUS_SRC_ALPHA,
                        VkPass.FACTOR_SRC_ALPHA,
                        VkPass.FACTOR_ONE_MINUS_SRC_ALPHA);
        scene.add(mPass);
    }

    @Override
    protected void updateFrame(VkPassFrame frame, long timeMs) {
        VkPass pass = mPass;
        if (pass == null) return;
        synchronized (mLock) {
            BokehScene scene = mScene;
            if (scene == null) {
                frame.setVertexCount(pass, 0);
                return;
            }

            // 与 BokehGL.drawFrame 相同：先推进场景，再把存活的光斑摊成顶点。
            scene.update();
            int vertexCount = buildVertices(scene);

            // GLES 的 uMVP 只有正交投影（像素坐标，y 向下）。
            Mat4.orthoM(mProj, 0f, mWidth, mHeight, 0f, -1f, 1f);
            // GL clip → Vulkan clip：y 取反，z 从 [-w, w] 映射到 [0, w]。
            Mat4.multiplyMM(mVkMvp, CLIP_CORRECTION, mProj);
            System.arraycopy(mVkMvp, 0, mPush, 0, 16);
            mPush[16] = scene.ringPx();

            frame.pushConstants(pass, mPush);
            if (vertexCount > 0) {
                frame.upload(BUFFER, mVertices, vertexCount * STRIDE_BYTES);
            }
            frame.setVertexCount(pass, vertexCount);
        }
    }

    private void applyResizeLocked() {
        BokehScene scene = mScene;
        if (scene == null || mWidth <= 0 || mHeight <= 0) return;
        if (mWidth == mSceneWidth && mHeight == mSceneHeight && mPreview == mScenePreview) return;
        mSceneWidth = mWidth;
        mSceneHeight = mHeight;
        mScenePreview = mPreview;
        scene.resize(mWidth, mHeight, mPreview);
    }

    /** 与 BokehGL.buildVertices 逐项对应：跳过透明与完全移出屏幕的光斑。 */
    private int buildVertices(BokehScene scene) {
        ByteBuffer out = mVertices;
        out.clear();
        int vertexCount = 0;
        final int offsetX = scene.offsetX();
        final int offsetY = scene.offsetY();
        List<BokehCircle> circles = scene.circles();
        for (int i = 0; i < circles.size(); i++) {
            BokehCircle circle = circles.get(i);
            if (circle.alpha == 0) {
                continue;
            }
            float cx = circle.x + offsetX;
            float cy = circle.y + offsetY;
            float r = circle.radius;
            if (cx + r < 0f || cx - r > mWidth) {
                continue;
            }
            float alpha = circle.alpha / 255f;
            float fr = ((circle.color >> 16) & 0xFF) / 255f;
            float fg = ((circle.color >> 8) & 0xFF) / 255f;
            float fb = (circle.color & 0xFF) / 255f;
            // 原版描边色：每个通道 (c*3/4)+63
            float rr = ((((circle.color >> 16) & 0xFF) * 3) / 4 + 63) / 255f;
            float rg = ((((circle.color >> 8) & 0xFF) * 3) / 4 + 63) / 255f;
            float rb = (((circle.color & 0xFF) * 3) / 4 + 63) / 255f;
            putVertex(out, -1f, -1f, cx, cy, r, fr, fg, fb, alpha, rr, rg, rb);
            putVertex(out, 1f, -1f, cx, cy, r, fr, fg, fb, alpha, rr, rg, rb);
            putVertex(out, 1f, 1f, cx, cy, r, fr, fg, fb, alpha, rr, rg, rb);
            putVertex(out, -1f, -1f, cx, cy, r, fr, fg, fb, alpha, rr, rg, rb);
            putVertex(out, 1f, 1f, cx, cy, r, fr, fg, fb, alpha, rr, rg, rb);
            putVertex(out, -1f, 1f, cx, cy, r, fr, fg, fb, alpha, rr, rg, rb);
            vertexCount += VERTICES_PER_CIRCLE;
        }
        return vertexCount;
    }

    private static void putVertex(
            ByteBuffer out,
            float cornerX,
            float cornerY,
            float cx,
            float cy,
            float radius,
            float fillR,
            float fillG,
            float fillB,
            float alpha,
            float ringR,
            float ringG,
            float ringB) {
        out.putFloat(cornerX)
                .putFloat(cornerY)
                .putFloat(cx)
                .putFloat(cy)
                .putFloat(radius)
                .putFloat(fillR)
                .putFloat(fillG)
                .putFloat(fillB)
                .putFloat(alpha)
                .putFloat(ringR)
                .putFloat(ringG)
                .putFloat(ringB);
    }

    /**
     * GL clip → Vulkan clip：y 取反，z 从 [-w, w] 映射到 [0, w]。
     * 与其余 VK delegate 使用同一份矩阵。
     */
    private static final float[] CLIP_CORRECTION = {
        1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 0.5f, 0, 0, 0, 0.5f, 1,
    };
}
