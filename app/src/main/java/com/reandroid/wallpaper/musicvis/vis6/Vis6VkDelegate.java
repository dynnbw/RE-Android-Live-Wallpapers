package com.reandroid.wallpaper.musicvis.vis6;

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
import java.nio.FloatBuffer;

/**
 * vis6（环形频谱）的 VK 渲染器 —— 壁纸与预览共用。
 *
 * <p>几何完全复用 {@link CircleScene#updateRingVertices()}：GLES 版每环上传一份
 * 内外两圈的条带顶点，再按环逐个 {@code glDrawArrays(GL_TRIANGLE_STRIP)}。这里保持
 * 同样的「每环一个 buffer、每环一次 draw、拓扑仍是三角形条带」，只是 buffer 与 pass
 * 在 {@code buildScene} 里按设置上限一次性建好，之后每帧只改顶点数与内容。
 *
 * <p>顶点是场景坐标系里的 (x, y)。GLES 的 MVP 是正交投影乘旋转，这里再左乘
 * GL → Vulkan 的裁剪空间修正（y 取反、z 从 [-1, 1] 映射到 [0, 1]），所以环形的位置、
 * 宽高比与滚动旋转都与 GLES 逐字一致。
 *
 * <p>重着色路径与 {@code MusicVisCircleGL.drawFrame} 相同：取灰阶贴图并乘上逐顶点的
 * 色调/饱和度/亮度。GLES 的片元着色器声明了 {@code aTexCoord}，但渲染器从未启用该属性，
 * 采样坐标恒为默认值 (0, 0)；这里照此采样，保证取到的明度与原版一致。
 *
 * <p>音频采集不随渲染线程停下：渲染器建好且可见时启动，不可见或 surface 销毁时停止，
 * 避免壁纸被挡住后仍在后台录音。
 */
final class Vis6VkDelegate extends VkPassRendererDelegate {

    private static final String TAG = "Vis6VK";

    /** 设置里环数的上限（{@code circle_ring_count} 的 max）。buffer 与 pass 按它一次性声明。 */
    private static final int MAX_RINGS = 32;

    /** x, y, hue, saturation, brightness —— 重着色路径的 aAdjust 与位置放在同一条顶点里。 */
    private static final int FLOATS_PER_VERTEX = 5;

    private static final int STRIDE_BYTES = FLOATS_PER_VERTEX * 4;

    /** 推送常量：mat4（已乘过裁剪修正）。 */
    private static final int PUSH_FLOATS = 16;

    private static final int PUSH_BYTES = PUSH_FLOATS * 4;

    private static final String TEXTURE_GREY = "grey";

    /** 与 {@code MusicVisCircleGL} 的 mGreyTextureId 是同一张灰阶贴图。 */
    private static final String ASSET_GREY = "musicvis/drawable/musicvis_grey.png";

    private SharedPreferences mPrefs;
    private CircleScene mScene;
    private VkPassScene mPassScene;

    private final VkPass[] mPlainPasses = new VkPass[MAX_RINGS];
    private final VkPass[] mColorPasses = new VkPass[MAX_RINGS];
    private final ByteBuffer[] mRingBuffers = new ByteBuffer[MAX_RINGS];
    private final FloatBuffer[] mRingFloats = new FloatBuffer[MAX_RINGS];
    private final String[] mRingBufferNames = new String[MAX_RINGS];

    private final float[] mPush = new float[PUSH_FLOATS];
    private final float[] mVkMvp = new float[16];

    private boolean mTexturesUploaded;

    /** 希望采音频吗 —— 不可见时挂起，重新可见时恢复。 */
    private boolean mAudioRequested = true;

    /** native 渲染器建好之前不采音频，避免 VK 初始化失败后一直录音。 */
    private boolean mRendererBuilt;

    private int mWidth;
    private int mHeight;

