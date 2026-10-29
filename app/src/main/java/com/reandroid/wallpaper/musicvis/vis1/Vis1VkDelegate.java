package com.reandroid.wallpaper.musicvis.vis1;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.Surface;

import com.reandroid.plugin.VkPass;
import com.reandroid.plugin.VkPassFrame;
import com.reandroid.plugin.VkPassRendererDelegate;
import com.reandroid.plugin.VkPassScene;
import com.reandroid.utils.Mat4;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * vis1（Visualizer）的 VK 渲染器 —— 壁纸与预览共用。
 *
 * 几何完全复用 {@link VisualizerScene#buildDots}：GLES 版每帧也是拿同一份
 * 像素坐标 + 角坐标画三角形（一个采样两个三角形），这里只是把 {@code FloatBuffer}
 * 换成 VK 的顶点缓冲，片元着色器再把四边形切成边缘柔化的圆。
 *
 * 投影仍是桌面像素正交（y 向下），再拼上 GL → Vulkan 的裁剪空间修正
 * （y 取反、z 从 [-1,1] 映射到 [0,1]）。于是"中线 + 采样值 × 系数"这句像素数学
 * 与 GLES 版逐字一致。
 *
 * 音频采集不随渲染线程停下：渲染器建好且可见时启动，不可见或 surface 销毁时停止，
 * 避免壁纸被挡住后仍在后台录音。
 */
final class Vis1VkDelegate extends VkPassRendererDelegate {

    private static final String VERTEX_BUFFER = "dots";

    /** x, y, 角 x, 角 y —— 与 GLES 的 aPosition / aCorner 一一对应。 */
    private static final int FLOATS_PER_VERTEX = 4;

    private static final int STRIDE_BYTES = FLOATS_PER_VERTEX * 4;

    /** 一个采样 6 个顶点（两个三角形）；顶点数上限 = 1024 个采样。 */
    private static final int MAX_VERTICES =
            VisualizerScene.CAPTURE_SIZE * VisualizerScene.FLOATS_PER_DOT / FLOATS_PER_VERTEX;

    /** 推送常量：mat4 投影（16）+ vec4 颜色（4）+ float 柔边（1），共 84 字节。 */
    private static final int PUSH_FLOATS = 16 + 4 + 1;

    private static final int PUSH_BYTES = PUSH_FLOATS * 4;

    private final SharedPreferences mPrefs;
    private VisualizerScene mScene;
    private VkPassScene mPassScene;
    private VkPass mPass;
    private ByteBuffer mVertexBuffer;
    private FloatBuffer mVertexFloats;

    private final float[] mPush = new float[PUSH_FLOATS];
    private final float[] mProjection = new float[16];
    private final float[] mMvp = new float[16];

    /** 希望采音频吗 —— 不可见时挂起，重新可见时恢复。 */
    private boolean mAudioRequested = true;

    /** native 渲染器建好之前不采音频，避免 VK 初始化失败后一直录音。 */
    private boolean mRendererBuilt;

    private int mWidth;
    private int mHeight;

    Vis1VkDelegate(Context context, SharedPreferences prefs) {
        super(context);
        mPrefs = prefs;
    }

    @Override
    public String logTag() {
        return "Vis1VK";
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
        VisualizerScene scene = mScene;
        if (scene == null) {
            mWidth = width;
            mHeight = height;
            scene = new VisualizerScene(width, height, context);
            // 与 GLES 的 onCreate → tryInjectPrefs 一样：先注入设置，再允许 start()。
            applyPrefs(scene, mPrefs);
            mScene = scene;
            synchronized (scene) {
                updateProjectionLocked();
            }
        } else {
            // 顶点是按屏宽铺满、按屏高定振幅算出来的，尺寸与渲染线程的读取要串行化。
            synchronized (scene) {
                mWidth = width;
                mHeight = height;
                scene.resize(width, height);
                updateProjectionLocked();
            }
        }
    }

    @Override
    public void onPluginPrefsChanged(SharedPreferences prefs) {
        VisualizerScene scene = mScene;
        if (scene == null) return;
        synchronized (scene) {
            applyPrefs(scene, prefs);
            applyClearColor(scene);
        }
    }

    private static void applyPrefs(VisualizerScene scene, SharedPreferences prefs) {
        if (prefs != null) scene.setPluginPrefs(prefs);
    }

    /** 背景色走 musicvis_bg_color；默认 #000000，与原版的 c.drawColor(0xff000000) 一致。 */
    private void applyClearColor(VisualizerScene scene) {
        VkPassScene passScene = mPassScene;
        if (passScene != null) {
            passScene.setClearColor(scene.mBgColor[0], scene.mBgColor[1], scene.mBgColor[2], 1.0f);
        }
    }

    @Override
    public void onVisibilityChanged(boolean visible) {
        mAudioRequested = visible;
        VisualizerScene scene = mScene;
        if (scene == null) return;
        if (visible) {
            // 渲染器还没建好时由 buildScene 接手启动。
            if (mRendererBuilt) scene.start();
        } else {
            scene.stop();
        }
    }

    @Override
    public void onSurfaceCreated(Surface surface, int width, int height) {
        super.onSurfaceCreated(surface, width, height);
        // surface 重建不会重走 buildScene；可见时就恢复采集。
        if (mAudioRequested) startAudio();
    }

    @Override
    public void onSurfaceDestroyed() {
        // 壁纸被挡住 / 预览窗口销毁时 Surface 先没：立即停采集，不等渲染线程退出。
        stopAudio();
        super.onSurfaceDestroyed();
    }

    private void startAudio() {
        VisualizerScene scene = mScene;
        if (scene != null && mRendererBuilt) scene.start();
    }

    private void stopAudio() {
        VisualizerScene scene = mScene;
        if (scene != null) scene.stop();
    }

    @Override
    protected void buildScene(VkPassScene scene) {
        mPassScene = scene;
        VisualizerScene visualizer = mScene;
        if (visualizer != null) {
            applyClearColor(visualizer);
        } else {
            scene.setClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        }
        scene.createBuffer(VERTEX_BUFFER, STRIDE_BYTES, MAX_VERTICES, true);

        mPass = scene.add(new VkPass(
                        "shaders/vis1_vk.vert.spv",
                        "shaders/vis1_vk.frag.spv",
                        new int[] {2, 2},
                        STRIDE_BYTES,
                        VkPass.TOPOLOGY_TRIANGLE_LIST,
                        VkPass.BLEND_ALPHA))
                .buffer(VERTEX_BUFFER, 0)
                .pushConstants(PUSH_BYTES)
                // GLES 的 glBlendFunc(SRC_ALPHA, ONE_MINUS_SRC_ALPHA) 对 alpha 通道同样生效；
                // 通用 BLEND_ALPHA 预设给 srcAlpha 用的是 ONE。
                .blendFactors(
                        VkPass.FACTOR_SRC_ALPHA,
                        VkPass.FACTOR_ONE_MINUS_SRC_ALPHA,
                        VkPass.FACTOR_SRC_ALPHA,
                        VkPass.FACTOR_ONE_MINUS_SRC_ALPHA);

        mVertexBuffer = ByteBuffer.allocateDirect(MAX_VERTICES * STRIDE_BYTES)
                .order(ByteOrder.nativeOrder());
        mVertexFloats = mVertexBuffer.asFloatBuffer();

        mRendererBuilt = true;
        // 渲染器可用后才开始采集；prefs 已在 ensureScene 里注入。
        if (mAudioRequested) startAudio();
    }

    @Override
    protected void updateFrame(VkPassFrame frame, long timeMs) {
        VisualizerScene scene = mScene;
        if (scene == null || mPass == null) return;
        synchronized (scene) {
            if (mWidth <= 0 || mHeight <= 0) {
                frame.setVertexCount(mPass, 0);
                return;
            }

            scene.updateTrace();
            final int floats = scene.vertexFloats();
            if (floats <= 0) {
                frame.setVertexCount(mPass, 0);
                return;
            }

            // 顶点已是像素坐标 + 角坐标，原样上传；投影在顶点着色器里做。
            mVertexFloats.clear();
            mVertexFloats.put(scene.vertices(), 0, floats);

            float[] color = scene.lineColor();
            mPush[16] = color[0];
            mPush[17] = color[1];
            mPush[18] = color[2];
            mPush[19] = 1.0f;
            mPush[20] = scene.feather();

            frame.upload(VERTEX_BUFFER, mVertexBuffer, floats * 4);
            frame.setVertexCount(mPass, floats / FLOATS_PER_VERTEX);
            frame.pushConstants(mPass, mPush);
        }
    }

    @Override
    public void destroyRenderer() {
        mRendererBuilt = false;
        stopAudio();
        super.destroyRenderer();
    }

    /** 调用方需持有 scene 锁。 */
    private void updateProjectionLocked() {
        // 屏幕像素、y 向下（与 GLES 的 orthoM(0, w, h, 0) 同一个口径）
        Mat4.orthoM(mProjection, 0.0f, mWidth, mHeight, 0.0f, -1.0f, 1.0f);
        // GL 裁剪空间 → Vulkan：y 取反，z 从 [-w, w] 映射到 [0, w]
        Mat4.multiplyMM(mMvp, CLIP_CORRECTION, mProjection);
        System.arraycopy(mMvp, 0, mPush, 0, 16);
    }

    private static final float[] CLIP_CORRECTION = {
        1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 0.5f, 0, 0, 0, 0.5f, 1,
    };
}
