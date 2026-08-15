package com.reandroid.wallpaper.fireworks;

import android.content.Context;
import android.opengl.GLES20;
import android.os.SystemClock;
import android.util.Log;

import com.reandroid.utils.AssetLoader;
import com.reandroid.utils.MathUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 烟花壁纸的「草地夜景」背景层（自包含实现，不依赖 grass 壁纸包）。
 *
 * 复刻 grass 壁纸夜间元素：夜空渐变、闪烁星星、随风摆动的草叶。
 * 恒夜：只有夜晚背景 + 草 + 星星，无太阳/月亮/蒲公英/萤火虫/天气。
 * 绘制顺序由 FireworksGL 控制：夜空 → 星星 → 烟花(加法混合) → 草叶，草遮挡烟花。
 *
 * 数据/着色器为 fireworks 包内自有副本（assets/fireworks/...），
 * 逻辑与常量对齐 grass 壁纸夜间行为（D1_CLEAR 晴夜）。
 */
public class FireworksGrassBackdrop {

    private static final String TAG = "FireworksGrassBackdrop";

    // ---- 草叶常量（对齐 grass 壁纸） ----
    private static final int DEFAULT_BLADE_COUNT = 200;
    private static final int DEFAULT_STAR_COUNT = 2048;
    private static final float MAX_BEND = 0.09f;
    private static final float TESSELATION = 0.5f;
    private static final float HALF_TESSELATION = 0.25f;
    // 脏判定基准：上次几何重建时各草叶的角度（高 FPS 下防冻结）
    private static final float ANGLE_DIRTY_EPSILON = 0.00075f;
    private static final int WIND_SAMPLE_COUNT = 64;
    private static final float MIN_SAMPLE_SPAN = 0.0001f;

    // 恒夜风参数（D1_CLEAR + isNight）：
    // windTimeScale=1.0, windAmplitudeScale=1.0, windDayNightScale=0.94
    private static final float NIGHT_WIND_SCALE = 0.94f;
    // 夜间草叶亮度：grass 夜间 simple 模式 newB=0 → 黑色剪影
    private static final float NIGHT_GRASS_BRIGHTNESS = 0.0f;

    // ---- 星星常量（对齐 grass 壁纸） ----
    private static final int STAR_TEXTURE_GROUP_COUNT = 4;
    private static final int STAR_ALPHA_BIN_COUNT = 8;
    private static final int STAR_BATCH_GROUP_COUNT = STAR_TEXTURE_GROUP_COUNT * STAR_ALPHA_BIN_COUNT;
    private static final int FLOATS_PER_STAR = 6 * 4; // 两个三角形 * 3 顶点 * (x,y,u,v)
    private static final int STAR_TINT_WHITE = 0;
    private static final int STAR_TINT_RED = 1;
    private static final int STAR_TINT_BLUE = 2;
    private static final int STAR_TINT_YELLOW = 3;

    // ---- 草叶结构 ----
    private static final class Blade {
        float angle;
        int size;
        float xPos, yPos, offset, scale, lengthX, lengthY, hardness;
        float h, s, b;
        float turbulencex;
    }

    // ---- 风场（Perlin 湍流，移植自 grass 壁纸） ----
    private static final class WindField {
        private static final int B = 0x100;
        private static final int BM = 0xff;
        private static final int N = 0x1000;

        private final int[] p = new int[B + B + 2];
        private final float[][] g2 = new float[B + B + 2][2];

        void init(Random random) {
            for (int i = 0; i < B; i++) {
                p[i] = i;
                g2[i][0] = random.nextFloat() * 2.0f - 1.0f;
                g2[i][1] = random.nextFloat() * 2.0f - 1.0f;
                float len = (float) Math.sqrt(g2[i][0] * g2[i][0] + g2[i][1] * g2[i][1]);
                g2[i][0] /= len;
                g2[i][1] /= len;
            }
            for (int i = B - 1; i >= 0; i--) {
                int j = random.nextInt(B);
                int temp = p[i];
                p[i] = p[j];
                p[j] = temp;
            }
            for (int i = 0; i < B + 2; i++) {
                p[B + i] = p[i];
                g2[B + i][0] = g2[i][0];
                g2[B + i][1] = g2[i][1];
            }
        }

        float turbulencef2(float x, float y, float octaves) {
            float t = 0.0f;
            for (float f = 1.0f; f <= octaves; f *= 2.0f) {
                t += Math.abs(noisef2(f * x, f * y)) / f;
            }
            return t;
        }

        private float noisef2(float x, float y) {
            float t = x + N;
            int bx0 = ((int) t) & BM;
            int bx1 = (bx0 + 1) & BM;
            float rx0 = t - (int) t;
            float rx1 = rx0 - 1.0f;

            t = y + N;
            int by0 = ((int) t) & BM;
            int by1 = (by0 + 1) & BM;
            float ry0 = t - (int) t;
            float ry1 = ry0 - 1.0f;

            int i = p[bx0];
            int j = p[bx1];
            int b00 = p[i + by0];
            int b10 = p[j + by0];
            int b01 = p[i + by1];
            int b11 = p[j + by1];

            float sx = noiseSCurve(rx0);
            float sy = noiseSCurve(ry0);

            float u = rx0 * g2[b00][0] + ry0 * g2[b00][1];
            float v = rx1 * g2[b10][0] + ry0 * g2[b10][1];
            float a = MathUtils.mix(u, v, sx);

            u = rx0 * g2[b01][0] + ry1 * g2[b01][1];
            v = rx1 * g2[b11][0] + ry1 * g2[b11][1];
            float b = MathUtils.mix(u, v, sx);

            return 1.5f * MathUtils.mix(a, b, sy);
        }

