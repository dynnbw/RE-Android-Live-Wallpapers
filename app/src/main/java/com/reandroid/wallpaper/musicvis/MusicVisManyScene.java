package com.reandroid.wallpaper.musicvis;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.Matrix;

import com.reandroid.gles.GLESScene;
import com.reandroid.wallpaper.R;
import com.reandroid.gles.RawResourceLoader;

import androidx.preference.PreferenceManager;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class MusicVisManyScene extends GLESScene {
    private static final int LINE_COUNT = 256;

    private final Context mContext;

    private int mQuadProgram;
    private int mQuadPosLoc;
    private int mQuadTexLoc;
    private int mQuadMvpLoc;
    private int mQuadSamplerLoc;

    private int mLineProgram;
    private int mLinePosLoc;
    private int mLineTexLoc;
    private int mLineMvpLoc;
    private int mLineSamplerLoc;

    private int mTexBackground;
    private int mTexFrame;
    private int mTexNeedle;
    private int mTexPeakOn;
    private int mTexPeakOff;
    private int mTexBlack;
    private int mTexAlbum;
    private int mTexLine;

    private FloatBuffer mPosBuffer;
    private FloatBuffer mTexBuffer;
    private FloatBuffer mLinePosBuffer;
    private FloatBuffer mLineTexBuffer;
    private float[] mQuadUvs;

    private final float[] mProj = new float[16];
    private final float[] mMvp = new float[16];
    private final float[] mTmp = new float[16];

    private final float[] mPointData = new float[LINE_COUNT * 8];
    private final float[] mLinePositions = new float[LINE_COUNT * 4];
    private final float[] mLineTexCoords = new float[LINE_COUNT * 4];

    private AudioCapture mAudioCapture;
    private int[] mVizData = new int[1024];

    private int mNeedlePos = 0;
    private int mNeedleSpeed = 0;
    private int mNeedleMass = 10;
    private int mSpringForceAtOrigin = 200;

    private float mAngle = 0f;
    private int mPeak = 0;

    private float mRotate = 0f;
    private float mTilt = -20f;
    private int mIdle = 0;
    private int mWaveCounter = 0;

    private int fadeoutcounter = 0;
    private int fadeincounter = 0;
    private int wave1pos = 0;
    private int wave1amp = 0;
    private int wave2pos = 0;
    private int wave2amp = 0;
    private int wave3pos = 0;
    private int wave3amp = 0;
    private int wave4pos = 0;
    private int wave4amp = 0;
    private final float[] idleWave = new float[LINE_COUNT * 8];
    private int lastWaveCounter = 0;

    private float mAutoRotation = 0f;
    private long mLastTimeMs = 0L;

    private boolean mUseTriangleStrip = true;
    private boolean mHasPrefInit = false;

    public MusicVisManyScene(int width, int height, Context context) {
        super(width, height);
        mContext = context;
        initPointData();
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
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        mRotate = (xOffset - 0.5f) * 90f;
    }

    @Override
    public void drawFrame(long timeMs) {
        initGLIfNeeded();
        if (mQuadProgram == 0 || mLineProgram == 0) return;

        updateRenderMode();

        updateAutoRotation(timeMs);
        updateNeedle();
        updateWaveData();
        applyIdleAndFade();
        updateLineBuffers();

        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        float[] base = new float[16];
        Matrix.setIdentityM(base, 0);
        Matrix.rotateM(base, 0, mTilt, 1f, 0f, 0f);
        Matrix.rotateM(base, 0, mAutoRotation + mRotate, 0f, 1f, 0f);

        float[] reflect = base.clone();
        Matrix.translateM(reflect, 0, 0f, -1f, 0f);
        Matrix.scaleM(reflect, 0, 1f, -1f, 1f);
        drawVizLayer(reflect);

        drawReflectPlane(reflect);

        float[] normal = base.clone();
        drawVizLayer(normal);

        wave1pos++;
        wave1amp++;
        wave2pos--;
        wave2amp++;
        wave3pos++;
        wave3amp++;
        wave4pos++;
        wave4amp++;
    }

    private void initGLIfNeeded() {
        if (mQuadProgram != 0 || mResources == null) return;

        String quadVs = RawResourceLoader.readRawText(mResources, R.raw.musicvis_many_quad_vs);
        String quadFs = RawResourceLoader.readRawText(mResources, R.raw.musicvis_many_quad_fs);
        String lineVs = RawResourceLoader.readRawText(mResources, R.raw.musicvis_many_line_vs);
        String lineFs = RawResourceLoader.readRawText(mResources, R.raw.musicvis_many_line_fs);
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

        mTexBackground = GLTextureUtils.loadTexture(mResources, R.drawable.musicvis_vu_background);
        mTexFrame = GLTextureUtils.loadTexture(mResources, R.drawable.musicvis_vu_frame);
        mTexNeedle = GLTextureUtils.loadTexture(mResources, R.drawable.musicvis_vu_needle);
        mTexPeakOn = GLTextureUtils.loadTexture(mResources, R.drawable.musicvis_vu_peak_on);
        mTexPeakOff = GLTextureUtils.loadTexture(mResources, R.drawable.musicvis_vu_peak_off);
        mTexBlack = GLTextureUtils.loadTexture(mResources, R.drawable.musicvis_black);
        mTexAlbum = GLTextureUtils.loadTexture(mResources, R.drawable.musicvis_albumart);
        mTexLine = GLTextureUtils.loadTexture(mResources, R.drawable.musicvis_fire);

        mQuadUvs = RawResourceLoader.readRawFloatArray(mResources, R.raw.musicvis_quad_uv);

        updateProjection();
    }

    private void updateProjection() {
        float aspect = (float) mWidth / (float) mHeight;
        Matrix.frustumM(mProj, 0, -aspect, aspect, -1f, 1f, 1f, 6000f);
    }

    private void updateRenderMode() {
        boolean pref = PreferenceManager.getDefaultSharedPreferences(mContext)
                .getBoolean("musicvis_use_triangle_strip", true);
        if (!mHasPrefInit || pref != mUseTriangleStrip) {
            mUseTriangleStrip = pref;
            mHasPrefInit = true;
        }
    }

    private void drawVizLayer(float[] baseMatrix) {
        float[] layer = baseMatrix.clone();
        for (int i = 0; i < 6; i++) {
            if ((i & 1) == 1) {
                drawVU(layer);
            } else {
                drawWave(layer);
            }
            Matrix.rotateM(layer, 0, 60f, 0f, 1f, 0f);
        }
    }

    private void drawVU(float[] baseMatrix) {
        float scale = 0.0041f;
        float[] model = baseMatrix.clone();
        Matrix.scaleM(model, 0, scale, scale, scale);

        setMvp(model);
        drawQuad(mTexBackground, -208f, -33f, 600f, 208f, 200f, 600f);

        int peakTex = mPeak > 0 ? mTexPeakOn : mTexPeakOff;
        drawQuad(peakTex, 140f, 70f, 600f, 196f, 128f, 600f);

        float[] needleModel = baseMatrix.clone();
        Matrix.translateM(needleModel, 0, 0f, -57f * scale, 0f);
        Matrix.rotateM(needleModel, 0, mAngle - 90f, 0f, 0f, 1f);
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

    private void drawWave(float[] baseMatrix) {
        float[] model = baseMatrix.clone();
        Matrix.scaleM(model, 0, 0.008f, 0.008f / 2048f, 0.008f);
        Matrix.translateM(model, 0, 0f, 81920f, 350f);

        setLineMvp(model);
        GLES20.glUseProgram(mLineProgram);
        GLES20.glUniform1i(mLineSamplerLoc, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTexLine);

        GLES20.glEnableVertexAttribArray(mLinePosLoc);
        GLES20.glEnableVertexAttribArray(mLineTexLoc);

        GLES20.glVertexAttribPointer(mLinePosLoc, 2, GLES20.GL_FLOAT, false, 0, mLinePosBuffer);
        GLES20.glVertexAttribPointer(mLineTexLoc, 2, GLES20.GL_FLOAT, false, 0, mLineTexBuffer);

        int mode = mUseTriangleStrip ? GLES20.GL_TRIANGLE_STRIP : GLES20.GL_LINES;
        GLES20.glDrawArrays(mode, 0, LINE_COUNT * 2);

        GLES20.glDisableVertexAttribArray(mLinePosLoc);
        GLES20.glDisableVertexAttribArray(mLineTexLoc);
    }

    private void drawReflectPlane(float[] baseMatrix) {
        float[] model = baseMatrix.clone();
        setMvp(model);
        drawQuadXZ(mTexAlbum, -1500f, 1500f, -60f, -1500f, 1500f);
    }

    private void setMvp(float[] model) {
        Matrix.multiplyMM(mMvp, 0, mProj, 0, model, 0);
        GLES20.glUseProgram(mQuadProgram);
        GLES20.glUniformMatrix4fv(mQuadMvpLoc, 1, false, mMvp, 0);
        GLES20.glUniform1i(mQuadSamplerLoc, 0);
    }

    private void setLineMvp(float[] model) {
        Matrix.multiplyMM(mMvp, 0, mProj, 0, model, 0);
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
        mPosBuffer = toFloatBuffer(positions);
        mTexBuffer = toFloatBuffer(uvs);

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
        mPosBuffer = toFloatBuffer(positions);
        mTexBuffer = toFloatBuffer(uvs);

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

    private void updateAutoRotation(long timeMs) {
        if (mLastTimeMs == 0L) {
            mLastTimeMs = timeMs;
            return;
        }
        long delta = timeMs - mLastTimeMs;
        if (delta > 80) delta = 80;
        mAutoRotation += 0.3f * delta / 35f;
        while (mAutoRotation > 360f) mAutoRotation -= 360f;
        mLastTimeMs = timeMs;
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

    private void initPointData() {
        int outlen = mPointData.length / 8;
        int half = outlen / 2;
        for (int i = 0; i < outlen; i++) {
            mPointData[i * 8] = i - half;
            mPointData[i * 8 + 2] = 0f;
            mPointData[i * 8 + 3] = 0f;
            mPointData[i * 8 + 4] = i - half;
            mPointData[i * 8 + 6] = 1.0f;
            mPointData[i * 8 + 7] = 0f;
        }
    }

    private void updateWaveData() {
        int len = 0;
        if (mAudioCapture != null) {
            mVizData = mAudioCapture.getFormattedData(512, 1);
            len = mVizData.length;
        }

        if (len == 0) {
            if (mIdle == 0) {
                mIdle = 1;
            }
            return;
        }

        if (mIdle != 0) {
            mIdle = 0;
        }

        len /= 4;
        if (len > LINE_COUNT) len = LINE_COUNT;
        for (int i = 0; i < len; i++) {
            int amp = (mVizData[i * 4] + mVizData[i * 4 + 1] + mVizData[i * 4 + 2] + mVizData[i * 4 + 3]);
            mPointData[i * 8 + 1] = amp;
            mPointData[i * 8 + 5] = -amp;
        }
        mWaveCounter++;
    }

    private void applyIdleAndFade() {
        if (mIdle != 0) {
            if (fadeoutcounter > 0) {
                for (int i = 0; i < LINE_COUNT; i++) {
                    float val = Math.abs(mPointData[i * 8 + 1]);
                    val = val * 0.95f;
                    if (val < 2f) val = 2f;
                    mPointData[i * 8 + 1] = val;
                    mPointData[i * 8 + 5] = -val;
                }
                fadeoutcounter--;
                if (fadeoutcounter == 0) {
                    wave1amp = 0;
                    wave2amp = 0;
                    wave3amp = 0;
                    wave4amp = 0;
                }
            } else {
                makeIdleWave(mPointData);
            }
            fadeincounter = 15;
        } else {
            if (fadeincounter > 0 && fadeoutcounter == 0) {
                makeIdleWave(idleWave);
                if (lastWaveCounter != mWaveCounter) {
                    lastWaveCounter = mWaveCounter;
                    for (int i = 0; i < LINE_COUNT; i++) {
                        float val = Math.abs(mPointData[i * 8 + 1]);
                        mPointData[i * 8 + 1] = (val * (15 - fadeincounter) + idleWave[i * 8 + 1] * fadeincounter) / 15f;
                        mPointData[i * 8 + 5] = (-val * (15 - fadeincounter) + idleWave[i * 8 + 5] * fadeincounter) / 15f;
                    }
                }
                fadeincounter--;
                if (fadeincounter == 0) {
                    fadeoutcounter = 100;
                }
            } else {
                fadeoutcounter = 100;
            }
        }
    }

    private void makeIdleWave(float[] points) {
        float amp1 = (float) Math.sin(0.007f * wave1amp) * 120f * 1024f;
        float amp2 = (float) Math.sin(0.023f * wave2amp) * 80f * 1024f;
        float amp3 = (float) Math.sin(0.011f * wave3amp) * 40f * 1024f;
        float amp4 = (float) Math.sin(0.031f * wave4amp) * 20f * 1024f;
        for (int i = 0; i < LINE_COUNT; i++) {
            float val = (float) (Math.sin(0.013f * (wave1pos + i * 4)) * amp1
                    + Math.sin(0.029f * (wave2pos + i * 4)) * amp2);
            float off = (float) (Math.sin(0.005f * (wave3pos + i * 4)) * amp3
                    + Math.sin(0.017f * (wave4pos + i * 4)) * amp4);
            if (val < 2f && val > -2f) val = 2f;
            points[i * 8 + 1] = val + off;
            points[i * 8 + 5] = -val + off;
        }
    }

    private void updateLineBuffers() {
        for (int i = 0; i < LINE_COUNT; i++) {
            int base = i * 8;
            int out = i * 4;
            mLinePositions[out] = mPointData[base];
            mLinePositions[out + 1] = mPointData[base + 1];
            mLinePositions[out + 2] = mPointData[base + 4];
            mLinePositions[out + 3] = mPointData[base + 5];

            mLineTexCoords[out] = mPointData[base + 2];
            mLineTexCoords[out + 1] = mPointData[base + 3];
            mLineTexCoords[out + 2] = mPointData[base + 6];
            mLineTexCoords[out + 3] = mPointData[base + 7];
        }
        mLinePosBuffer = toFloatBuffer(mLinePositions);
        mLineTexBuffer = toFloatBuffer(mLineTexCoords);
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
