package com.reandroid.wallpaper.cosmicflow;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.opengl.Matrix;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.Surface;

import com.reandroid.plugin.VkPass;
import com.reandroid.plugin.VkPassFrame;
import com.reandroid.plugin.VkPassRendererDelegate;
import com.reandroid.plugin.VkPassScene;
import com.reandroid.utils.AssetLoader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Cosmic Flow 的 VK 渲染器 —— 壁纸与预览共用。
 *
 * <p>两个 pass，顺序与 GLES 一致：先背景（方形网格 + bg_grey 纹理 + primary 色调 +
 * 波浪位移），再流场（100×100 噪声位移网格，flow_greyscale 的 alpha 加权次级色）。
 * 两次 draw 都是 SRC_ALPHA/ONE，与 GLES 的 {@code glBlendFunc(GL_SRC_ALPHA, GL_ONE)} 相同。
 *
 * <p>通用渲染器没有索引缓冲，所以 GLES 的两份索引表在构建期展开成顶点：背景的 6 个索引
 * 展开成三角形列表，流场的 19998 个索引按原顺序展开成一条三角形条带（含原来的退化三角形）。
 */
final class CosmicVkDelegate extends VkPassRendererDelegate {

    /** 流场网格（原版 Config.FLOW_MESH_SIZE）。 */
    private static final int FLOW_MESH_SIZE = 100;
    /** 展开后的条带顶点数，等于 GLES 的 19998 个索引。 */
    private static final int FLOW_STRIP_VERTICES = (FLOW_MESH_SIZE - 1) * (FLOW_MESH_SIZE * 2 + 2);

    private static final int FLOW_FLOATS_PER_VERTEX = 3; // x, y, z
    private static final int FLOW_STRIDE_BYTES = FLOW_FLOATS_PER_VERTEX * 4;

    private static final int BG_VERTICES = 6; // 两个三角形
    private static final int BG_FLOATS_PER_VERTEX = 5; // x, y, z, u, v
    private static final int BG_STRIDE_BYTES = BG_FLOATS_PER_VERTEX * 4;

    /** 两个 vec4：时间/高度缩放/色号/偏移 + primary 色。 */
    private static final int BG_PUSH_BYTES = 8 * 4;
    /** mat4 + 4 个 vec4，正好 128 字节的上限。 */
    private static final int FLOW_PUSH_BYTES = 32 * 4;

    private static final String BG_TEXTURE = "cosmicflow/drawable/bg_grey.png";
    private static final String FLOW_TEXTURE = "cosmicflow/drawable/flow_greyscale.png";

    private final SharedPreferences mPrefs;
    private CosmicScene mScene;

    private VkPass mBgPass;
    private VkPass mFlowPass;
    private ByteBuffer mBgBuffer;
    private ByteBuffer mFlowBuffer;
    /** 流场顶点是静态的，surface 重建后重传一次即可。 */
    private volatile boolean mFlowUploaded;

    private final float[] mBgPush = new float[8];
    private final float[] mFlowPush = new float[32];

    private final float[] mProjMatrix = new float[16];
    private final float[] mViewMatrix = new float[16];
    private final float[] mModelMatrix = new float[16];
    private final float[] mMvpMatrix = new float[16];
    private final float[] mTempMatrix = new float[16];

    private volatile int mWidth;
    private volatile int mHeight;
    private long mLastFrameMs = -1;

    CosmicVkDelegate(Context context, SharedPreferences prefs) {
        super(context);
        mPrefs = prefs;
    }

