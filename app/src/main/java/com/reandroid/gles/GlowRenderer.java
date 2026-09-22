package com.reandroid.gles;

import android.opengl.GLES30;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * HDR 中间缓冲 + 辉光。
 *
 * <p>整个场景画进一张浮点纹理，再从中提取亮部、横向纵向各模糊一次、加回画面。
 * 它**不认识 grass** —— 输入是纹理、输出是叠加后的画面；着色器源码由调用方给路径。
 *
 * <p>它解决的问题不是"把截断消掉"（那做不到：要让 {@code v <= 1} 逐像素不变、
 * 又把 {@code v > 1} 压回 {@code <= 1}，那个函数在 {@code v > 1} 上只能是恒等于 1）——
 * 而是让那块白**读成"在发光"而不是"涂白了"**。所以基准色调不动。
 *
 * <p><b>建不出 FBO 就整体退化成空操作</b>，画面与不用它时逐像素相同 ——
 * 增强可以是没有的，但不能把画面弄坏。
 */
public final class GlowRenderer {

    private static final String TAG = "GlowRenderer";

    /** ES3 里 RGBA16F 未必能当颜色附件，所以建完必须验（见 {@link #createFbo}）。 */
    private static final int GL_RGBA16F = 0x881A;
    private static final int GL_HALF_FLOAT = 0x140B;

    /** 全屏四边形：(x, y, u, v) × 4，TRIANGLE_STRIP，已经是 NDC。 */
    private static final float[] QUAD = {
            -1.0f, -1.0f, 0.0f, 0.0f,
             1.0f, -1.0f, 1.0f, 0.0f,
            -1.0f,  1.0f, 0.0f, 1.0f,
             1.0f,  1.0f, 1.0f, 1.0f,
    };

    /** 着色器源码的读取方式由调用方提供 —— 这样它不必知道资源路径的约定。 */
    public interface ShaderSource {
        String read(String assetPath);
    }

    private int mWidth, mHeight, mHalfW, mHalfH;

    /** 记住创建参数，尺寸变化时要按新尺寸原样重建。 */
    private String mVsAsset, mBrightFsAsset, mBlurFsAsset, mCompositeFsAsset;
    private ShaderSource mShaderSource;

    private int mSceneFbo, mSceneTex;
    private int mBloomFboA, mBloomTexA;
    private int mBloomFboB, mBloomTexB;

    private int mQuadVbo;
    private final FloatBuffer mQuadBuffer;
    private final int[] mScratch = new int[1];

    private int mBrightProgram, mBlurProgram, mCompositeProgram;
    private int mBrightPos, mBrightUv, mBlurPos, mBlurUv, mCompositePos, mCompositeUv;
    private int mBrightScene, mBrightTexel, mBrightThreshold, mBrightKnee;
    private int mBlurSource, mBlurStep;
    private int mCompositeScene, mCompositeBloom, mCompositeStrength;

    /** 是否可用。false 时所有绘制方法都是空操作。 */
    private boolean mReady;

