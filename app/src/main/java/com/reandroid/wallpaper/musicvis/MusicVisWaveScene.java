package com.reandroid.wallpaper.musicvis;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.util.Log;

import com.reandroid.wallpaper.R;
import com.reandroid.gles.GLESScene;
import com.reandroid.gles.RawResourceLoader;

import androidx.preference.PreferenceManager;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;

public class MusicVisWaveScene extends GLESScene {
    public enum Mode { PCM, FFT }

    private static final String TAG = "MusicVisWaveScene";

    private static final int LINE_COUNT = 1024;

    private final Context mContext;
    private final Mode mMode;
    private final int mTextureResId;

    private int mProgram;
    private int mPosLoc;
    private int mTexLoc;
    private int mMvpLoc;
    private int mSamplerLoc;
    private int mTextureId;

    private FloatBuffer mPosBuffer;
    private FloatBuffer mTexBuffer;

    private final float[] mPointData = new float[LINE_COUNT * 8];
    private final float[] mPositions = new float[LINE_COUNT * 2 * 2];
    private final float[] mTexCoords = new float[LINE_COUNT * 2 * 2];

    private final float[] mProj = new float[16];
    private final float[] mModel = new float[16];
    private final float[] mMvp = new float[16];

    private AudioCapture mAudioCapture;
    private int[] mVizData = new int[1024];
    private short[] mAnalyzer = new short[512];

    private int mIdle = 0;
    private int mWaveCounter = 0;
    private float mYRotation = 0f;

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
    private final float[] idleWave = new float[8192];
    private int lastWaveCounter = 0;

    private boolean mUseTriangleStrip = true;
    private boolean mHasPrefInit = false;

    // HSL colorization
    private int mColorProgram, mColorPosLoc, mColorTexLoc, mColorMvpLoc, mColorSamplerLoc, mColorAdjustLoc;
    private int mGreyTextureId;
    private FloatBuffer mAdjustBuffer;
    private final float[] mAdjustData = new float[LINE_COUNT * 2 * 3];
    private float mHue, mSaturation = 1f, mBrightness = 1f;
    private boolean mRecolorEnabled;
    private final float[] mBgColor = new float[3];

    private static final int FADEOUT_LENGTH = 100;
    private static final float FADEOUT_FACTOR = 0.95f;
    private static final int FADEIN_LENGTH = 15;

    public MusicVisWaveScene(int width, int height, Context context, Mode mode, int textureResId) {
        super(width, height);
        mContext = context;
        mMode = mode;
        mTextureResId = textureResId;
        initPointData();
    }

    @Override
    protected void onCreate() {
        // no-op
    }