    @Override
    public String logTag() {
        return "CosmicFlowVK";
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
            mScene = new CosmicScene();
            if (mPrefs != null) mScene.setPluginPrefs(mPrefs);
        }
    }

    @Override
    public void onPluginPrefsChanged(SharedPreferences prefs) {
        if (mScene != null) mScene.setPluginPrefs(prefs);
    }

    @Override
    public void onSceneOffset(float xOffset) {
        if (mScene != null) mScene.setScroll(xOffset, SystemClock.uptimeMillis());
    }

    @Override
    public void onTouchEvent(MotionEvent event) {
        if (event == null || mScene == null) return;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            mScene.setTouched(true);
            mScene.onTap(SystemClock.uptimeMillis());
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            mScene.setTouched(false);
        }
    }

    /** 新 surface（可能是新设备）上的顶点缓冲是空的，流场网格要重传。 */
    @Override
    public void onSurfaceCreated(Surface surface, int width, int height) {
        mFlowUploaded = false;
        super.onSurfaceCreated(surface, width, height);
    }

    @Override
    protected void buildScene(VkPassScene scene) {
        scene.setClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        scene.createBuffer("bg", BG_STRIDE_BYTES, BG_VERTICES, false);
        scene.createBuffer("flow", FLOW_STRIDE_BYTES, FLOW_STRIP_VERTICES, false);
        scene.createTexture("bgTexture");
        scene.createTexture("flowTexture");
        uploadTexture(scene, "bgTexture", BG_TEXTURE);
        uploadTexture(scene, "flowTexture", FLOW_TEXTURE);

        mBgBuffer = buildBackgroundVertices();
        mFlowBuffer = buildFlowVertices();
        mFlowUploaded = false;

        mBgPass = scene.add(new VkPass(
                        "shaders/cosmicflow_vk_bg.vert.spv",
                        "shaders/cosmicflow_vk_bg.frag.spv",
                        new int[] {3, 2},
                        BG_STRIDE_BYTES,
                        VkPass.TOPOLOGY_TRIANGLE_LIST,
                        VkPass.BLEND_ADDITIVE))
                .buffer("bg", BG_VERTICES)
                .pushConstants(BG_PUSH_BYTES)
                // GLES 的 glBlendFunc(SRC_ALPHA, ONE) 对颜色和 alpha 都生效
                .blendFactors(
                        VkPass.FACTOR_SRC_ALPHA, VkPass.FACTOR_ONE,
                        VkPass.FACTOR_SRC_ALPHA, VkPass.FACTOR_ONE);
        mBgPass.texture(0, "bgTexture");

        mFlowPass = scene.add(new VkPass(
                        "shaders/cosmicflow_vk_flow.vert.spv",
                        "shaders/cosmicflow_vk_flow.frag.spv",
                        new int[] {3},
                        FLOW_STRIDE_BYTES,
                        VkPass.TOPOLOGY_TRIANGLE_STRIP,
                        VkPass.BLEND_ADDITIVE))
                .buffer("flow", FLOW_STRIP_VERTICES)
                .pushConstants(FLOW_PUSH_BYTES)
                .blendFactors(
                        VkPass.FACTOR_SRC_ALPHA, VkPass.FACTOR_ONE,
                        VkPass.FACTOR_SRC_ALPHA, VkPass.FACTOR_ONE);
        mFlowPass.texture(0, "flowTexture");
    }

    @Override
    protected void updateFrame(VkPassFrame frame, long timeMs) {
        if (mScene == null || mWidth <= 0 || mHeight <= 0) return;

        // 与 GLES 相同：首帧按 16ms 计，之后按真实间隔（CosmicScene 内部再钳一次）
        float dt = mLastFrameMs < 0 ? 0.016f : (timeMs - mLastFrameMs) / 1000.0f;
        mScene.update(Math.min(dt, 0.1f), timeMs);
        mLastFrameMs = timeMs;
        // 投影与模型的矩阵都在渲染线程上算，避免 resize 时和主线程写到一半
        updateProjection(mWidth, mHeight);
        matrixSetUp();

        if (!mFlowUploaded) {
            mFlowBuffer.position(0);
            frame.upload("flow", mFlowBuffer, FLOW_STRIP_VERTICES * FLOW_STRIDE_BYTES);
            mFlowUploaded = true;
        }
        mBgBuffer.position(0);
        frame.upload("bg", mBgBuffer, BG_VERTICES * BG_STRIDE_BYTES);

        float t = mScene.animationTimeMs / 1000.0f;

        mBgPush[0] = t * 0.6f;
        mBgPush[1] = 1.0f / (mScene.noiseScale / 8.0f);
        mBgPush[2] = 0.0f; // 色号（原版恒 0）
        mBgPush[3] = mScene.xOffsetEff;
        mBgPush[4] = mScene.primaryColor[0];
        mBgPush[5] = mScene.primaryColor[1];
        mBgPush[6] = mScene.primaryColor[2];
        mBgPush[7] = 0.0f;
        frame.setVertexCount(mBgPass, BG_VERTICES);
        frame.pushConstants(mBgPass, mBgPush);

        System.arraycopy(mMvpMatrix, 0, mFlowPush, 0, 16);
        mFlowPush[16] = t * 0.6f;
        mFlowPush[17] = mScene.noiseScale;
        mFlowPush[18] = 0.0f;
        mFlowPush[19] = 0.0f;

        // 噪声源点漂移（原版公式，所有频率 ×0.6）
        mFlowPush[20] = 0.45f
                + 0.7f
                        * (0.32f * (float) Math.cos(t * 0.02f * 0.6f)
                                + 0.1f * (float) Math.sin(t * 0.01f * 0.6f)
                                + 0.07f * (float) Math.sin(t * 0.005f * 0.6f));
        mFlowPush[21] = 0.5f
                + 0.7f
                        * (0.30f * (float) Math.sin(t * 0.02f * 0.6f)
                                + 0.1f * (float) Math.cos(t * 0.01f * 0.6f)
                                + 0.07f * (float) Math.cos(t * 0.005f * 0.6f));
        mFlowPush[22] = 0.0f;
        mFlowPush[23] = 0.0f;

        mFlowPush[24] = mScene.primaryColor[0];
        mFlowPush[25] = mScene.primaryColor[1];
        mFlowPush[26] = mScene.primaryColor[2];
        mFlowPush[27] = 0.0f;
        mFlowPush[28] = mScene.secondaryColor[0];
        mFlowPush[29] = mScene.secondaryColor[1];
        mFlowPush[30] = mScene.secondaryColor[2];
        mFlowPush[31] = 0.0f;

        frame.setVertexCount(mFlowPass, FLOW_STRIP_VERTICES);
        frame.pushConstants(mFlowPass, mFlowPush);
    }

    /** 原版 onSurfaceChanged 投影：竖屏 ±aspect×±1，横屏 ±0.7×±aspect。 */
    private void updateProjection(int width, int height) {
        if (width <= 0 || height <= 0) return;
        float ratio;
        if (width <= height) {
            ratio = (float) width / (float) height;
            Matrix.frustumM(mProjMatrix, 0, -ratio, ratio, -1.0f, 1.0f, 2.5f, 10.0f);
        } else {
            ratio = (float) height / (float) width;
            Matrix.frustumM(mProjMatrix, 0, -0.7f, 0.7f, -ratio, ratio, 2.5f, 10.0f);
        }
        Matrix.setLookAtM(mViewMatrix, 0, 0f, 0f, -5f, 0f, 0f, 0f, 0f, 1f, 0f);
    }

    /** 原版 matrixSetUp：模型 = rotateX90 × scale(6,4,7) × translate(-0.47+xOffset, -0.5, 0)。 */
    private void matrixSetUp() {
        Matrix.setIdentityM(mModelMatrix, 0);
        Matrix.rotateM(mModelMatrix, 0, 90.0f, 1.0f, 0.0f, 0.0f);
        Matrix.rotateM(mModelMatrix, 0, 0.0f, 0.0f, 0.0f, 1.0f);
        Matrix.scaleM(mModelMatrix, 0, 6.0f, 4.0f, 7.0f);
        Matrix.translateM(mModelMatrix, 0, -0.47f + mScene.xOffsetEff, -0.5f, 0.0f);
        Matrix.multiplyMM(mTempMatrix, 0, mViewMatrix, 0, mModelMatrix, 0);
        Matrix.multiplyMM(mMvpMatrix, 0, mProjMatrix, 0, mTempMatrix, 0);
    }

    private void uploadTexture(VkPassScene scene, String name, String asset) {
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
        scene.setTexture(name, pixels, width, height);
    }

    /** GLES 的索引 {0,1,2,1,2,3} 展开成三角形列表。 */
    private static ByteBuffer buildBackgroundVertices() {
        // x, y, z, u, v —— 顶点顺序与 GLES 的 squarePos / squareTexCoord 一致
        float[] vertices = {
            -1.0f, 1.0f, 0.02f, 0.0f, 0.0f, 1.0f, 1.0f, 0.02f, 1.0f, 0.0f, -1.0f, -1.0f, 0.02f,
            0.0f, 1.0f, 1.0f, 1.0f, 0.02f, 1.0f, 0.0f, -1.0f, -1.0f, 0.02f, 0.0f, 1.0f, 1.0f, -1.0f,
            0.02f, 1.0f, 1.0f,
        };
        ByteBuffer buffer =
                ByteBuffer.allocateDirect(vertices.length * 4).order(ByteOrder.nativeOrder());
        buffer.asFloatBuffer().put(vertices);
        buffer.position(0);
        return buffer;
    }

    /** 展开 99 行 × 202 索引的条带；行间的两个重复顶点就是原来的退化三角形。 */
    private static ByteBuffer buildFlowVertices() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(FLOW_STRIP_VERTICES * FLOW_STRIDE_BYTES)
                .order(ByteOrder.nativeOrder());
        for (int row = 0; row < FLOW_MESH_SIZE - 1; row++) {
            for (int column = 0; column < FLOW_MESH_SIZE; column++) {
                putFlowVertex(buffer, row * FLOW_MESH_SIZE + column);
                putFlowVertex(buffer, (row + 1) * FLOW_MESH_SIZE + column);
            }
            putFlowVertex(buffer, (row + 1) * FLOW_MESH_SIZE + FLOW_MESH_SIZE - 1);
            putFlowVertex(buffer, (row + 1) * FLOW_MESH_SIZE);
        }
        buffer.position(0);
        return buffer;
    }

    /** 网格坐标：x = j/100，y = i·0.7/100，与 GLES 的流场顶点缓冲一致。 */
    private static void putFlowVertex(ByteBuffer buffer, int vertexIndex) {
        int row = vertexIndex / FLOW_MESH_SIZE;
        int column = vertexIndex % FLOW_MESH_SIZE;
        buffer.putFloat(column / (float) FLOW_MESH_SIZE);
        buffer.putFloat(row * 0.7f / FLOW_MESH_SIZE);
        buffer.putFloat(0.0f);
    }
}