        private static float noiseSCurve(float t) {
            return t * t * (3.0f - 2.0f * t);
        }
    }

    // ---- 星星结构（移植自 grass 壁纸） ----
    private static final class Star {
        float xNorm, yNorm, sizePx, baseAlpha;
        float twinklePhase, twinkleSpeed, shiftPhase, shiftSpeed;
        int tintType;
    }

    // ---- 配置（跟随 grass 壁纸设置，默认值与 grass 布局一致） ----
    private int mBladeCount = DEFAULT_BLADE_COUNT;
    private float mGrassHeightScale = 1.0f;
    private float mGrassWidthScale = 1.0f;
    private float mGrassHardnessScale = 1.0f;
    private int mStarCount = DEFAULT_STAR_COUNT;

    // ---- 状态 ----
    private final Random mRandom = new Random(System.currentTimeMillis());
    private final WindField mWindField = new WindField();
    private Blade[] mBlades;
    private int[] mBladeSizes;
    private int mVertexCount;
    private int mIndexCount;
    private float[] mRenderedAngles;
    private Star[] mStars;
    private int mWidth;
    private int mHeight;
    private float mXDraw;
    private boolean mGrassGeometryDirty = true;
    private boolean mGLReady;
    private Context mContext;

    // ---- GL 资源 ----
    private int mQuadProgram; // 夜空/星星共用（fireworks 自带着色器）
    private int mQuadPositionHandle;
    private int mQuadTexHandle;
    private int mQuadMatrixHandle;
    private int mQuadSamplerHandle;
    private int mQuadAlphaHandle;
    private int mQuadColorHandle;

    private int mBladeProgram; // 草叶（逐顶点颜色）
    private int mBladePositionHandle;
    private int mBladeColorHandle;
    private int mBladeTexHandle;
    private int mBladeMatrixHandle;
    private int mBladeSamplerHandle;

    private int mTexNight;
    private int mTexAA;
    private int mTexStarWhite;
    private int mTexStarWarm;
    private int mTexStarCool;
    private int mTexStarYellow;

    private FloatBuffer mQuadBuffer;
    private final float[][] mStarBatchVertices = new float[STAR_BATCH_GROUP_COUNT][];
    private final int[] mStarBatchFloatCounts = new int[STAR_BATCH_GROUP_COUNT];
    private FloatBuffer mBladeVertexBuffer;
    private ShortBuffer mBladeIndexBuffer;
    private float[] mBladeVertexArray;
    private int mBladeVertexFloatCount;
    private float mLastXDraw;
    private float mStarTimeSec;

    public boolean isReady() {
        return mGLReady;
    }

    /**
     * 应用 grass 壁纸的配置（草数量/高宽硬缩放/星星数量）。
     * 数值与 grass 壁纸设置页语义一致：百分比/100，缩放范围同 GrassScene。
     */
    public void setConfig(int bladeCount, float heightScale, float widthScale,
                          float hardnessScale, int starCount) {
        bladeCount = MathUtils.clamp(bladeCount, 1, 1000);
        heightScale = MathUtils.clamp(heightScale, 0.1f, 10.0f);
        widthScale = MathUtils.clamp(widthScale, 0.1f, 10.0f);
        hardnessScale = MathUtils.clamp(hardnessScale, 0.3f, 10.0f);
        starCount = MathUtils.clamp(starCount, 0, 10000);

        boolean countChanged = bladeCount != mBladeCount;
        boolean scaleChanged = heightScale != mGrassHeightScale
                || widthScale != mGrassWidthScale
                || hardnessScale != mGrassHardnessScale;
        boolean starChanged = starCount != mStarCount;

        mBladeCount = bladeCount;
        mGrassHeightScale = heightScale;
        mGrassWidthScale = widthScale;
        mGrassHardnessScale = hardnessScale;
        mStarCount = starCount;

        if (mGLReady && countChanged) {
            // 草数量变化：重建草叶几何（风场保留）
            initBlades();
            buildGrassBuffers();
            mRenderedAngles = null;
            mGrassGeometryDirty = true;
        }
        if (mGLReady && scaleChanged) {
            mGrassGeometryDirty = true;
        }
        if (mGLReady && starChanged) {
            ensureStars(mWidth, mHeight, mStarCount);
        }
    }