    public GlowRenderer() {
        mQuadBuffer = ByteBuffer.allocateDirect(QUAD.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        mQuadBuffer.put(QUAD);
        mQuadBuffer.position(0);
    }

    public boolean isReady() {
        return mReady;
    }

    /**
     * 建缓冲与程序。**任一步失败都返回 false 并释放已建的部分** ——
     * 不留下半建成的状态，半建成会画出花屏，比不生效糟得多。
     */
    public boolean init(int width, int height, String vsAsset,
                        String brightFsAsset, String blurFsAsset, String compositeFsAsset,
                        ShaderSource source) {
        release();
        mVsAsset = vsAsset;
        mBrightFsAsset = brightFsAsset;
        mBlurFsAsset = blurFsAsset;
        mCompositeFsAsset = compositeFsAsset;
        mShaderSource = source;
        mWidth = width;
        mHeight = height;
        mHalfW = GlowParams.halfSize(width);
        mHalfH = GlowParams.halfSize(height);

        mBrightProgram = GlProgramUtils.createProgram(TAG,
                source.read(vsAsset), source.read(brightFsAsset));
        mBlurProgram = GlProgramUtils.createProgram(TAG,
                source.read(vsAsset), source.read(blurFsAsset));
        mCompositeProgram = GlProgramUtils.createProgram(TAG,
                source.read(vsAsset), source.read(compositeFsAsset));
        if (mBrightProgram == 0 || mBlurProgram == 0 || mCompositeProgram == 0) {
            Log.w(TAG, "辉光着色器没建起来，退化成不启用");
            release();
            return false;
        }

        mSceneTex = createTexture(mWidth, mHeight);
        mBloomTexA = createTexture(mHalfW, mHalfH);
        mBloomTexB = createTexture(mHalfW, mHalfH);
        mSceneFbo = createFbo(mSceneTex);
        mBloomFboA = createFbo(mBloomTexA);
        mBloomFboB = createFbo(mBloomTexB);
        if (mSceneTex == 0 || mBloomTexA == 0 || mBloomTexB == 0
                || mSceneFbo == 0 || mBloomFboA == 0 || mBloomFboB == 0) {
            Log.w(TAG, "HDR 缓冲建不起来（RGBA16F 可能不是颜色附件可用的），退化成不启用");
            release();
            return false;
        }

        bindQuadAttributes();
        mReady = true;
        return true;
    }

    private void bindQuadAttributes() {
        GLES30.glGenBuffers(1, mScratch, 0);
        mQuadVbo = mScratch[0];
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, mQuadVbo);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, QUAD.length * 4,
                mQuadBuffer, GLES30.GL_STATIC_DRAW);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);

        mBrightPos = GLES30.glGetAttribLocation(mBrightProgram, "aPosition");
        mBrightUv = GLES30.glGetAttribLocation(mBrightProgram, "aTexCoord");
        mBlurPos = GLES30.glGetAttribLocation(mBlurProgram, "aPosition");
        mBlurUv = GLES30.glGetAttribLocation(mBlurProgram, "aTexCoord");
        mCompositePos = GLES30.glGetAttribLocation(mCompositeProgram, "aPosition");
        mCompositeUv = GLES30.glGetAttribLocation(mCompositeProgram, "aTexCoord");

        mBrightScene = GLES30.glGetUniformLocation(mBrightProgram, "uScene");
        mBrightTexel = GLES30.glGetUniformLocation(mBrightProgram, "uTexel");
        mBrightThreshold = GLES30.glGetUniformLocation(mBrightProgram, "uThreshold");
        mBrightKnee = GLES30.glGetUniformLocation(mBrightProgram, "uSoftKnee");
        mBlurSource = GLES30.glGetUniformLocation(mBlurProgram, "uSource");
        mBlurStep = GLES30.glGetUniformLocation(mBlurProgram, "uStep");
        mCompositeScene = GLES30.glGetUniformLocation(mCompositeProgram, "uScene");
        mCompositeBloom = GLES30.glGetUniformLocation(mCompositeProgram, "uBloom");
        mCompositeStrength = GLES30.glGetUniformLocation(mCompositeProgram, "uStrength");
    }

    private static int createTexture(int w, int h) {
        int[] ids = new int[1];
        GLES30.glGenTextures(1, ids, 0);
        if (ids[0] == 0) {
            return 0;
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0]);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GL_RGBA16F, w, h, 0,
                GLES30.GL_RGBA, GL_HALF_FLOAT, null);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0);
        return ids[0];
    }

    /**
     * 建 FBO 并**当场验证完整性**。
     *
     * <p>不完整返回 0，调用方据此整体退化。ES3 里 {@code RGBA16F} 可采样、
     * 但**默认不保证能作颜色附件** —— 少了这一步，症状是辉光画不出来或画面全黑，
     * 而 GL 不会报任何错。
     */
    private static int createFbo(int texture) {
        int[] ids = new int[1];
        GLES30.glGenFramebuffers(1, ids, 0);
        if (ids[0] == 0) {
            return 0;
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, ids[0]);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, texture, 0);
        int status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            Log.w(TAG, "FBO 不完整，status=0x" + Integer.toHexString(status));
            GLES30.glDeleteFramebuffers(1, ids, 0);
            return 0;
        }
        return ids[0];
    }

    /**
     * 尺寸变化：**按新尺寸重建**。尺寸没变则什么都不做。
     *
     * <p>早先这里是"停用，等下一次 {@link #init} "，理由是"重建路径更容易写出花屏"。
     * <b>那个判断是错的，而且设备当场就揭穿了</b>：实际生命周期是
     * {@code initGL()} **之后**紧接着一次 {@code resize()}，于是这一停用等于
     * 保证辉光永远不会生效 —— 而且不报错，只是画面上什么都没有。
     *
     * <p>重建其实就是重跑一遍 {@code init()}（它开头自己会 {@code release()}），
     * 没有额外的脆弱之处。而尺寸真变了却沿用旧缓冲，画面会被拉伸 —— 那才是真会出问题的。
     */
    public void resize(int width, int height) {
        if (width == mWidth && height == mHeight) {
            return;
        }
        init(width, height, mVsAsset, mBrightFsAsset, mBlurFsAsset, mCompositeFsAsset, mShaderSource);
    }

    public void release() {
        mReady = false;
        int[] fbos = { mSceneFbo, mBloomFboA, mBloomFboB };
        GLES30.glDeleteFramebuffers(3, fbos, 0);
        int[] texs = { mSceneTex, mBloomTexA, mBloomTexB };
        GLES30.glDeleteTextures(3, texs, 0);
        if (mQuadVbo != 0) {
            int[] vbo = { mQuadVbo };
            GLES30.glDeleteBuffers(1, vbo, 0);
        }
        if (mBrightProgram != 0) GLES30.glDeleteProgram(mBrightProgram);
        if (mBlurProgram != 0) GLES30.glDeleteProgram(mBlurProgram);
        if (mCompositeProgram != 0) GLES30.glDeleteProgram(mCompositeProgram);
        mSceneFbo = mBloomFboA = mBloomFboB = 0;
        mSceneTex = mBloomTexA = mBloomTexB = 0;
        mQuadVbo = 0;
        mBrightProgram = mBlurProgram = mCompositeProgram = 0;
    }

    /** 绑定 HDR 缓冲并设视口。未 ready 时是空操作。 */
    public void beginScene() {
        if (!mReady) {
            return;
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, mSceneFbo);
        GLES30.glViewport(0, 0, mWidth, mHeight);
    }

    /**
     * 解绑 → 亮部提取 → 横向模糊 → 纵向模糊 → 加法合成到默认帧缓冲。
     *
     * @param threshold 亮度阈值；低于它的不发辉光
     * @param softKnee  阈值之上的过渡宽度
     * @param radius    模糊半径（像素，半分辨率下）
     * @param strength  辉光叠加强度
     */
    public void endScene(float threshold, float softKnee, float radius, float strength) {
        if (!mReady) {
            return;
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        GLES30.glViewport(0, 0, mWidth, mHeight);
        // **不动混合开关。** 这几遍的输出 alpha 都是 1，而
        // `SRC_ALPHA/ONE_MINUS_SRC_ALPHA` 在 alpha=1 时结果就是 src 本身，
        // 所以加不加混合完全一样。反过来，这里要是调了 glDisable，
        // 就会**绕过调用方的混合状态缓存** —— 下一帧它按缓存判断"没变"而跳过设置，
        // 于是整帧的混合都是关的，背景/太阳/月亮这些靠 alpha 的东西全都变样。

        // 亮部提取 + 降采样 → A
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, mBloomFboA);
        GLES30.glViewport(0, 0, mHalfW, mHalfH);
        useQuad(mBrightProgram, mBrightPos, mBrightUv);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mSceneTex);
        GLES30.glUniform1i(mBrightScene, 0);
        GLES30.glUniform2f(mBrightTexel, 1.0f / mWidth, 1.0f / mHeight);
        GLES30.glUniform1f(mBrightThreshold, threshold);
        GLES30.glUniform1f(mBrightKnee, softKnee);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);

        // 横向 → B
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, mBloomFboB);
        useQuad(mBlurProgram, mBlurPos, mBlurUv);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mBloomTexA);
        GLES30.glUniform1i(mBlurSource, 0);
        GLES30.glUniform2f(mBlurStep, GlowParams.blurStep(mHalfW, radius), 0.0f);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);

        // 纵向 → A
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, mBloomFboA);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mBloomTexB);
        GLES30.glUniform1i(mBlurSource, 0);
        GLES30.glUniform2f(mBlurStep, 0.0f, GlowParams.blurStep(mHalfH, radius));
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);

        // 合成 → 默认帧缓冲
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        GLES30.glViewport(0, 0, mWidth, mHeight);
        useQuad(mCompositeProgram, mCompositePos, mCompositeUv);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mSceneTex);
        GLES30.glUniform1i(mCompositeScene, 0);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mBloomTexA);
        GLES30.glUniform1i(mCompositeBloom, 1);
        GLES30.glUniform1f(mCompositeStrength, strength);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);

        // 收摊：纹理单元与顶点属性都还回去，别让下一帧的场景绘制继承这里的绑定。
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glDisableVertexAttribArray(mCompositePos);
        GLES30.glDisableVertexAttribArray(mCompositeUv);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
    }

    private void useQuad(int program, int positionHandle, int uvHandle) {
        GLES30.glUseProgram(program);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, mQuadVbo);
        GLES30.glEnableVertexAttribArray(positionHandle);
        GLES30.glVertexAttribPointer(positionHandle, 2, GLES30.GL_FLOAT, false, 16, 0);
        GLES30.glEnableVertexAttribArray(uvHandle);
        GLES30.glVertexAttribPointer(uvHandle, 2, GLES30.GL_FLOAT, false, 16, 8);
    }
}
