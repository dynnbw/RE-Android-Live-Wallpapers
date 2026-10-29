package com.reandroid.wallpaper.musicvis;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.util.Log;
import android.view.Surface;

import com.reandroid.plugin.VkPass;
import com.reandroid.plugin.VkPassFrame;
import com.reandroid.plugin.VkPassRendererDelegate;
import com.reandroid.plugin.VkPassScene;
import com.reandroid.utils.AssetLoader;
import com.reandroid.utils.Mat4;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * vis2 / vis3 的 Vulkan 渲染器 —— 壁纸与预览共用。
 *
 * <p>与 GLES 版 {@link MusicVisWaveGL} 一一对应：同一个 {@link WaveScene} 负责音频、
 * 平滑、空闲波形与投影，这里只把 {@code mPositions / mTexCoords / mAdjustData}
 * 按 VK 需要的交错布局填进顶点缓冲。两个壁纸只差采集模式与线条纹理。
 *
 * <p>GLES 每帧按 {@code mRecolorEnabled} 选普通着色器或 HSL 着色器，按
 * {@code mUseTriangleStrip} 选 {@code GL_TRIANGLE_STRIP} 或 {@code GL_LINES}。
 * VK 的图元拓扑在建管线时固定，所以每种组合各声明一个 pass（共四个），
 * 每帧只给当前组合设置顶点数，其余置 0。
 *
 * <p>音频采集不随渲染线程停下：渲染器建好且可见时启动，不可见或 surface 销毁时停止，
 * 避免壁纸被挡住后仍在后台录音。
 */
public final class MusicVisWaveVkDelegate extends VkPassRendererDelegate {

    private static final String TAG = "MusicVisWaveVK";

    private static final String PLAIN_BUFFER = "wave";
    private static final String COLOR_BUFFER = "waveColor";
    private static final String LINE_TEXTURE = "lineTexture";
    private static final String GREY_TEXTURE = "greyTexture";
    private static final String GREY_TEXTURE_ASSET = "musicvis/drawable/musicvis_grey.png";

    /** 与 GLES 的 {@code WaveScene.LINE_COUNT * 2} 相同。 */
    private static final int VERTEX_COUNT = WaveScene.LINE_COUNT * 2;

    /** 普通路径：x, y, u, v。 */
    private static final int PLAIN_FLOATS_PER_VERTEX = 4;

    private static final int PLAIN_STRIDE_BYTES = PLAIN_FLOATS_PER_VERTEX * 4;

    /** HSL 路径：x, y, u, v, hue, saturation, brightness。 */
    private static final int COLOR_FLOATS_PER_VERTEX = 7;

    private static final int COLOR_STRIDE_BYTES = COLOR_FLOATS_PER_VERTEX * 4;

    /** 推送常量只有 mvp 一个 mat4，64 字节。 */
    private static final int PUSH_FLOATS = 16;

    private static final int PUSH_BYTES = PUSH_FLOATS * 4;

    /**
     * GL 裁剪空间 → Vulkan：y 取反，z 从 [-w, w] 映射到 [0, w]。
     *
     * <p>与 GLES 一样，顶点先经 {@code WaveScene.mMvp}（正交投影 × 模型）变换，
     * 这里再左乘修正矩阵。
     */
    private static final float[] CLIP_CORRECTION = {
        1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 0.5f, 0, 0, 0, 0.5f, 1,
    };

    private SharedPreferences mPrefs;
    private final WaveScene.Mode mMode;
    private final String mLineTextureAsset;
    /** vis2 用 PCM、vis3 用 FFT；着色器文件按壁纸分开，靠模式选出前缀。 */
    private final String mShaderPrefix;

    private volatile WaveScene mScene;
    private volatile VkPassScene mPassScene;

    private VkPass mPlainStrip;
    private VkPass mPlainLine;
    private VkPass mColorStrip;
    private VkPass mColorLine;

    private ByteBuffer mPlainVertices;
    private ByteBuffer mColorVertices;
    private final float[] mPush = new float[PUSH_FLOATS];

