package com.reandroid.wallpaper.musicvis;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.util.Log;
import android.view.Surface;

import com.reandroid.plugin.PluginPrefsProvider;
import com.reandroid.plugin.VkPass;
import com.reandroid.plugin.VkPassFrame;
import com.reandroid.plugin.VkPassRendererDelegate;
import com.reandroid.plugin.VkPassScene;
import com.reandroid.utils.AssetLoader;
import com.reandroid.utils.Mat4;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * vis5（Many）的 VK 渲染器 —— 壁纸与预览共用。
 *
 * <p>逐 pass 对应 {@link MusicVisManyGL#drawFrame}：先画反射层（六个面板交替为波形与
 * VU 表，每个之间绕 Y 轴转 60°），再画反射地板，最后画正立的同一组面板。几何、投影、
 * 矩阵链与每帧的数据更新全部复用 {@link ManyScene}，这里只把 GLES 每次 draw 的
 * buffer / 纹理 / 混合搬进固定的 pass。
 *
 * <p>GLES 每帧按波形模式选 PCM 或 FFT 数据、按「重新着色」开关选普通或 HSL 着色器、
 * 按设置选三角形条带或线段。VK 的图元拓扑、纹理绑定在建管线时固定，所以每个波形位置
 * 各声明八条 pass（两种纹理 × 两种拓扑 × 普通/HSL），每帧只给当前组合设置顶点数。
 *
 * <p>GLES 在帧首打开 {@code SRC_ALPHA / ONE_MINUS_SRC_ALPHA} 混合且从不关闭，所以这里
 * 每条 pass 都用同一组因子，包括 alpha 通道。
 *
 * <p>音频采集不随渲染线程停下：渲染器建好且可见时启动，不可见或 surface 销毁时停止，
 * 避免壁纸被挡住后仍在后台录音。
 */
public final class MusicVisManyVkDelegate extends VkPassRendererDelegate {

    private static final String TAG = "MusicVisManyVK";

    /** ManyScene 每帧使用的线条数。 */
    private static final int LINE_COUNT = 256;

    /** 每条波形 pass 的顶点数。 */
    private static final int WAVE_VERTICES = LINE_COUNT * 2;

    /** mAdjustData 里 PCM 与 FFT 两段的分界（每个顶点三个分量）。 */
    private static final int ADJUST_SECTION = LINE_COUNT * 2 * 3;

    private static final int QUAD_VERTICES = 4;

    /** 面板顶点：x, y, z, u, v。 */
    private static final int QUAD_FLOATS = 5;

    private static final int QUAD_STRIDE_BYTES = QUAD_FLOATS * 4;

    private static final int QUAD_BYTES = QUAD_VERTICES * QUAD_STRIDE_BYTES;

    /** 波形纹理路径顶点：x, y, u, v。 */
    private static final int PLAIN_FLOATS = 4;

    private static final int PLAIN_STRIDE_BYTES = PLAIN_FLOATS * 4;

    /** HSL 路径顶点：x, y, u, v, hue, saturation, brightness。 */
    private static final int COLOR_FLOATS = 7;

    private static final int COLOR_STRIDE_BYTES = COLOR_FLOATS * 4;

    /** 推送常量只有 mvp 一个 mat4，64 字节。 */
    private static final int PUSH_FLOATS = 16;

    private static final int PUSH_BYTES = PUSH_FLOATS * 4;

    /** 与 {@link MusicVisManyGL} 里 VU 面板的缩放的同一个常量。 */
    private static final float VU_SCALE = 0.0041f;

    // ---- buffer 名字 ----

    private static final String BUF_VU_BACKGROUND = "vuBackground";
    private static final String BUF_VU_PEAK = "vuPeak";
    private static final String BUF_VU_NEEDLE = "vuNeedle";
    private static final String BUF_VU_ERASE = "vuErase";
    private static final String BUF_VU_FRAME = "vuFrame";
    private static final String BUF_REFLECT_PLANE = "reflectPlane";
    private static final String BUF_WAVE_PCM = "wavePcm";
    private static final String BUF_WAVE_PCM_COLOR = "wavePcmColor";
    private static final String BUF_WAVE_FFT = "waveFft";
    private static final String BUF_WAVE_FFT_COLOR = "waveFftColor";

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
    private static final String TEX_ALBUM = "album";
    private static final String TEX_FIRE = "fire";
    private static final String TEX_ICE = "ice";
    private static final String TEX_GREY = "grey";

    private static final String[][] TEXTURES = {
        {TEX_BACKGROUND, "musicvis/drawable/musicvis_vu_background.png"},
        {TEX_FRAME, "musicvis/drawable/musicvis_vu_frame.png"},
        {TEX_NEEDLE, "musicvis/drawable/musicvis_vu_needle.png"},
        {TEX_PEAK_ON, "musicvis/drawable/musicvis_vu_peak_on.png"},
        {TEX_PEAK_OFF, "musicvis/drawable/musicvis_vu_peak_off.png"},
        {TEX_BLACK, "musicvis/drawable/musicvis_black.png"},
        {TEX_ALBUM, "musicvis/drawable/musicvis_albumart.png"},
        {TEX_FIRE, "musicvis/drawable/musicvis_fire.png"},
        {TEX_ICE, "musicvis/drawable/musicvis_ice.png"},
        {TEX_GREY, "musicvis/drawable/musicvis_grey.png"},
    };

    // ---- 着色器 ----

    private static final String SHADER_QUAD_VS = "shaders/vis5_vk.vert.spv";
    private static final String SHADER_QUAD_FS = "shaders/vis5_vk.frag.spv";
    private static final String SHADER_LINE_VS = "shaders/vis5_vk_line.vert.spv";
    private static final String SHADER_LINE_FS = "shaders/vis5_vk_line.frag.spv";
    private static final String SHADER_COLOR_VS = "shaders/vis5_vk_color.vert.spv";
    private static final String SHADER_COLOR_FS = "shaders/vis5_vk_color.frag.spv";

    // ---- pass 下标 ----

    /** 每个波形位置的八种组合。 */
    private static final int WAVE_FIRE_STRIP = 0;
    private static final int WAVE_FIRE_LINE = 1;
    private static final int WAVE_ICE_STRIP = 2;
    private static final int WAVE_ICE_LINE = 3;
    private static final int WAVE_COLOR_PCM_STRIP = 4;
    private static final int WAVE_COLOR_PCM_LINE = 5;
    private static final int WAVE_COLOR_FFT_STRIP = 6;
    private static final int WAVE_COLOR_FFT_LINE = 7;
    private static final int WAVE_VARIANTS = 8;

    /** 每个 VU 面板的绘制顺序，与 drawVU 一致。 */
    private static final int VU_BACKGROUND = 0;
    private static final int VU_PEAK_ON = 1;
    private static final int VU_PEAK_OFF = 2;
    private static final int VU_NEEDLE = 3;
    private static final int VU_ERASE = 4;
    private static final int VU_FRAME = 5;
    private static final int VU_PASSES = 6;

    /** 两层各三个波形、三个 VU 面板。 */
    private static final int WAVE_SLOTS = 6;

    private static final int VU_SLOTS = 6;

    /**
     * GL 裁剪空间到 Vulkan：y 取反，z 从 [-w, w] 映射到 [0, w]。
     *
     * <p>与 GLES 一样，顶点先经 {@code proj × model} 变换，这里再左乘修正矩阵。
     */
    private static final float[] CLIP_CORRECTION = {
        1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 0.5f, 0, 0, 0, 0.5f, 1,
    };

    private SharedPreferences mPrefs;
    private final PluginPrefsProvider mPrefsProvider;

    private volatile ManyScene mScene;
    private volatile VkPassScene mPassScene;

    private final VkPass[][] mWavePasses = new VkPass[WAVE_SLOTS][WAVE_VARIANTS];
    private final VkPass[][] mVuPasses = new VkPass[VU_SLOTS][VU_PASSES];
    private VkPass mPlanePass;

    // 静态几何：每帧内容不变，但每帧重传的开销只有几十字节。
    private ByteBuffer mBackgroundQuad;
    private ByteBuffer mPeakQuad;
    private ByteBuffer mNeedleQuad;
    private ByteBuffer mEraseQuad;
    private ByteBuffer mFrameQuad;
    private ByteBuffer mPlaneQuad;

    private ByteBuffer mPcmPlain;
    private ByteBuffer mPcmColor;
    private ByteBuffer mFftPlain;
    private ByteBuffer mFftColor;

    // 矩阵与推送常量的暂存，避免逐帧分配。
    private final float[] mBase = new float[16];
    private final float[] mReflect = new float[16];
    private final float[] mLayer = new float[16];
    private final float[] mModel = new float[16];
    private final float[] mNeedle = new float[16];
    private final float[] mErase = new float[16];
    private final float[] mMvp = new float[16];
    private final float[] mClipMvp = new float[16];

    private boolean mTexturesUploaded;
    private boolean mRendererBuilt;

    /** 希望采音频吗 —— 不可见时挂起，重新可见时恢复。 */
    private boolean mAudioRequested = true;

    private int mWidth;
    private int mHeight;

    public MusicVisManyVkDelegate(Context context, SharedPreferences prefs) {
        super(context);
        mPrefs = prefs;
        // vis5 要按 vis2 / vis3 自己的设置模拟它们的观感。设置只按插件隔离保存，
        // 这里按与宿主相同的命名约定取回那两份；预览路径同样适用。
        // 用 application context，避免把设置页的 Activity 留在 delegate 里。
        Context appContext = context.getApplicationContext() != null
                ? context.getApplicationContext()
                : context;
        mPrefsProvider =
                pluginId ->
                        appContext.getSharedPreferences(
                                "plugin_" + pluginId, Context.MODE_PRIVATE);
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
        ManyScene scene = mScene;
        if (scene == null) {
            scene = new ManyScene(width, height, context);
            // 与 GLES 的构造器一致：先接上设置、跨插件读取能力与音量增益来源，再允许 start()。
            if (mPrefs != null) scene.setPluginPrefs(mPrefs);
            scene.setPluginPrefsProvider(mPrefsProvider);
            scene.setVolumeGainSource(
                    AudioVisBase.volumeGainSource(context, scene::followsSystemVolume));
            mWidth = width;
            mHeight = height;
            scene.updateProjection();
            mScene = scene;
            // 预览可能先建渲染器（尺寸还是 0）再拿到尺寸，那时 buildScene 已经跑过。
            if (mAudioRequested && mRendererBuilt) startAudio();
        } else {
            // 尺寸在渲染线程的读取之外变化，与 GLES 重建场景后的效果一致。
            synchronized (scene) {
                mWidth = width;
                mHeight = height;
                scene.resizeWaves(width, height);
                scene.updateProjection();
            }
        }
    }

    @Override
    public void onPluginPrefsChanged(SharedPreferences prefs) {
        mPrefs = prefs;
        ManyScene scene = mScene;
        if (scene == null) return;
        synchronized (scene) {
            scene.setPluginPrefs(prefs);
        }
    }

    @Override
    public void onSceneOffset(float xOffset) {
        ManyScene scene = mScene;
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
        ManyScene scene = mScene;
        if (scene != null && mRendererBuilt) scene.start();
    }

    private void stopAudio() {
        ManyScene scene = mScene;
        if (scene != null) scene.stop();
    }

    // ─────────────────────────── 声明场景 ───────────────────────────

    @Override
    protected void buildScene(VkPassScene scene) {
        mPassScene = scene;
        // native 渲染器可能被销毁后重建：新场景里的纹理是空的，全部重新上传。
        mTexturesUploaded = false;

        ManyScene current = mScene;
        if (current != null) {
            applyClearColor(current);
        } else {
            scene.setClearColor(0f, 0f, 0f, 1f);
        }

        float[] uvs = loadQuadUvs();
        buildQuadBuffers(uvs);
        allocateWaveBuffers();

        scene.createBuffer(BUF_VU_BACKGROUND, QUAD_STRIDE_BYTES, QUAD_VERTICES, true);
        scene.createBuffer(BUF_VU_PEAK, QUAD_STRIDE_BYTES, QUAD_VERTICES, true);
        scene.createBuffer(BUF_VU_NEEDLE, QUAD_STRIDE_BYTES, QUAD_VERTICES, true);
        scene.createBuffer(BUF_VU_ERASE, QUAD_STRIDE_BYTES, QUAD_VERTICES, true);
        scene.createBuffer(BUF_VU_FRAME, QUAD_STRIDE_BYTES, QUAD_VERTICES, true);
        scene.createBuffer(BUF_REFLECT_PLANE, QUAD_STRIDE_BYTES, QUAD_VERTICES, true);
        scene.createBuffer(BUF_WAVE_PCM, PLAIN_STRIDE_BYTES, WAVE_VERTICES, true);
        scene.createBuffer(BUF_WAVE_PCM_COLOR, COLOR_STRIDE_BYTES, WAVE_VERTICES, true);
        scene.createBuffer(BUF_WAVE_FFT, PLAIN_STRIDE_BYTES, WAVE_VERTICES, true);
        scene.createBuffer(BUF_WAVE_FFT_COLOR, COLOR_STRIDE_BYTES, WAVE_VERTICES, true);

        for (String[] texture : TEXTURES) scene.createTexture(texture[0]);

        // pass 的声明顺序就是绘制顺序：反射层 → 反射地板 → 正立层。
        for (int layer = 0; layer < 2; layer++) {
            for (int i = 0; i < 6; i++) {
                if ((i & 1) == 1) {
                    addVuPasses(scene, layer * 3 + (i >> 1));
                } else {
                    addWavePasses(scene, layer * 3 + (i >> 1));
                }
            }
            if (layer == 0) {
                mPlanePass = addQuadPass(scene, BUF_REFLECT_PLANE, TEX_ALBUM);
            }
        }

        mRendererBuilt = true;
        // 渲染器可用后才开始采集；prefs 已在 ensureScene 里注入。
        if (mAudioRequested) startAudio();
    }

    private void addWavePasses(VkPassScene scene, int slot) {
        VkPass[] passes = mWavePasses[slot];
        passes[WAVE_FIRE_STRIP] =
                addWavePass(scene, BUF_WAVE_PCM, false, VkPass.TOPOLOGY_TRIANGLE_STRIP, TEX_FIRE);
        passes[WAVE_FIRE_LINE] =
                addWavePass(scene, BUF_WAVE_PCM, false, VkPass.TOPOLOGY_LINE_LIST, TEX_FIRE);
        passes[WAVE_ICE_STRIP] =
                addWavePass(scene, BUF_WAVE_FFT, false, VkPass.TOPOLOGY_TRIANGLE_STRIP, TEX_ICE);
        passes[WAVE_ICE_LINE] =
                addWavePass(scene, BUF_WAVE_FFT, false, VkPass.TOPOLOGY_LINE_LIST, TEX_ICE);
        passes[WAVE_COLOR_PCM_STRIP] = addWavePass(
                scene, BUF_WAVE_PCM_COLOR, true, VkPass.TOPOLOGY_TRIANGLE_STRIP, TEX_GREY);
        passes[WAVE_COLOR_PCM_LINE] =
                addWavePass(scene, BUF_WAVE_PCM_COLOR, true, VkPass.TOPOLOGY_LINE_LIST, TEX_GREY);
        passes[WAVE_COLOR_FFT_STRIP] = addWavePass(
                scene, BUF_WAVE_FFT_COLOR, true, VkPass.TOPOLOGY_TRIANGLE_STRIP, TEX_GREY);
        passes[WAVE_COLOR_FFT_LINE] =
                addWavePass(scene, BUF_WAVE_FFT_COLOR, true, VkPass.TOPOLOGY_LINE_LIST, TEX_GREY);
    }

    private VkPass addWavePass(
            VkPassScene scene,
            String bufferName,
            boolean color,
            int topology,
            String textureName) {
        int stride = color ? COLOR_STRIDE_BYTES : PLAIN_STRIDE_BYTES;
        int[] attribSizes = color ? new int[] {2, 2, 3} : new int[] {2, 2};
        String vs = color ? SHADER_COLOR_VS : SHADER_LINE_VS;
        String fs = color ? SHADER_COLOR_FS : SHADER_LINE_FS;
        VkPass pass = scene.add(
                        new VkPass(vs, fs, attribSizes, stride, topology, VkPass.BLEND_ALPHA))
                .buffer(bufferName, 0)
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

    private void addVuPasses(VkPassScene scene, int slot) {
        VkPass[] passes = mVuPasses[slot];
        passes[VU_BACKGROUND] = addQuadPass(scene, BUF_VU_BACKGROUND, TEX_BACKGROUND);
        passes[VU_PEAK_ON] = addQuadPass(scene, BUF_VU_PEAK, TEX_PEAK_ON);
        passes[VU_PEAK_OFF] = addQuadPass(scene, BUF_VU_PEAK, TEX_PEAK_OFF);
        passes[VU_NEEDLE] = addQuadPass(scene, BUF_VU_NEEDLE, TEX_NEEDLE);
        passes[VU_ERASE] = addQuadPass(scene, BUF_VU_ERASE, TEX_BLACK);
        passes[VU_FRAME] = addQuadPass(scene, BUF_VU_FRAME, TEX_FRAME);
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
                .buffer(bufferName, 0)
                .pushConstants(PUSH_BYTES)
                .texture(0, textureName);
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
        ManyScene s = mScene;
        if (s == null || mPassScene == null) {
            setEmptyFrame(frame);
            return;
        }

        ensureTextures();

        synchronized (s) {
            // 与 MusicVisManyGL.drawFrame 开头逐句一致。
            s.updateRenderMode();
            s.updateAutoRotation(timeMs);
            s.updateNeedle();
            s.updateWaveData();
            s.updateWaveDataFFT();
            s.applyIdleAndFade();
            s.updateLineBuffers();
            s.updateAdjustBuffer();

            applyClearColor(s);

            uploadQuads(frame);
            uploadWaveBuffers(frame, s);

            float[] base = mBase;
            Mat4.setIdentityM(base);
            Mat4.translateM(base, 0f, 1f, 0f); // camera height offset
            Mat4.rotateM(base, s.mTilt, 1f, 0f, 0f);
            Mat4.rotateM(base, s.mAutoRotation + s.mRotate, 0f, 1f, 0f);

            float[] reflect = mReflect;
            System.arraycopy(base, 0, reflect, 0, 16);
            Mat4.translateM(reflect, 0f, -1f, 0f);
            Mat4.scaleM(reflect, 1f, -1f, 1f);
            drawVizLayer(frame, s, reflect, 0, 0);

            drawReflectPlane(frame, s, reflect);

            drawVizLayer(frame, s, base, 3, 3);

            s.endFrame();
        }
    }

    /** 清屏色就是场景每帧读到的背景色。 */
    private void applyClearColor(ManyScene scene) {
        VkPassScene passScene = mPassScene;
        if (passScene == null) return;
        if (scene != null) {
            passScene.setClearColor(scene.mBgColor[0], scene.mBgColor[1], scene.mBgColor[2], 1.0f);
        } else {
            passScene.setClearColor(0f, 0f, 0f, 1f);
        }
    }

    private void setEmptyFrame(VkPassFrame frame) {
        for (VkPass[] passes : mWavePasses) {
            for (VkPass pass : passes) {
                if (pass != null) frame.setVertexCount(pass, 0);
            }
        }
        for (VkPass[] passes : mVuPasses) {
            for (VkPass pass : passes) {
                if (pass != null) frame.setVertexCount(pass, 0);
            }
        }
        if (mPlanePass != null) frame.setVertexCount(mPlanePass, 0);
    }

    /** 与 GLES 的 drawVizLayer 相同：六个位置交替波形与 VU，每个之后绕 Y 轴转 60°。 */
    private void drawVizLayer(
            VkPassFrame frame, ManyScene s, float[] base, int waveOffset, int vuOffset) {
        System.arraycopy(base, 0, mLayer, 0, 16);
        int waveIdx = 0;
        for (int i = 0; i < 6; i++) {
            if ((i & 1) == 1) {
                drawVu(frame, s, vuOffset + (i >> 1), mLayer);
            } else {
                drawWave(frame, s, waveOffset + waveIdx, waveIdx, mLayer);
                waveIdx++;
            }
            Mat4.rotateM(mLayer, 60f, 0f, 1f, 0f);
        }
    }

    private void drawVu(VkPassFrame frame, ManyScene s, int slot, float[] layer) {
        VkPass[] passes = mVuPasses[slot];
        for (VkPass pass : passes) frame.setVertexCount(pass, 0);

        float scale = VU_SCALE;
        System.arraycopy(layer, 0, mModel, 0, 16);
        Mat4.scaleM(mModel, scale, scale, scale);
        Mat4.multiplyMM(mMvp, s.mProj, mModel);

        frame.setVertexCount(passes[VU_BACKGROUND], QUAD_VERTICES);
        pushMvp(frame, passes[VU_BACKGROUND], mMvp);

        int peak = s.mNeedle.mPeak > 0 ? VU_PEAK_ON : VU_PEAK_OFF;
        frame.setVertexCount(passes[peak], QUAD_VERTICES);
        pushMvp(frame, passes[peak], mMvp);

        System.arraycopy(layer, 0, mNeedle, 0, 16);
        Mat4.translateM(mNeedle, 0f, -57f * scale, 0f);
        Mat4.rotateM(mNeedle, s.mNeedle.mAngle - 90f, 0f, 0f, 1f);
        Mat4.scaleM(mNeedle, scale, scale, scale);
        Mat4.multiplyMM(mMvp, s.mProj, mNeedle);
        frame.setVertexCount(passes[VU_NEEDLE], QUAD_VERTICES);
        pushMvp(frame, passes[VU_NEEDLE], mMvp);

        System.arraycopy(layer, 0, mErase, 0, 16);
        Mat4.scaleM(mErase, scale, scale, scale);
        Mat4.multiplyMM(mMvp, s.mProj, mErase);
        frame.setVertexCount(passes[VU_ERASE], QUAD_VERTICES);
        pushMvp(frame, passes[VU_ERASE], mMvp);

        // GLES 在画完指针与擦除块之后重新 setMvp(model)，外框用的是和背景同一个矩阵。
        System.arraycopy(layer, 0, mModel, 0, 16);
        Mat4.scaleM(mModel, scale, scale, scale);
        Mat4.multiplyMM(mMvp, s.mProj, mModel);
        frame.setVertexCount(passes[VU_FRAME], QUAD_VERTICES);
        pushMvp(frame, passes[VU_FRAME], mMvp);
    }

    private void drawWave(
            VkPassFrame frame, ManyScene s, int slot, int waveIdx, float[] layer) {
        VkPass[] passes = mWavePasses[slot];
        for (VkPass pass : passes) frame.setVertexCount(pass, 0);

        // 与 GLES 相同：模式 1 全部用 FFT；模式 2 只有第三个位置用 FFT。
        boolean useFft = (s.mWaveMode == 1) || (s.mWaveMode == 2 && waveIdx == 2);
        boolean recolor = useFft ? s.mRecolorFFT : s.mRecolorPCM;
        boolean strip = s.mUseTriangleStrip;
        int variant;
        if (recolor) {
            variant =
                    (useFft ? WAVE_COLOR_FFT_STRIP : WAVE_COLOR_PCM_STRIP) + (strip ? 0 : 1);
        } else {
            variant = (useFft ? WAVE_ICE_STRIP : WAVE_FIRE_STRIP) + (strip ? 0 : 1);
        }

        System.arraycopy(layer, 0, mModel, 0, 16);
        Mat4.scaleM(mModel, 0.008f, 0.008f / 2048f, 0.008f);
        Mat4.translateM(mModel, 0f, 81920f, 350f);
        Mat4.multiplyMM(mMvp, s.mProj, mModel);

        VkPass active = passes[variant];
        frame.setVertexCount(active, WAVE_VERTICES);
        pushMvp(frame, active, mMvp);
    }

    private void drawReflectPlane(VkPassFrame frame, ManyScene s, float[] reflect) {
        if (mPlanePass == null) return;
        Mat4.multiplyMM(mMvp, s.mProj, reflect);
        frame.setVertexCount(mPlanePass, QUAD_VERTICES);
        pushMvp(frame, mPlanePass, mMvp);
    }

    private void pushMvp(VkPassFrame frame, VkPass pass, float[] mvp) {
        Mat4.multiplyMM(mClipMvp, CLIP_CORRECTION, mvp);
        frame.pushConstants(pass, mClipMvp);
    }

    // ─────────────────────────── 顶点数据 ───────────────────────────

    private void uploadQuads(VkPassFrame frame) {
        frame.upload(BUF_VU_BACKGROUND, mBackgroundQuad, QUAD_BYTES);
        frame.upload(BUF_VU_PEAK, mPeakQuad, QUAD_BYTES);
        frame.upload(BUF_VU_NEEDLE, mNeedleQuad, QUAD_BYTES);
        frame.upload(BUF_VU_ERASE, mEraseQuad, QUAD_BYTES);
        frame.upload(BUF_VU_FRAME, mFrameQuad, QUAD_BYTES);
        frame.upload(BUF_REFLECT_PLANE, mPlaneQuad, QUAD_BYTES);
    }

    private void uploadWaveBuffers(VkPassFrame frame, ManyScene s) {
        mPcmPlain.clear();
        mPcmColor.clear();
        mFftPlain.clear();
        mFftColor.clear();

        fillWaveVertices(
                mPcmPlain, mPcmColor, s.mLinePositions, s.mLineTexCoords, s.mAdjustData, 0);
        fillWaveVertices(
                mFftPlain,
                mFftColor,
                s.mWaveFFT.mPositions,
                s.mWaveFFT.mTexCoords,
                s.mAdjustData,
                ADJUST_SECTION);

        frame.upload(BUF_WAVE_PCM, mPcmPlain, WAVE_VERTICES * PLAIN_STRIDE_BYTES);
        frame.upload(BUF_WAVE_PCM_COLOR, mPcmColor, WAVE_VERTICES * COLOR_STRIDE_BYTES);
        frame.upload(BUF_WAVE_FFT, mFftPlain, WAVE_VERTICES * PLAIN_STRIDE_BYTES);
        frame.upload(BUF_WAVE_FFT_COLOR, mFftColor, WAVE_VERTICES * COLOR_STRIDE_BYTES);
    }

    private static void fillWaveVertices(
            ByteBuffer plain,
            ByteBuffer color,
            float[] positions,
            float[] texCoords,
            float[] adjust,
            int adjustOffset) {
        for (int i = 0; i < WAVE_VERTICES; i++) {
            int p = i * 2;
            float x = positions[p];
            float y = positions[p + 1];
            float u = texCoords[p];
            float v = texCoords[p + 1];
            plain.putFloat(x).putFloat(y).putFloat(u).putFloat(v);

            int a = adjustOffset + i * 3;
            color.putFloat(x)
                    .putFloat(y)
                    .putFloat(u)
                    .putFloat(v)
                    .putFloat(adjust[a])
                    .putFloat(adjust[a + 1])
                    .putFloat(adjust[a + 2]);
        }
        plain.position(0);
        color.position(0);
    }

    private void allocateWaveBuffers() {
        mPcmPlain = newVertexBuffer(WAVE_VERTICES * PLAIN_STRIDE_BYTES);
        mPcmColor = newVertexBuffer(WAVE_VERTICES * COLOR_STRIDE_BYTES);
        mFftPlain = newVertexBuffer(WAVE_VERTICES * PLAIN_STRIDE_BYTES);
        mFftColor = newVertexBuffer(WAVE_VERTICES * COLOR_STRIDE_BYTES);
    }

    private void buildQuadBuffers(float[] uvs) {
        // 与 MusicVisManyGL.drawVU / drawReflectPlane 的坐标逐字一致。
        mBackgroundQuad = quadBuffer(uvs, -208f, -33f, 600f, 208f, 200f, 600f);
        mPeakQuad = quadBuffer(uvs, 140f, 70f, 600f, 196f, 128f, 600f);
        mNeedleQuad = quadBuffer(uvs, -44f, -102f + 57f, 600f, 44f, 160f + 57f, 600f);
        mEraseQuad = quadBuffer(uvs, -100f, -105f, 600f, 100f, -55f, 600f);
        mFrameQuad = quadBuffer(uvs, -236f, -60f, 600f, 236f, 230f, 600f);
        // 地板高度取 ManyScene.mFloorY（固定 -200）与 drawQuadXZ 的两端坐标一致。
        mPlaneQuad = planeBuffer(uvs, -1500f, 1500f, -200f, -1500f, 1500f);
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

    /** drawQuadXZ：四个顶点都落在 y 平面上，x/z 取两端的值。 */
    private static ByteBuffer planeBuffer(
            float[] uv, float x1, float x2, float y, float z1, float z2) {
        ByteBuffer buffer = newVertexBuffer(QUAD_BYTES);
        putQuadVertex(buffer, x1, y, z1, uv[0], uv[1]);
        putQuadVertex(buffer, x2, y, z1, uv[2], uv[3]);
        putQuadVertex(buffer, x1, y, z2, uv[4], uv[5]);
        putQuadVertex(buffer, x2, y, z2, uv[6], uv[7]);
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
