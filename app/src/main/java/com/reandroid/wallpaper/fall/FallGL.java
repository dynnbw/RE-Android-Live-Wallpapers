/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.reandroid.wallpaper.fall;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.opengl.GLES30;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;

import com.reandroid.utils.MathUtils;
import com.reandroid.utils.AssetLoader;
import com.reandroid.utils.SkyField;
import com.reandroid.gles.GLESScene;
import com.reandroid.gles.GlowRenderer;
import com.reandroid.settings.WallpaperSettings;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;

public class FallGL extends GLESScene {
    private static final String TAG = "FallGL";
    private static final long PERF_SYNC_INTERVAL_MS = 1000L;

    // ---- HDR + 辉光 ----
    //
    // ⚠ 阈值是**取舍**，不是硬约束。这套合成是 `场景 + 辉光`，加法、没有色调映射，
    // 屏幕在 1.0 截断。而白日天空最亮的一端本身就有约 0.93 —— 阈值只要低到
    // 能收到发光体，就必然也收到天空最亮的那一段。两者不可能干净分开。
    //
    // 曾经把阈值抬到 1.0 来求"辉光只来自发光体"，代价是发光体必须亮过 1，
    // 于是核心被压成一片平白、边界是一道圆弧硬边、颜色也读不出来。**那个取舍不划算。**
    // 现在两边一起降：阈值 0.80、发光体峰值压到 1 附近不再截断 ——
    // 颜色留得住，代价是天空最亮的那一小段也会带一点晕（参考图本来也有这层雾）。
    //
    // 取值是上机调下来的，观感不对就调这里。

    /** 亮度阈值。低于它的不发辉光。 */
    private static final float GLOW_THRESHOLD = 0.80f;
    /** 阈值之上的过渡宽度。硬阈值会在光晕边缘留下可见的台阶。 */
    private static final float GLOW_SOFT_KNEE = 0.25f;
    /**
     * 模糊半径（像素，半分辨率下）。光晕发硬/发窄先调它，不是加遍数。
     *
     * <p><b>想让它更黄就调这里，不是把发光体做大。</b> 亮的核在心里必定是白的
     * （要进辉光就得亮过阈值，亮过 1.0 就被截断成白），
     * 能读出颜色的只有核心外面那圈晕 —— 而这圈有多宽、多暖，全看模糊铺多开。
     * 把发光体做大只会把白色区域一起做大。
     *
     * <p><b>但这条有个上限，别贪。</b> 模糊是 5 抽头近似，抽头落在
     * ±1.38·半径 与 ±3.23·半径 处 —— 半径给到 18 时那是屏幕上 ±50px 与 ±116px，
     * 等于把同一张图在四个方向各叠了一份。**树冠的遮罩边缘对比度最高**，
     * 重影全部显在那里，树会被打成一片白色星芒，完全看不出是树。
     * 上机实测 18 就是这样，退回 9（与 grass 同档）后干净。
     * 真需要更宽的光晕，得给 GlowRenderer 加模糊遍数，而不是继续抬这个值。
     */
    private static final float GLOW_RADIUS = 9.0f;
    /** 辉光叠加强度。 */
    private static final float GLOW_STRENGTH = 0.70f;

    /**
     * 星星闪烁时间的回绕周期。**必须与着色器里的 {@code STAR_WRAP_S} 一致。**
     *
     * <p>不是随手定的数：见 {@link #mWStarTimeHandle} 那一处的说明。
     */
    private static final long STAR_WRAP_MS = 30000L;

    // ---- 天空里的发光体 ----

    /**
     * 屏幕 UV 上的位置（0,0 = 左下）。
     *
     * <p>放在上方是因为色场的浅色端在屏幕顶部 —— 见 {@code pond_sky_fields.txt}
     * 里每条色带的走向。摆低会和天空的明暗对不上，读起来像"光在水里"。
     */
    private static final float EMITTER_X = 0.45f;
    /** 竖直位置（0 = 屏幕底、1 = 屏幕顶）。**上机调下来的，不是推导出来的。** */
    private static final float EMITTER_Y = 0.6f;
    /**
     * 横向半轴，占**屏幕宽度**的比例。
     *
     * <p>纵向半轴不在这里 —— 它由这一个乘上 {@link #EMITTER_ASPECT} 再按屏幕长宽比折算，
     * 见 {@code drawWaterQuad}。**不能直接把这个值也当纵向的 UV 半径**：
     * UV 是逐轴归一化的，竖屏上 x 的 0.36 只有 389px，y 的 0.22 却有 528px，
     * 于是"横向的 UV 值更大"画出来反而是个**竖**椭圆 —— 上机就是这么栽的。
     */
    private static final float EMITTER_HALF_W = 0.55f;
    /**
     * 纵向半轴 ÷ 横向半轴，**在屏幕上量**（&lt; 1 才是横扁的）。
     *
     * <p>参考图里这一团是横跨、扁的；用户的话是"原图是左右"。
     */
    private static final float EMITTER_ASPECT = 0.60f;
    /**
     * 黄白。**偏黄要够狠**：中心那一块必定被截断成纯白，
     * 能读出"黄"的只有它周围那一圈 —— 颜色本身不黄，整团就是白的。
     */
    private static final float[] EMITTER_COLOR = {1.00f, 0.74f, 0.30f};
    /**
     * 峰值亮度。
     *
     * <p><b>与 {@link #GLOW_THRESHOLD} 是一对，改一个必须同时看另一个：</b>
     * 峰值在阈值之下 → 完全不发辉光；远在阈值之上 → 那一大片被屏幕截断成同一个白，
     * 平、没有颜色、边界是一道圆弧硬边。
     *
     * <p>上机实测过 6.0 与 3.5，两次都是后面那种。原因不是"太亮"那么简单：
     * 发光体中心那片天空本身已经有约 0.66，峰值一高，
     * "天空 + 发光体 > 1"的范围就覆盖整个 blob，颜色（黄）全部丢在截断里。
     *
     * <p>辉光关掉时**不画**（增益给 0）。不画而不是画暗一点：一个很亮的高斯斑
     * 没有辉光来收尾，看着就是一块糊在天空上的白渍，比不做还难看。
     */
    private static final float EMITTER_GAIN = 1.5f;
    private static final long ANR_FRAME_THRESHOLD_MS = 200L;
    /** assets/fall/drawable/ 下 leaves_N.png 的文件数（绿叶全开时用满）。 */
    private static final int LEAF_TEXTURE_FILES = 20;