    private boolean mTexturesUploaded;
    private boolean mRendererBuilt;
    /** 希望采音频吗 —— 不可见时挂起，重新可见时恢复。 */
    private boolean mAudioRequested = true;

    private int mWidth;
    private int mHeight;

    public MusicVisWaveVkDelegate(
            Context context,
            SharedPreferences prefs,
            WaveScene.Mode mode,
            String lineTextureAsset) {
        super(context);
        mPrefs = prefs;
        mMode = mode;
        mLineTextureAsset = lineTextureAsset;
        mShaderPrefix = mode == WaveScene.Mode.FFT ? "shaders/vis3_vk" : "shaders/vis2_vk";
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
        WaveScene scene = mScene;
        if (scene == null) {
            scene = new WaveScene(width, height, mMode, context);
            // 与 GLES 的 onCreate → tryInjectPrefs 一样：先注入设置，再允许 start()。
            if (mPrefs != null) scene.setPluginPrefs(mPrefs);
            mWidth = width;
            mHeight = height;
            mScene = scene;
            applyClearColor(scene);
            // 预览可能先建渲染器（尺寸还是 0）再拿到尺寸，这时 buildScene 已经跑过。
            if (mAudioRequested && mRendererBuilt) startAudio();
        } else {
            // 尺寸变化在渲染线程的读取之外发生，与 GLES 重建场景后的效果一致。
            synchronized (scene) {
                mWidth = width;
                mHeight = height;
                scene.mWidth = width;
                scene.mHeight = height;
            }
        }
    }

    @Override
    public void onPluginPrefsChanged(SharedPreferences prefs) {
        mPrefs = prefs;
        WaveScene scene = mScene;
        if (scene == null) return;
        synchronized (scene) {
            scene.setPluginPrefs(prefs);
        }
        applyClearColor(scene);
    }

    @Override
    public void onSceneOffset(float xOffset) {
        WaveScene scene = mScene;
        if (scene == null) return;
        synchronized (scene) {
            scene.setOffset(xOffset, 0f, mWidth, mHeight);
        }
    }