    Vis6VkDelegate(Context context, SharedPreferences prefs) {
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

        CircleScene scene = mScene;
        if (scene != null && mWidth == width && mHeight == height) return;

        // CircleScene 的尺寸只在构造时确定（GLES 版同样如此），
        // 所以尺寸变化时整体重建，顺带释放旧场景的音频采集。
        if (scene != null) {
            synchronized (scene) {
                scene.release();
            }
        }

        mWidth = width;
        mHeight = height;
        scene = new CircleScene(width, height, context);
        // 与 GLES 的 onCreate → tryInjectPrefs 一样：先注入设置，再允许 start()。
        applyPrefs(scene, mPrefs);
        mScene = scene;
        // 尺寸在渲染器建好之后才到齐时（预览常见），这里补一次采集启动。
        if (mRendererBuilt && mAudioRequested) scene.start();
    }

    @Override
    public void onPluginPrefsChanged(SharedPreferences prefs) {
        mPrefs = prefs;
        CircleScene scene = mScene;
        if (scene == null) return;
        // 改环数会整体替换顶点/幅度数组，必须与渲染线程的读取串行化。
        synchronized (scene) {
            applyPrefs(scene, prefs);
            applyClearColor(scene);
        }
    }

    private static void applyPrefs(CircleScene scene, SharedPreferences prefs) {
        if (prefs != null) scene.setPluginPrefs(prefs);
    }

    /** 清屏色就是场景读到的背景色；buildScene 与设置变更时各下发一次。 */
    private void applyClearColor(CircleScene scene) {
        VkPassScene passScene = mPassScene;
        if (passScene == null) return;
        passScene.setClearColor(scene.mBgColor[0], scene.mBgColor[1], scene.mBgColor[2], 1.0f);
    }

    @Override
    public void onSceneOffset(float xOffset) {
        CircleScene scene = mScene;
        if (scene != null) scene.setOffset(xOffset, 0f, 0, 0);
    }

    @Override
    public void onVisibilityChanged(boolean visible) {
        mAudioRequested = visible;
        CircleScene scene = mScene;
        if (scene == null) return;
        if (visible) {
            // 渲染器还没建好时由 buildScene 接手启动。
            if (mRendererBuilt) scene.start();
        } else {
            scene.stop();
        }
    }

    @Override
    public void onSurfaceCreated(android.view.Surface surface, int width, int height) {
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
        CircleScene scene = mScene;
        if (scene != null && mRendererBuilt) scene.start();
    }

    private void stopAudio() {
        CircleScene scene = mScene;
        if (scene != null) scene.stop();
    }

    @Override
    protected void buildScene(VkPassScene scene) {
        mPassScene = scene;
        // native 渲染器可能被销毁后重建：新场景里的纹理是空的，必须重新上传。
        mTexturesUploaded = false;
        CircleScene visual = mScene;
        if (visual != null) {
            applyClearColor(visual);
        } else {
            scene.setClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        }

        // GLES 的灰阶贴图用 GL_LINEAR + CLAMP_TO_EDGE，与 createTexture 的默认采样一致。
        scene.createTexture(TEXTURE_GREY);

        for (int i = 0; i < MAX_RINGS; i++) {
            String bufferName = "ring" + i;
            mRingBufferNames[i] = bufferName;
            scene.createBuffer(bufferName, STRIDE_BYTES, CircleScene.STRIP_VERTS, true);
            ByteBuffer buffer = ByteBuffer.allocateDirect(CircleScene.STRIP_VERTS * STRIDE_BYTES)
                    .order(ByteOrder.nativeOrder());
            mRingBuffers[i] = buffer;
            mRingFloats[i] = buffer.asFloatBuffer();
        }

        // 先声明全部纯色 pass，再声明全部重着色 pass：同一族内按下标升序，
        // 与 GLES 逐环绘制的顺序一致；另一族每帧顶点数为 0，不会参与绘制。
        for (int i = 0; i < MAX_RINGS; i++) {
            mPlainPasses[i] = scene.add(new VkPass(
                            "shaders/vis6_vk.vert.spv",
                            "shaders/vis6_vk.frag.spv",
                            new int[] {2},
                            STRIDE_BYTES,
                            VkPass.TOPOLOGY_TRIANGLE_STRIP,
                            VkPass.BLEND_NONE))
                    .buffer(mRingBufferNames[i], 0)
                    .pushConstants(PUSH_BYTES);
        }
        for (int i = 0; i < MAX_RINGS; i++) {
            mColorPasses[i] = scene.add(new VkPass(
                            "shaders/vis6_vk_color.vert.spv",
                            "shaders/vis6_vk_color.frag.spv",
                            new int[] {2, 3},
                            STRIDE_BYTES,
                            VkPass.TOPOLOGY_TRIANGLE_STRIP,
                            VkPass.BLEND_NONE))
                    .buffer(mRingBufferNames[i], 0)
                    .pushConstants(PUSH_BYTES)
                    .texture(0, TEXTURE_GREY);
        }

        mRendererBuilt = true;
        // 渲染器可用后才开始采集；prefs 已在 ensureScene 里注入。
        if (mAudioRequested) startAudio();
    }

