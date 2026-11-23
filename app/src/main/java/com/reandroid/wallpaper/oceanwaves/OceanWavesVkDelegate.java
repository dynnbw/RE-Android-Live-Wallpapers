package com.reandroid.wallpaper.oceanwaves;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.util.Log;

import com.reandroid.plugin.VkPass;
import com.reandroid.plugin.VkPassFrame;
import com.reandroid.plugin.VkPassRendererDelegate;
import com.reandroid.plugin.VkPassScene;
import com.reandroid.utils.AssetLoader;
import com.reandroid.utils.Mat4;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 海浪的 VK 渲染器 —— 壁纸与预览共用。
 *
 * <p>GLES 版是一张 32×32 的网格，用索引画 1024 个 quad。通用 pass 不带索引缓冲，
 * 所以这里在 CPU 上按 GLES 的索引顺序把它展开成三角形列表（6144 个顶点）。顶点属性
 * 与网格一致：屏幕像素位置 + 0..1 的 uv；波浪、焦散与反射全部在着色器里，Scene 只提供
 * 时间、缩放与滚动。
 *
 * <p>顶点 uv 的上方取 0：本项目的投影是 y 向下，而 GL 与 Vulkan 上传的位图首行都对应
 * v=0。位置变换交给推送常量里的 uMvp（正交投影左乘 GL → Vulkan 的裁剪空间修正），
 * 与其余 VK delegate 用的是同一份矩阵。
 */
final class OceanWavesVkDelegate extends VkPassRendererDelegate {

    private static final String TAG = "OceanWavesVK";

    private static final int SUBDIVISIONS = 32;
    private static final int QUAD_COUNT = SUBDIVISIONS * SUBDIVISIONS;
    /** 每个 quad 展开成两个三角形。 */
    private static final int CORNERS_PER_QUAD = 6;

    private static final int VERTEX_COUNT = QUAD_COUNT * CORNERS_PER_QUAD;

    /** x, y, u, v。 */
    private static final int FLOATS_PER_VERTEX = 4;

    private static final int STRIDE_BYTES = FLOATS_PER_VERTEX * 4;

    /** mat4 + vec4 参数 + vec2 设计尺寸 + vec2 占位，共 96 字节。 */
    private static final int PUSH_FLOATS = 24;

    private static final int PUSH_BYTES = PUSH_FLOATS * 4;

    /** 海床贴图铺满一次要多少设计单位（参考实现的 imagewidth），再除以缩放设置。 */
    private static final float IMAGE_WIDTH = 1440f;

    private static final String VERT_SHADER = "shaders/oceanwaves_vk.vert.spv";
    private static final String FRAG_SHADER = "shaders/oceanwaves_vk.frag.spv";

    private static final String TEX_SEABED = "oceanwaves/drawable/seabed.webp";
    private static final String TEX_ENVMAP = "oceanwaves/drawable/envmap.png";
    private static final String TEX_RIPPLE = "oceanwaves/drawable/ripple.png";

    private SharedPreferences mPrefs;
    private OceanWavesScene mScene;
    private VkPassScene mPassScene;
    private VkPass mPass;

    private final ByteBuffer mVertices =
            ByteBuffer.allocateDirect(VERTEX_COUNT * STRIDE_BYTES).order(ByteOrder.nativeOrder());

    private final float[] mPush = new float[PUSH_FLOATS];
    private final float[] mProj = new float[16];
    private final float[] mVkMvp = new float[16];

    private int mWidth;
    private int mHeight;
    private int mScrollPixels;

    private boolean mGridDirty = true;
    private boolean mBufferDirty = true;
    private boolean mTexturesUploaded;

    OceanWavesVkDelegate(Context context, SharedPreferences prefs) {
        super(context);
        mPrefs = prefs;
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
        boolean resized = width != mWidth || height != mHeight;
        mWidth = width;
        mHeight = height;
        if (mScene == null) {
            mScene = new OceanWavesScene();
            if (mPrefs != null) mScene.setPluginPrefs(mPrefs);
        }
        if (resized) {
            mScene.resize(width, height);
            // 滚动量按设计单位缓存，尺寸变了要重算（Scene.setOffset 用的是当前视图宽度）。
            mScene.setOffset(mScrollPixels);
            mGridDirty = true;
        }
    }

    @Override
    public void onPluginPrefsChanged(SharedPreferences prefs) {
        mPrefs = prefs;
        if (mScene != null) mScene.setPluginPrefs(prefs);
    }

    @Override
    public void onSceneOffset(float xOffset, int xPixels) {
        // GLES 的 setOffset 用的就是 xPixels，这里逐字照搬，不做比例换算。
        mScrollPixels = xPixels;
        if (mScene != null) mScene.setOffset(xPixels);
    }

