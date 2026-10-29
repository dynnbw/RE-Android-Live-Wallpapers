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
 * vis4（VU 表）的 VK 渲染器 —— 壁纸与预览共用。
 *
 * <p>逐 pass 对应 {@link MusicVisVuGL#drawFrame}：背景、峰值灯（开/关两张纹理，按
 * {@link VuScene#mPeak} 二选一）、指针、擦除块、外框。几何、投影、指针物理全部复用
 * {@link VuScene}，这里只把 GLES 每次 draw 的 buffer / 纹理 / 矩阵搬进固定的 pass。
 *
 * <p>GLES 在帧首打开 {@code SRC_ALPHA / ONE_MINUS_SRC_ALPHA} 混合且从不关闭，所以这里
 * 每条 pass 都用同一组因子，包括 alpha 通道。清屏色固定为不透明黑，与
 * {@code glClearColor(0, 0, 0, 1)} 一致（vis4 不读 {@code musicvis_bg_color}）。
 *
 * <p>音频采集不随渲染线程停下：渲染器建好且可见时启动，不可见或 surface 销毁时停止，
 * 避免壁纸被挡住后仍在后台录音。
 */
public final class MusicVisVuVkDelegate extends VkPassRendererDelegate {

    private static final String TAG = "MusicVisVuVK";

    private static final int QUAD_VERTICES = 4;

    /** 四边形顶点：x, y, z, u, v。 */
    private static final int QUAD_FLOATS = 5;

    private static final int QUAD_STRIDE_BYTES = QUAD_FLOATS * 4;

    private static final int QUAD_BYTES = QUAD_VERTICES * QUAD_STRIDE_BYTES;

    /** 推送常量只有 mvp 一个 mat4，64 字节。 */
    private static final int PUSH_FLOATS = 16;

    private static final int PUSH_BYTES = PUSH_FLOATS * 4;

    /** 与 {@link MusicVisVuGL} 里的缩放常量相同。 */
    private static final float VU_SCALE = 0.0041f;

    /** drawFrame 里背景、峰值、擦除块、外框共用的竖直偏移（模型单位）。 */
    private static final float SCENE_OFFSET_Y = -90f;

    /** 指针的旋转轴在四边形坐标系里的竖直位置（模型单位）。 */
    private static final float NEEDLE_OFFSET_Y = -147f;

    // ---- buffer 名字 ----

    private static final String BUF_BACKGROUND = "vuBackground";
    private static final String BUF_PEAK = "vuPeak";
    private static final String BUF_NEEDLE = "vuNeedle";
    private static final String BUF_ERASE = "vuErase";
    private static final String BUF_FRAME = "vuFrame";

    // ---- 纹理 ----
    //
    // GLES 的 GLTextureUtils.loadTextureFromAsset 默认 LINEAR/LINEAR + CLAMP_TO_EDGE，
    // 正好是 createTexture 的默认采样方式。

    private static final String TEX_BACKGROUND = "vuBackground";
    private static final String TEX_FRAME = "vuFrame";
    private static final String TEX_NEEDLE = "vuNeedle";
    private static final String TEX_PEAK_ON = "vuPeakOn";
    private static final String TEX_PEAK_OFF = "vuPeakOff";
    private static final String TEX_BLACK = "black";

    private static final String[][] TEXTURES = {
        {TEX_BACKGROUND, "musicvis/drawable/musicvis_vu_background.png"},
        {TEX_FRAME, "musicvis/drawable/musicvis_vu_frame.png"},
        {TEX_NEEDLE, "musicvis/drawable/musicvis_vu_needle.png"},
        {TEX_PEAK_ON, "musicvis/drawable/musicvis_vu_peak_on.png"},
        {TEX_PEAK_OFF, "musicvis/drawable/musicvis_vu_peak_off.png"},
        {TEX_BLACK, "musicvis/drawable/musicvis_black.png"},
    };

    // ---- 着色器 ----

    private static final String SHADER_QUAD_VS = "shaders/vis4_vk.vert.spv";
    private static final String SHADER_QUAD_FS = "shaders/vis4_vk.frag.spv";

    /**
     * GL 裁剪空间到 Vulkan：y 取反，z 从 [-w, w] 映射到 [0, w]。
     *
     * <p>与 GLES 一样，顶点先经 {@code proj × model} 变换，这里再左乘修正矩阵。
     */
    private static final float[] CLIP_CORRECTION = {
        1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 0.5f, 0, 0, 0, 0.5f, 1,
    };

    private SharedPreferences mPrefs;

    private volatile VuScene mScene;
    private volatile VkPassScene mPassScene;

    private VkPass mBackgroundPass;
    private VkPass mPeakOnPass;
    private VkPass mPeakOffPass;
    private VkPass mNeedlePass;
    private VkPass mErasePass;
    private VkPass mFramePass;

    // 静态几何：每帧内容不变，但每帧重传的开销只有几十字节。
    private ByteBuffer mBackgroundQuad;
    private ByteBuffer mPeakQuad;
    private ByteBuffer mNeedleQuad;
    private ByteBuffer mEraseQuad;
    private ByteBuffer mFrameQuad;

    // 矩阵与推送常量的暂存，避免逐帧分配。
    private final float[] mModel = new float[16];
    private final float[] mMvp = new float[16];
    private final float[] mClipMvp = new float[16];

    private boolean mTexturesUploaded;
    private boolean mRendererBuilt;

    /** 希望采音频吗 —— 不可见时挂起，重新可见时恢复。 */
    private boolean mAudioRequested = true;

    public MusicVisVuVkDelegate(Context context, SharedPreferences prefs) {
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
        VuScene scene = mScene;
        if (scene == null) {
            scene = new VuScene(width, height, context);
            // 与 GLES 的构造器一致：先接上设置，再允许 start()。
            if (mPrefs != null) scene.setPluginPrefs(mPrefs);
            scene.updateProjection();
            mScene = scene;
            // 预览可能先建渲染器（尺寸还是 0）再拿到尺寸，那时 buildScene 已经跑过。
            if (mAudioRequested && mRendererBuilt) startAudio();
        } else {
            // 尺寸在渲染线程的读取之外变化，与 GLES 重建场景后的效果一致。
            synchronized (scene) {
                scene.mWidth = width;
                scene.mHeight = height;
                scene.updateProjection();
            }
        }
    }

    @Override
    public void onPluginPrefsChanged(SharedPreferences prefs) {
        mPrefs = prefs;
        VuScene scene = mScene;
        if (scene == null) return;
        synchronized (scene) {
            scene.setPluginPrefs(prefs);
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
        // native 句柄已销毁：别再让设置变更对着旧场景。
        mPassScene = null;
    }

    private void startAudio() {
        VuScene scene = mScene;
        if (scene != null && mRendererBuilt) scene.start();
    }

    private void stopAudio() {
        VuScene scene = mScene;
        if (scene != null) scene.stop();
    }

    // ─────────────────────────── 声明场景 ───────────────────────────

    @Override
    protected void buildScene(VkPassScene scene) {
        mPassScene = scene;
        // native 渲染器可能被销毁后重建：新场景里的纹理是空的，全部重新上传。
        mTexturesUploaded = false;

        // 与 glClearColor(0, 0, 0, 1) 一致；vis4 不读背景色设置。
        scene.setClearColor(0f, 0f, 0f, 1f);

        buildQuadBuffers(loadQuadUvs());

        scene.createBuffer(BUF_BACKGROUND, QUAD_STRIDE_BYTES, QUAD_VERTICES, true);
        scene.createBuffer(BUF_PEAK, QUAD_STRIDE_BYTES, QUAD_VERTICES, true);
        scene.createBuffer(BUF_NEEDLE, QUAD_STRIDE_BYTES, QUAD_VERTICES, true);
        scene.createBuffer(BUF_ERASE, QUAD_STRIDE_BYTES, QUAD_VERTICES, true);
        scene.createBuffer(BUF_FRAME, QUAD_STRIDE_BYTES, QUAD_VERTICES, true);

        for (String[] texture : TEXTURES) scene.createTexture(texture[0]);

        // pass 的声明顺序就是绘制顺序，与 drawFrame 一致。
        mBackgroundPass = addQuadPass(scene, BUF_BACKGROUND, TEX_BACKGROUND);
        mPeakOnPass = addQuadPass(scene, BUF_PEAK, TEX_PEAK_ON);
        mPeakOffPass = addQuadPass(scene, BUF_PEAK, TEX_PEAK_OFF);
        mNeedlePass = addQuadPass(scene, BUF_NEEDLE, TEX_NEEDLE);
        mErasePass = addQuadPass(scene, BUF_ERASE, TEX_BLACK);
        mFramePass = addQuadPass(scene, BUF_FRAME, TEX_FRAME);

        mRendererBuilt = true;
        // 渲染器可用后才开始采集；prefs 已在 ensureScene 里注入。
        if (mAudioRequested) startAudio();
    }

    private VkPass addQuadPass(VkPassScene scene, String bufferName, String textureName) {
        VkPass pass = scene.add(
                        new VkPass(
                                SHADER_QUAD_VS,
                                SHADER_QUAD_FS,
                                new int[] {3, 2},
                                QUAD_STRIDE_BYTES,
                                VkPass.TOPOLOGY_TRIANGLE_STRIP,
                                VkPass.BLEND_ALPHA))
                // 每帧只改峰值那两条的顶点数；其余四条一直是 4 个顶点。
                .buffer(bufferName, QUAD_VERTICES)
                .pushConstants(PUSH_BYTES)
                .texture(0, textureName);
        // GLES 的 glBlendFunc(SRC_ALPHA, ONE_MINUS_SRC_ALPHA) 对 alpha 通道同样生效；
        // 通用 BLEND_ALPHA 预设的 srcAlpha 是 ONE，这里按原样覆盖。
        pass.blendFactors(
                VkPass.FACTOR_SRC_ALPHA,
                VkPass.FACTOR_ONE_MINUS_SRC_ALPHA,
                VkPass.FACTOR_SRC_ALPHA,
                VkPass.FACTOR_ONE_MINUS_SRC_ALPHA);
        return pass;
    }

    // ─────────────────────────── 每帧 ───────────────────────────

    @Override
    protected void updateFrame(VkPassFrame frame, long timeMs) {
        VuScene s = mScene;
        if (s == null || mPassScene == null) {
            setEmptyFrame(frame);
            return;
        }

        ensureTextures();

        synchronized (s) {
            // 与 MusicVisVuGL.drawFrame 开头逐句一致。
            s.updateNeedle();

            uploadQuads(frame);

            // 背景、峰值、擦除块、外框共用同一个矩阵：平移在前、缩放在后。
            float scale = VU_SCALE;
            Mat4.setIdentityM(mModel);
            Mat4.translateM(mModel, 0f, SCENE_OFFSET_Y * scale, 0f);
            Mat4.scaleM(mModel, scale, scale, scale);
            Mat4.multiplyMM(mMvp, s.mProj, mModel);
            pushMvp(frame, mBackgroundPass, mMvp);

            boolean peakOn = s.mPeak > 0;
            frame.setVertexCount(mPeakOnPass, peakOn ? QUAD_VERTICES : 0);
            frame.setVertexCount(mPeakOffPass, peakOn ? 0 : QUAD_VERTICES);
            pushMvp(frame, peakOn ? mPeakOnPass : mPeakOffPass, mMvp);

            // 指针：translate(-147 * scale) · rotate(angle - 90, Z) · scale。
            Mat4.setIdentityM(mModel);
            Mat4.translateM(mModel, 0f, NEEDLE_OFFSET_Y * scale, 0f);
            Mat4.rotateM(mModel, s.mAngle - 90f, 0f, 0f, 1f);
            Mat4.scaleM(mModel, scale, scale, scale);
            Mat4.multiplyMM(mMvp, s.mProj, mModel);
            pushMvp(frame, mNeedlePass, mMvp);

            // 擦除块与外框回到背景矩阵（GLES 每画一组前重新 setModelMatrix）。
            Mat4.setIdentityM(mModel);
            Mat4.translateM(mModel, 0f, SCENE_OFFSET_Y * scale, 0f);
            Mat4.scaleM(mModel, scale, scale, scale);
            Mat4.multiplyMM(mMvp, s.mProj, mModel);
            pushMvp(frame, mErasePass, mMvp);
            pushMvp(frame, mFramePass, mMvp);
        }
    }

    private void setEmptyFrame(VkPassFrame frame) {
        if (mBackgroundPass != null) frame.setVertexCount(mBackgroundPass, 0);
        if (mPeakOnPass != null) frame.setVertexCount(mPeakOnPass, 0);
        if (mPeakOffPass != null) frame.setVertexCount(mPeakOffPass, 0);
        if (mNeedlePass != null) frame.setVertexCount(mNeedlePass, 0);
        if (mErasePass != null) frame.setVertexCount(mErasePass, 0);
        if (mFramePass != null) frame.setVertexCount(mFramePass, 0);
    }

    private void pushMvp(VkPassFrame frame, VkPass pass, float[] mvp) {
        Mat4.multiplyMM(mClipMvp, CLIP_CORRECTION, mvp);
        frame.pushConstants(pass, mClipMvp);
    }

    // ─────────────────────────── 顶点数据 ───────────────────────────

    private void uploadQuads(VkPassFrame frame) {
        frame.upload(BUF_BACKGROUND, mBackgroundQuad, QUAD_BYTES);
        frame.upload(BUF_PEAK, mPeakQuad, QUAD_BYTES);
        frame.upload(BUF_NEEDLE, mNeedleQuad, QUAD_BYTES);
        frame.upload(BUF_ERASE, mEraseQuad, QUAD_BYTES);
        frame.upload(BUF_FRAME, mFrameQuad, QUAD_BYTES);
    }

    /** 与 MusicVisVuGL.drawFrame 的每个 drawQuad 坐标逐字一致。 */
    private void buildQuadBuffers(float[] uvs) {
        mBackgroundQuad = quadBuffer(uvs, -208f, -33f, 0f, 208f, 200f, 0f);
        mPeakQuad = quadBuffer(uvs, 140f, 70f, 0f, 196f, 128f, 0f);
        mNeedleQuad = quadBuffer(uvs, -44f, -102f + 57f, 0f, 44f, 160f + 57f, 0f);
        mEraseQuad = quadBuffer(uvs, -100f, -105f, 0f, 100f, -55f, 0f);
        mFrameQuad = quadBuffer(uvs, -236f, -60f, 0f, 236f, 230f, 0f);
    }

    /** drawQuad：x1/y1/z1 那一行取 v=1，x1/y2/z2 那一行取 v=0。 */
    private static ByteBuffer quadBuffer(
            float[] uv, float x1, float y1, float z1, float x2, float y2, float z2) {
        ByteBuffer buffer = newVertexBuffer(QUAD_BYTES);
        putQuadVertex(buffer, x1, y1, z1, uv[0], uv[1]);
        putQuadVertex(buffer, x2, y1, z1, uv[2], uv[3]);
        putQuadVertex(buffer, x1, y2, z2, uv[4], uv[5]);
        putQuadVertex(buffer, x2, y2, z2, uv[6], uv[7]);
        buffer.position(0);
        return buffer;
    }

    private static void putQuadVertex(
            ByteBuffer buffer, float x, float y, float z, float u, float v) {
        buffer.putFloat(x).putFloat(y).putFloat(z).putFloat(u).putFloat(v);
    }

    private static ByteBuffer newVertexBuffer(int bytes) {
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
    }

    /** 与 GLES 从 musicvis/data/musicvis_quad_uv.csv 读的是同一份角纹理坐标。 */
    private float[] loadQuadUvs() {
        try {
            float[] uvs = AssetLoader.readFloatArray(context, "musicvis/data/musicvis_quad_uv.csv");
            if (uvs.length >= 8) return uvs;
        } catch (RuntimeException e) {
            Log.e(TAG, "读取四边形纹理坐标失败", e);
        }
        Log.w(TAG, "四边形纹理坐标缺失，退回 GLES 版的默认值");
        return new float[] {0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f};
    }

    // ─────────────────────────── 纹理 ───────────────────────────

    private void ensureTextures() {
        if (mTexturesUploaded || mPassScene == null) return;
        mTexturesUploaded = true;
        for (String[] texture : TEXTURES) uploadTexture(texture[0], texture[1]);
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
