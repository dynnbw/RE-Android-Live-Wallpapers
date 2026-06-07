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
 * GL renderer for VU meter wallpaper (vis4).
 * Delegates all scene logic to VuScene.
 */
public class MusicVisVuGL extends GLESScene {

    private final Context mContext;
    private final VuScene mScene;

    private int mProgram;
    private int mPosLoc;
    private int mTexLoc;
    private int mMvpLoc;
    private int mSamplerLoc;

    private int mTexBackground;
    private int mTexFrame;
    private int mTexNeedle;
    private int mTexPeakOn;
    private int mTexPeakOff;
    private int mTexBlack;

    private FloatBuffer mPosBuffer;
    private FloatBuffer mTexBuffer;
    private float[] mQuadUvs;

    private final float[] mMvp = new float[16];
    private final float[] mModel = new float[16];

    public MusicVisVuGL(int width, int height, Context context) {
        super(width, height);
        mContext = context;
        mScene = new VuScene(width, height, context);
    }

    @Override
    protected void onCreate() {}

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
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
    public void drawFrame(long timeMs) {
        initGLIfNeeded();
        if (mProgram == 0) return;

        mScene.updateNeedle();

        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        // background
        setModelMatrix(0f, -90f * 0.0041f, 0f, 0f, 0f, 0f, 1f, 0.0041f, 0.0041f, 0.0041f);
        drawQuad(mTexBackground, -208f, -33f, 0f, 208f, 200f, 0f);

        // peak
        int peakTex = mScene.mPeak > 0 ? mTexPeakOn : mTexPeakOff;
        drawQuad(peakTex, 140f, 70f, -1f, 196f, 128f, -1f);

        // needle (rotation around 44,217 from top-left)
        setModelMatrix(0f, -147f * 0.0041f, 0f, mScene.mAngle - 90f, 0f, 0f, 1f, 0.0041f, 0.0041f, 0.0041f);
        drawQuad(mTexNeedle, -44f, -102f + 57f, 0f, 44f, 160f + 57f, 0f);

        // restore
        setModelMatrix(0f, -90f * 0.0041f, 0f, 0f, 0f, 0f, 1f, 0.0041f, 0.0041f, 0.0041f);
        drawQuadNoTex(mTexBlack, -100f, -105f, 0f, 100f, -55f, 0f);

        // frame
        drawQuad(mTexFrame, -236f, -60f, 0f, 236f, 230f, 0f);
    }

    private void initGLIfNeeded() {
        if (mProgram != 0 || mContext == null) return;
        String vs = AssetLoader.readText(mContext, "musicvis/shaders/GLES/musicvis_vu_vs.glsl");
        String fs = AssetLoader.readText(mContext, "musicvis/shaders/GLES/musicvis_vu_fs.glsl");
        mProgram = createProgram(vs, fs);
        if (mProgram == 0) return;
        mPosLoc = GLES20.glGetAttribLocation(mProgram, "aPosition");
        mTexLoc = GLES20.glGetAttribLocation(mProgram, "aTexCoord");
        mMvpLoc = GLES20.glGetUniformLocation(mProgram, "uMVP");
        mSamplerLoc = GLES20.glGetUniformLocation(mProgram, "uTex");

        mQuadUvs = AssetLoader.readFloatArray(mContext, "musicvis/data/musicvis_quad_uv.csv");

        mPosBuffer = ByteBuffer.allocateDirect(12 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        mTexBuffer = ByteBuffer.allocateDirect(mQuadUvs.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();

        mTexBackground = GLTextureUtils.loadTextureFromAsset(mContext, "musicvis/drawable/musicvis_vu_background.png");
        mTexFrame = GLTextureUtils.loadTextureFromAsset(mContext, "musicvis/drawable/musicvis_vu_frame.png");
        mTexNeedle = GLTextureUtils.loadTextureFromAsset(mContext, "musicvis/drawable/musicvis_vu_needle.png");
        mTexPeakOn = GLTextureUtils.loadTextureFromAsset(mContext, "musicvis/drawable/musicvis_vu_peak_on.png");
        mTexPeakOff = GLTextureUtils.loadTextureFromAsset(mContext, "musicvis/drawable/musicvis_vu_peak_off.png");
        mTexBlack = GLTextureUtils.loadTextureFromAsset(mContext, "musicvis/drawable/musicvis_black.png");

        mScene.updateProjection();
    }

    private void setModelMatrix(float tx, float ty, float tz, float angle, float rx, float ry, float rz,
                                float sx, float sy, float sz) {
        Matrix.setIdentityM(mModel, 0);
        Matrix.translateM(mModel, 0, tx, ty, tz);
        if (angle != 0f) {
            Matrix.rotateM(mModel, 0, angle, rx, ry, rz);
        }
        Matrix.scaleM(mModel, 0, sx, sy, sz);
        Matrix.multiplyMM(mMvp, 0, mScene.mProj, 0, mModel, 0);
    }

    private void drawQuad(int texId, float x1, float y1, float z1, float x2, float y2, float z2) {
        drawQuadInternal(texId, x1, y1, z1, x2, y2, z2, true);
    }

    private void drawQuadNoTex(int texId, float x1, float y1, float z1, float x2, float y2, float z2) {
        drawQuadInternal(texId, x1, y1, z1, x2, y2, z2, false);
    }

    private void drawQuadInternal(int texId, float x1, float y1, float z1, float x2, float y2, float z2, boolean texCoords) {
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

        GLES20.glUseProgram(mProgram);
        GLES20.glUniformMatrix4fv(mMvpLoc, 1, false, mMvp, 0);
        GLES20.glUniform1i(mSamplerLoc, 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);

        GLES20.glEnableVertexAttribArray(mPosLoc);
        GLES20.glEnableVertexAttribArray(mTexLoc);

        GLES20.glVertexAttribPointer(mPosLoc, 3, GLES20.GL_FLOAT, false, 0, mPosBuffer);
        GLES20.glVertexAttribPointer(mTexLoc, 2, GLES20.GL_FLOAT, false, 0, mTexBuffer);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(mPosLoc);
        GLES20.glDisableVertexAttribArray(mTexLoc);
    }


}
