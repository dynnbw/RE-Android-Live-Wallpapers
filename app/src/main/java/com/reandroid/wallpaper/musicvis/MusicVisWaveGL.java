package com.reandroid.wallpaper.musicvis;

import com.reandroid.utils.GLTextureUtils;
import android.content.Context;
import android.content.SharedPreferences;
import android.opengl.GLES30;
import android.util.Log;

import com.reandroid.utils.AssetLoader;
import com.reandroid.gles.GLESScene;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * GL renderer for waveform wallpapers (vis2 PCM, vis3 FFT).
 * Delegates all scene logic to WaveScene.
 */
public class MusicVisWaveGL extends GLESScene {

    private static final String TAG = "MusicVisWaveGL";

    protected final WaveScene mScene;
    protected final Context mContext;

    private int mProgram;
    private int mPosLoc;
    private int mTexLoc;
    private int mMvpLoc;
    private int mSamplerLoc;
    private int mTextureId;

    private FloatBuffer mPosBuffer;
    private FloatBuffer mTexBuffer;

    // HSL colorization
    private int mColorProgram, mColorPosLoc, mColorTexLoc, mColorMvpLoc, mColorSamplerLoc, mColorAdjustLoc;
    private int mGreyTextureId;
    private FloatBuffer mAdjustBuffer;
    private final String mLineTextureAssetPath;

    public MusicVisWaveGL(int width, int height, Context context, WaveScene.Mode mode, String textureAssetPath) {
        super(width, height);
        mContext = context;
        mScene = new WaveScene(width, height, mode, context);
        mLineTextureAssetPath = textureAssetPath;
    }

    /** Preview-friendly constructor: uses PCM mode. */
    public MusicVisWaveGL(int width, int height, Context context) {
        this(width, height, context, WaveScene.Mode.PCM, "musicvis/drawable/musicvis_fire.png");
    }

    public void setPluginPrefs(SharedPreferences p) {
        mScene.setPluginPrefs(p);
    }

    @Override
    protected void onCreate() {}

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
        if (mProgram == 0) return;

        WaveScene s = mScene;

        if (s.mFftSizeChanged && s.mMode == WaveScene.Mode.FFT) {
            s.mFftSizeChanged = false;
            if (s.mAudioCapture != null) {
                s.mAudioCapture.stop();
                s.mAudioCapture.release();
            }
            s.mAudioCapture = new AudioCapture(AudioCapture.TYPE_FFT, s.mFftSize);
            s.mVizData = new int[s.mAudioCapture.getSize()];
            s.mAnalyzer = new int[s.mAudioCapture.getSize() / 2];
            s.mPcmSmoothed = new float[s.mAudioCapture.getSize()];
            s.mAudioCapture.start();
        }

        s.updateWaveData();
        s.applyIdleAndFade();
        s.updateBuffers();
        s.updateAdjustBuffer();
        s.updateMvp();

        uploadBuffers(s);

        GLES30.glClearColor(s.mBgColor[0], s.mBgColor[1], s.mBgColor[2], 1.0f);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);

        if (s.mRecolorEnabled && mColorProgram != 0) {
            GLES30.glUseProgram(mColorProgram);
            GLES30.glUniformMatrix4fv(mColorMvpLoc, 1, false, s.mMvp, 0);
            GLES30.glUniform1i(mColorSamplerLoc, 0);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mGreyTextureId);
            GLES30.glEnableVertexAttribArray(mColorPosLoc);
            GLES30.glEnableVertexAttribArray(mColorTexLoc);
            GLES30.glEnableVertexAttribArray(mColorAdjustLoc);
            GLES30.glVertexAttribPointer(mColorPosLoc, 2, GLES30.GL_FLOAT, false, 0, mPosBuffer);
            GLES30.glVertexAttribPointer(mColorTexLoc, 2, GLES30.GL_FLOAT, false, 0, mTexBuffer);
            GLES30.glVertexAttribPointer(mColorAdjustLoc, 3, GLES30.GL_FLOAT, false, 0, mAdjustBuffer);
            int dm = s.mUseTriangleStrip ? GLES30.GL_TRIANGLE_STRIP : GLES30.GL_LINES;
            GLES30.glDrawArrays(dm, 0, WaveScene.LINE_COUNT * 2);
            GLES30.glDisableVertexAttribArray(mColorPosLoc);
            GLES30.glDisableVertexAttribArray(mColorTexLoc);
            GLES30.glDisableVertexAttribArray(mColorAdjustLoc);
        } else {
            GLES30.glUseProgram(mProgram);
            GLES30.glUniformMatrix4fv(mMvpLoc, 1, false, s.mMvp, 0);
            GLES30.glUniform1i(mSamplerLoc, 0);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mTextureId);
            GLES30.glEnableVertexAttribArray(mPosLoc);
            GLES30.glEnableVertexAttribArray(mTexLoc);
            GLES30.glVertexAttribPointer(mPosLoc, 2, GLES30.GL_FLOAT, false, 0, mPosBuffer);
            GLES30.glVertexAttribPointer(mTexLoc, 2, GLES30.GL_FLOAT, false, 0, mTexBuffer);
            int dm = s.mUseTriangleStrip ? GLES30.GL_TRIANGLE_STRIP : GLES30.GL_LINES;
            GLES30.glDrawArrays(dm, 0, WaveScene.LINE_COUNT * 2);
            GLES30.glDisableVertexAttribArray(mPosLoc);
            GLES30.glDisableVertexAttribArray(mTexLoc);
        }
    }

    private void initGLIfNeeded() {
        if (mProgram != 0 || mResources == null) return;

        GLES30.glClearColor(0f, 0f, 0f, 1f);

        String vs = AssetLoader.readText(mContext, "musicvis/shaders/GLES/musicvis_wave_vs.glsl");
        String fs = AssetLoader.readText(mContext, "musicvis/shaders/GLES/musicvis_wave_fs.glsl");
        mProgram = createProgram(vs, fs);
        if (mProgram == 0) { Log.e(TAG, "Program creation failed"); return; }
        mPosLoc = GLES30.glGetAttribLocation(mProgram, "aPosition");
        mTexLoc = GLES30.glGetAttribLocation(mProgram, "aTexCoord");
        mMvpLoc = GLES30.glGetUniformLocation(mProgram, "uMVP");
        mSamplerLoc = GLES30.glGetUniformLocation(mProgram, "uTex");
        mTextureId = GLTextureUtils.loadTextureFromAsset(mContext, mLineTextureAssetPath);

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
        mGreyTextureId = GLTextureUtils.loadTextureFromAsset(mContext, "musicvis/drawable/musicvis_grey.png");

        int posSize = WaveScene.LINE_COUNT * 2 * 2 * 4;
        int texSize = WaveScene.LINE_COUNT * 2 * 2 * 4;
        int adjSize = WaveScene.LINE_COUNT * 2 * 3 * 4;
        mPosBuffer = ByteBuffer.allocateDirect(posSize).order(ByteOrder.nativeOrder()).asFloatBuffer();
        mTexBuffer = ByteBuffer.allocateDirect(texSize).order(ByteOrder.nativeOrder()).asFloatBuffer();
        mAdjustBuffer = ByteBuffer.allocateDirect(adjSize).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    private void uploadBuffers(WaveScene s) {
        mPosBuffer.position(0);
        mPosBuffer.put(s.mPositions).position(0);
        mTexBuffer.position(0);
        mTexBuffer.put(s.mTexCoords).position(0);
        mAdjustBuffer.position(0);
        mAdjustBuffer.put(s.mAdjustData).position(0);
    }


}
