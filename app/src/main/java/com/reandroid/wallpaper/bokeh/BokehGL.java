package com.reandroid.wallpaper.bokeh;

import android.content.Context;
import android.opengl.GLES30;
import android.view.MotionEvent;

import com.reandroid.gles.GLESScene;
import com.reandroid.utils.AssetLoader;
import com.reandroid.utils.Mat4;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;

/**
 * 散景彩虹的 GLES 渲染。场景逻辑全在 {@link BokehScene}。
 *
 * <p>原版用 Canvas 画实心圆 + 3px 描边；这里把每个光斑摊成一个四边形，
 * 在片元着色器里按到圆心的距离同时画出填充与描边，一次 draw 画完全部光斑。
 */
public class BokehGL extends GLESScene {

    private static final int MAX_CIRCLES = 360;
    /** corner(2) + center(2) + radius(1) + fill(4) + ring(3)。 */
    private static final int FLOATS_PER_VERTEX = 12;

    private static final int VERTICES_PER_CIRCLE = 6;
    private static final int STRIDE_BYTES = FLOATS_PER_VERTEX * 4;

    private final Context mContext;
    private final BokehScene mScene = new BokehScene();
    private final float[] mProj = new float[16];
    private final FloatBuffer mVertexBuffer;

    private int mProgram;
    private int mCornerLoc;
    private int mCenterLoc;
    private int mRadiusLoc;
    private int mFillLoc;
    private int mRingLoc;
    private int mMvpLoc;
    private int mRingPxLoc;
    private int mVertexCount;

    private int mSceneWidth = -1;
    private int mSceneHeight = -1;
    private boolean mScenePreview;

    public BokehGL(int width, int height, Context context) {
        super(width, height);
        mContext = context;
        mVertexBuffer = ByteBuffer.allocateDirect(
                        MAX_CIRCLES * VERTICES_PER_CIRCLE * FLOATS_PER_VERTEX * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
    }

    @Override
    protected void onCreate() {}

    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        mScene.setOffset(xPixels, yPixels);
    }

    @Override
    public void onCommand(String action, int x, int y, int z) {
        if ("android.wallpaper.tap".equals(action)) {
            mScene.addCircleAt(x, y);
        }
    }

    @Override
    public void onTouchEvent(MotionEvent event) {
        // 应用内预览：点一下也生成一个光斑
        if (event != null && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mScene.addCircleAt(event.getX(), event.getY());
        }
    }

    @Override
    public void drawFrame(long timeMs) {
        initGLIfNeeded();
        ensureSceneSize();

        mScene.update();

        GLES30.glClearColor(0f, 0f, 0f, 1f);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        if (mProgram == 0) {
            return;
        }

        buildVertices();
        if (mVertexCount == 0) {
            return;
        }

        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        GLES30.glUseProgram(mProgram);
        GLES30.glUniformMatrix4fv(mMvpLoc, 1, false, mProj, 0);
        GLES30.glUniform1f(mRingPxLoc, mScene.ringPx());

        mVertexBuffer.position(0);
        GLES30.glEnableVertexAttribArray(mCornerLoc);
        GLES30.glVertexAttribPointer(
                mCornerLoc, 2, GLES30.GL_FLOAT, false, STRIDE_BYTES, mVertexBuffer);
        mVertexBuffer.position(2);
        GLES30.glEnableVertexAttribArray(mCenterLoc);
        GLES30.glVertexAttribPointer(
                mCenterLoc, 2, GLES30.GL_FLOAT, false, STRIDE_BYTES, mVertexBuffer);
        mVertexBuffer.position(4);
        GLES30.glEnableVertexAttribArray(mRadiusLoc);
        GLES30.glVertexAttribPointer(
                mRadiusLoc, 1, GLES30.GL_FLOAT, false, STRIDE_BYTES, mVertexBuffer);
        mVertexBuffer.position(5);
        GLES30.glEnableVertexAttribArray(mFillLoc);
        GLES30.glVertexAttribPointer(
                mFillLoc, 4, GLES30.GL_FLOAT, false, STRIDE_BYTES, mVertexBuffer);
        mVertexBuffer.position(9);
        GLES30.glEnableVertexAttribArray(mRingLoc);
        GLES30.glVertexAttribPointer(
                mRingLoc, 3, GLES30.GL_FLOAT, false, STRIDE_BYTES, mVertexBuffer);

        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, mVertexCount);

        GLES30.glDisableVertexAttribArray(mCornerLoc);
        GLES30.glDisableVertexAttribArray(mCenterLoc);
        GLES30.glDisableVertexAttribArray(mRadiusLoc);
        GLES30.glDisableVertexAttribArray(mFillLoc);
        GLES30.glDisableVertexAttribArray(mRingLoc);
    }

    private void ensureSceneSize() {
        if (mWidth == mSceneWidth && mHeight == mSceneHeight && isPreview() == mScenePreview) {
            return;
        }
        mSceneWidth = mWidth;
        mSceneHeight = mHeight;
        mScenePreview = isPreview();
        mScene.resize(mWidth, mHeight, mScenePreview);
        Mat4.orthoM(mProj, 0.0f, mWidth, mHeight, 0.0f, -1.0f, 1.0f);
    }

    private void buildVertices() {
        mVertexBuffer.clear();
        mVertexCount = 0;
        final int offsetX = mScene.offsetX();
        final int offsetY = mScene.offsetY();
        List<BokehCircle> circles = mScene.circles();
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
            putVertex(-1f, -1f, cx, cy, r, fr, fg, fb, alpha, rr, rg, rb);
            putVertex(1f, -1f, cx, cy, r, fr, fg, fb, alpha, rr, rg, rb);
            putVertex(1f, 1f, cx, cy, r, fr, fg, fb, alpha, rr, rg, rb);
            putVertex(-1f, -1f, cx, cy, r, fr, fg, fb, alpha, rr, rg, rb);
            putVertex(1f, 1f, cx, cy, r, fr, fg, fb, alpha, rr, rg, rb);
            putVertex(-1f, 1f, cx, cy, r, fr, fg, fb, alpha, rr, rg, rb);
            mVertexCount += VERTICES_PER_CIRCLE;
        }
        mVertexBuffer.position(0);
    }

    private void putVertex(
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
        mVertexBuffer
                .put(cornerX)
                .put(cornerY)
                .put(cx)
                .put(cy)
                .put(radius)
                .put(fillR)
                .put(fillG)
                .put(fillB)
                .put(alpha)
                .put(ringR)
                .put(ringG)
                .put(ringB);
    }

    private void initGLIfNeeded() {
        if (mProgram != 0 || mContext == null) {
            return;
        }
        String vs = AssetLoader.readText(mContext, "bokeh/shaders/GLES/bokeh_vs.glsl");
        String fs = AssetLoader.readText(mContext, "bokeh/shaders/GLES/bokeh_fs.glsl");
        mProgram = createProgram(vs, fs);
        if (mProgram == 0) {
            return;
        }
        mCornerLoc = GLES30.glGetAttribLocation(mProgram, "aCorner");
        mCenterLoc = GLES30.glGetAttribLocation(mProgram, "aCenter");
        mRadiusLoc = GLES30.glGetAttribLocation(mProgram, "aRadius");
        mFillLoc = GLES30.glGetAttribLocation(mProgram, "aFill");
        mRingLoc = GLES30.glGetAttribLocation(mProgram, "aRing");
        mMvpLoc = GLES30.glGetUniformLocation(mProgram, "uMVP");
        mRingPxLoc = GLES30.glGetUniformLocation(mProgram, "uRingPx");
    }
}