    private int mProgram;       // leaf shader
    private int mWaterProgram;  // water shader (GPU ripple)
    private int mPositionHandle;
    private int mTexCoordHandle;
    private int mMatrixHandle;
    private int mAlphaHandle;
    private int mSamplerHandle;
    private int mColorHandle;
    // Water shader uniforms
    private int mWMatrixHandle;
    private int mWAlphaHandle;
    private int mWMaskHandle;
    private int mWColorHandle;
    private int mWEmitterPosHandle;
    private int mWEmitterRadiusHandle;
    private int mWEmitterColorHandle;
    private int mWEmitterGainHandle;
    private int mWStarAmountHandle;
    private int mWStarTimeHandle;
    private int mWStarAspectHandle;
    /** 落叶着色器里的时段染色 uniform。 */
    private int mTintHandle;
    private int mTintAmountHandle;
    private int mTintValueHandle;
    private int mWPositionHandle;
    private int mWTexCoordHandle;
    private int mWGlHeightHandle;
    private int mWBgScaleHandle;
    private int mWMeshScaleXHandle;
    private int mWMeshScaleYHandle;
    private int mWDxMulHandle;
    private int mWXOffsetHandle;
    private int mWRotateHandle;
    private int mWDropHandle;
    private int mWDropCountHandle;
    private int[] mLeafTextures;
    /** 河床的树：单通道遮罩（白=天空 黑=树）。 */
    private int mMaskTexture;

    /*
     * 河床的天空：pond_sky_fields.txt 的四段色场各生成一条 24x64 竖直色带，
     * 按权重加权求和（权重由 FallScene 按时段算出）。
     *
     * 下面三张表**必须同序**，而且要与 FallScene#getSkyWeights() 的顺序一致
     * （{@code [夜, 晨, 昏, 昼]}）—— 它们就是靠下标对齐的，只动一处就会串色。
     */
    private static final String[] SKY_SECTIONS = {
            "SKY_FIELD_NIGHT", "SKY_FIELD_MORNING", "SKY_FIELD_DUSK", "SKY_FIELD_DAY"
    };
    private static final String[] SKY_SAMPLER_UNIFORMS = {
            "uSkyNight", "uSkyMorning", "uSkyDusk", "uSkyDay"
    };
    private static final String[] SKY_WEIGHT_UNIFORMS = {
            "uWeightNight", "uWeightMorning", "uWeightDusk", "uWeightDay"
    };
    private final int[] mSkyTextures = new int[SKY_SECTIONS.length];
    private final int[] mSkySamplerHandles = new int[SKY_SECTIONS.length];
    private final int[] mSkyWeightHandles = new int[SKY_SECTIONS.length];
    private FloatBuffer mWaterMeshVertexBuffer;
    private FloatBuffer mWaterMeshTexCoordBuffer;
    private FloatBuffer mLeafQuadVertexBuffer;
    /**
     * 叶片四边形的顶点模板（见 drawQuad）：z 恒为 0，uv 固定，
     * 每帧只有 4 个角点的 x/y 会变，所以模板只需要作一次，
     * 调用时改写那 8 个槽位即可，不必每次重新构造整个数组。
     */
    private static final float[] LEAF_QUAD_TEMPLATE = {
            0f, 0f, 0f, 0.0f, 0.0f,
            0f, 0f, 0f, 1.0f, 0.0f,
            0f, 0f, 0f, 1.0f, 1.0f,
            0f, 0f, 0f, 0.0f, 1.0f
    };
    private final float[] mLeafQuadVertices = LEAF_QUAD_TEMPLATE.clone();
    private ShortBuffer mWaterIndexBuffer;
    private final float[] mModelMatrix = new float[16];
    private final float[] mViewMatrix = new float[16];
    private final float[] mMVPMatrix = new float[16];
    /** drawLeafQuad 的临时矩阵：每片叶子用两次，不能每帧新分配。 */
    private final float[] mLeafMvMatrix = new float[16];
    private boolean mGLInitialized = false;
    /** HDR 中间缓冲 + 辉光。建不出来时整体退化成空操作，画面与不用它时相同。 */
    private GlowRenderer mGlowRenderer;
    private int mFrameCount = 0;
    private final FallScene mScene;
    private final Context mContext;
    private int mTargetFps = 30;
    private long mTargetFrameMs = 33L;
    private boolean mAnrDiagEnabled = false;
    private long mLastPerfSyncMs = 0L;
    private long mDiagFrameCount = 0L;
    private long mDiagAccumulatedMs = 0L;
    private long mDiagMaxMs = 0L;
    private static final float TOUCH_TRIGGER_DISTANCE_THRESHOLD_PX = 42.0f;
    private float mLastTouchTriggerX = -1.0f;
    private float mLastTouchTriggerY = -1.0f;

