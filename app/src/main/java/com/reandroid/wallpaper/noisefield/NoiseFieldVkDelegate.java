package com.reandroid.wallpaper.noisefield;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.opengl.Matrix;
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
 * NoiseField（泡泡）的 VK 渲染器 —— 壁纸与预览共用。
 *
 * <p>GLES 版是两次 draw：先是加法的点精灵（粒子位置由 Scene 每帧推进，点大小随速度变化，
 * 贴 noisefield_dot 的圆斑），再是同样加法混合的多边形背景。这里各写成一个 pass，顺序与
 * NoiseFieldGL.drawFrame 的 drawDots / drawBackground 一致。
 *
 * <p>点精灵的投影矩阵照抄 NoiseFieldGL.updateMvp 的矩阵链（绕 y 转 180°、x 取反、z 平移 1），
 * 末尾再乘一次 Vulkan 的裁剪空间修正（y 取反、z 由 [-1,1] 映射到 [0,1]）。
 *
 * <p>粒子的推进顺序也和 GLES 相同：本帧上传的是上一帧留下的状态，绘制完才调
 * {@link NoiseFieldScene#updateParticles()}。
 */
final class NoiseFieldVkDelegate extends VkPassRendererDelegate {

    private static final String TAG = "NoiseFieldVK";

    private static final String DOT_BUFFER = "dots";
    private static final String BG_BUFFER = "bg";
    private static final String DOT_TEXTURE = "dot";

    private static final String DOT_VS = "shaders/noisefield_vk_dot.vert.spv";
    private static final String DOT_FS = "shaders/noisefield_vk_dot.frag.spv";
    private static final String BG_VS = "shaders/noisefield_vk_bg.vert.spv";
    private static final String BG_FS = "shaders/noisefield_vk_bg.frag.spv";

    private static final String DOT_ASSET = "noisefield/drawable/noisefield_dot.png";
    private static final String BG_MESH_ASSET = "noisefield/data/noisefield_bg_mesh.csv";

    /** 与 layout.json 里 noisefield_dot_count 的上限一致。 */
    private static final int MAX_DOTS = 150;
    /** x, y, z, speed, alpha —— 与 GLES 的三个顶点属性一一对应。 */
    private static final int DOT_FLOATS = 5;

    private static final int DOT_STRIDE = DOT_FLOATS * 4;
    /** x, y, z, r, g, b, a —— 背景网格是 5 列 CSV 展开来的。 */
    private static final int BG_FLOATS = 7;

    private static final int BG_STRIDE = BG_FLOATS * 4;
    /** mat4 + 点大小的缩放系数。 */
    private static final int PUSH_FLOATS = 17;

    private static final int PUSH_BYTES = PUSH_FLOATS * 4;

    /** 与 NoiseFieldGL.setPluginPrefs 读的同一个键。 */
    private static final String KEY_SIZE = "noisefield_size";

    private final SharedPreferences mPrefs;
    private NoiseFieldScene mScene;

    private VkPass mDotPass;
    private VkPass mBgPass;

    private ByteBuffer mDotBuffer;
    private ByteBuffer mBgBuffer;
    private int mBgVertexCount;
    private boolean mBgUploaded;
    private boolean mDotTextureReady;

    private final float[] mProjMatrix = new float[16];
    private final float[] mTempMatrix = new float[16];
    private final float[] mGlMvp = new float[16];
    private final float[] mVkMvp = new float[16];
    private final float[] mPush = new float[PUSH_FLOATS];

    /** GLES 的 mScaleSize：点大小随屏幕密度放大。 */
    private final float mScaleSize;
    /** GLES 的 mSizeMultiplier：设置里的 noisefield_size。 */
    private volatile float mSizeMultiplier = 1f;

    private volatile int mWidth;
    private volatile int mHeight;

    NoiseFieldVkDelegate(Context context, SharedPreferences prefs) {
        super(context);
        mPrefs = prefs;
        mScaleSize = context.getResources().getDisplayMetrics().densityDpi / 240f;
        mSizeMultiplier = readSizeMultiplier(prefs);
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

        NoiseFieldScene scene = mScene;
        if (scene == null) {
            scene = new NoiseFieldScene(width, height);
            // 与 NoiseFieldGL 的初始化顺序一致：先取设置，再按新的粒子数重建数组并摆位
            if (mPrefs != null) scene.setPluginPrefs(mPrefs);
            scene.start();
            if (scene.consumeParamsDirty()) scene.allocateArrays();
            scene.positionParticles();
            mScene = scene;
        } else {
            synchronized (scene) {
                scene.resize(width, height);
            }
        }
    }

    @Override
    public void onPluginPrefsChanged(SharedPreferences prefs) {
        mSizeMultiplier = readSizeMultiplier(prefs);
        NoiseFieldScene scene = mScene;
        if (scene == null) return;
        // 换粒子数会整体替换位置/速度/alpha 数组，必须与渲染线程的读取串行化
        synchronized (scene) {
            scene.setPluginPrefs(prefs);
        }
    }

    /** 触摸原点。壁纸与预览都走完整的 {@link #onTouchEvent}，这条留给只报按下位置的宿主。 */
    @Override
    public void onSceneTouch(float x, float y) {
        NoiseFieldScene scene = mScene;
        if (scene == null) return;
        synchronized (scene) {
            scene.onCommandTouch((int) x, (int) y);
        }
    }

    /** 与 GLES 一样把整串事件交给 Scene：按下/移动推粒子，抬起结束。 */
    @Override
    public void onTouchEvent(MotionEvent event) {
        NoiseFieldScene scene = mScene;
        if (scene == null || event == null) return;
        synchronized (scene) {
            scene.onTouchEvent(event);
        }
    }

    /** 桌面上的点击从 onCommand 进来，与 NoiseFieldGL.onCommand 处理同两种指令。 */
    @Override
    public void onCommand(String action, int x, int y, int z, Bundle extras) {
        if (!WallpaperManager.COMMAND_TAP.equals(action)
                && !WallpaperManager.COMMAND_SECONDARY_TAP.equals(action)) {
            return;
        }
        NoiseFieldScene scene = mScene;
        if (scene == null) return;
        synchronized (scene) {
            scene.onCommandTouch(x, y);
        }
    }

    @Override
    protected void buildScene(VkPassScene scene) {
        // 渲染器可能被销毁后重建：新场景里的缓冲与纹理都是空的
        mBgUploaded = false;
        mDotTextureReady = false;
        scene.setClearColor(0f, 0f, 0f, 1f);

        scene.createBuffer(DOT_BUFFER, DOT_STRIDE, MAX_DOTS, true);
        mBgVertexCount = buildBackgroundVertices();
        scene.createBuffer(BG_BUFFER, BG_STRIDE, mBgVertexCount, false);

        // GLES 的采样参数是 LINEAR + CLAMP_TO_EDGE
        scene.createTexture(
                DOT_TEXTURE, VkPassScene.FILTER_LINEAR, VkPassScene.ADDRESS_CLAMP_TO_EDGE);
        mDotTextureReady = uploadDotTexture(scene);

        mDotBuffer =
                ByteBuffer.allocateDirect(MAX_DOTS * DOT_STRIDE).order(ByteOrder.nativeOrder());

        mDotPass = scene.add(new VkPass(
                        DOT_VS,
                        DOT_FS,
                        new int[] {3, 1, 1},
                        DOT_STRIDE,
                        VkPass.TOPOLOGY_POINT_LIST,
                        VkPass.BLEND_ADDITIVE))
                .buffer(DOT_BUFFER, 0)
                .pushConstants(PUSH_BYTES)
                // GLES 的 glBlendFunc(SRC_ALPHA, ONE) 对颜色和 alpha 都生效
                .blendFactors(
                        VkPass.FACTOR_SRC_ALPHA, VkPass.FACTOR_ONE,
                        VkPass.FACTOR_SRC_ALPHA, VkPass.FACTOR_ONE);
        mDotPass.texture(0, DOT_TEXTURE);

        mBgPass = scene.add(new VkPass(
                        BG_VS,
                        BG_FS,
                        new int[] {3, 4},
                        BG_STRIDE,
                        VkPass.TOPOLOGY_TRIANGLE_LIST,
                        VkPass.BLEND_ADDITIVE))
                .buffer(BG_BUFFER, mBgVertexCount)
                .blendFactors(
                        VkPass.FACTOR_SRC_ALPHA, VkPass.FACTOR_ONE,
                        VkPass.FACTOR_SRC_ALPHA, VkPass.FACTOR_ONE);
    }

    @Override
    protected void updateFrame(VkPassFrame frame, long timeMs) {
        NoiseFieldScene scene = mScene;
        if (scene == null || mWidth <= 0 || mHeight <= 0) {
            frame.setVertexCount(mDotPass, 0);
            return;
        }

        synchronized (scene) {
            // 与 NoiseFieldGL.drawFrame 相同：设置变了先按新的粒子数重建数组
            if (scene.consumeParamsDirty()) {
                scene.allocateArrays();
                scene.positionParticles();
            }
            scene.updateFrameScale(timeMs);

            int count = Math.min(scene.getDotCount(), MAX_DOTS);
            if (mDotTextureReady && count > 0) {
                fillDotVertices(scene, count);
                frame.upload(DOT_BUFFER, mDotBuffer, count * DOT_STRIDE);
            } else {
                // GLES 在纹理没就绪时整趟跳过点精灵
                count = 0;
            }
            frame.setVertexCount(mDotPass, count);

            updateMvp();
            System.arraycopy(mVkMvp, 0, mPush, 0, 16);
            mPush[16] = mScaleSize * mSizeMultiplier;
            frame.pushConstants(mDotPass, mPush);

            // 背景顶点是静态的，每个渲染器只传一次
            if (!mBgUploaded) {
                mBgBuffer.position(0);
                frame.upload(BG_BUFFER, mBgBuffer, mBgVertexCount * BG_STRIDE);
                mBgUploaded = true;
            }

            // 与 GLES 一样，绘制完之后才推进粒子
            scene.updateParticles();
        }
    }

    /** 三个数组按 GLES 的属性布局交错成一个顶点缓冲。 */
    private void fillDotVertices(NoiseFieldScene scene, int count) {
        float[] positions = scene.getDotPositions();
        float[] speeds = scene.getDotSpeeds();
        float[] alphas = scene.getDotAlpha();
        FloatBuffer floats = mDotBuffer.asFloatBuffer();
        for (int i = 0; i < count; i++) {
            int src = i * 3;
            int dst = i * DOT_FLOATS;
            floats.put(dst, positions[src]);
            floats.put(dst + 1, positions[src + 1]);
            floats.put(dst + 2, positions[src + 2]);
            floats.put(dst + 3, speeds[i]);
            floats.put(dst + 4, alphas[i]);
        }
        mDotBuffer.position(0);
    }

    /**
     * 与 NoiseFieldGL.updateMvp 相同的矩阵链，末尾再乘一次 Vulkan 的裁剪空间修正。
     *
     * <p>GLES 的 z 平移把粒子放到近平面上（z = -1），点精灵才能按点的尺寸光栅化；
     * 这里逐项照抄，避免投影随屏幕方向变化时与 GLES 出现构图偏差。
     */
    private void updateMvp() {
        float[] proj = mProjMatrix;
        float[] tmp = mTempMatrix;
        if (mWidth > mHeight) {
            float aspect = (float) mWidth / (float) mHeight;
            Matrix.frustumM(proj, 0, -aspect, aspect, -1f, 1f, 1f, 100f);
        } else {
            float aspect = (float) mHeight / (float) mWidth;
            Matrix.frustumM(proj, 0, -0.5f, 1f, -aspect, aspect, 1f, 100f);
        }

        Matrix.setRotateM(tmp, 0, 180f, 0f, 1f, 0f);
        Matrix.multiplyMM(mGlMvp, 0, proj, 0, tmp, 0);

        Matrix.setIdentityM(tmp, 0);
        Matrix.scaleM(tmp, 0, -1f, 1f, 1f);
        Matrix.multiplyMM(proj, 0, mGlMvp, 0, tmp, 0);

        Matrix.setIdentityM(tmp, 0);
        Matrix.translateM(tmp, 0, 0f, 0f, 1f);
        Matrix.multiplyMM(mGlMvp, 0, proj, 0, tmp, 0);

        Matrix.multiplyMM(mVkMvp, 0, CLIP_CORRECTION, 0, mGlMvp, 0);
    }

    /** 把 5 列（x, y, r, g, b）的 CSV 展开成交错顶点，z 与 alpha 按 GLES 补齐。 */
    private int buildBackgroundVertices() {
        float[] mesh = AssetLoader.readFloatArray(context, BG_MESH_ASSET);
        int count = mesh.length / 5;
        mBgBuffer = ByteBuffer.allocateDirect(count * BG_STRIDE).order(ByteOrder.nativeOrder());
        FloatBuffer floats = mBgBuffer.asFloatBuffer();
        for (int i = 0; i < count; i++) {
            int src = i * 5;
            int dst = i * BG_FLOATS;
            floats.put(dst, mesh[src]);
            floats.put(dst + 1, mesh[src + 1]);
            floats.put(dst + 2, 0f);
            floats.put(dst + 3, mesh[src + 2]);
            floats.put(dst + 4, mesh[src + 3]);
            floats.put(dst + 5, mesh[src + 4]);
            floats.put(dst + 6, 1f);
        }
        mBgBuffer.position(0);
        return count;
    }

    private boolean uploadDotTexture(VkPassScene scene) {
        try {
            Bitmap bitmap = AssetLoader.decodeBitmap(context, DOT_ASSET);
            if (bitmap == null) return false;
            Bitmap argb = bitmap.getConfig() == Bitmap.Config.ARGB_8888
                    ? bitmap
                    : bitmap.copy(Bitmap.Config.ARGB_8888, false);
            if (argb != bitmap) bitmap.recycle();
            int width = argb.getWidth();
            int height = argb.getHeight();
            int[] pixels = new int[width * height];
            argb.getPixels(pixels, 0, width, 0, 0, width, height);
            argb.recycle();
            scene.setTexture(DOT_TEXTURE, pixels, width, height);
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "dot texture decode failed", e);
            return false;
        }
    }

    private static float readSizeMultiplier(SharedPreferences prefs) {
        if (prefs == null) return 1f;
        return prefs.getInt(KEY_SIZE, 100) / 100f;
    }

    /**
     * GL clip → Vulkan clip：y 取反，z 从 [-w,w] 映射到 [0,w]。
     * 与 CubeVkDelegate / CosmicVkDelegate 使用同一份矩阵。
     */
    private static final float[] CLIP_CORRECTION = {
        1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 0.5f, 0, 0, 0, 0.5f, 1,
    };
}