    @Override
    public void onVisibilityChanged(boolean visible) {
        mAudioRequested = visible;
        if (mScene == null) return;
        if (visible) {
            // 渲染器还没建好时由 buildScene 接手启动。
            if (mRendererBuilt) startAudio();
        } else {
            stopAudio();
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

    @Override
    public void destroyRenderer() {
        mRendererBuilt = false;
        stopAudio();
        super.destroyRenderer();
        // native 句柄已销毁：别再让设置变更对着旧场景清屏。
        mPassScene = null;
    }

    private void startAudio() {
        WaveScene scene = mScene;
        if (scene != null && mRendererBuilt) scene.start();
    }

    private void stopAudio() {
        WaveScene scene = mScene;
        if (scene != null) scene.stop();
    }

    /** 背景色走 musicvis_bg_color；默认 #000000，与原版一致。 */
    private void applyClearColor(WaveScene scene) {
        VkPassScene passScene = mPassScene;
        if (passScene == null) return;
        if (scene != null) {
            passScene.setClearColor(scene.mBgColor[0], scene.mBgColor[1], scene.mBgColor[2], 1.0f);
        } else {
            passScene.setClearColor(0f, 0f, 0f, 1f);
        }
    }

    // ─────────────────────────── 声明场景 ───────────────────────────

    @Override
    protected void buildScene(VkPassScene scene) {
        mPassScene = scene;
        // native 渲染器可能被销毁后重建：新场景里的纹理是空的，全部重新上传。
        mTexturesUploaded = false;

        applyClearColor(mScene);

        scene.createBuffer(PLAIN_BUFFER, PLAIN_STRIDE_BYTES, VERTEX_COUNT, true);
        scene.createBuffer(COLOR_BUFFER, COLOR_STRIDE_BYTES, VERTEX_COUNT, true);

        // GLES 的两张纹理都是 LINEAR + CLAMP_TO_EDGE，正好是 createTexture 的默认值。
        scene.createTexture(LINE_TEXTURE);
        scene.createTexture(GREY_TEXTURE);

        String plainVert = mShaderPrefix + ".vert.spv";
        String plainFrag = mShaderPrefix + ".frag.spv";
        String colorVert = mShaderPrefix + "_color.vert.spv";
        String colorFrag = mShaderPrefix + "_color.frag.spv";

        // 普通路径与 HSL 路径各两种拓扑：GLES 在 draw 时二选一，这里各建一条管线。
        mPlainStrip = addPass(
                scene,
                plainVert,
                plainFrag,
                new int[] {2, 2},
                PLAIN_STRIDE_BYTES,
                PLAIN_BUFFER,
                VkPass.TOPOLOGY_TRIANGLE_STRIP,
                LINE_TEXTURE);
        mPlainLine = addPass(
                scene,
                plainVert,
                plainFrag,
                new int[] {2, 2},
                PLAIN_STRIDE_BYTES,
                PLAIN_BUFFER,
                VkPass.TOPOLOGY_LINE_LIST,
                LINE_TEXTURE);
        mColorStrip = addPass(
                scene,
                colorVert,
                colorFrag,
                new int[] {2, 2, 3},
                COLOR_STRIDE_BYTES,
                COLOR_BUFFER,
                VkPass.TOPOLOGY_TRIANGLE_STRIP,
                GREY_TEXTURE);
        mColorLine = addPass(
                scene,
                colorVert,
                colorFrag,
                new int[] {2, 2, 3},
                COLOR_STRIDE_BYTES,
                COLOR_BUFFER,
                VkPass.TOPOLOGY_LINE_LIST,
                GREY_TEXTURE);

        mPlainVertices = ByteBuffer.allocateDirect(VERTEX_COUNT * PLAIN_STRIDE_BYTES)
                .order(ByteOrder.nativeOrder());
        mColorVertices = ByteBuffer.allocateDirect(VERTEX_COUNT * COLOR_STRIDE_BYTES)
                .order(ByteOrder.nativeOrder());

        mRendererBuilt = true;
        // 渲染器可用后才开始采集；prefs 已在 ensureScene 里注入。
        if (mAudioRequested) startAudio();
    }

    private VkPass addPass(
            VkPassScene scene,
            String vs,
            String fs,
            int[] attribSizes,
            int strideBytes,
            String bufferName,
            int topology,
            String textureName) {
        // GLES 版没有开混合（默认的 ONE / ZERO 等价于直接覆盖），这里用 BLEND_NONE。
        VkPass pass = scene.add(
                        new VkPass(vs, fs, attribSizes, strideBytes, topology, VkPass.BLEND_NONE))
                .buffer(bufferName, 0)
                .pushConstants(PUSH_BYTES);
        pass.texture(0, textureName);
        return pass;
    }

    // ─────────────────────────── 每帧 ───────────────────────────

    @Override
    protected void updateFrame(VkPassFrame frame, long timeMs) {
        WaveScene s = mScene;
        if (s == null || mWidth <= 0 || mHeight <= 0 || mPassScene == null) {
            setEmptyFrame(frame);
            return;
        }

        ensureTextures();

        synchronized (s) {
            recreateFftCaptureIfNeeded(s);

            s.updateWaveData();
            s.applyIdleAndFade();
            s.updateBuffers();
            s.updateAdjustBuffer();
            s.updateMvp();

            boolean recolor = s.mRecolorEnabled;
            boolean strip = s.mUseTriangleStrip;

            buildVertices(s, recolor);
            Mat4.multiplyMM(mPush, CLIP_CORRECTION, s.mMvp);

            frame.setVertexCount(mPlainStrip, 0);
            frame.setVertexCount(mPlainLine, 0);
            frame.setVertexCount(mColorStrip, 0);
            frame.setVertexCount(mColorLine, 0);

            VkPass active;
            ByteBuffer buffer;
            int strideBytes;
            if (recolor) {
                active = strip ? mColorStrip : mColorLine;
                buffer = mColorVertices;
                strideBytes = COLOR_STRIDE_BYTES;
            } else {
                active = strip ? mPlainStrip : mPlainLine;
                buffer = mPlainVertices;
                strideBytes = PLAIN_STRIDE_BYTES;
            }
            buffer.position(0);
            frame.upload(recolor ? COLOR_BUFFER : PLAIN_BUFFER, buffer, VERTEX_COUNT * strideBytes);
            frame.setVertexCount(active, VERTEX_COUNT);
            frame.pushConstants(active, mPush);
        }
    }

    /**
     * FFT 尺寸改变时重建采集，与 GLES 版 {@code drawFrame} 开头那段逐句一致。
     *
     * <p>{@code createAudioCapture} 会重新接上「跟随系统音量」的增益来源；
     * 这里必须走它，而不是直接 new。
     */
    private static void recreateFftCaptureIfNeeded(WaveScene s) {
        if (!s.mFftSizeChanged || s.mMode != WaveScene.Mode.FFT) return;
        s.mFftSizeChanged = false;
        if (s.mAudioCapture != null) {
            s.mAudioCapture.stop();
            s.mAudioCapture.release();
        }
        s.mAudioCapture = s.createAudioCapture(AudioCapture.TYPE_FFT, s.mFftSize);
        s.mVizData = new int[s.mAudioCapture.getSize()];
        s.mAnalyzer = new int[s.mAudioCapture.getSize() / 2];
        s.mPcmSmoothed = new float[s.mAudioCapture.getSize()];
        s.mAudioCapture.start();
    }

    private void setEmptyFrame(VkPassFrame frame) {
        if (mPlainStrip != null) frame.setVertexCount(mPlainStrip, 0);
        if (mPlainLine != null) frame.setVertexCount(mPlainLine, 0);
        if (mColorStrip != null) frame.setVertexCount(mColorStrip, 0);
        if (mColorLine != null) frame.setVertexCount(mColorLine, 0);
    }

    private void buildVertices(WaveScene s, boolean recolor) {
        if (recolor) {
            ByteBuffer buffer = mColorVertices;
            buffer.clear();
            for (int i = 0; i < VERTEX_COUNT; i++) {
                int p = i * 2;
                int a = i * 3;
                buffer.putFloat(s.mPositions[p])
                        .putFloat(s.mPositions[p + 1])
                        .putFloat(s.mTexCoords[p])
                        .putFloat(s.mTexCoords[p + 1])
                        .putFloat(s.mAdjustData[a])
                        .putFloat(s.mAdjustData[a + 1])
                        .putFloat(s.mAdjustData[a + 2]);
            }
        } else {
            ByteBuffer buffer = mPlainVertices;
            buffer.clear();
            for (int i = 0; i < VERTEX_COUNT; i++) {
                int p = i * 2;
                buffer.putFloat(s.mPositions[p])
                        .putFloat(s.mPositions[p + 1])
                        .putFloat(s.mTexCoords[p])
                        .putFloat(s.mTexCoords[p + 1]);
            }
        }
    }

    // ─────────────────────────── 纹理 ───────────────────────────

    private void ensureTextures() {
        if (mTexturesUploaded || mPassScene == null) return;
        mTexturesUploaded = true;
        uploadTexture(LINE_TEXTURE, mLineTextureAsset);
        uploadTexture(GREY_TEXTURE, GREY_TEXTURE_ASSET);
    }

    private void uploadTexture(String name, String assetPath) {
        Bitmap bitmap;
        try {
            bitmap = AssetLoader.decodeBitmap(context, assetPath);
        } catch (RuntimeException e) {
            Log.e(TAG, "纹理解码失败: " + assetPath, e);
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
        // 行序与 GLES 的 GLUtils.texImage2D 相同：位图第一行就是纹理 v=0。
        mPassScene.setTexture(name, pixels, width, height);
    }
}
