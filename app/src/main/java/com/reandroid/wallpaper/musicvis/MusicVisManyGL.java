package com.reandroid.wallpaper.musicvis;

import com.reandroid.utils.GLTextureUtils;
import android.content.Context;
import android.opengl.GLES20;
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
    private final float[] mTmp = new float[16];

    public MusicVisManyGL(int width, int height, Context context) {
        super(width, height);
        mContext = context;
        mScene = new ManyScene(width, height, context);
    }

    public void setPluginPrefs(android.content.SharedPreferences p) {
        mScene.setPluginPrefs(p);
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

        GLES20.glClearColor(s.mBgColor[0], s.mBgColor[1], s.mBgColor[2], 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        float[] base = new float[16];
        Matrix.setIdentityM(base, 0);
        Matrix.translateM(base, 0, 0f, 1.0f, 0f); // camera height offset
        Matrix.rotateM(base, 0, s.mTilt, 1f, 0f, 0f);
        Matrix.rotateM(base, 0, s.mAutoRotation + s.mRotate, 0f, 1f, 0f);

        float[] reflect = base.clone();
        Matrix.translateM(reflect, 0, 0f, -1f, 0f);
        Matrix.scaleM(reflect, 0, 1f, -1f, 1f);
        drawVizLayer(reflect);

        drawReflectPlane(reflect);

        float[] normal = base.clone();
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

        mQuadPosLoc = GLES20.glGetAttribLocation(mQuadProgram, "aPosition");
        mQuadTexLoc = GLES20.glGetAttribLocation(mQuadProgram, "aTexCoord");
        mQuadMvpLoc = GLES20.glGetUniformLocation(mQuadProgram, "uMVP");
        mQuadSamplerLoc = GLES20.glGetUniformLocation(mQuadProgram, "uTex");

        mLinePosLoc = GLES20.glGetAttribLocation(mLineProgram, "aPosition");
        mLineTexLoc = GLES20.glGetAttribLocation(mLineProgram, "aTexCoord");
        mLineMvpLoc = GLES20.glGetUniformLocation(mLineProgram, "uMVP");
        mLineSamplerLoc = GLES20.glGetUniformLocation(mLineProgram, "uTex");

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
            mColorPosLoc = GLES20.glGetAttribLocation(mColorProgram, "aPosition");
            mColorTexLoc = GLES20.glGetAttribLocation(mColorProgram, "aTexCoord");
            mColorAdjustLoc = GLES20.glGetAttribLocation(mColorProgram, "aAdjust");
            mColorMvpLoc = GLES20.glGetUniformLocation(mColorProgram, "uMVP");
            mColorSamplerLoc = GLES20.glGetUniformLocation(mColorProgram, "uTex");
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
        float[] layer = baseMatrix.clone();
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
        float[] model = baseMatrix.clone();
        Matrix.scaleM(model, 0, scale, scale, scale);

        setMvp(model);
        drawQuad(mTexBackground, -208f, -33f, 600f, 208f, 200f, 600f);

        int peakTex = s.mNeedle.mPeak > 0 ? mTexPeakOn : mTexPeakOff;
        drawQuad(peakTex, 140f, 70f, 600f, 196f, 128f, 600f);

        float[] needleModel = baseMatrix.clone();
        Matrix.translateM(needleModel, 0, 0f, -57f * scale, 0f);
        Matrix.rotateM(needleModel, 0, s.mNeedle.mAngle - 90f, 0f, 0f, 1f);
        Matrix.scaleM(needleModel, 0, scale, scale, scale);
        setMvp(needleModel);
        drawQuad(mTexNeedle, -44f, -102f + 57f, 600f, 44f, 160f + 57f, 600f);

        float[] eraseModel = baseMatrix.clone();
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

        float[] model = baseMatrix.clone();
        Matrix.scaleM(model, 0, 0.008f, 0.008f / 2048f, 0.008f);
        Matrix.translateM(model, 0, 0f, 81920f, 350f);

        Matrix.multiplyMM(mMvp, 0, s.mProj, 0, model, 0);

        boolean recolor = (useFFT ? s.mRecolorFFT : s.mRecolorPCM) && mColorProgram != 0;
        if (recolor) {
            GLES20.glUseProgram(mColorProgram);
            GLES20.glUniformMatrix4fv(mColorMvpLoc, 1, false, mMvp, 0);
            GLES20.glUniform1i(mColorSamplerLoc, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTexGrey);
            GLES20.glEnableVertexAttribArray(mColorPosLoc);
            GLES20.glEnableVertexAttribArray(mColorTexLoc);
            GLES20.glEnableVertexAttribArray(mColorAdjustLoc);
            GLES20.glVertexAttribPointer(mColorPosLoc, 2, GLES20.GL_FLOAT, false, 0, posBuf);
            GLES20.glVertexAttribPointer(mColorTexLoc, 2, GLES20.GL_FLOAT, false, 0, texBuf);
            // Use PCM or FFT section of adjust buffer
            mAdjustBuffer.position(useFFT ? LINE_COUNT * 2 * 3 : 0);
            GLES20.glVertexAttribPointer(mColorAdjustLoc, 3, GLES20.GL_FLOAT, false, 0, mAdjustBuffer);
        } else {
            GLES20.glUseProgram(mLineProgram);
            GLES20.glUniformMatrix4fv(mLineMvpLoc, 1, false, mMvp, 0);
            GLES20.glUniform1i(mLineSamplerLoc, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            // vis2 fire texture for PCM, vis3 ice texture for FFT
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, useFFT ? mTexLineFFT : mTexLine);
            GLES20.glEnableVertexAttribArray(mLinePosLoc);
            GLES20.glEnableVertexAttribArray(mLineTexLoc);
            GLES20.glVertexAttribPointer(mLinePosLoc, 2, GLES20.GL_FLOAT, false, 0, posBuf);
            GLES20.glVertexAttribPointer(mLineTexLoc, 2, GLES20.GL_FLOAT, false, 0, texBuf);
        }

        int glMode = s.mUseTriangleStrip ? GLES20.GL_TRIANGLE_STRIP : GLES20.GL_LINES;
        GLES20.glDrawArrays(glMode, 0, LINE_COUNT * 2);

        if (recolor) {
            GLES20.glDisableVertexAttribArray(mColorPosLoc);
            GLES20.glDisableVertexAttribArray(mColorTexLoc);
            GLES20.glDisableVertexAttribArray(mColorAdjustLoc);
        } else {
            GLES20.glDisableVertexAttribArray(mLinePosLoc);
            GLES20.glDisableVertexAttribArray(mLineTexLoc);
        }
    }

    private void drawReflectPlane(float[] baseMatrix) {
        float[] model = baseMatrix.clone();
        setMvp(model);
        drawQuadXZ(mTexAlbum, -1500f, 1500f, mScene.mFloorY, -1500f, 1500f);
    }

    private void setMvp(float[] model) {
        Matrix.multiplyMM(mMvp, 0, mScene.mProj, 0, model, 0);
        GLES20.glUseProgram(mQuadProgram);
        GLES20.glUniformMatrix4fv(mQuadMvpLoc, 1, false, mMvp, 0);
        GLES20.glUniform1i(mQuadSamplerLoc, 0);
    }

    private void setLineMvp(float[] model) {
        Matrix.multiplyMM(mMvp, 0, mScene.mProj, 0, model, 0);
        GLES20.glUseProgram(mLineProgram);
        GLES20.glUniformMatrix4fv(mLineMvpLoc, 1, false, mMvp, 0);
    }

    private void drawQuad(int texId, float x1, float y1, float z1, float x2, float y2, float z2) {
        float[] positions = new float[] {
                x1, y1, z1,
                x2, y1, z1,
                x1, y2, z2,
                x2, y2, z2
        };
        float[] uvs = mQuadUvs;
        mPosBuffer.position(0);
        mPosBuffer.put(positions).position(0);
        mTexBuffer.position(0);
        mTexBuffer.put(uvs).position(0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);

        GLES20.glEnableVertexAttribArray(mQuadPosLoc);
        GLES20.glEnableVertexAttribArray(mQuadTexLoc);
        GLES20.glVertexAttribPointer(mQuadPosLoc, 3, GLES20.GL_FLOAT, false, 0, mPosBuffer);
        GLES20.glVertexAttribPointer(mQuadTexLoc, 2, GLES20.GL_FLOAT, false, 0, mTexBuffer);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(mQuadPosLoc);
        GLES20.glDisableVertexAttribArray(mQuadTexLoc);
    }

    private void drawQuadXZ(int texId, float x1, float x2, float y, float z1, float z2) {
        float[] positions = new float[] {
                x1, y, z1,
                x2, y, z1,
                x1, y, z2,
                x2, y, z2
        };
        float[] uvs = mQuadUvs;
        mPosBuffer.position(0);
        mPosBuffer.put(positions).position(0);
        mTexBuffer.position(0);
        mTexBuffer.put(uvs).position(0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);

        GLES20.glEnableVertexAttribArray(mQuadPosLoc);
        GLES20.glEnableVertexAttribArray(mQuadTexLoc);
        GLES20.glVertexAttribPointer(mQuadPosLoc, 3, GLES20.GL_FLOAT, false, 0, mPosBuffer);
        GLES20.glVertexAttribPointer(mQuadTexLoc, 2, GLES20.GL_FLOAT, false, 0, mTexBuffer);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(mQuadPosLoc);
        GLES20.glDisableVertexAttribArray(mQuadTexLoc);
    }

    // ---- shader helpers ----


}