    @Override
    public void start() {
        if (mAudioCapture == null) {
            int type = (mMode == Mode.FFT) ? AudioCapture.TYPE_FFT : AudioCapture.TYPE_PCM;
            int size = (mMode == Mode.FFT) ? 512 : 1024;
            mAudioCapture = new AudioCapture(type, size);
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
        mYRotation = (xOffset * 4f) * (mMode == Mode.FFT ? 360f : 180f);
    }

    @Override
    public void drawFrame(long timeMs) {
        initGLIfNeeded();
        if (mProgram == 0) return;

        updateSettings();

        updateWaveData();
        applyIdleAndFade();
        updateBuffers();
        updateAdjustBuffer();
        updateMvp();

        GLES20.glClearColor(mBgColor[0], mBgColor[1], mBgColor[2], 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        if (mRecolorEnabled && mColorProgram != 0) {
            GLES20.glUseProgram(mColorProgram);
            GLES20.glUniformMatrix4fv(mColorMvpLoc, 1, false, mMvp, 0);
            GLES20.glUniform1i(mColorSamplerLoc, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mGreyTextureId);
            GLES20.glEnableVertexAttribArray(mColorPosLoc);
            GLES20.glEnableVertexAttribArray(mColorTexLoc);
            GLES20.glEnableVertexAttribArray(mColorAdjustLoc);
            GLES20.glVertexAttribPointer(mColorPosLoc, 2, GLES20.GL_FLOAT, false, 0, mPosBuffer);
            GLES20.glVertexAttribPointer(mColorTexLoc, 2, GLES20.GL_FLOAT, false, 0, mTexBuffer);
            GLES20.glVertexAttribPointer(mColorAdjustLoc, 3, GLES20.GL_FLOAT, false, 0, mAdjustBuffer);
            int dm = mUseTriangleStrip ? GLES20.GL_TRIANGLE_STRIP : GLES20.GL_LINES;
            GLES20.glDrawArrays(dm, 0, LINE_COUNT * 2);
            GLES20.glDisableVertexAttribArray(mColorPosLoc);
            GLES20.glDisableVertexAttribArray(mColorTexLoc);
            GLES20.glDisableVertexAttribArray(mColorAdjustLoc);
        } else {
            GLES20.glUseProgram(mProgram);
            GLES20.glUniformMatrix4fv(mMvpLoc, 1, false, mMvp, 0);
            GLES20.glUniform1i(mSamplerLoc, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextureId);
            GLES20.glEnableVertexAttribArray(mPosLoc);
            GLES20.glEnableVertexAttribArray(mTexLoc);
            GLES20.glVertexAttribPointer(mPosLoc, 2, GLES20.GL_FLOAT, false, 0, mPosBuffer);
            GLES20.glVertexAttribPointer(mTexLoc, 2, GLES20.GL_FLOAT, false, 0, mTexBuffer);
            int dm = mUseTriangleStrip ? GLES20.GL_TRIANGLE_STRIP : GLES20.GL_LINES;
            GLES20.glDrawArrays(dm, 0, LINE_COUNT * 2);
            GLES20.glDisableVertexAttribArray(mPosLoc);
            GLES20.glDisableVertexAttribArray(mTexLoc);
        }
    }

    private void initGLIfNeeded() {
        if (mProgram != 0 || mResources == null) return;

        GLES20.glClearColor(0f, 0f, 0f, 1f);

        String vs = RawResourceLoader.readRawText(mResources, R.raw.musicvis_wave_vs);
        String fs = RawResourceLoader.readRawText(mResources, R.raw.musicvis_wave_fs);
        mProgram = createProgram(vs, fs);
        if (mProgram == 0) { Log.e(TAG, "Program creation failed"); return; }
        mPosLoc = GLES20.glGetAttribLocation(mProgram, "aPosition");
        mTexLoc = GLES20.glGetAttribLocation(mProgram, "aTexCoord");
        mMvpLoc = GLES20.glGetUniformLocation(mProgram, "uMVP");
        mSamplerLoc = GLES20.glGetUniformLocation(mProgram, "uTex");
        mTextureId = GLTextureUtils.loadTexture(mResources, mTextureResId);

        String cvs = RawResourceLoader.readRawText(mResources, R.raw.musicvis_wave_color_vs);
        String cfs = RawResourceLoader.readRawText(mResources, R.raw.musicvis_wave_color_fs);
        mColorProgram = createProgram(cvs, cfs);
        if (mColorProgram != 0) {
            mColorPosLoc = GLES20.glGetAttribLocation(mColorProgram, "aPosition");
            mColorTexLoc = GLES20.glGetAttribLocation(mColorProgram, "aTexCoord");
            mColorAdjustLoc = GLES20.glGetAttribLocation(mColorProgram, "aAdjust");
            mColorMvpLoc = GLES20.glGetUniformLocation(mColorProgram, "uMVP");
            mColorSamplerLoc = GLES20.glGetUniformLocation(mColorProgram, "uTex");
        }
        mGreyTextureId = GLTextureUtils.loadTexture(mResources, R.drawable.musicvis_grey);
    }

    private void updateSettings() {
        String pn = (mMode == Mode.PCM) ? "musicvis2_prefs" : "musicvis3_prefs";
        SharedPreferences p = mContext.getSharedPreferences(pn, Context.MODE_PRIVATE);
        mUseTriangleStrip = p.getBoolean("musicvis_use_triangle_strip", true);
        mRecolorEnabled = p.getBoolean("musicvis_recolor", false);
        mHue = safeGetInt(p, "musicvis_hue", 0) / 255f;
        mSaturation = safeGetInt(p, "musicvis_saturation", 255) / 255f;
        mBrightness = safeGetInt(p, "musicvis_brightness", 255) / 255f;
        String hex = p.getString("musicvis_bg_color", "#000000");
        try { int c = Color.parseColor(hex); mBgColor[0] = Color.red(c)/255f; mBgColor[1] = Color.green(c)/255f; mBgColor[2] = Color.blue(c)/255f; }
        catch (Exception e) { mBgColor[0] = mBgColor[1] = mBgColor[2] = 0f; }
    }

    private static int safeGetInt(SharedPreferences p, String k, int d) {
        try { return p.getInt(k, d); } catch (ClassCastException e) { return d; }
    }

    private void updateAdjustBuffer() {
        float h = mRecolorEnabled ? mHue : -1f;
        float s = mRecolorEnabled ? mSaturation : 1f;
        float v = mRecolorEnabled ? mBrightness : 1f;
        for (int i = 0; i < LINE_COUNT * 2; i++) {
            int b = i * 3; mAdjustData[b] = h; mAdjustData[b + 1] = s; mAdjustData[b + 2] = v;
        }
        mAdjustBuffer = toFloatBuffer(mAdjustData);
    }

    private void initPointData() {
        int outlen = mPointData.length / 8;
        int half = outlen / 2;
        for (int i = 0; i < outlen; i++) {
            mPointData[i * 8] = i - half;      // start X
            mPointData[i * 8 + 2] = 0f;       // start S
            mPointData[i * 8 + 3] = 0f;       // start T
            mPointData[i * 8 + 4] = i - half; // end X
            mPointData[i * 8 + 6] = 1.0f;     // end S
            mPointData[i * 8 + 7] = 0f;       // end T
        }
    }

    private void updateWaveData() {
        int len = 0;
        if (mAudioCapture != null) {
            if (mMode == Mode.PCM) {
                mVizData = mAudioCapture.getFormattedData(1, 1);
                len = mVizData.length;
            } else {
                mVizData = mAudioCapture.getFormattedData(1, 1);
                len = mVizData.length / 2;
            }
        }

        if (len == 0) {
            if (mIdle == 0) {
                mIdle = 1;
            }
            return;
        }

        if (mMode == Mode.PCM) {
            int outlen = mPointData.length / 8;
            if (len > outlen) len = outlen;
            if (mIdle != 0) mIdle = 0;
            for (int i = 0; i < len; i++) {
                int amp = mVizData[i];
                mPointData[i * 8 + 1] = amp;
                mPointData[i * 8 + 5] = -amp;
            }
        } else {
            len = len / 2; // bins are in pairs
            if (len > mAnalyzer.length) len = mAnalyzer.length;
            if (mIdle != 0) mIdle = 0;

            for (int i = 1; i < len - 1; i++) {
                int val1 = mVizData[i * 2];
                int val2 = mVizData[i * 2 + 1];
                int val = val1 * val1 + val2 * val2;
                short newval = (short) (val * (i / 16 + 1));
                short oldval = mAnalyzer[i];
                if (newval < oldval - 800) {
                    newval = (short) (oldval - 800);
                }
                mAnalyzer[i] = newval;
            }

            int outlen = mPointData.length / 8;
            int width = Math.min(mWidth, outlen);
            int skip = (outlen - width) / 2;

            int srcidx = 0;
            int cnt = 0;
            for (int i = 0; i < width; i++) {
                float val = mAnalyzer[srcidx] / 8f;
                if (val < 1f && val > -1f) val = 1f;
                mPointData[(i + skip) * 8 + 1] = val;
                mPointData[(i + skip) * 8 + 5] = -val;
                cnt += len;
                if (cnt > width) {
                    srcidx++;
                    cnt -= width;
                }
            }
        }
        mWaveCounter++;
    }

    private void applyIdleAndFade() {
        int width = mWidth;
        if (width > 1024) width = 1024;
        int skip = (1024 - width) / 2;
        int end = 1024 - skip;

        if (mIdle != 0) {
            if (fadeoutcounter > 0) {
                for (int i = skip; i < end; i++) {
                    float val = Math.abs(mPointData[i * 8 + 1]);
                    val = val * FADEOUT_FACTOR;
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
            fadeincounter = FADEIN_LENGTH;
        } else {
            if (fadeincounter > 0 && fadeoutcounter == 0) {
                makeIdleWave(idleWave);
                if (lastWaveCounter != mWaveCounter) {
                    lastWaveCounter = mWaveCounter;
                    for (int i = skip; i < end; i++) {
                        float val = Math.abs(mPointData[i * 8 + 1]);
                        mPointData[i * 8 + 1] = (val * (FADEIN_LENGTH - fadeincounter)
                                + idleWave[i * 8 + 1] * fadeincounter) / FADEIN_LENGTH;
                        mPointData[i * 8 + 5] = (-val * (FADEIN_LENGTH - fadeincounter)
                                + idleWave[i * 8 + 5] * fadeincounter) / FADEIN_LENGTH;
                    }
                }
                fadeincounter--;
                if (fadeincounter == 0) {
                    fadeoutcounter = FADEOUT_LENGTH;
                }
            } else {
                fadeoutcounter = FADEOUT_LENGTH;
            }
        }
    }

    private void makeIdleWave(float[] points) {
        float amp1 = (float) Math.sin(0.007f * wave1amp) * 120f;
        float amp2 = (float) Math.sin(0.023f * wave2amp) * 80f;
        float amp3 = (float) Math.sin(0.011f * wave3amp) * 40f;
        float amp4 = (float) Math.sin(0.031f * wave4amp) * 20f;
        int skip = (1024 - mWidth) / 2;
        if (skip < 0) skip = 0;
        int end = 1024 - skip;
        for (int i = skip; i < end; i++) {
            float val = (float) (Math.sin(0.013f * (wave1pos + i)) * amp1
                    + Math.sin(0.029f * (wave2pos + i)) * amp2);
            float off = (float) (Math.sin(0.005f * (wave3pos + i)) * amp3
                    + Math.sin(0.017f * (wave4pos + i)) * amp4);
            if (val < 2f && val > -2f) val = 2f;
            points[i * 8 + 1] = val + off;
            points[i * 8 + 5] = -val + off;
        }
        wave1pos++;
        wave1amp++;
        wave2pos--;
        wave2amp++;
        wave3pos++;
        wave3amp++;
        wave4pos++;
        wave4amp++;
    }

    private void updateBuffers() {
        for (int i = 0; i < LINE_COUNT; i++) {
            int base = i * 8;
            int out = i * 4;
            mPositions[out] = mPointData[base];
            mPositions[out + 1] = mPointData[base + 1];
            mPositions[out + 2] = mPointData[base + 4];
            mPositions[out + 3] = mPointData[base + 5];

            mTexCoords[out] = mPointData[base + 2];
            mTexCoords[out + 1] = mPointData[base + 3];
            mTexCoords[out + 2] = mPointData[base + 6];
            mTexCoords[out + 3] = mPointData[base + 7];
        }
        mPosBuffer = toFloatBuffer(mPositions);
        mTexBuffer = toFloatBuffer(mTexCoords);
    }

    private void updateMvp() {
        float left, right, bottom, top;
        if (mWidth > mHeight) {
            float aspect = (float) mWidth / (float) mHeight;
            left = -aspect;
            right = aspect;
            bottom = -1f;
            top = 1f;
        } else {
            float aspect = (float) mHeight / (float) mWidth;
            left = -1f;
            right = 1f;
            bottom = -aspect;
            top = aspect;
        }
        Matrix.orthoM(mProj, 0, left, right, bottom, top, -1f, 1f);

        float scale = 0.004165f * (1.0f + 2f * Math.abs((float) Math.sin(Math.toRadians(mYRotation))));
        Matrix.setIdentityM(mModel, 0);
        Matrix.rotateM(mModel, 0, mYRotation, 0f, 0f, 1f);
        Matrix.scaleM(mModel, 0, scale, scale, scale);

        Matrix.multiplyMM(mMvp, 0, mProj, 0, mModel, 0);
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
            Log.e(TAG, "Link failed: " + GLES20.glGetProgramInfoLog(program));
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
            Log.e(TAG, "Shader compile failed: " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }
}