    @Override
    protected void updateFrame(VkPassFrame frame, long timeMs) {
        CircleScene scene = mScene;
        if (scene == null) return;
        ensureTextures();

        int active;
        boolean recolor;
        synchronized (scene) {
            // 预览路径的设置是直接推给 CircleScene 的（不经过 delegate），
            // 所以每帧从场景取一次背景色，与 GLES 每帧 clearColor 的口径一致。
            applyClearColor(scene);
            scene.updateAudio();
            scene.updateRingVertices();
            scene.updateMvp();

            // GL clip → Vulkan clip：y 取反，z 从 [-w, w] 映射到 [0, w]。
            Mat4.multiplyMM(mVkMvp, CLIP_CORRECTION, scene.mMvp);
            System.arraycopy(mVkMvp, 0, mPush, 0, 16);

            active = Math.min(scene.mRingCount, MAX_RINGS);
            recolor = scene.mRecolorEnabled;

            for (int i = 0; i < active; i++) {
                fillRingBuffer(i, scene, recolor);
                frame.upload(
                        mRingBufferNames[i],
                        mRingBuffers[i],
                        CircleScene.STRIP_VERTS * STRIDE_BYTES);
            }

            int count = CircleScene.STRIP_VERTS;
            for (int i = 0; i < MAX_RINGS; i++) {
                boolean on = i < active;
                frame.setVertexCount(mPlainPasses[i], on && !recolor ? count : 0);
                frame.setVertexCount(mColorPasses[i], on && recolor ? count : 0);
            }

            for (int i = 0; i < active; i++) {
                frame.pushConstants(recolor ? mColorPasses[i] : mPlainPasses[i], mPush);
            }
        }
    }

    /**
     * 一个环的条带顶点。位置来自场景；重着色参数与 GLES 一样按环取色调
     * （动态模式每环错开 0.04），纯色路径下这三个分量不参与绘制。
     */
    private void fillRingBuffer(int ring, CircleScene scene, boolean recolor) {
        float hue = 0f;
        float saturation = 0f;
        float brightness = 0f;
        if (recolor) {
            hue = scene.mRecolorDynamic ? (scene.mHue + ring * 0.04f) % 1f : scene.mHue;
            saturation = scene.mSaturation;
            brightness = scene.mBrightness;
        }

        float[] vertices = scene.mRingVertices[ring];
        FloatBuffer out = mRingFloats[ring];
        out.clear();
        for (int j = 0; j < CircleScene.STRIP_VERTS; j++) {
            out.put(vertices[j * 2]);
            out.put(vertices[j * 2 + 1]);
            out.put(hue);
            out.put(saturation);
            out.put(brightness);
        }
    }

    private void ensureTextures() {
        if (mTexturesUploaded || mPassScene == null) return;
        mTexturesUploaded = true;

        Bitmap bitmap;
        try {
            bitmap = AssetLoader.decodeBitmap(context, ASSET_GREY);
        } catch (RuntimeException e) {
            Log.e(TAG, "decode failed: " + ASSET_GREY, e);
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
        mPassScene.setTexture(TEXTURE_GREY, pixels, width, height);
    }

    @Override
    public void destroyRenderer() {
        mRendererBuilt = false;
        stopAudio();
        super.destroyRenderer();
    }

    /**
     * GL clip → Vulkan clip：y 取反，z 从 [-w, w] 映射到 [0, w]。
     * 与 Vis1VkDelegate 使用同一份矩阵。
     */
    private static final float[] CLIP_CORRECTION = {
        1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 0.5f, 0, 0, 0, 0.5f, 1,
    };
}