    public void initGL(Context context, int width, int height) {
        mContext = context;
        mWidth = width;
        mHeight = height;

        String quadVs = AssetLoader.readText(context, "fireworks/shaders/GLES/fireworks_vs.glsl");
        String quadFs = AssetLoader.readText(context, "fireworks/shaders/GLES/fireworks_fs.glsl");
        mQuadProgram = createProgram(quadVs, quadFs);
        mQuadPositionHandle = GLES20.glGetAttribLocation(mQuadProgram, "aPosition");
        mQuadTexHandle = GLES20.glGetAttribLocation(mQuadProgram, "aTexCoord");
        mQuadMatrixHandle = GLES20.glGetUniformLocation(mQuadProgram, "uMVPMatrix");
        mQuadSamplerHandle = GLES20.glGetUniformLocation(mQuadProgram, "uSampler");
        mQuadAlphaHandle = GLES20.glGetUniformLocation(mQuadProgram, "uAlpha");
        mQuadColorHandle = GLES20.glGetUniformLocation(mQuadProgram, "uColor");

        String bladeVs = AssetLoader.readText(context, "fireworks/shaders/GLES/grass_blade_vs.glsl");
        String bladeFs = AssetLoader.readText(context, "fireworks/shaders/GLES/grass_blade_fs.glsl");
        mBladeProgram = createProgram(bladeVs, bladeFs);
        mBladePositionHandle = GLES20.glGetAttribLocation(mBladeProgram, "aPosition");
        mBladeColorHandle = GLES20.glGetAttribLocation(mBladeProgram, "aColor");
        mBladeTexHandle = GLES20.glGetAttribLocation(mBladeProgram, "aTexCoord");
        mBladeMatrixHandle = GLES20.glGetUniformLocation(mBladeProgram, "uMVPMatrix");
        mBladeSamplerHandle = GLES20.glGetUniformLocation(mBladeProgram, "uSampler");

        // 夜空渐变纹理（自包含数据文件）
        String skyText = AssetLoader.readText(context, "fireworks/data/sky_field_night.txt");
        int[][] nightField = parseSkyFieldSection(skyText, "SKY_FIELD_NIGHT");
        mTexNight = createSkyFieldTexture(nightField, true);
        mTexAA = createAlphaTexture();
        mTexStarWhite = createSolidColorTexture((byte) 255, (byte) 255, (byte) 255, (byte) 255);
        mTexStarWarm = createSolidColorTexture((byte) 255, (byte) 168, (byte) 152, (byte) 255);
        mTexStarCool = createSolidColorTexture((byte) 158, (byte) 202, (byte) 255, (byte) 255);
        mTexStarYellow = createSolidColorTexture((byte) 255, (byte) 238, (byte) 170, (byte) 255);

        // 草叶
        mWindField.init(mRandom);
        initBlades();
        buildGrassBuffers();

        // 星星（数量跟随 grass 壁纸设置）
        ensureStars(width, height, mStarCount);

        mGLReady = true;
    }

    public void resize(int width, int height) {
        mWidth = width;
        mHeight = height;
        if (mBlades == null) return;
        updateBladePositionsForViewport();
        ensureStars(width, height, mStarCount);
        mGrassGeometryDirty = true;
    }

    /**
     * 每帧更新：草叶角度（风场）、滚动偏移、星星时间。
     * @param animNowMs 动画时间（uptime 基准）
     * @param xOffset   桌面滚动偏移（预览固定 0.5，与 grass 壁纸一致）
     */
    public void update(long animNowMs, float xOffset) {
        if (mBlades == null) return;

        // 恒夜晴空：windTimeScale=1.0 × windDayNightScale(夜)=0.94
        float noiseNow = SystemClock.uptimeMillis() * 0.00004f * NIGHT_WIND_SCALE;
        boolean bladeAnglesDirty = updateBladeAngles(noiseNow, NIGHT_WIND_SCALE);
        // 上一帧的脏标记已被几何重建消费：记录新角度基线（防高 FPS 冻结）
        if (mGrassGeometryDirty) {
            markAnglesRendered();
        }
        mGrassGeometryDirty = bladeAnglesDirty;
        mXDraw = MathUtils.mix(mWidth, 0.0f, xOffset);
        mStarTimeSec = animNowMs * 0.001f;
    }

    /** 夜空渐变（标准 Alpha 混合）。 */
    public void drawSky(float[] projection) {
        if (!mGLReady || mTexNight == 0) return;
        GLES20.glUseProgram(mQuadProgram);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glUniformMatrix4fv(mQuadMatrixHandle, 1, false, projection, 0);
        GLES20.glUniform1f(mQuadAlphaHandle, 1.0f);
        GLES20.glUniform3f(mQuadColorHandle, 1.0f, 1.0f, 1.0f);

        // 与原版一致：全屏四边，垂直方向 -32px 上扩，UV 横向 0..2 无缝衔接
        float[] verts = {
                0.0f, -32.0f, 0.0f, 0.0f,
                0.0f, mHeight, 0.0f, 1.0f,
                mWidth, mHeight, 2.0f, 1.0f,
                mWidth, -32.0f, 2.0f, 0.0f
        };
        drawQuad(verts, mTexNight);
    }