    public FallGL(Context context, int width, int height) {
        super(width, height);
        mContext = context;
        mScene = new FallScene(width, height);
        Log.d(TAG, "FallGL创建: " + width + "x" + height);
    }

    /** Plugin path: set host-provided prefs for settings isolation. */
    public void setPluginPrefs(SharedPreferences prefs) {
        mScene.setPluginPrefs(prefs);
    }

    @Override
    protected void onCreate() {
        Log.d(TAG, "onCreate()调用, mResources=" + mResources + ", 宽度=" + mWidth + ", 高度=" + mHeight);
    }

    @Override
    public void release() {
        if (mLeafTextures != null && mLeafTextures.length > 0) {
            GLES30.glDeleteTextures(mLeafTextures.length, mLeafTextures, 0);
            mLeafTextures = null;
        }

        if (mMaskTexture != 0) {
            int[] tex = new int[] { mMaskTexture };
            GLES30.glDeleteTextures(1, tex, 0);
            mMaskTexture = 0;
        }
        releaseSkyTextures();

        if (mProgram != 0) {
            GLES30.glDeleteProgram(mProgram);
            mProgram = 0;
        }
        if (mWaterProgram != 0) {
            GLES30.glDeleteProgram(mWaterProgram);
            mWaterProgram = 0;
        }

        if (mGlowRenderer != null) {
            mGlowRenderer.release();
            mGlowRenderer = null;
        }

        mWaterMeshVertexBuffer = null;
        mWaterMeshTexCoordBuffer = null;
        mGLInitialized = false;
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        mScene.resize(width, height);
        if (mGlowRenderer != null) {
            mGlowRenderer.resize(width, height);
        }
    }

    @Override
    public void start() {
        if (mGLInitialized) {
            return;
        }
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        } catch (Throwable ignored) {
        }

        mGLInitialized = true;
        mScene.ensureResources();
        // 预览（设置页）里把一天压进 30 秒，否则永远只看得到"现在"这一刻
        mScene.setPreview(isPreview());
        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        GLES30.glViewport(0, 0, mWidth, mHeight);

        createProgram();
        createWaterProgram();

        // 建不出来（FBO 不完整 / 着色器失败）时它整体退化成空操作，画面照旧
        mGlowRenderer = new GlowRenderer();
        mGlowRenderer.init(mWidth, mHeight,
                "fall/shaders/GLES/glow_quad_vs.glsl",
                "fall/shaders/GLES/glow_bright_fs.glsl",
                "fall/shaders/GLES/glow_blur_fs.glsl",
                "fall/shaders/GLES/glow_composite_fs.glsl",
                assetPath -> AssetLoader.readText(mContext, assetPath));

