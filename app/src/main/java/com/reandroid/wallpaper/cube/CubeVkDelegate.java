package com.reandroid.wallpaper.cube;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.MotionEvent;

import com.reandroid.plugin.VkPass;
import com.reandroid.plugin.VkPassFrame;
import com.reandroid.plugin.VkPassRendererDelegate;
import com.reandroid.plugin.VkPassScene;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Cube 的 VK 渲染器 —— 壁纸与预览共用。
 *
 * <p>GLES 版是两次 draw：形状线段用 GL_LINES，触摸圆圈用 GL_LINE_LOOP，都是白色、
 * {@code SRC_ALPHA / ONE_MINUS_SRC_ALPHA} 混合。通用 pass 渲染器只支持三角形图元，
 * 所以这里把两种线扩成屏幕空间里 2 像素宽的三角形，按 GLES 的先后写进同一个顶点批，
 * 一次 draw 画完。位置仍按 GLES 的 NDC 计算，Vulkan 的 y 翻转交给 CLIP_CORRECTION。
 *
 * <p>线段每帧重算并整批上传（与 GLES 每帧重建 FloatBuffer 一致）；形状只有几十段，
 * 顶点上限取 1024 已经覆盖最长的一种。
 */
final class CubeVkDelegate extends VkPassRendererDelegate {

    private static final String VERTEX_BUFFER = "lines";

    /** x, y, alpha —— 线的颜色固定为白，只有触摸圆圈需要 0.6 的 alpha。 */
    private static final int FLOATS_PER_VERTEX = 3;

    private static final int STRIDE_BYTES = FLOATS_PER_VERTEX * 4;
    private static final int VERTICES_PER_QUAD = 6;
    /** 最长的形状 36 段线 = 216 顶点；触摸圆圈 32 段 = 192 顶点；留足余量。 */
    private static final int MAX_VERTICES = 1024;

    private static final int PUSH_BYTES = 16 * 4;

    private static final int CIRCLE_SEGMENTS = 32;
    private static final float TOUCH_CIRCLE_RADIUS = 0.08f;
    /** GLES 的 glLineWidth(2f)。 */
    private static final float LINE_WIDTH_PX = 2.0f;

    private static final float TOUCH_RING_ALPHA = 0.6f;

    private final SharedPreferences mPrefs;
    private CubeScene mScene;

    private VkPass mPass;
    private ByteBuffer mVertices;
    private int mVertexCount;
    private final float[] mPush = new float[16];

    private int mWidth;
    private int mHeight;

    // 圆圈每帧重算成内外两圈 NDC 顶点，避免逐帧分配。
    private final float[] mRingOuterX = new float[CIRCLE_SEGMENTS];
    private final float[] mRingOuterY = new float[CIRCLE_SEGMENTS];
    private final float[] mRingInnerX = new float[CIRCLE_SEGMENTS];
    private final float[] mRingInnerY = new float[CIRCLE_SEGMENTS];

    CubeVkDelegate(Context context, SharedPreferences prefs) {
        super(context);
        mPrefs = prefs;
    }

    @Override
    public String logTag() {
        return "CubeVK";
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

        CubeScene scene = mScene;
        if (scene == null) {
            mWidth = width;
            mHeight = height;
            scene = new CubeScene(context);
            // 与 CubeGL.onCreate 一致：线宽按密度缩放。
            scene.mScaleSize = context.getResources().getDisplayMetrics().densityDpi / 240f;
            scene.setScreenSize(width, height);
            applyPrefs(scene, mPrefs);
            mScene = scene;
        } else {
            // 尺寸与屏幕尺寸要和每帧的读取在同一把锁下更新。
            synchronized (scene) {
                mWidth = width;
                mHeight = height;
                scene.setScreenSize(width, height);
            }
        }
    }

    @Override
    public void onPluginPrefsChanged(SharedPreferences prefs) {
        CubeScene scene = mScene;
        if (scene == null) return;
        // 换形状会整体替换点/线/投影数组，必须与渲染线程的读取串行化。
        synchronized (scene) {
            applyPrefs(scene, prefs);
        }
    }