    /** 闪烁星星（标准 Alpha 混合，恒夜 starVisibility=1）。 */
    public void drawStars(float[] projection) {
        if (!mGLReady || mStars == null || mTexStarWhite == 0) return;
        GLES20.glUseProgram(mQuadProgram);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glUniformMatrix4fv(mQuadMatrixHandle, 1, false, projection, 0);
        GLES20.glUniform3f(mQuadColorHandle, 1.0f, 1.0f, 1.0f);

        clearBatchCounters();
        float t = mStarTimeSec;
        for (Star s : mStars) {
            float twinkle = 0.65f + 0.35f * (float) Math.sin((t * s.twinkleSpeed) + s.twinklePhase);
            float alpha = MathUtils.clamp(s.baseAlpha * twinkle, 0.03f, 0.82f);
            float shift = 0.5f + 0.5f * (float) Math.sin((t * s.shiftSpeed) + s.shiftPhase);
            int textureGroup = textureGroupForTint(s.tintType, shift);
            int alphaBin = (int) (alpha * (STAR_ALPHA_BIN_COUNT - 1) + 0.5f);
            if (alphaBin < 0) alphaBin = 0;
            if (alphaBin >= STAR_ALPHA_BIN_COUNT) alphaBin = STAR_ALPHA_BIN_COUNT - 1;
            int group = (textureGroup * STAR_ALPHA_BIN_COUNT) + alphaBin;
            appendStarQuad(group, s.xNorm * mWidth, s.yNorm * mHeight, s.sizePx);
        }

        for (int textureGroup = 0; textureGroup < STAR_TEXTURE_GROUP_COUNT; textureGroup++) {
            int texture = textureForGroup(textureGroup);
            for (int alphaBin = 0; alphaBin < STAR_ALPHA_BIN_COUNT; alphaBin++) {
                int group = (textureGroup * STAR_ALPHA_BIN_COUNT) + alphaBin;
                int floatCount = mStarBatchFloatCounts[group];
                if (floatCount <= 0) continue;
                drawStarBatch(texture, mStarBatchVertices[group], floatCount,
                        alphaBin / (float) (STAR_ALPHA_BIN_COUNT - 1));
            }
        }
    }

    /** 草叶（标准 Alpha 混合，最后绘制遮挡烟花）。 */
    public void drawGrass(float[] projection) {
        if (!mGLReady || mBlades == null || mBladeVertexBuffer == null) return;

        // 角度或滚动偏移变化时才重建几何并上传
        if (mGrassGeometryDirty || mXDraw != mLastXDraw || mBladeVertexArray == null) {
            rebuildBladeVertexArray();
            mBladeVertexBuffer.clear();
            mBladeVertexBuffer.put(mBladeVertexArray, 0, mBladeVertexFloatCount).position(0);
            mLastXDraw = mXDraw;
        }

        GLES20.glUseProgram(mBladeProgram);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glUniformMatrix4fv(mBladeMatrixHandle, 1, false, projection, 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTexAA);
        GLES20.glUniform1i(mBladeSamplerHandle, 0);

        mBladeVertexBuffer.position(0);
        GLES20.glEnableVertexAttribArray(mBladePositionHandle);
        GLES20.glVertexAttribPointer(mBladePositionHandle, 2, GLES20.GL_FLOAT, false, 32, mBladeVertexBuffer);
        mBladeVertexBuffer.position(2);
        GLES20.glEnableVertexAttribArray(mBladeColorHandle);
        GLES20.glVertexAttribPointer(mBladeColorHandle, 4, GLES20.GL_FLOAT, false, 32, mBladeVertexBuffer);
        mBladeVertexBuffer.position(6);
        GLES20.glEnableVertexAttribArray(mBladeTexHandle);
        GLES20.glVertexAttribPointer(mBladeTexHandle, 2, GLES20.GL_FLOAT, false, 32, mBladeVertexBuffer);

        mBladeIndexBuffer.position(0);
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, mIndexCount, GLES20.GL_UNSIGNED_SHORT, mBladeIndexBuffer);