        try {
            mLeafTextures = loadLeafTextures();
        } catch (Exception e) {
            Log.e(TAG, "GL线程加载枫叶纹理失败", e);
            mLeafTextures = createPlaceholderLeafTextures();
        }
        // ensureResources() already set mLeafTextureCount from prefs — don't override
        try {
            mMaskTexture = loadMaskTexture("fall/drawable/pond_mask.png");
            loadPondSkyTextures();
        } catch (Exception e) {
            Log.e(TAG, "GL线程加载河床纹理失败", e);
            mMaskTexture = 0;
            releaseSkyTextures();
        }
        // 任一缺失都会让水面变成未定义采样，各自退回占位（全白遮罩 = 整屏天空）
        if (mMaskTexture == 0) {
            mMaskTexture = createSolidMaskTexture();
        }
        ensureSkyTextures();
    }

    @Override
    public void onCommand(String action, int x, int y, int z) {
        if (action != null && action.toLowerCase().contains("tap")) {
            mScene.addDrop(x, y);
        }
    }

    @Override
    public void onTouchEvent(MotionEvent event) {
        if (event == null) {
            return;
        }
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mScene.addDrop((int) x, (int) y);
                mLastTouchTriggerX = x;
                mLastTouchTriggerY = y;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!mScene.isSwipeRippleEnabled()) {
                    break;
                }
                if (mLastTouchTriggerX < 0.0f || mLastTouchTriggerY < 0.0f) {
                    mScene.addDrop((int) x, (int) y);
                    mLastTouchTriggerX = x;
                    mLastTouchTriggerY = y;
                    break;
                }
                float dx = x - mLastTouchTriggerX;
                float dy = y - mLastTouchTriggerY;
                float distance = (float) Math.sqrt(dx * dx + dy * dy);
                if (distance >= TOUCH_TRIGGER_DISTANCE_THRESHOLD_PX) {
                    mScene.addDrop((int) x, (int) y);
                    mLastTouchTriggerX = x;
                    mLastTouchTriggerY = y;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mLastTouchTriggerX = -1.0f;
                mLastTouchTriggerY = -1.0f;
                break;
            default:
                break;
        }
    }

    /** 滑动水波纹开关（滑动每 42px 触发一次水波纹），供 FallView 查询。 */
    public boolean isSwipeRippleEnabled() {
        return mScene.isSwipeRippleEnabled();
    }

    public void addDrop(int x, int y) {
        mScene.addDrop(x, y);
    }

    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        mScene.setOffset(xOffset);
    }

    @Override
    public void drawFrame(long timeMs) {
        if (mFrameCount == 0) {
            Log.d(TAG, "首次drawFrame()开始");
        }
        mFrameCount++;

        long frameStart = SystemClock.uptimeMillis();
        syncPerfSettingsIfNeeded(frameStart);

        if (!mGLInitialized) {
            if (mResources != null) {
                start();
            } else {
                return;
            }
        }

        if (mProgram == 0) {
            Log.w(TAG, "程序未初始化");
            return;
        }

        mScene.update(timeMs);
        FallScene.SceneData sceneData = mScene.getSceneData();
        syncWaterMeshBuffers(sceneData);

        // 整个场景先画进 HDR 中间缓冲，画完再提取亮部、模糊、加回屏幕。
        // 关掉时这两句都不执行，渲染路径与加辉光之前逐帧相同。
        final boolean glow = isGlowActive();
        if (glow) {
            mGlowRenderer.beginScene();
        }

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);

        drawWaterQuad(sceneData);  // uses mWaterProgram internally
        drawLeaves(sceneData);

        /*
         * **辉光在落叶之后合成** —— 光晕会像洗树冠那样也落在落叶上，参考图就是这样。
         *
         * 曾经把它挪到落叶之前，因为那时辉光是一大块平的白斑，叶子经过就被糊成白影。
         * 但那是辉光本身有病，不是合成位置的问题。辉光恢复成正常的渐变之后挪回这里：
         * 叶子只是被照亮一点，不会糊掉。**"不受辉光影响"的叶子看着像贴上去的。**
         */
        if (glow) {
            mGlowRenderer.endScene(GLOW_THRESHOLD, GLOW_SOFT_KNEE, GLOW_RADIUS, GLOW_STRENGTH);
        }

        /*
         * glGetError 是同步查询，可能强制冲刷管线、把帧时间拉长，所以只在诊断模式下做。
         * 原来每帧无条件调用。
         */
        if (mAnrDiagEnabled) {
            int glError = GLES30.glGetError();
            if (glError != GLES30.GL_NO_ERROR) {
                Log.w(TAG, "drawFrame中GL错误: " + glError);
            }
        }

        long frameCost = SystemClock.uptimeMillis() - frameStart;
        recordFrameCost(frameCost);
    }

    /** 落叶要不要走 HDR 那一趟：开关、设备能力两者都要满足。 */
    private boolean isGlowActive() {
        return mScene.isGlowEnabled() && mGlowRenderer != null && mGlowRenderer.isReady();
    }

    /**
     * 落叶。单独一个方法只为一件事：<b>不能从 drawFrame 里提前 return</b> ——
     * 那会把场景留在 HDR 中间缓冲里没合成，症状是整屏全黑。
     */
    private void drawLeaves(FallScene.SceneData sceneData) {
        if (mProgram == 0) {
            return;
        }
        GLES30.glUseProgram(mProgram);
        float[] tint = mScene.getLeafTintRgb();
        GLES30.glUniform3f(mTintHandle, tint[0], tint[1], tint[2]);
        GLES30.glUniform1f(mTintAmountHandle, mScene.getLeafTintAmount());
        GLES30.glUniform1f(mTintValueHandle, mScene.getLeafTintValue());
        for (FallScene.Leaf leaf : sceneData.getLeaves()) {
            drawLeaf(leaf, sceneData);
        }
    }

    private void syncWaterMeshBuffers(FallScene.SceneData sceneData) {
        boolean rebuildBuffers = mScene.consumeMeshBufferRebuildRequested()
                || mWaterMeshVertexBuffer == null
                || mWaterMeshTexCoordBuffer == null
                || mWaterMeshVertexBuffer.capacity() != sceneData.getWaterMeshVertices().length
                || mWaterMeshTexCoordBuffer.capacity() != sceneData.getWaterMeshTexCoords().length;

        if (rebuildBuffers) {
            mWaterMeshVertexBuffer = createFloatBuffer(sceneData.getWaterMeshVertices());
            mWaterMeshTexCoordBuffer = createFloatBuffer(sceneData.getWaterMeshTexCoords());
        }
        // Texcoords static — ripple displacement computed in vertex shader
    }

    private void drawWaterQuad(FallScene.SceneData sceneData) {
        if (mWaterProgram == 0) return;
        GLES30.glUseProgram(mWaterProgram);

        Matrix.setIdentityM(mModelMatrix, 0);
        Matrix.multiplyMM(mViewMatrix, 0, sceneData.getViewMatrix(), 0, mModelMatrix, 0);
        Matrix.multiplyMM(mMVPMatrix, 0, sceneData.getProjectionMatrix(), 0, mViewMatrix, 0);
        GLES30.glUniformMatrix4fv(mWMatrixHandle, 1, false, mMVPMatrix, 0);
        GLES30.glUniform1f(mWAlphaHandle, 1.0f);
        GLES30.glUniform4f(mWColorHandle, 1.0f, 1.0f, 1.0f, 1.0f);

        // Scene parameters for GPU ripple
        GLES30.glUniform1f(mWGlHeightHandle, sceneData.getGlHeight());
        GLES30.glUniform1f(mWBgScaleHandle, sceneData.getBgScale());
        GLES30.glUniform1f(mWMeshScaleXHandle, sceneData.getMeshScaleX());
        GLES30.glUniform1f(mWMeshScaleYHandle, sceneData.getMeshScaleY());
        GLES30.glUniform1f(mWDxMulHandle, sceneData.getDxMul());
        GLES30.glUniform1f(mWXOffsetHandle, sceneData.getXOffset());
        GLES30.glUniform1f(mWRotateHandle, (float) sceneData.getRotate());
        GLES30.glUniform1f(mWDropCountHandle, (float) sceneData.getActiveDropCount());
        GLES30.glUniform4fv(mWDropHandle, Math.max(1, sceneData.getActiveDropCount()), sceneData.getDropData(), 0);

        GLES30.glEnableVertexAttribArray(mWPositionHandle);
        mWaterMeshVertexBuffer.position(0);
        GLES30.glVertexAttribPointer(mWPositionHandle, 3, GLES30.GL_FLOAT, false, 12, mWaterMeshVertexBuffer);

        // 水面顶点着色器不使用 aTexCoord 属性（vTexCoord 由 VS 程序化计算），
        // 未声明的属性 location 为 -1，启用/绑定它会触发 GL_INVALID_VALUE (1281)。
        if (mWTexCoordHandle >= 0) {
            GLES30.glEnableVertexAttribArray(mWTexCoordHandle);
            mWaterMeshTexCoordBuffer.position(0);
            GLES30.glVertexAttribPointer(mWTexCoordHandle, 2, GLES30.GL_FLOAT, false, 8, mWaterMeshTexCoordBuffer);
        }

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mMaskTexture);
        GLES30.glUniform1i(mWMaskHandle, 0);

        // 四条天空色带绑到单元 1..4，权重与 mSkyTextures 同序（见 SKY_SECTIONS 的说明）
        float[] skyWeights = mScene.getSkyWeights();
        for (int i = 0; i < mSkyTextures.length; i++) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1 + i);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mSkyTextures[i]);
            GLES30.glUniform1i(mSkySamplerHandles[i], 1 + i);
            GLES30.glUniform1f(mSkyWeightHandles[i], skyWeights[i]);
        }

        /*
         * 天空里的发光体。位置固定，亮度 = 日夜权重 × 峰值；辉光关着时整团不画。
         */
        GLES30.glUniform2f(mWEmitterPosHandle, EMITTER_X, EMITTER_Y);
        // 纵向半轴按屏幕长宽比折算成 UV。不折的话竖屏上画出来是竖椭圆，见 EMITTER_HALF_W。
        float emitterRadiusY = EMITTER_HALF_W * EMITTER_ASPECT * mWidth / (float) mHeight;
        GLES30.glUniform2f(mWEmitterRadiusHandle, EMITTER_HALF_W, emitterRadiusY);
        GLES30.glUniform3f(mWEmitterColorHandle, EMITTER_COLOR[0], EMITTER_COLOR[1], EMITTER_COLOR[2]);
        GLES30.glUniform1f(mWEmitterGainHandle,
                isGlowActive() ? mScene.getEmitterWeight() * EMITTER_GAIN : 0.0f);

        /*
         * 夜空星星的时间。
         *
         * **必须回绕，不能传绝对时间。** 传 uptime 秒数（上机时约 2956）的话，
         * 正弦自变量落到几千的量级，低位精度一丢就变成每两三秒一步的阶梯 ——
         * 上机用 glReadPixels 回读证实过。回绕到 [0, 30) 之后量级只有几十。
         *
         * 回绕本身不会跳：着色器里每颗星的速度是基频的整数倍，t 从 30 回到 0 时
         * 相位正好走完整数圈。**两者必须一起改** —— 周期和倍数任改一个都会在回绕处留下跳变。
         */
        GLES30.glUniform1f(mWStarAmountHandle, mScene.getStarAmount());
        GLES30.glUniform1f(mWStarTimeHandle,
                (SystemClock.uptimeMillis() % STAR_WRAP_MS) * 0.001f);
        // 星点的形状修正，见着色器里 uStarAspect 的说明
        GLES30.glUniform1f(mWStarAspectHandle, mHeight / (float) Math.max(1, mWidth));

        int indexCount = sceneData.getWaterMeshIndexCount();
        if (indexCount > 0) {
            if (mWaterIndexBuffer == null || mWaterIndexBuffer.capacity() != sceneData.getWaterMeshIndices().length) {
                mWaterIndexBuffer = ByteBuffer.allocateDirect(sceneData.getWaterMeshIndices().length * 2)
                        .order(ByteOrder.nativeOrder()).asShortBuffer();
                mWaterIndexBuffer.put(sceneData.getWaterMeshIndices()).position(0);
            }
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_SHORT, mWaterIndexBuffer);
        }

        GLES30.glDisableVertexAttribArray(mWPositionHandle);
        if (mWTexCoordHandle >= 0) {
            GLES30.glDisableVertexAttribArray(mWTexCoordHandle);
        }
    }

    private void drawLeaf(FallScene.Leaf leaf, FallScene.SceneData sceneData) {
        if (mLeafTextures == null || mLeafTextures.length == 0) {
            return;
        }
        float sizeMul = mScene.getLeafSizeMultiplier();

        if (leaf.altitude > 0.0f) {
            float shadowAlpha = 1.0f;
            if (leaf.altitude >= 0.4f) {
                shadowAlpha = 1.0f - (leaf.altitude - 0.4f) / 0.1f;
            }
            shadowAlpha = MathUtils.clamp(shadowAlpha, 0.0f, 1.0f) * 0.15f;

            float shadowOffset = leaf.altitude * 0.2f;
            int texture = mLeafTextures[leaf.leafTextureIndex % mLeafTextures.length];
            drawLeafQuad(leaf.x - shadowOffset, leaf.y - shadowOffset, leaf.scale * sizeMul, leaf.angle,
                    texture, shadowAlpha, true, sceneData);
        }

        float leafAlpha = 1.0f;
        if (leaf.altitude > 0.0f) {
            if (leaf.altitude >= 0.4f) {
                leafAlpha = 1.0f - (leaf.altitude - 0.4f) / 0.1f;
            }
            leafAlpha = MathUtils.clamp(leafAlpha, 0.0f, 1.0f);
        }

        int texture = mLeafTextures[leaf.leafTextureIndex % mLeafTextures.length];
        drawLeafQuad(leaf.x, leaf.y, leaf.scale * sizeMul, leaf.angle, texture, leafAlpha, false,
                sceneData);
    }

    private void drawLeafQuad(float x, float y, float scale, float rotation, int texture, float alpha,
            boolean silhouette, FallScene.SceneData sceneData) {
        float drawX = x - sceneData.getXOffset() * 2.0f;
        Matrix.setIdentityM(mModelMatrix, 0);
        Matrix.translateM(mModelMatrix, 0, drawX, y, 0);
        Matrix.rotateM(mModelMatrix, 0, rotation, 0, 0, 1);
        Matrix.scaleM(mModelMatrix, 0, scale, scale, 1);

        float[] mvMatrix = mLeafMvMatrix;
        Matrix.multiplyMM(mvMatrix, 0, sceneData.getViewMatrix(), 0, mModelMatrix, 0);
        Matrix.multiplyMM(mMVPMatrix, 0, sceneData.getProjectionMatrix(), 0, mvMatrix, 0);

        drawQuad(-FallScene.LEAF_SIZE, -FallScene.LEAF_SIZE, FallScene.LEAF_SIZE, FallScene.LEAF_SIZE,
                texture, alpha, silhouette);
    }

    private void drawQuad(float left, float top, float right, float bottom, int texture, float alpha,
            boolean silhouette) {
        float[] vertices = mLeafQuadVertices;
        vertices[0] = left;   vertices[1] = bottom;
        vertices[5] = right;  vertices[6] = bottom;
        vertices[10] = right; vertices[11] = top;
        vertices[15] = left;  vertices[16] = top;

        if (mLeafQuadVertexBuffer == null || mLeafQuadVertexBuffer.capacity() != vertices.length) {
            mLeafQuadVertexBuffer = createFloatBuffer(vertices);
        } else {
            mLeafQuadVertexBuffer.position(0);
            mLeafQuadVertexBuffer.put(vertices);
            mLeafQuadVertexBuffer.position(0);
        }

        GLES30.glUniformMatrix4fv(mMatrixHandle, 1, false, mMVPMatrix, 0);
        GLES30.glUniform1f(mAlphaHandle, alpha);
        if (silhouette) {
            GLES30.glUniform4f(mColorHandle, 0.0f, 0.0f, 0.0f, 1.0f);
        } else {
            GLES30.glUniform4f(mColorHandle, 1.0f, 1.0f, 1.0f, 1.0f);
        }

        GLES30.glEnableVertexAttribArray(mPositionHandle);
        GLES30.glVertexAttribPointer(mPositionHandle, 3, GLES30.GL_FLOAT, false, 20, mLeafQuadVertexBuffer);

        mLeafQuadVertexBuffer.position(3);
        GLES30.glEnableVertexAttribArray(mTexCoordHandle);
        GLES30.glVertexAttribPointer(mTexCoordHandle, 2, GLES30.GL_FLOAT, false, 20, mLeafQuadVertexBuffer);

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture);
        GLES30.glUniform1i(mSamplerHandle, 0);

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_FAN, 0, 4);
        GLES30.glDisableVertexAttribArray(mPositionHandle);
        GLES30.glDisableVertexAttribArray(mTexCoordHandle);
    }

    private void createProgram() {
        String vertexShader = AssetLoader.readText(mContext, "fall/shaders/GLES/fall_vs.glsl");
        String fragmentShader = AssetLoader.readText(mContext, "fall/shaders/GLES/fall_fs.glsl");
        int vs = compileShader(GLES30.GL_VERTEX_SHADER, vertexShader);
        int fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentShader);
        if (vs == 0 || fs == 0) {
            Log.e(TAG, "着色器编译失败!");
            return;
        }

        mProgram = GLES30.glCreateProgram();
        GLES30.glAttachShader(mProgram, vs);
        GLES30.glAttachShader(mProgram, fs);
        GLES30.glLinkProgram(mProgram);

        int[] linkStatus = new int[1];
        GLES30.glGetProgramiv(mProgram, GLES30.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] != GLES30.GL_TRUE) {
            Log.e(TAG, "程序链接失败: " + GLES30.glGetProgramInfoLog(mProgram));
        }

        mPositionHandle = GLES30.glGetAttribLocation(mProgram, "aPosition");
        mTexCoordHandle = GLES30.glGetAttribLocation(mProgram, "aTexCoord");
        mMatrixHandle = GLES30.glGetUniformLocation(mProgram, "uMVPMatrix");
        mAlphaHandle = GLES30.glGetUniformLocation(mProgram, "uAlpha");
        mSamplerHandle = GLES30.glGetUniformLocation(mProgram, "uSampler");
        mColorHandle = GLES30.glGetUniformLocation(mProgram, "uColor");
        mTintHandle = GLES30.glGetUniformLocation(mProgram, "uTint");
        mTintAmountHandle = GLES30.glGetUniformLocation(mProgram, "uTintAmount");
        mTintValueHandle = GLES30.glGetUniformLocation(mProgram, "uValue");

        GLES30.glDeleteShader(vs);
        GLES30.glDeleteShader(fs);
    }

    private void createWaterProgram() {
        // Generate vertex shader with dynamic drop array size (no hard limit)
        int maxDrops = WallpaperSettings.getFallMaxDrops(80);
        String template = AssetLoader.readText(mContext, "fall/shaders/GLES/fall_water_vs.glsl");
        String vertexShader = template.replace("$DROP_SIZE", String.valueOf(maxDrops));
        String fragmentShader = AssetLoader.readText(mContext, "fall/shaders/GLES/fall_water_fs.glsl");
        int vs = compileShader(GLES30.GL_VERTEX_SHADER, vertexShader);
        int fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentShader);
        if (vs == 0 || fs == 0) {
            Log.e(TAG, "Water着色器编译失败!");
            if (vs != 0) GLES30.glDeleteShader(vs);
            if (fs != 0) GLES30.glDeleteShader(fs);
            return;
        }

        mWaterProgram = GLES30.glCreateProgram();
        GLES30.glAttachShader(mWaterProgram, vs);
        GLES30.glAttachShader(mWaterProgram, fs);
        GLES30.glLinkProgram(mWaterProgram);

        int[] linkStatus = new int[1];
        GLES30.glGetProgramiv(mWaterProgram, GLES30.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] != GLES30.GL_TRUE) {
            Log.e(TAG, "Water程序链接失败: " + GLES30.glGetProgramInfoLog(mWaterProgram));
        }

        mWPositionHandle     = GLES30.glGetAttribLocation(mWaterProgram, "aPosition");
        mWTexCoordHandle     = GLES30.glGetAttribLocation(mWaterProgram, "aTexCoord");
        mWMatrixHandle       = GLES30.glGetUniformLocation(mWaterProgram, "uMVPMatrix");
        mWAlphaHandle        = GLES30.glGetUniformLocation(mWaterProgram, "uAlpha");
        mWMaskHandle         = GLES30.glGetUniformLocation(mWaterProgram, "uMask");
        for (int i = 0; i < SKY_SECTIONS.length; i++) {
            mSkySamplerHandles[i] = GLES30.glGetUniformLocation(mWaterProgram, SKY_SAMPLER_UNIFORMS[i]);
            mSkyWeightHandles[i]  = GLES30.glGetUniformLocation(mWaterProgram, SKY_WEIGHT_UNIFORMS[i]);
        }
        mWColorHandle        = GLES30.glGetUniformLocation(mWaterProgram, "uColor");
        mWEmitterPosHandle   = GLES30.glGetUniformLocation(mWaterProgram, "uEmitterPos");
        mWEmitterRadiusHandle = GLES30.glGetUniformLocation(mWaterProgram, "uEmitterRadius");
        mWEmitterColorHandle = GLES30.glGetUniformLocation(mWaterProgram, "uEmitterColor");
        mWEmitterGainHandle  = GLES30.glGetUniformLocation(mWaterProgram, "uEmitterGain");
        mWStarAmountHandle   = GLES30.glGetUniformLocation(mWaterProgram, "uStarAmount");
        mWStarTimeHandle     = GLES30.glGetUniformLocation(mWaterProgram, "uStarTime");
        mWStarAspectHandle   = GLES30.glGetUniformLocation(mWaterProgram, "uStarAspect");
        mWGlHeightHandle     = GLES30.glGetUniformLocation(mWaterProgram, "u_glHeight");
        mWBgScaleHandle      = GLES30.glGetUniformLocation(mWaterProgram, "u_bgScale");
        mWMeshScaleXHandle   = GLES30.glGetUniformLocation(mWaterProgram, "u_meshScaleX");
        mWMeshScaleYHandle   = GLES30.glGetUniformLocation(mWaterProgram, "u_meshScaleY");
        mWDxMulHandle        = GLES30.glGetUniformLocation(mWaterProgram, "u_dxMul");
        mWXOffsetHandle      = GLES30.glGetUniformLocation(mWaterProgram, "u_xOffset");
        mWRotateHandle       = GLES30.glGetUniformLocation(mWaterProgram, "u_rotate");
        mWDropHandle         = GLES30.glGetUniformLocation(mWaterProgram, "u_drop");
        mWDropCountHandle    = GLES30.glGetUniformLocation(mWaterProgram, "u_dropCount");

        GLES30.glDeleteShader(vs);
        GLES30.glDeleteShader(fs);
    }


    private int loadTexture(String assetPath) {
        Bitmap bitmap = AssetLoader.decodeBitmap(mContext, assetPath);
        if (bitmap == null) {
            return 0;
        }

        int[] texture = new int[1];
        GLES30.glGenTextures(1, texture, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture[0]);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0);
        bitmap.recycle();
        return texture[0];
    }

    /**
     * 把白底黑图的河床遮罩上传成单通道纹理（GL_R8）。
     * 解码与条带化的细节见 {@link AssetLoader#decodeMask}。
     */
    private int loadMaskTexture(String assetPath) {
        AssetLoader.Mask mask = AssetLoader.decodeMask(mContext, assetPath);
        if (mask == null || mask.pixels.length == 0) {
            return 0;
        }

        ByteBuffer buf = ByteBuffer.allocateDirect(mask.pixels.length).order(ByteOrder.nativeOrder());
        buf.put(mask.pixels).position(0);

        int[] tex = new int[1];
        GLES30.glGenTextures(1, tex, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0]);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        // 单字节/像素：行对齐按 4 字节算的话，宽度不是 4 的倍数时行会错位
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, mask.width, mask.height, 0,
                GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, buf);
        return tex[0];
    }

    /** 1×1 全白遮罩：贴图缺失时的退路，效果是整屏天空。 */
    private int createSolidMaskTexture() {
        int[] tex = new int[1];
        GLES30.glGenTextures(1, tex, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0]);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        ByteBuffer buf = ByteBuffer.allocateDirect(1).order(ByteOrder.nativeOrder());
        buf.put((byte) 255).position(0);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, 1, 1, 0,
                GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, buf);
        return tex[0];
    }

    /**
     * 从 pond_sky_fields.txt 的四段色场各生成一条 24×64 的竖直天空色带
     * （清晨 / 白日 / 黄昏 / 夜晚，见 {@link #SKY_SECTIONS}）。
     *
     * <p>只有黄昏那一段是原版就有的，其余三段是日夜变换新加的。
     */
    private void loadPondSkyTextures() {
        String text = AssetLoader.readText(mContext, "fall/data/pond_sky_fields.txt");
        for (int i = 0; i < SKY_SECTIONS.length; i++) {
            int[][] field = SkyField.parseSection(text, SKY_SECTIONS[i]);
            if (field == null) {
                Log.e(TAG, "天空色场解析失败: " + SKY_SECTIONS[i]);
                mSkyTextures[i] = 0;
                continue;
            }
            mSkyTextures[i] = SkyField.createTexture(field, false);
        }
    }

    /** 缺哪段补哪段：少一条色带就会让那一段时间的水面采样到未定义的纹理。 */
    private void ensureSkyTextures() {
        for (int i = 0; i < mSkyTextures.length; i++) {
            if (mSkyTextures[i] == 0) {
                mSkyTextures[i] = createPlaceholderTexture(256, 256, Color.parseColor("#4A6FA5"));
            }
        }
    }

    /** 删掉四条天空色带。名字为 0 的项会被 GL 忽略，不必先挑出有效的。 */
    private void releaseSkyTextures() {
        GLES30.glDeleteTextures(mSkyTextures.length, mSkyTextures, 0);
        Arrays.fill(mSkyTextures, 0);
    }

    private int createPlaceholderTexture(int width, int height, int color) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(color);

        int[] texture = new int[1];
        GLES30.glGenTextures(1, texture, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture[0]);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0);
        bitmap.recycle();
        return texture[0];
    }

    private int[] loadLeafTextures() {
        // 只加载场景真正会用到的那几张：绿叶关掉时是 14，多传 6 张 512² 会白占约 8 MB 显存
        // （含 mipmap）。getLeafTextureCount() 在 ensureResources() 里就已按 prefs 定好。
        int leafCount = MathUtils.clamp(mScene.getLeafTextureCount(), 1, LEAF_TEXTURE_FILES);
        int[] textures = new int[leafCount];
        for (int i = 0; i < leafCount; i++) {
            textures[i] = loadLeafTexture("fall/drawable/leaves_" + i + ".png");
        }
        return textures;
    }

    private int loadLeafTexture(String assetPath) {
        Bitmap bitmap = AssetLoader.decodeBitmap(mContext, assetPath);
        if (bitmap == null) {
            return 0;
        }

        int[] texture = new int[1];
        GLES30.glGenTextures(1, texture, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture[0]);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0);
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D);
        bitmap.recycle();
        return texture[0];
    }

    private int[] createPlaceholderLeafTextures() {
        int[] textures = new int[14];
        int[] colors = {
                Color.parseColor("#8B4513"), Color.parseColor("#A0522D"),
                Color.parseColor("#8B6914"), Color.parseColor("#B8860B"),
                Color.parseColor("#D2691E"), Color.parseColor("#CD853F"),
                Color.parseColor("#DEB887"), Color.parseColor("#F4A460"),
                Color.parseColor("#FF8C00"), Color.parseColor("#FFA500"),
                Color.parseColor("#FFD700"), Color.parseColor("#FFFF00"),
                Color.parseColor("#FF6347"), Color.parseColor("#FF4500")
        };
        for (int i = 0; i < textures.length; i++) {
            textures[i] = createPlaceholderTexture(64, 64, colors[i]);
        }
        return textures;
    }

    private void syncPerfSettingsIfNeeded(long nowMs) {
        if (nowMs - mLastPerfSyncMs < PERF_SYNC_INTERVAL_MS) {
            return;
        }
        mLastPerfSyncMs = nowMs;
        int fps = WallpaperSettings.getGlobalFrameRate(30);
        mTargetFps = Math.max(1, fps);
        mTargetFrameMs = Math.max(1L, 1000L / mTargetFps);
        mAnrDiagEnabled = WallpaperSettings.isVulkanAnrDiagnosticsEnabled(true);
    }

    private void recordFrameCost(long frameCostMs) {
        if (!mAnrDiagEnabled) {
            return;
        }
        if (frameCostMs >= ANR_FRAME_THRESHOLD_MS) {
            Log.w(TAG, "Slow frame: " + frameCostMs + "ms, targetFps=" + mTargetFps);
        }
        mDiagFrameCount++;
        mDiagAccumulatedMs += frameCostMs;
        if (frameCostMs > mDiagMaxMs) {
            mDiagMaxMs = frameCostMs;
        }
        if (mDiagFrameCount >= 120) {
            long avg = mDiagAccumulatedMs / Math.max(1L, mDiagFrameCount);
            Log.i(TAG, "FrameStats avg=" + avg + "ms max=" + mDiagMaxMs + "ms fpsTarget=" + mTargetFps);
            mDiagFrameCount = 0L;
            mDiagAccumulatedMs = 0L;
            mDiagMaxMs = 0L;
        }
    }

}