    @Override
    public void onSceneOffset(float xOffset) {
        if (mScene != null) mScene.setOffset(xOffset);
    }

    @Override
    public void onTouchEvent(MotionEvent event) {
        if (mScene != null) mScene.onTouchEvent(event);
    }

    private static void applyPrefs(CubeScene scene, SharedPreferences prefs) {
        scene.setPluginPrefs(prefs);
        String shape = prefs != null ? prefs.getString(CubeScene.KEY_SHAPE, "cube") : "cube";
        scene.loadShape(shape);
    }

    @Override
    protected void buildScene(VkPassScene scene) {
        scene.setClearColor(0f, 0f, 0f, 1f);
        scene.createBuffer(VERTEX_BUFFER, STRIDE_BYTES, MAX_VERTICES, true);

        mPass = scene.add(new VkPass(
                        "shaders/cube_vk.vert.spv",
                        "shaders/cube_vk.frag.spv",
                        new int[] {2, 1},
                        STRIDE_BYTES,
                        VkPass.TOPOLOGY_TRIANGLE_LIST,
                        VkPass.BLEND_ALPHA))
                .buffer(VERTEX_BUFFER, 0)
                .pushConstants(PUSH_BYTES)
                // GLES glBlendFunc(SRC_ALPHA, ONE_MINUS_SRC_ALPHA) 对 alpha 通道同样生效；
                // 通用 BLEND_ALPHA 预设给 srcAlpha 用的是 ONE。
                .blendFactors(
                        VkPass.FACTOR_SRC_ALPHA,
                        VkPass.FACTOR_ONE_MINUS_SRC_ALPHA,
                        VkPass.FACTOR_SRC_ALPHA,
                        VkPass.FACTOR_ONE_MINUS_SRC_ALPHA);

        mVertices = ByteBuffer.allocateDirect(MAX_VERTICES * STRIDE_BYTES)
                .order(ByteOrder.nativeOrder());
        System.arraycopy(CLIP_CORRECTION, 0, mPush, 0, 16);
    }

    @Override
    protected void updateFrame(VkPassFrame frame, long timeMs) {
        CubeScene scene = mScene;
        if (scene == null || mWidth <= 0 || mHeight <= 0) {
            frame.setVertexCount(mPass, 0);
            return;
        }

        synchronized (scene) {
            // CubeScene 的时间基准是 System.currentTimeMillis()（mStartTimeMs 与
            // refreshFlags 都用它）；VK 帧循环给的是 uptimeMillis，不能直接喂进去。
            scene.rotateAndProject(System.currentTimeMillis());

            mVertexCount = 0;
            mVertices.clear();
            addShapeLines(scene);
            addTouchRing(scene);

            if (mVertexCount == 0) {
                frame.setVertexCount(mPass, 0);
                return;
            }

            mVertices.position(0);
            frame.upload(VERTEX_BUFFER, mVertices, mVertexCount * STRIDE_BYTES);
            frame.setVertexCount(mPass, mVertexCount);
            frame.pushConstants(mPass, mPush);
        }
    }

    /** 与 CubeGL.drawLines 相同：每条线两个端点，扩大到 2 像素宽的四边形。 */
    private void addShapeLines(CubeScene scene) {
        float halfW = mWidth * 0.5f;
        float halfH = mHeight * 0.5f;
        for (CubeScene.ThreeDLine line : scene.mLines) {
            float x0 = scene.mProjectedX[line.startPoint] / halfW;
            float y0 = -scene.mProjectedY[line.startPoint] / halfH;
            float x1 = scene.mProjectedX[line.endPoint] / halfW;
            float y1 = -scene.mProjectedY[line.endPoint] / halfH;
            addSegment(x0, y0, x1, y1, halfW, halfH, 1.0f);
        }
    }

