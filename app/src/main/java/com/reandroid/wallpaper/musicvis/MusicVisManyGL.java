package com.reandroid.wallpaper.musicvis;

import com.reandroid.utils.GLTextureUtils;
import android.content.Context;
import android.opengl.GLES30;
import android.opengl.Matrix;

import com.reandroid.utils.AssetLoader;
import com.reandroid.gles.GLESScene;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * GL renderer for composite vis5 wallpaper.
 * Delegates all scene logic to ManyScene.
 */
public class MusicVisManyGL extends GLESScene {

    private static final int LINE_COUNT = 256;
    private final Context mContext;
    private final ManyScene mScene;

    // Quad program
    private int mQuadProgram;
    private int mQuadPosLoc;
    private int mQuadTexLoc;
    private int mQuadMvpLoc;
    private int mQuadSamplerLoc;

    // Line program
    private int mLineProgram;
    private int mLinePosLoc;
    private int mLineTexLoc;
    private int mLineMvpLoc;
    private int mLineSamplerLoc;

    // HSL color shader
    private int mColorProgram;
    private int mColorPosLoc;
    private int mColorTexLoc;
    private int mColorMvpLoc;
    private int mColorSamplerLoc;
    private int mColorAdjustLoc;
    private int mTexGrey;
    private FloatBuffer mAdjustBuffer;

    // Textures
    private int mTexBackground;
    private int mTexFrame;
    private int mTexNeedle;
    private int mTexPeakOn;
    private int mTexPeakOff;
    private int mTexBlack;
    private int mTexAlbum;
    private int mTexLine;    // fire (PCM, vis2)
    private int mTexLineFFT;  // ice (FFT, vis3)

    // Buffers
    private FloatBuffer mPosBuffer;
    private FloatBuffer mTexBuffer;
    private FloatBuffer mLinePosBuffer;
    private FloatBuffer mLineTexBuffer;
    private FloatBuffer mLinePosBufferFFT;
    private FloatBuffer mLineTexBufferFFT;
    private float[] mQuadUvs;

    private final float[] mMvp = new float[16];
    // drawFrame 每帧构造 base 再复制两份（原先是 base.clone()，同样每帧 3 次分配）
    private final float[] mBaseMatrix = new float[16];
    private final float[] mReflectMatrix = new float[16];
    private final float[] mNormalMatrix = new float[16];
    // drawQuad / drawQuadXZ 都是叶子方法，不会互相嵌套，共用一个 scratch
    private final float[] mQuadPositions = new float[12];
    // 下面六个原先都是 baseMatrix.clone()。调用链是
    // drawFrame → drawVizLayer → {drawVU, drawWave}，以及 drawFrame → drawReflectPlane，
    // 都是不重入的叶子路径，所以每个 clone 换成各自的字段即可。
    // drawVU 里 model 在 needleModel/eraseModel 之后还要再用一次，故三者必须是不同的数组。
    private final float[] mVizLayerMatrix = new float[16];
    private final float[] mVuModelMatrix = new float[16];
    private final float[] mVuNeedleMatrix = new float[16];
    private final float[] mVuEraseMatrix = new float[16];
    private final float[] mWaveModelMatrix = new float[16];
    private final float[] mReflectPlaneMatrix = new float[16];

    public MusicVisManyGL(int width, int height, Context context) {
        super(width, height);
        mContext = context;
        mScene = new ManyScene(width, height, context);
    }

    public void setPluginPrefs(android.content.SharedPreferences p) {
        mScene.setPluginPrefs(p);
    }

    /** 跨插件读取能力（vis5 要读 vis2 / vis3 的设置），由宿主注入。 */
    public void setPluginPrefsProvider(com.reandroid.plugin.PluginPrefsProvider provider) {
        mScene.setPluginPrefsProvider(provider);
    }

