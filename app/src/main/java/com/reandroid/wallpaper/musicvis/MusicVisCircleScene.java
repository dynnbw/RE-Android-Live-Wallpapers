package com.reandroid.wallpaper.musicvis;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.opengl.GLES20;
import android.opengl.Matrix;

import com.reandroid.gles.AssetLoader;
import com.reandroid.gles.GLESScene;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class MusicVisCircleScene extends GLESScene {
    private static final int RING_SEGMENTS = 128;
    private static final int STRIP_VERTS = (RING_SEGMENTS + 1) * 2;
    private static final float TWO_PI = (float) (Math.PI * 2.0);
    private static final int DEFAULT_RING_COUNT = 16;
    private static final float HALF_THICKNESS_SCALE = 0.002f;

    private final Context mContext;

    // GL
    private int mProgram, mPosLoc, mMvpLoc;
    private int mColorProgram, mColorPosLoc, mColorMvpLoc, mColorSamplerLoc, mColorAdjustLoc;
    private int mGreyTextureId;

    // Dynamic ring data
    private int mRingCount;
    private float mHalfThickness;
    private FloatBuffer[] mRingBuffers;
    private FloatBuffer[] mRingAdjustBuffers;
    private float[][] mRingVertices;
    private float[][] mRingAdjust;
    private float[] mRingAmps;
    private int[] mBinStart, mBinEnd;

    private final float[] mProj = new float[16];
    private final float[] mMvp = new float[16];

    // Audio
    private AudioCapture mAudioCapture;
    private int[] mVizData = new int[512];

    // State
    private float mRotation;
    private boolean mRecolorEnabled;
    private boolean mRecolorDynamic;
    private float mHue, mSaturation = 1f, mBrightness = 1f;
    private final float[] mBgColor = new float[3];

    public MusicVisCircleScene(int width, int height, Context context) {
        super(width, height);
        mContext = context;
        mRingCount = DEFAULT_RING_COUNT;
        mHalfThickness = 8 * HALF_THICKNESS_SCALE;
        allocateRingData();
    }

    private void allocateRingData() {
        mRingBuffers = new FloatBuffer[mRingCount];
        mRingAdjustBuffers = new FloatBuffer[mRingCount];
        mRingVertices = new float[mRingCount][STRIP_VERTS * 2];
        mRingAdjust = new float[mRingCount][STRIP_VERTS * 3];
        mRingAmps = new float[mRingCount];
        mBinStart = new int[mRingCount];
        mBinEnd = new int[mRingCount];
        final int binMin = 2;
        final int binMax = 120;
        for (int i = 0; i < mRingCount; i++) {
            double t0 = (double) i / mRingCount;
            double t1 = (double) (i + 1) / mRingCount;
            mBinStart[i] = (int) (binMin * Math.pow((double) binMax / binMin, t0));
            mBinEnd[i] = (int) (binMin * Math.pow((double) binMax / binMin, t1));
        }
    }

    @Override
    protected void onCreate() {}

    @Override
    public void start() {
        if (mAudioCapture == null) mAudioCapture = new AudioCapture(AudioCapture.TYPE_FFT, 512);
        mAudioCapture.start();
    }

    @Override
    public void stop() {
        if (mAudioCapture != null) { mAudioCapture.stop(); mAudioCapture.release(); mAudioCapture = null; }
    }

    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        mRotation = xOffset * 90f;
    }

    @Override
    public void drawFrame(long timeMs) {
        initGLIfNeeded();
        if (mProgram == 0) return;

        updateSettings();
        updateAudio();
        updateRingVertices();
        updateMvp();

        GLES20.glClearColor(mBgColor[0], mBgColor[1], mBgColor[2], 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        if (mRecolorEnabled && mColorProgram != 0) {
            GLES20.glUseProgram(mColorProgram);
            GLES20.glUniformMatrix4fv(mColorMvpLoc, 1, false, mMvp, 0);
            GLES20.glUniform1i(mColorSamplerLoc, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mGreyTextureId);
            GLES20.glEnableVertexAttribArray(mColorPosLoc);
            GLES20.glEnableVertexAttribArray(mColorAdjustLoc);
            for (int i = 0; i < mRingCount; i++) {
                float hue = mRecolorDynamic ? (mHue + i * 0.04f) % 1f : mHue;
                float s = mSaturation, v = mBrightness;
                for (int j = 0; j < STRIP_VERTS; j++) {
                    mRingAdjust[i][j*3]=hue; mRingAdjust[i][j*3+1]=s; mRingAdjust[i][j*3+2]=v;
                }
                mRingAdjustBuffers[i] = toFloatBuffer(mRingAdjust[i]);
                GLES20.glVertexAttribPointer(mColorPosLoc, 2, GLES20.GL_FLOAT, false, 0, mRingBuffers[i]);
                GLES20.glVertexAttribPointer(mColorAdjustLoc, 3, GLES20.GL_FLOAT, false, 0, mRingAdjustBuffers[i]);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, STRIP_VERTS);
            }
            GLES20.glDisableVertexAttribArray(mColorPosLoc);
            GLES20.glDisableVertexAttribArray(mColorAdjustLoc);
        } else {
            GLES20.glUseProgram(mProgram);
            GLES20.glUniformMatrix4fv(mMvpLoc, 1, false, mMvp, 0);
            GLES20.glEnableVertexAttribArray(mPosLoc);
            for (int i = 0; i < mRingCount; i++) {
                GLES20.glVertexAttribPointer(mPosLoc, 2, GLES20.GL_FLOAT, false, 0, mRingBuffers[i]);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, STRIP_VERTS);
            }
            GLES20.glDisableVertexAttribArray(mPosLoc);
        }
    }

    private void initGLIfNeeded() {
        if (mProgram != 0 || mContext == null) return;
        GLES20.glClearColor(0, 0, 0, 1);

        String vs = "attribute vec2 aPosition;uniform mat4 uMVP;void main(){gl_Position=uMVP*vec4(aPosition,0,1);}";
        String fs = "precision mediump float;void main(){gl_FragColor=vec4(1,1,1,0.75);}";
        mProgram = createProgram(vs, fs);
        mPosLoc = GLES20.glGetAttribLocation(mProgram, "aPosition");
        mMvpLoc = GLES20.glGetUniformLocation(mProgram, "uMVP");

        String cvs = AssetLoader.readText(mContext, "musicvis/shaders/GLES/musicvis_wave_color_vs.glsl");
        String cfs = AssetLoader.readText(mContext, "musicvis/shaders/GLES/musicvis_wave_color_fs.glsl");
        mColorProgram = createProgram(cvs, cfs);
        if (mColorProgram != 0) {
            mColorPosLoc = GLES20.glGetAttribLocation(mColorProgram, "aPosition");
            mColorAdjustLoc = GLES20.glGetAttribLocation(mColorProgram, "aAdjust");
            mColorMvpLoc = GLES20.glGetUniformLocation(mColorProgram, "uMVP");
            mColorSamplerLoc = GLES20.glGetUniformLocation(mColorProgram, "uTex");
        }
        mGreyTextureId = GLTextureUtils.loadTextureFromAsset(mContext, "musicvis/drawable/musicvis_grey.png");
    }

    private void updateSettings() {
        SharedPreferences p = mContext.getSharedPreferences("musicvis6_prefs", Context.MODE_PRIVATE);
        mRecolorEnabled = p.getBoolean("musicvis_recolor", false);
        mRecolorDynamic = "dynamic".equals(p.getString("musicvis_recolor_mode", "static"));
        if (!mRecolorDynamic) mHue = safeGetInt(p, "musicvis_hue", 0) / 255f;
        mSaturation = safeGetInt(p, "musicvis_saturation", 255) / 255f;
        mBrightness = safeGetInt(p, "musicvis_brightness", 255) / 255f;
        String hex = p.getString("musicvis_bg_color", "#000000");
        try { int c = Color.parseColor(hex); mBgColor[0]=Color.red(c)/255f; mBgColor[1]=Color.green(c)/255f; mBgColor[2]=Color.blue(c)/255f; }
        catch (Exception e) { mBgColor[0]=mBgColor[1]=mBgColor[2]=0f; }

        int newRingCount = safeGetInt(p, "circle_ring_count", DEFAULT_RING_COUNT);
        float newHalfThickness = safeGetInt(p, "circle_line_width", 8) * HALF_THICKNESS_SCALE;
        if (newRingCount != mRingCount) {
            mRingCount = newRingCount;
            allocateRingData();
        }
        mHalfThickness = newHalfThickness;
    }

    private void updateAudio() {
        if (mAudioCapture == null) return;
        mVizData = mAudioCapture.getFormattedData(1, 1);
        int len = mVizData.length / 2;
        if (len == 0) return;

        for (int r = 0; r < mRingCount; r++) {
            int start = mBinStart[r];
            int end = Math.min(mBinEnd[r], len);
            float sum = 0;
            int count = 0;
            for (int b = start; b < end; b++) {
                int v1 = mVizData[b*2], v2 = mVizData[b*2+1];
                sum += (float) Math.sqrt(v1*v1 + v2*v2);
                count++;
            }
            mRingAmps[r] = count > 0 ? Math.min(1f, sum / count / 40f) : 0f;
        }

        if (mRecolorDynamic && mRecolorEnabled) {
            float avg = 0;
            for (int r = 0; r < mRingCount; r++) avg += mRingAmps[r];
            avg /= mRingCount;
            mHue = (mHue + avg * 0.02f) % 1f;
        }
    }

    private void updateRingVertices() {
        for (int i = 0; i < mRingCount; i++) {
            float baseR = 0.15f + 0.85f * i / Math.max(1, mRingCount - 1f);
            float amp = mRingAmps[i] * 0.15f;
            for (int j = 0; j <= RING_SEGMENTS; j++) {
                float a = TWO_PI * (j % RING_SEGMENTS) / RING_SEGMENTS;
                float r = baseR + amp * (float) Math.sin(a * 5 + i);
                float cos = (float) Math.cos(a);
                float sin = (float) Math.sin(a);
                float ir = r - mHalfThickness;
                float or = r + mHalfThickness;
                int ip = j * 4;
                int op = j * 4 + 2;
                mRingVertices[i][ip]   = cos * ir;
                mRingVertices[i][ip+1] = sin * ir;
                mRingVertices[i][op]   = cos * or;
                mRingVertices[i][op+1] = sin * or;
            }
            mRingBuffers[i] = toFloatBuffer(mRingVertices[i]);
        }
    }

    private void updateMvp() {
        float aspect = (float) mWidth / mHeight;
        float w = 1.2f, h = 1.2f;
        if (aspect >= 1f) w *= aspect; else h /= aspect;
        Matrix.orthoM(mProj, 0, -w, w, -h, h, -1, 1);
        Matrix.setIdentityM(mMvp, 0);
        Matrix.rotateM(mMvp, 0, mRotation, 0, 0, 1);
        float[] tmp = new float[16];
        Matrix.multiplyMM(tmp, 0, mProj, 0, mMvp, 0);
        System.arraycopy(tmp, 0, mMvp, 0, 16);
    }

    private FloatBuffer toFloatBuffer(float[] data) {
        ByteBuffer bb = ByteBuffer.allocateDirect(data.length * 4);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(data).position(0);
        return fb;
    }

    private static int safeGetInt(SharedPreferences p, String k, int d) {
        try { return p.getInt(k, d); } catch (ClassCastException e) { return d; }
    }

    private int createProgram(String vs, String fs) {
        int v = loadShader(GLES20.GL_VERTEX_SHADER, vs);
        int f = loadShader(GLES20.GL_FRAGMENT_SHADER, fs);
        if (v == 0 || f == 0) return 0;
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v); GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        return p;
    }

    private int loadShader(int t, String s) {
        int sh = GLES20.glCreateShader(t);
        GLES20.glShaderSource(sh, s); GLES20.glCompileShader(sh);
        return sh;
    }
}