    /**
     * 一条线段的四边形。偏移在像素空间里取半线宽，再换回 NDC，因此横竖斜的
     * 视觉粗细一致；GL 的线段没有端口延伸，这里用平头四边形。
     */
    private void addSegment(
            float x0, float y0, float x1, float y1, float halfW, float halfH, float alpha) {
        if (mVertexCount + VERTICES_PER_QUAD > MAX_VERTICES) return;
        float dx = x1 - x0;
        float dy = y1 - y0;
        float px = dx * halfW;
        float py = dy * halfH;
        float len = (float) Math.sqrt(px * px + py * py);
        if (len < 1.0E-6f) return;

        float halfLine = LINE_WIDTH_PX * 0.5f;
        // 像素空间法线 (-py, px) / len，换回 NDC 时 x 除以 halfW、y 除以 halfH。
        float ox = -dy * halfH / (len * halfW) * halfLine;
        float oy = dx * halfW / (len * halfH) * halfLine;

        putVertex(x0 + ox, y0 + oy, alpha);
        putVertex(x0 - ox, y0 - oy, alpha);
        putVertex(x1 + ox, y1 + oy, alpha);
        putVertex(x1 + ox, y1 + oy, alpha);
        putVertex(x0 - ox, y0 - oy, alpha);
        putVertex(x1 - ox, y1 - oy, alpha);
    }

    /**
     * 与 CubeGL.drawTouchPoint 相同的位置，画成内外半径相差 2 像素的圆环带：
     * 相邻段共用端点，接缝处不会像独立的四边形那样叠出更亮的点。
     */
    private void addTouchRing(CubeScene scene) {
        if (scene.mTouchX < 0 || scene.mTouchY < 0) return;
        if (scene.mScreenWidth <= 0 || scene.mScreenHeight <= 0) return;
        if (mVertexCount + CIRCLE_SEGMENTS * VERTICES_PER_QUAD > MAX_VERTICES) return;

        float halfW = mWidth * 0.5f;
        float halfH = mHeight * 0.5f;
        float ndcX = (scene.mTouchX / scene.mScreenWidth) * 2f - 1f;
        float ndcY = -((scene.mTouchY / scene.mScreenHeight) * 2f - 1f);

        // GLES 里圆的半径按半屏高度定，x 方向再按宽高比折算；像素空间里就是正圆。
        float radiusPx = TOUCH_CIRCLE_RADIUS * halfH;
        float outerPx = radiusPx + LINE_WIDTH_PX * 0.5f;
        float innerPx = Math.max(radiusPx - LINE_WIDTH_PX * 0.5f, 0f);

        for (int i = 0; i < CIRCLE_SEGMENTS; i++) {
            double angle = i * 2.0 * Math.PI / CIRCLE_SEGMENTS;
            float c = (float) Math.cos(angle);
            float s = (float) Math.sin(angle);
            mRingOuterX[i] = ndcX + c * outerPx / halfW;
            mRingOuterY[i] = ndcY + s * outerPx / halfH;
            mRingInnerX[i] = ndcX + c * innerPx / halfW;
            mRingInnerY[i] = ndcY + s * innerPx / halfH;
        }

        for (int i = 0; i < CIRCLE_SEGMENTS; i++) {
            int j = (i + 1) % CIRCLE_SEGMENTS;
            putVertex(mRingOuterX[i], mRingOuterY[i], TOUCH_RING_ALPHA);
            putVertex(mRingInnerX[i], mRingInnerY[i], TOUCH_RING_ALPHA);
            putVertex(mRingOuterX[j], mRingOuterY[j], TOUCH_RING_ALPHA);

            putVertex(mRingOuterX[j], mRingOuterY[j], TOUCH_RING_ALPHA);
            putVertex(mRingInnerX[i], mRingInnerY[i], TOUCH_RING_ALPHA);
            putVertex(mRingInnerX[j], mRingInnerY[j], TOUCH_RING_ALPHA);
        }
    }

    private void putVertex(float x, float y, float alpha) {
        mVertices.putFloat(x).putFloat(y).putFloat(alpha);
        mVertexCount++;
    }

    private static final float[] CLIP_CORRECTION = {
        1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 0.5f, 0, 0, 0, 0.5f, 1,
    };
}
