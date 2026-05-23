package com.reandroid.wallpaper.musicvis;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.Matrix;

import com.reandroid.gles.GLESScene;
import com.reandroid.wallpaper.R;
import com.reandroid.gles.RawResourceLoader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class MusicVisVuScene extends GLESScene {
    private final Context mContext;

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

    private final float[] mProj = new float[16];
    private final float[] mModel = new float[16];
    private final float[] mMvp = new float[16];

    private AudioCapture mAudioCapture;
    private int[] mVizData = new int[1024];

    private int mNeedlePos = 0;
    private int mNeedleSpeed = 0;
    private int mNeedleMass = 10;
    private int mSpringForceAtOrigin = 200;

    private float mAngle = 0f;
    private int mPeak = 0;

    public MusicVisVuScene(int width, int height, Context context) {
        super(width, height);
        mContext = context;
    }

    @Override
    protected void onCreate() {
        // no-op
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        updateProjection();
    }

    @Override
    public void start() {
        if (mAudioCapture == null) {
            mAudioCapture = new AudioCapture(AudioCapture.TYPE_PCM, 1024);
        }
        mAudioCapture.start();
    }

    @Override
    public void stop() {
        if (mAudioCapture != null) {
            mAudioCapture.stop();
            mAudioCapture.release();
            mAudioCapture = null;
        }
    }

    @Override
    public void drawFrame(long timeMs) {
        initGLIfNeeded();
        if (mProgram == 0) return;

        updateNeedle();

        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        // background
        setModelMatrix(0f, -90f * 0.0041f, 0f, 0f, 0f, 0f, 1f, 0.0041f, 0.0041f, 0.0041f);
        drawQuad(mTexBackground, -208f, -33f, 0f, 208f, 200f, 0f);

        // peak
        int peakTex = mPeak > 0 ? mTexPeakOn : mTexPeakOff;
        drawQuad(peakTex, 140f, 70f, -1f, 196f, 128f, -1f);

        // needle (rotation around 44,217 from top-left)
        setModelMatrix(0f, -147f * 0.0041f, 0f, mAngle - 90f, 0f, 0f, 1f, 0.0041f, 0.0041f, 0.0041f);
        drawQuad(mTexNeedle, -44f, -102f + 57f, 0f, 44f, 160f + 57f, 0f);

        // restore
        setModelMatrix(0f, -90f * 0.0041f, 0f, 0f, 0f, 0f, 1f, 0.0041f, 0.0041f, 0.0041f);
        drawQuadNoTex(mTexBlack, -100f, -105f, 0f, 100f, -55f, 0f);

        // frame
        drawQuad(mTexFrame, -236f, -60f, 0f, 236f, 230f, 0f);
    }

    private void initGLIfNeeded() {
        if (mProgram != 0 || mResources == null) return;
        String vs = RawResourceLoader.readRawText(mResources, R.raw.musicvis_vu_vs);
        String fs = RawResourceLoader.readRawText(mResources, R.raw.musicvis_vu_fs);
        mProgram = createProgram(vs, fs);
        if (mProgram == 0) return;
        mPosLoc = GLES20.glGetAttribLocation(mProgram, "aPosition");
        mTexLoc = GLES20.glGetAttribLocation(mProgram, "aTexCoord");
        mMvpLoc = GLES20.glGetUniformLocation(mProgram, "uMVP");
        mSamplerLoc = GLES20.glGetUniformLocation(mProgram, "uTex");

        mQuadUvs = RawResourceLoader.readRawFloatArray(mResources, R.raw.musicvis_quad_uv);

        mTexBackground = GLTextureUtils.loadTexture(mResources, R.drawable.musicvis_vu_background);
        mTexFrame = GLTextureUtils.loadTexture(mResources, R.drawable.musicvis_vu_frame);
        mTexNeedle = GLTextureUtils.loadTexture(mResources, R.drawable.musicvis_vu_needle);
        mTexPeakOn = GLTextureUtils.loadTexture(mResources, R.drawable.musicvis_vu_peak_on);
        mTexPeakOff = GLTextureUtils.loadTexture(mResources, R.drawable.musicvis_vu_peak_off);
        mTexBlack = GLTextureUtils.loadTexture(mResources, R.drawable.musicvis_black);

        updateProjection();
    }

    private void updateNeedle() {
        int len = 0;
        if (mAudioCapture != null) {
            mVizData = mAudioCapture.getFormattedData(512, 1);
            len = mVizData.length;
        }

        int volt = 0;
        if (len > 0) {
            for (int i = 0; i < len; i++) {
                int val = mVizData[i];
                if (val < 0) val = -val;
                volt += val;
            }
            volt = volt / len;
        }

        int netforce = volt - mNeedleSpeed * 3 - (mNeedlePos + mSpringForceAtOrigin);
        int acceleration = netforce / mNeedleMass;
        mNeedleSpeed += acceleration;
        mNeedlePos += mNeedleSpeed;
        if (mNeedlePos < 0) {
            mNeedlePos = 0;
            mNeedleSpeed = 0;
        } else if (mNeedlePos > 32767) {
            if (mNeedlePos > 33333) {
                mPeak = 10;
            }
            mNeedlePos = 32767;
            mNeedleSpeed = 0;
        }
        if (mPeak > 0) mPeak--;

        mAngle = 131f - (mNeedlePos / 410f);
    }

    private void updateProjection() {
        if (mWidth > mHeight) {
            float aspect = (float) mWidth / (float) mHeight;
            Matrix.orthoM(mProj, 0, -aspect, aspect, -1f, 1f, -1f, 1f);
        } else {
            float aspect = (float) mHeight / (float) mWidth;
            Matrix.orthoM(mProj, 0, -1f, 1f, -aspect, aspect, -1f, 1f);
        }
    }

    private void setModelMatrix(float tx, float ty, float tz, float angle, float rx, float ry, float rz,
                                float sx, float sy, float sz) {
        Matrix.setIdentityM(mModel, 0);
        Matrix.translateM(mModel, 0, tx, ty, tz);
        if (angle != 0f) {
            Matrix.rotateM(mModel, 0, angle, rx, ry, rz);
        }
        Matrix.scaleM(mModel, 0, sx, sy, sz);
        Matrix.multiplyMM(mMvp, 0, mProj, 0, mModel, 0);
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
        mPosBuffer = toFloatBuffer(positions);
        mTexBuffer = toFloatBuffer(uvs);

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

    private FloatBuffer toFloatBuffer(float[] data) {
        ByteBuffer bb = ByteBuffer.allocateDirect(data.length * 4);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(data);
        fb.position(0);
        return fb;
    }

    private int createProgram(String vs, String fs) {
        int v = loadShader(GLES20.GL_VERTEX_SHADER, vs);
        int f = loadShader(GLES20.GL_FRAGMENT_SHADER, fs);
        if (v == 0 || f == 0) return 0;
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, v);
        GLES20.glAttachShader(program, f);
        GLES20.glLinkProgram(program);
        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] == 0) {
            GLES20.glDeleteProgram(program);
            return 0;
        }
        return program;
    }

    private int loadShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }
}