        GLES20.glDisableVertexAttribArray(mBladePositionHandle);
        GLES20.glDisableVertexAttribArray(mBladeColorHandle);
        GLES20.glDisableVertexAttribArray(mBladeTexHandle);
    }

    public void release() {
        if (mQuadProgram != 0) {
            GLES20.glDeleteProgram(mQuadProgram);
            mQuadProgram = 0;
        }
        if (mBladeProgram != 0) {
            GLES20.glDeleteProgram(mBladeProgram);
            mBladeProgram = 0;
        }
        int[] tex = new int[]{mTexNight, mTexAA, mTexStarWhite, mTexStarWarm, mTexStarCool, mTexStarYellow};
        GLES20.glDeleteTextures(tex.length, tex, 0);
        mTexNight = 0;
        mTexAA = 0;
        mTexStarWhite = 0;
        mTexStarWarm = 0;
        mTexStarCool = 0;
        mTexStarYellow = 0;
        mBladeVertexBuffer = null;
        mBladeIndexBuffer = null;
        mGLReady = false;
    }

    // ---- 草叶逻辑（移植自 grass 壁纸） ----

    private void initBlades() {
        mBlades = new Blade[mBladeCount];
        mBladeSizes = new int[mBladeCount];
        mVertexCount = 0;
        mIndexCount = 0;
        for (int i = 0; i < mBladeCount; i++) {
            Blade blade = new Blade();
            createBlade(blade);
            mBlades[i] = blade;
            mBladeSizes[i] = blade.size;
            mIndexCount += blade.size * 2 * 3;
            mVertexCount += blade.size + 2;
        }
    }

    private void buildGrassBuffers() {
        // 顶点数 = (size+2)*2（每段 2 顶点，与 grass 壁纸一致）
        int vertexTotal = mVertexCount * 2;
        mBladeVertexArray = new float[vertexTotal * 8];
        mBladeVertexFloatCount = 0;
        mBladeVertexBuffer = ByteBuffer.allocateDirect(vertexTotal * 8 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();

        short[] idx = buildGrassIndexArray();
        mBladeIndexBuffer = ByteBuffer.allocateDirect(idx.length * 2)
                .order(ByteOrder.nativeOrder()).asShortBuffer();
        mBladeIndexBuffer.put(idx).position(0);
    }

    private short[] buildGrassIndexArray() {
        short[] idx = new short[mIndexCount];
        int idxIdx = 0;
        int vtxIdx = 0;
        for (int size : mBladeSizes) {
            for (int ct = 0; ct < size; ct++) {
                idx[idxIdx] = (short) (vtxIdx);
                idx[idxIdx + 1] = (short) (vtxIdx + 1);
                idx[idxIdx + 2] = (short) (vtxIdx + 2);
                idx[idxIdx + 3] = (short) (vtxIdx + 1);
                idx[idxIdx + 4] = (short) (vtxIdx + 3);
                idx[idxIdx + 5] = (short) (vtxIdx + 2);
                idxIdx += 6;
                vtxIdx += 2;
            }
            vtxIdx += 2;
        }
        return idx;
    }

    private void updateBladePositionsForViewport() {
        for (Blade blade : mBlades) {
            float xpos = random(-mWidth, mWidth);
            blade.xPos = xpos;
            blade.turbulencex = xpos * 0.006f;
            blade.yPos = mHeight;
        }
    }

    private boolean updateBladeAngles(float noiseNow, float windAmplitudeScale) {
        if (mBlades == null || mBlades.length == 0) return false;

        float minTx = Float.MAX_VALUE;
        float maxTx = -Float.MAX_VALUE;
        for (Blade blade : mBlades) {
            if (blade.turbulencex < minTx) minTx = blade.turbulencex;
            if (blade.turbulencex > maxTx) maxTx = blade.turbulencex;
        }

        float span = maxTx - minTx;
        float sampleStep;
        float[] windSamples = new float[WIND_SAMPLE_COUNT];
        if (span < MIN_SAMPLE_SPAN) {
            float sample = mWindField.turbulencef2(minTx, noiseNow, 4.0f);
            for (int i = 0; i < WIND_SAMPLE_COUNT; i++) {
                windSamples[i] = sample;
            }
            sampleStep = 1.0f;
        } else {
            sampleStep = span / (WIND_SAMPLE_COUNT - 1);
            for (int i = 0; i < WIND_SAMPLE_COUNT; i++) {
                float x = minTx + i * sampleStep;
                windSamples[i] = mWindField.turbulencef2(x, noiseNow, 4.0f);
            }
        }

        boolean dirty = false;
        boolean noRenderBaseline = mRenderedAngles == null || mRenderedAngles.length != mBlades.length;
        for (int i = 0; i < mBlades.length; i++) {
            Blade blade = mBlades[i];

            float noiseValue;
            if (span < MIN_SAMPLE_SPAN) {
                noiseValue = windSamples[0];
            } else {
                float t = (blade.turbulencex - minTx) / sampleStep;
                int idx = (int) t;
                if (idx < 0) idx = 0;
                if (idx >= WIND_SAMPLE_COUNT - 1) idx = WIND_SAMPLE_COUNT - 2;
                float frac = t - idx;
                noiseValue = windSamples[idx] + (windSamples[idx + 1] - windSamples[idx]) * frac;
            }

            float newAngle = (noiseValue - 0.5f) * 0.5f * windAmplitudeScale;
            blade.angle = MathUtils.clamp(blade.angle + (newAngle + blade.offset - blade.angle) * 0.15f,
                    -MAX_BEND, MAX_BEND);
            if (!dirty && (noRenderBaseline
                    || Math.abs(blade.angle - mRenderedAngles[i]) >= ANGLE_DIRTY_EPSILON)) {
                dirty = true;
            }
        }
        return dirty;
    }

    /** 几何重建后调用：记录当前角度作为下一轮脏判定基准。 */
    private void markAnglesRendered() {
        if (mBlades == null) return;
        if (mRenderedAngles == null || mRenderedAngles.length != mBlades.length) {
            mRenderedAngles = new float[mBlades.length];
        }
        for (int i = 0; i < mBlades.length; i++) {
            mRenderedAngles[i] = mBlades[i].angle;
        }
    }

    private void createBlade(Blade blade) {
        float size = random(4.0f) + 4.0f;
        float xpos = random(-mWidth, mWidth);
        blade.angle = 0.0f;
        blade.size = (int) (size / TESSELATION);
        blade.xPos = xpos;
        blade.yPos = mHeight;
        blade.offset = random(0.2f) - 0.1f;
        blade.scale = 4.0f / (size / TESSELATION) + (random(0.6f) + 0.2f) * TESSELATION;
        blade.lengthX = (random(4.5f) + 3.0f) * TESSELATION * size;
        blade.lengthY = (random(5.5f) + 2.0f) * TESSELATION * size;
        blade.hardness = (random(1.0f) + 0.2f) * TESSELATION;
        blade.h = random(0.02f) + 0.2f;
        blade.s = random(0.22f) + 0.78f;
        blade.b = random(0.65f) + 0.35f;
        blade.turbulencex = xpos * 0.006f;
    }

    private void rebuildBladeVertexArray() {
        float[] out = mBladeVertexArray;
        int cursor = 0;
        for (Blade blade : mBlades) {
            cursor = appendBladeVertices(blade, out, cursor);
        }
        mBladeVertexFloatCount = cursor;
    }

    private int appendBladeVertices(Blade blade, float[] out, int cursor) {
        float scale = blade.scale * mGrassWidthScale;
        float angle = blade.angle;
        float xpos = blade.xPos + mXDraw;
        int size = blade.size;

        // 恒夜：brightness=0 → v=0 黑色剪影；无 tint/去饱和
        float h = blade.h;
        float s = blade.s;
        float v = MathUtils.mix(0.0f, blade.b, NIGHT_GRASS_BRIGHTNESS);
        int color = MathUtils.hsbToRgb(h, s, v);
        float r = android.graphics.Color.red(color) / 255.0f;
        float g = android.graphics.Color.green(color) / 255.0f;
        float b = android.graphics.Color.blue(color) / 255.0f;

        float bottomX = xpos;
        float bottomY = blade.yPos;
        float d = angle * blade.hardness * mGrassHardnessScale;
        float stepCos = (float) Math.cos(d);
        float stepSin = (float) Math.sin(d);
        float currentCos = 0.0f;
        float currentSin = 1.0f;

        float si = size * scale;
        cursor = putVertex(out, cursor, bottomX - si, bottomY + HALF_TESSELATION, r, g, b, 1.0f, 0.0f, 0.0f);
        cursor = putVertex(out, cursor, bottomX + si, bottomY + HALF_TESSELATION, r, g, b, 1.0f, 1.0f, 0.0f);

        for (; size > 0; size--) {
            float lengthX = blade.lengthX * mGrassHeightScale;
            float lengthY = blade.lengthY * mGrassHeightScale;
            float topX = bottomX - currentCos * lengthX;
            float topY = bottomY - currentSin * lengthY;
            si = size * scale;
            float spi = si - scale;
            cursor = putVertex(out, cursor, topX - spi, topY, r, g, b, 1.0f, 0.0f, 0.0f);
            cursor = putVertex(out, cursor, topX + spi, topY, r, g, b, 1.0f, 1.0f, 0.0f);
            bottomX = topX;
            bottomY = topY;
            float nextCos = currentCos * stepCos - currentSin * stepSin;
            float nextSin = currentSin * stepCos + currentCos * stepSin;
            currentCos = nextCos;
            currentSin = nextSin;
        }
        return cursor;
    }

    private int putVertex(float[] out, int cursor,
            float x, float y, float r, float g, float b, float a, float s, float t) {
        if (cursor + 8 > out.length) return out.length + 1;
        out[cursor++] = x;
        out[cursor++] = y;
        out[cursor++] = r;
        out[cursor++] = g;
        out[cursor++] = b;
        out[cursor++] = a;
        out[cursor++] = s;
        out[cursor++] = t;
        return cursor;
    }

    // ---- 星星逻辑（移植自 grass 壁纸） ----

    private int mLastStarWidth = -1;
    private int mLastStarHeight = -1;

    private void ensureStars(int width, int height, int count) {
        int c = MathUtils.clamp(count, 0, 10000);
        if (width == mLastStarWidth && height == mLastStarHeight
                && mStars != null && mStars.length == c) {
            return;
        }
        mLastStarWidth = width;
        mLastStarHeight = height;
        mStars = new Star[c];

        long seed = (((long) width) << 32) ^ (height * 1103515245L + 12345L);
        Random random = new Random(seed);

        for (int i = 0; i < c; i++) {
            Star s = new Star();
            s.xNorm = random.nextFloat();
            float y = random.nextFloat();
            s.yNorm = MathUtils.clamp((float) Math.pow(y, 1.15f), 0.0f, 1.0f);
            s.sizePx = 1.2f + random.nextFloat() * 2.2f;
            s.baseAlpha = 0.22f + random.nextFloat() * 0.58f;
            s.twinklePhase = random.nextFloat() * 6.2831855f;
            s.twinkleSpeed = 0.7f + random.nextFloat() * 2.2f;
            s.shiftPhase = random.nextFloat() * 6.2831855f;
            s.shiftSpeed = 0.12f + random.nextFloat() * 0.38f;
            s.tintType = pickTint(random);
            mStars[i] = s;
        }
    }

    private static int pickTint(Random random) {
        float r = random.nextFloat();
        if (r < 0.86f) return STAR_TINT_WHITE;
        if (r < 0.91f) return STAR_TINT_RED;
        if (r < 0.95f) return STAR_TINT_BLUE;
        return STAR_TINT_YELLOW;
    }

    private int textureGroupForTint(int tintType, float shift) {
        switch (tintType) {
            case STAR_TINT_RED:
                return shift > 0.6f ? 1 : 3;
            case STAR_TINT_BLUE:
                return shift > 0.45f ? 2 : 0;
            case STAR_TINT_YELLOW:
                return shift > 0.35f ? 3 : 0;
            case STAR_TINT_WHITE:
            default:
                return 0;
        }
    }

    private int textureForGroup(int group) {
        switch (group) {
            case 1:
                return mTexStarWarm;
            case 2:
                return mTexStarCool;
            case 3:
                return mTexStarYellow;
            case 0:
            default:
                return mTexStarWhite;
        }
    }

    private void clearBatchCounters() {
        for (int i = 0; i < mStarBatchFloatCounts.length; i++) {
            mStarBatchFloatCounts[i] = 0;
        }
    }

    private void appendStarQuad(int group, float cx, float cy, float size) {
        ensureGroupCapacity(group, FLOATS_PER_STAR);
        float[] out = mStarBatchVertices[group];
        int cursor = mStarBatchFloatCounts[group];

        float half = size * 0.5f;
        float x0 = cx - half;
        float y0 = cy - half;
        float x1 = cx - half;
        float y1 = cy + half;
        float x2 = cx + half;
        float y2 = cy + half;
        float x3 = cx + half;
        float y3 = cy - half;

        cursor = putStarVertex(out, cursor, x0, y0, 0.0f, 1.0f);
        cursor = putStarVertex(out, cursor, x1, y1, 0.0f, 0.0f);
        cursor = putStarVertex(out, cursor, x2, y2, 1.0f, 0.0f);

        cursor = putStarVertex(out, cursor, x0, y0, 0.0f, 1.0f);
        cursor = putStarVertex(out, cursor, x2, y2, 1.0f, 0.0f);
        cursor = putStarVertex(out, cursor, x3, y3, 1.0f, 1.0f);

        mStarBatchFloatCounts[group] = cursor;
    }

    private int putStarVertex(float[] out, int cursor, float x, float y, float u, float v) {
        out[cursor++] = x;
        out[cursor++] = y;
        out[cursor++] = u;
        out[cursor++] = v;
        return cursor;
    }

    private void ensureGroupCapacity(int group, int appendFloatCount) {
        int required = mStarBatchFloatCounts[group] + appendFloatCount;
        float[] current = mStarBatchVertices[group];
        if (current != null && current.length >= required) {
            return;
        }

        int newSize = current == null ? 4096 : current.length;
        while (newSize < required) {
            newSize *= 2;
        }

        float[] expanded = new float[newSize];
        if (current != null && mStarBatchFloatCounts[group] > 0) {
            System.arraycopy(current, 0, expanded, 0, mStarBatchFloatCounts[group]);
        }
        mStarBatchVertices[group] = expanded;
    }

    private void drawStarBatch(int texture, float[] vertices, int floatCount, float alpha) {
        if (vertices == null || floatCount <= 0 || mQuadBuffer == null) return;
        if (mQuadBuffer.capacity() < floatCount) {
            mQuadBuffer = ByteBuffer.allocateDirect(floatCount * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
        }
        mQuadBuffer.clear();
        mQuadBuffer.put(vertices, 0, floatCount).position(0);

        GLES20.glEnableVertexAttribArray(mQuadPositionHandle);
        GLES20.glVertexAttribPointer(mQuadPositionHandle, 2, GLES20.GL_FLOAT, false, 16, mQuadBuffer);
        mQuadBuffer.position(2);
        GLES20.glEnableVertexAttribArray(mQuadTexHandle);
        GLES20.glVertexAttribPointer(mQuadTexHandle, 2, GLES20.GL_FLOAT, false, 16, mQuadBuffer);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glUniform1i(mQuadSamplerHandle, 0);
        GLES20.glUniform1f(mQuadAlphaHandle, alpha);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, floatCount / 4);

        GLES20.glDisableVertexAttribArray(mQuadPositionHandle);
        GLES20.glDisableVertexAttribArray(mQuadTexHandle);
    }

    private void drawQuad(float[] verts, int texture) {
        if (mQuadBuffer == null || mQuadBuffer.capacity() < 16) {
            mQuadBuffer = ByteBuffer.allocateDirect(4 * 4 * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
        }
        mQuadBuffer.clear();
        mQuadBuffer.put(verts).position(0);

        GLES20.glEnableVertexAttribArray(mQuadPositionHandle);
        GLES20.glVertexAttribPointer(mQuadPositionHandle, 2, GLES20.GL_FLOAT, false, 16, mQuadBuffer);
        mQuadBuffer.position(2);
        GLES20.glEnableVertexAttribArray(mQuadTexHandle);
        GLES20.glVertexAttribPointer(mQuadTexHandle, 2, GLES20.GL_FLOAT, false, 16, mQuadBuffer);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glUniform1i(mQuadSamplerHandle, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4);

        GLES20.glDisableVertexAttribArray(mQuadPositionHandle);
        GLES20.glDisableVertexAttribArray(mQuadTexHandle);
    }

    // ---- 纹理工具（自包含，与 grass 壁纸同逻辑） ----

    private static int[][] parseSkyFieldSection(String allText, String sectionName) {
        if (allText == null) return null;
        String marker = "[" + sectionName + "]";
        int start = allText.indexOf(marker);
        if (start < 0) return null;
        int bodyStart = start + marker.length();
        int next = allText.indexOf("[", bodyStart);
        String body = (next > bodyStart) ? allText.substring(bodyStart, next) : allText.substring(bodyStart);

        List<int[]> cols = new ArrayList<>();
        int depth = 0;
        int rowStart = -1;
        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (ch == '{') {
                depth++;
                if (depth == 2) rowStart = i + 1;
            } else if (ch == '}') {
                if (depth == 2 && rowStart >= 0) {
                    String row = body.substring(rowStart, i).trim();
                    if (!row.isEmpty() && row.contains("0x")) {
                        String[] parts = row.split(",");
                        int[] values = new int[parts.length];
                        for (int p = 0; p < parts.length; p++)
                            values[p] = Long.decode(parts[p].trim()).intValue();
                        cols.add(values);
                    }
                    rowStart = -1;
                }
                depth--;
            }
        }
        if (cols.isEmpty()) return null;
        return cols.toArray(new int[0][]);
    }

    private static int createSkyFieldTexture(int[][] fieldColors, boolean repeatS) {
        if (fieldColors == null || fieldColors.length == 0 || fieldColors[0].length == 0) return 0;

        final int cols = fieldColors.length;
        final int rows = fieldColors[0].length;
        final int targetW = 24;
        final int targetH = 64;
        byte[] rgba = new byte[targetW * targetH * 4];

        for (int y = 0; y < targetH; y++) {
            float v = y / (float) (targetH - 1);
            float srcY = v * (rows - 1);
            int y0 = Math.max(0, Math.min(rows - 1, (int) Math.floor(srcY)));
            int y1 = Math.min(rows - 1, y0 + 1);
            float ty = srcY - y0;
            for (int x = 0; x < targetW; x++) {
                float u = x / (float) (targetW - 1);
                float srcX = u * (cols - 1);
                int x0 = Math.max(0, Math.min(cols - 1, (int) Math.floor(srcX)));
                int x1 = Math.min(cols - 1, x0 + 1);
                float tx = srcX - x0;

                int c00 = fieldColors[x0][y0], c10 = fieldColors[x1][y0];
                int c01 = fieldColors[x0][y1], c11 = fieldColors[x1][y1];

                int r = Math.round(MathUtils.lerp(MathUtils.lerp(android.graphics.Color.red(c00), android.graphics.Color.red(c10), tx),
                        MathUtils.lerp(android.graphics.Color.red(c01), android.graphics.Color.red(c11), tx), ty));
                int g = Math.round(MathUtils.lerp(MathUtils.lerp(android.graphics.Color.green(c00), android.graphics.Color.green(c10), tx),
                        MathUtils.lerp(android.graphics.Color.green(c01), android.graphics.Color.green(c11), tx), ty));
                int b = Math.round(MathUtils.lerp(MathUtils.lerp(android.graphics.Color.blue(c00), android.graphics.Color.blue(c10), tx),
                        MathUtils.lerp(android.graphics.Color.blue(c01), android.graphics.Color.blue(c11), tx), ty));
                int a = Math.round(MathUtils.lerp(MathUtils.lerp(android.graphics.Color.alpha(c00), android.graphics.Color.alpha(c10), tx),
                        MathUtils.lerp(android.graphics.Color.alpha(c01), android.graphics.Color.alpha(c11), tx), ty));

                int idx = (y * targetW + x) * 4;
                rgba[idx] = (byte) r;
                rgba[idx + 1] = (byte) g;
                rgba[idx + 2] = (byte) b;
                rgba[idx + 3] = (byte) a;
            }
        }

        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                repeatS ? GLES20.GL_REPEAT : GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        ByteBuffer buf = ByteBuffer.allocateDirect(rgba.length).order(ByteOrder.nativeOrder());
        buf.put(rgba).position(0);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, targetW, targetH, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf);
        return tex[0];
    }

    /** 草叶抗锯齿 alpha 纹理（4x1 mipmap）。 */
    private static int createAlphaTexture() {
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT);
        byte[] mip0 = new byte[]{0, (byte) 255, (byte) 255, 0};
        byte[] mip1 = new byte[]{64, 64};
        byte[] mip2 = new byte[]{0};
        ByteBuffer b0 = ByteBuffer.allocateDirect(mip0.length).order(ByteOrder.nativeOrder());
        b0.put(mip0).position(0);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_ALPHA, 4, 1, 0,
                GLES20.GL_ALPHA, GLES20.GL_UNSIGNED_BYTE, b0);
        ByteBuffer b1 = ByteBuffer.allocateDirect(mip1.length).order(ByteOrder.nativeOrder());
        b1.put(mip1).position(0);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 1, GLES20.GL_ALPHA, 2, 1, 0,
                GLES20.GL_ALPHA, GLES20.GL_UNSIGNED_BYTE, b1);
        ByteBuffer b2 = ByteBuffer.allocateDirect(mip2.length).order(ByteOrder.nativeOrder());
        b2.put(mip2).position(0);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 2, GLES20.GL_ALPHA, 1, 1, 0,
                GLES20.GL_ALPHA, GLES20.GL_UNSIGNED_BYTE, b2);
        return tex[0];
    }

    /** 1x1 纯色纹理（星星配色）。 */
    private static int createSolidColorTexture(byte r, byte g, byte b, byte a) {
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        byte[] rgba = new byte[]{r, g, b, a};
        ByteBuffer buf = ByteBuffer.allocateDirect(rgba.length).order(ByteOrder.nativeOrder());
        buf.put(rgba).position(0);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf);
        return tex[0];
    }

    private int createProgram(String vertexSource, String fragmentSource) {
        int vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        if (vs == 0 || fs == 0) return 0;
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);
        int[] link = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, link, 0);
        if (link[0] == 0) {
            Log.e(TAG, "Program link failed: " + GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            return 0;
        }
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        return program;
    }

    private int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e(TAG, "Shader compile failed: " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    private float random(float range) {
        return mRandom.nextFloat() * range;
    }

    private float random(float min, float max) {
        return min + mRandom.nextFloat() * (max - min);
    }
}