    @Override
    protected void buildScene(VkPassScene scene) {
        mPassScene = scene;
        // native 渲染器可能被销毁后重建：新场景里的顶点缓冲与纹理都是空的，必须重新上传。
        mBufferDirty = true;
        mTexturesUploaded = false;

        scene.setClearColor(0.05f, 0.12f, 0.2f, 1f);
        scene.createBuffer("grid", STRIDE_BYTES, VERTEX_COUNT, true);
        // 与 GLES 的采样方式一致：海床与波纹平铺，环境图夹边。
        scene.createTexture("seabed", VkPassScene.FILTER_LINEAR, VkPassScene.ADDRESS_REPEAT);
        scene.createTexture("envmap", VkPassScene.FILTER_LINEAR, VkPassScene.ADDRESS_CLAMP_TO_EDGE);
        scene.createTexture("ripple", VkPassScene.FILTER_LINEAR, VkPassScene.ADDRESS_REPEAT);

        mPass = new VkPass(
                        VERT_SHADER,
                        FRAG_SHADER,
                        new int[] {2, 2},
                        STRIDE_BYTES,
                        VkPass.TOPOLOGY_TRIANGLE_LIST,
                        VkPass.BLEND_NONE)
                .buffer("grid", VERTEX_COUNT)
                .pushConstants(PUSH_BYTES);
        mPass.texture(0, "seabed");
        mPass.texture(1, "envmap");
        mPass.texture(2, "ripple");
        scene.add(mPass);
    }

    @Override
    protected void updateFrame(VkPassFrame frame, long timeMs) {
        OceanWavesScene scene = mScene;
        if (scene == null || mWidth <= 0 || mHeight <= 0) return;

        ensureTextures();

        // GLES 版同样按毫秒差推进时间；timeMs 的起点不影响相位。
        scene.update(timeMs);

        if (mGridDirty) {
            buildGrid();
            buildProjection();
            mGridDirty = false;
            mBufferDirty = true;
        }
        if (mBufferDirty) {
            frame.upload("grid", mVertices, VERTEX_COUNT * STRIDE_BYTES);
            mBufferDirty = false;
        }

        fillPush(scene);
        frame.pushConstants(mPass, mPush);
        frame.setVertexCount(mPass, VERTEX_COUNT);
    }

    /**
     * 与 GLES 的 buildGrid + 索引展开逐项对应：网格顶点铺满屏幕，uv 是整块网格上的
     * 0..1，每个 quad 按 (i0,i2,i1)、(i1,i2,i3) 的顺序拆成两个三角形。
     */
    private void buildGrid() {
        mVertices.clear();
        float step = 1f / SUBDIVISIONS;
        for (int y = 0; y < SUBDIVISIONS; y++) {
            float v0 = y * step;
            float v1 = (y + 1) * step;
            float py0 = v0 * mHeight;
            float py1 = v1 * mHeight;
            for (int x = 0; x < SUBDIVISIONS; x++) {
                float u0 = x * step;
                float u1 = (x + 1) * step;
                float px0 = u0 * mWidth;
                float px1 = u1 * mWidth;

                putVertex(px0, py0, u0, v0);
                putVertex(px0, py1, u0, v1);
                putVertex(px1, py0, u1, v0);
                putVertex(px1, py0, u1, v0);
                putVertex(px0, py1, u0, v1);
                putVertex(px1, py1, u1, v1);
            }
        }
        mVertices.position(0);
    }

    private void putVertex(float x, float y, float u, float v) {
        mVertices.putFloat(x).putFloat(y).putFloat(u).putFloat(v);
    }

    /** 与 GLES 的投影相同：正交 (0, w, h, 0)，再左乘 GL → Vulkan 的裁剪空间修正。 */
    private void buildProjection() {
        Mat4.orthoM(mProj, 0f, mWidth, mHeight, 0f, -1f, 1f);
        Mat4.multiplyMM(mVkMvp, CLIP_CORRECTION, mProj);
    }

    /** 推送常量的顺序与 oceanwaves_vk.vert 里的块布局一致。 */
    private void fillPush(OceanWavesScene scene) {
        System.arraycopy(mVkMvp, 0, mPush, 0, 16);
        mPush[16] = scene.scale();
        mPush[17] = scene.time();
        mPush[18] = scene.panX();
        mPush[19] = IMAGE_WIDTH / scene.zoom();
        mPush[20] = OceanWavesScene.DESIGN_WIDTH;
        mPush[21] = scene.viewHeightDesign();
        mPush[22] = 0f;
        mPush[23] = 0f;
    }

    private void ensureTextures() {
        if (mTexturesUploaded || mPassScene == null) return;
        mTexturesUploaded = true;
        uploadTexture("seabed", TEX_SEABED);
        uploadTexture("envmap", TEX_ENVMAP);
        uploadTexture("ripple", TEX_RIPPLE);
    }

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
        int width = argb.getWidth();
        int height = argb.getHeight();
        int[] pixels = new int[width * height];
        argb.getPixels(pixels, 0, width, 0, 0, width, height);
        argb.recycle();
        mPassScene.setTexture(name, pixels, width, height);
    }

    /**
     * GL clip → Vulkan clip：y 取反，z 从 [-w, w] 映射到 [0, w]。
     * 与其余 VK delegate 使用同一份矩阵。
     */
    private static final float[] CLIP_CORRECTION = {
        1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 0.5f, 0, 0, 0, 0.5f, 1,
    };
}