    @Override
    protected void onCreate() {}

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        mScene.resizeWaves(width, height);
        mScene.updateProjection();
    }

    @Override
    public void start() {
        mScene.start();
    }

    @Override
    public void stop() {
        mScene.stop();
    }

    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        mScene.setOffset(xOffset, yOffset, xPixels, yPixels);
    }

    @Override
    public void drawFrame(long timeMs) {
        initGLIfNeeded();
        if (mQuadProgram == 0 || mLineProgram == 0) return;

        ManyScene s = mScene;

        s.updateRenderMode();
        s.updateAutoRotation(timeMs);
        s.updateNeedle();
        s.updateWaveData();
        s.updateWaveDataFFT();
        s.applyIdleAndFade();
        s.updateLineBuffers();
        s.updateAdjustBuffer();

        uploadBuffers(s);

        GLES30.glClearColor(s.mBgColor[0], s.mBgColor[1], s.mBgColor[2], 1.0f);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);

        float[] base = mBaseMatrix;
        Matrix.setIdentityM(base, 0);
        Matrix.translateM(base, 0, 0f, 1.0f, 0f); // camera height offset
        Matrix.rotateM(base, 0, s.mTilt, 1f, 0f, 0f);
        Matrix.rotateM(base, 0, s.mAutoRotation + s.mRotate, 0f, 1f, 0f);

        float[] reflect = mReflectMatrix;
        System.arraycopy(base, 0, reflect, 0, 16);
        Matrix.translateM(reflect, 0, 0f, -1f, 0f);
        Matrix.scaleM(reflect, 0, 1f, -1f, 1f);
        drawVizLayer(reflect);

        drawReflectPlane(reflect);

        float[] normal = mNormalMatrix;
        System.arraycopy(base, 0, normal, 0, 16);
        drawVizLayer(normal);

        s.endFrame();
    }

    private void initGLIfNeeded() {
        if (mQuadProgram != 0 || mContext == null) return;

        String quadVs = AssetLoader.readText(mContext, "musicvis/shaders/GLES/musicvis_many_quad_vs.glsl");
        String quadFs = AssetLoader.readText(mContext, "musicvis/shaders/GLES/musicvis_many_quad_fs.glsl");
        String lineVs = AssetLoader.readText(mContext, "musicvis/shaders/GLES/musicvis_many_line_vs.glsl");
        String lineFs = AssetLoader.readText(mContext, "musicvis/shaders/GLES/musicvis_many_line_fs.glsl");
        mQuadProgram = createProgram(quadVs, quadFs);
        mLineProgram = createProgram(lineVs, lineFs);
        if (mQuadProgram == 0 || mLineProgram == 0) return;

        mQuadPosLoc = GLES30.glGetAttribLocation(mQuadProgram, "aPosition");
        mQuadTexLoc = GLES30.glGetAttribLocation(mQuadProgram, "aTexCoord");
        mQuadMvpLoc = GLES30.glGetUniformLocation(mQuadProgram, "uMVP");
        mQuadSamplerLoc = GLES30.glGetUniformLocation(mQuadProgram, "uTex");

        mLinePosLoc = GLES30.glGetAttribLocation(mLineProgram, "aPosition");
        mLineTexLoc = GLES30.glGetAttribLocation(mLineProgram, "aTexCoord");
        mLineMvpLoc = GLES30.glGetUniformLocation(mLineProgram, "uMVP");
        mLineSamplerLoc = GLES30.glGetUniformLocation(mLineProgram, "uTex");

        mTexBackground = GLTextureUtils.loadTextureFromAsset(mContext, "musicvis/drawable/musicvis_vu_background.png");
        mTexFrame = GLTextureUtils.loadTextureFromAsset(mContext, "musicvis/drawable/musicvis_vu_frame.png");
        mTexNeedle = GLTextureUtils.loadTextureFromAsset(mContext, "musicvis/drawable/musicvis_vu_needle.png");
        mTexPeakOn = GLTextureUtils.loadTextureFromAsset(mContext, "musicvis/drawable/musicvis_vu_peak_on.png");
        mTexPeakOff = GLTextureUtils.loadTextureFromAsset(mContext, "musicvis/drawable/musicvis_vu_peak_off.png");
        mTexBlack = GLTextureUtils.loadTextureFromAsset(mContext, "musicvis/drawable/musicvis_black.png");
        mTexAlbum = GLTextureUtils.loadTextureFromAsset(mContext, "musicvis/drawable/musicvis_albumart.png");
        mTexLine = GLTextureUtils.loadTextureFromAsset(mContext, "musicvis/drawable/musicvis_fire.png");
        mTexLineFFT = GLTextureUtils.loadTextureFromAsset(mContext, "musicvis/drawable/musicvis_ice.png");
        mTexGrey = GLTextureUtils.loadTextureFromAsset(mContext, "musicvis/drawable/musicvis_grey.png");

        // HSL color shader (reuses vis2/vis3 wave color shader)
        String cvs = AssetLoader.readText(mContext, "musicvis/shaders/GLES/musicvis_wave_color_vs.glsl");
        String cfs = AssetLoader.readText(mContext, "musicvis/shaders/GLES/musicvis_wave_color_fs.glsl");
        mColorProgram = createProgram(cvs, cfs);
        if (mColorProgram != 0) {
            mColorPosLoc = GLES30.glGetAttribLocation(mColorProgram, "aPosition");
            mColorTexLoc = GLES30.glGetAttribLocation(mColorProgram, "aTexCoord");
            mColorAdjustLoc = GLES30.glGetAttribLocation(mColorProgram, "aAdjust");
            mColorMvpLoc = GLES30.glGetUniformLocation(mColorProgram, "uMVP");
            mColorSamplerLoc = GLES30.glGetUniformLocation(mColorProgram, "uTex");
        }

        mQuadUvs = AssetLoader.readFloatArray(mContext, "musicvis/data/musicvis_quad_uv.csv");

        mPosBuffer = ByteBuffer.allocateDirect(12 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        mTexBuffer = ByteBuffer.allocateDirect(mQuadUvs.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        mLinePosBuffer = ByteBuffer.allocateDirect(mScene.mLinePositions.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        mLineTexBuffer = ByteBuffer.allocateDirect(mScene.mLineTexCoords.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        mLinePosBufferFFT = ByteBuffer.allocateDirect(mScene.mWaveFFT.mPositions.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        mLineTexBufferFFT = ByteBuffer.allocateDirect(mScene.mWaveFFT.mTexCoords.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        mAdjustBuffer = ByteBuffer.allocateDirect(mScene.mAdjustData.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();

        mScene.updateProjection();
    }

    private void uploadBuffers(ManyScene s) {
        mLinePosBuffer.position(0);
        mLinePosBuffer.put(s.mLinePositions).position(0);
        mLineTexBuffer.position(0);
        mLineTexBuffer.put(s.mLineTexCoords).position(0);
        // FFT: upload from WaveScene(FFT) built-in buffers
        mLinePosBufferFFT.position(0);
        mLinePosBufferFFT.put(s.mWaveFFT.mPositions).position(0);
        mLineTexBufferFFT.position(0);
        mLineTexBufferFFT.put(s.mWaveFFT.mTexCoords).position(0);
        // HSL adjust
        mAdjustBuffer.position(0);
        mAdjustBuffer.put(s.mAdjustData).position(0);
    }

    // ---- rendering helpers ----

    private void drawVizLayer(float[] baseMatrix) {
        float[] layer = mVizLayerMatrix;
        System.arraycopy(baseMatrix, 0, layer, 0, 16);
        int waveIdx = 0;
        for (int i = 0; i < 6; i++) {
            if ((i & 1) == 1) {
                drawVU(layer);
            } else {
                drawWave(layer, waveIdx++);
            }
            Matrix.rotateM(layer, 0, 60f, 0f, 1f, 0f);
        }
    }

    private void drawVU(float[] baseMatrix) {
        ManyScene s = mScene;
        float scale = 0.0041f;
        float[] model = mVuModelMatrix;
        System.arraycopy(baseMatrix, 0, model, 0, 16);
        Matrix.scaleM(model, 0, scale, scale, scale);

        setMvp(model);
        drawQuad(mTexBackground, -208f, -33f, 600f, 208f, 200f, 600f);

        int peakTex = s.mNeedle.mPeak > 0 ? mTexPeakOn : mTexPeakOff;
        drawQuad(peakTex, 140f, 70f, 600f, 196f, 128f, 600f);

        float[] needleModel = mVuNeedleMatrix;
        System.arraycopy(baseMatrix, 0, needleModel, 0, 16);
        Matrix.translateM(needleModel, 0, 0f, -57f * scale, 0f);
        Matrix.rotateM(needleModel, 0, s.mNeedle.mAngle - 90f, 0f, 0f, 1f);
        Matrix.scaleM(needleModel, 0, scale, scale, scale);
        setMvp(needleModel);
        drawQuad(mTexNeedle, -44f, -102f + 57f, 600f, 44f, 160f + 57f, 600f);

        float[] eraseModel = mVuEraseMatrix;
        System.arraycopy(baseMatrix, 0, eraseModel, 0, 16);
        Matrix.scaleM(eraseModel, 0, scale, scale, scale);
        setMvp(eraseModel);
        drawQuad(mTexBlack, -100f, -105f, 600f, 100f, -55f, 600f);

        setMvp(model);
        drawQuad(mTexFrame, -236f, -60f, 600f, 236f, 230f, 600f);
    }

    private void drawWave(float[] baseMatrix, int waveIdx) {
        ManyScene s = mScene;
        boolean useFFT = (s.mWaveMode == 1)
                || (s.mWaveMode == 2 && waveIdx == 2);
        FloatBuffer posBuf = useFFT ? mLinePosBufferFFT : mLinePosBuffer;
        FloatBuffer texBuf = useFFT ? mLineTexBufferFFT : mLineTexBuffer;

        float[] model = mWaveModelMatrix;
        System.arraycopy(baseMatrix, 0, model, 0, 16);
        Matrix.scaleM(model, 0, 0.008f, 0.008f / 2048f, 0.008f);
        Matrix.translateM(model, 0, 0f, 81920f, 350f);

        Matrix.multiplyMM(mMvp, 0, s.mProj, 0, model, 0);

        boolean recolor = (useFFT ? s.mRecolorFFT : s.mRecolorPCM) && mColorProgram != 0;
        if (recolor) {
            GLES30.glUseProgram(mColorProgram);
            GLES30.glUniformMatrix4fv(mColorMvpLoc, 1, false, mMvp, 0);
            GLES30.glUniform1i(mColorSamplerLoc, 0);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mTexGrey);
            GLES30.glEnableVertexAttribArray(mColorPosLoc);
            GLES30.glEnableVertexAttribArray(mColorTexLoc);
            GLES30.glEnableVertexAttribArray(mColorAdjustLoc);
            GLES30.glVertexAttribPointer(mColorPosLoc, 2, GLES30.GL_FLOAT, false, 0, posBuf);
            GLES30.glVertexAttribPointer(mColorTexLoc, 2, GLES30.GL_FLOAT, false, 0, texBuf);
            // Use PCM or FFT section of adjust buffer
            mAdjustBuffer.position(useFFT ? LINE_COUNT * 2 * 3 : 0);
            GLES30.glVertexAttribPointer(mColorAdjustLoc, 3, GLES30.GL_FLOAT, false, 0, mAdjustBuffer);
        } else {
            GLES30.glUseProgram(mLineProgram);
            GLES30.glUniformMatrix4fv(mLineMvpLoc, 1, false, mMvp, 0);
            GLES30.glUniform1i(mLineSamplerLoc, 0);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            // vis2 fire texture for PCM, vis3 ice texture for FFT
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, useFFT ? mTexLineFFT : mTexLine);
            GLES30.glEnableVertexAttribArray(mLinePosLoc);
            GLES30.glEnableVertexAttribArray(mLineTexLoc);
            GLES30.glVertexAttribPointer(mLinePosLoc, 2, GLES30.GL_FLOAT, false, 0, posBuf);
            GLES30.glVertexAttribPointer(mLineTexLoc, 2, GLES30.GL_FLOAT, false, 0, texBuf);
        }

        int glMode = s.mUseTriangleStrip ? GLES30.GL_TRIANGLE_STRIP : GLES30.GL_LINES;
        GLES30.glDrawArrays(glMode, 0, LINE_COUNT * 2);

        if (recolor) {
            GLES30.glDisableVertexAttribArray(mColorPosLoc);
            GLES30.glDisableVertexAttribArray(mColorTexLoc);
            GLES30.glDisableVertexAttribArray(mColorAdjustLoc);
        } else {
            GLES30.glDisableVertexAttribArray(mLinePosLoc);
            GLES30.glDisableVertexAttribArray(mLineTexLoc);
        }
    }

    private void drawReflectPlane(float[] baseMatrix) {
        float[] model = mReflectPlaneMatrix;
        System.arraycopy(baseMatrix, 0, model, 0, 16);
        setMvp(model);
        drawQuadXZ(mTexAlbum, -1500f, 1500f, mScene.mFloorY, -1500f, 1500f);
    }

    private void setMvp(float[] model) {
        Matrix.multiplyMM(mMvp, 0, mScene.mProj, 0, model, 0);
        GLES30.glUseProgram(mQuadProgram);
        GLES30.glUniformMatrix4fv(mQuadMvpLoc, 1, false, mMvp, 0);
        GLES30.glUniform1i(mQuadSamplerLoc, 0);
    }

    private void drawQuad(int texId, float x1, float y1, float z1, float x2, float y2, float z2) {
        float[] positions = mQuadPositions;
        positions[0] = x1; positions[1] = y1; positions[2] = z1;
        positions[3] = x2; positions[4] = y1; positions[5] = z1;
        positions[6] = x1; positions[7] = y2; positions[8] = z2;
        positions[9] = x2; positions[10] = y2; positions[11] = z2;
        float[] uvs = mQuadUvs;
        mPosBuffer.position(0);
        mPosBuffer.put(positions).position(0);
        mTexBuffer.position(0);
        mTexBuffer.put(uvs).position(0);

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId);

        GLES30.glEnableVertexAttribArray(mQuadPosLoc);
        GLES30.glEnableVertexAttribArray(mQuadTexLoc);
        GLES30.glVertexAttribPointer(mQuadPosLoc, 3, GLES30.GL_FLOAT, false, 0, mPosBuffer);
        GLES30.glVertexAttribPointer(mQuadTexLoc, 2, GLES30.GL_FLOAT, false, 0, mTexBuffer);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
        GLES30.glDisableVertexAttribArray(mQuadPosLoc);
        GLES30.glDisableVertexAttribArray(mQuadTexLoc);
    }

    private void drawQuadXZ(int texId, float x1, float x2, float y, float z1, float z2) {
        float[] positions = mQuadPositions;
        positions[0] = x1; positions[1] = y; positions[2] = z1;
        positions[3] = x2; positions[4] = y; positions[5] = z1;
        positions[6] = x1; positions[7] = y; positions[8] = z2;
        positions[9] = x2; positions[10] = y; positions[11] = z2;
        float[] uvs = mQuadUvs;
        mPosBuffer.position(0);
        mPosBuffer.put(positions).position(0);
        mTexBuffer.position(0);
        mTexBuffer.put(uvs).position(0);

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId);

        GLES30.glEnableVertexAttribArray(mQuadPosLoc);
        GLES30.glEnableVertexAttribArray(mQuadTexLoc);
        GLES30.glVertexAttribPointer(mQuadPosLoc, 3, GLES30.GL_FLOAT, false, 0, mPosBuffer);
        GLES30.glVertexAttribPointer(mQuadTexLoc, 2, GLES30.GL_FLOAT, false, 0, mTexBuffer);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
        GLES30.glDisableVertexAttribArray(mQuadPosLoc);
        GLES30.glDisableVertexAttribArray(mQuadTexLoc);
    }

    // ---- shader helpers ----


}
