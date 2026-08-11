package com.reandroid.wallpaper.musicvis.vis6;

import com.reandroid.utils.GLTextureUtils;
import android.content.Context;
import android.content.SharedPreferences;
import android.opengl.GLES20;

import com.reandroid.utils.AssetLoader;
import com.reandroid.gles.GLESScene;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * GL renderer for circle/ring wallpaper (vis6).
 * Delegates all scene logic to CircleScene.
 */
public class MusicVisCircleGL extends GLESScene {

    private final Context mContext;
    private final CircleScene mScene;

    // GL
    private int mProgram, mPosLoc, mMvpLoc;
    private int mColorProgram, mColorPosLoc, mColorMvpLoc, mColorSamplerLoc, mColorAdjustLoc;
    private int mGreyTextureId;

    // Per-ring GL buffers
    private FloatBuffer[] mRingBuffers;
    private FloatBuffer[] mRingAdjustBuffers;

    private SharedPreferences mPluginPrefs;

    public MusicVisCircleGL(int width, int height, Context context) {
        super(width, height);
        mContext = context;
        mScene = new CircleScene(width, height, context);
    }

    public void setPluginPrefs(SharedPreferences p) {
        mPluginPrefs = p;
        mScene.setPluginPrefs(p);
        // Scene re-read the prefs above — reallocate GL buffers if ring count changed
        if (mRingBuffers == null || mRingBuffers.length != mScene.mRingCount) {
            reallocateRingBuffers();
        }
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

        CircleScene s = mScene;
        s.updateAudio();
        s.updateRingVertices();
        s.updateMvp();

        uploadRingBuffers(s);

        GLES20.glClearColor(s.mBgColor[0], s.mBgColor[1], s.mBgColor[2], 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        if (s.mRecolorEnabled && mColorProgram != 0) {
            GLES20.glUseProgram(mColorProgram);
            GLES20.glUniformMatrix4fv(mColorMvpLoc, 1, false, s.mMvp, 0);
            GLES20.glUniform1i(mColorSamplerLoc, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mGreyTextureId);
            GLES20.glEnableVertexAttribArray(mColorPosLoc);
            GLES20.glEnableVertexAttribArray(mColorAdjustLoc);
            for (int i = 0; i < s.mRingCount; i++) {
                float hue = s.mRecolorDynamic ? (s.mHue + i * 0.04f) % 1f : s.mHue;
                float sat = s.mSaturation, bri = s.mBrightness;
                for (int j = 0; j < CircleScene.STRIP_VERTS; j++) {
                    s.mRingAdjust[i][j * 3]     = hue;
                    s.mRingAdjust[i][j * 3 + 1] = sat;
                    s.mRingAdjust[i][j * 3 + 2] = bri;
                }
                mRingAdjustBuffers[i].position(0);
                mRingAdjustBuffers[i].put(s.mRingAdjust[i]).position(0);
                GLES20.glVertexAttribPointer(mColorPosLoc, 2, GLES20.GL_FLOAT, false, 0, mRingBuffers[i]);
                GLES20.glVertexAttribPointer(mColorAdjustLoc, 3, GLES20.GL_FLOAT, false, 0, mRingAdjustBuffers[i]);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, CircleScene.STRIP_VERTS);
            }
            GLES20.glDisableVertexAttribArray(mColorPosLoc);
            GLES20.glDisableVertexAttribArray(mColorAdjustLoc);
        } else {
            GLES20.glUseProgram(mProgram);
            GLES20.glUniformMatrix4fv(mMvpLoc, 1, false, s.mMvp, 0);
            GLES20.glEnableVertexAttribArray(mPosLoc);
            for (int i = 0; i < s.mRingCount; i++) {
                GLES20.glVertexAttribPointer(mPosLoc, 2, GLES20.GL_FLOAT, false, 0, mRingBuffers[i]);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, CircleScene.STRIP_VERTS);
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

        allocateRingBuffers();
    }

    private void allocateRingBuffers() {
        CircleScene s = mScene;
        mRingBuffers = new FloatBuffer[s.mRingCount];
        mRingAdjustBuffers = new FloatBuffer[s.mRingCount];
        for (int i = 0; i < s.mRingCount; i++) {
            mRingBuffers[i] = ByteBuffer.allocateDirect(s.mRingVertices[i].length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            mRingAdjustBuffers[i] = ByteBuffer.allocateDirect(s.mRingAdjust[i].length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
        }
    }

    private void reallocateRingBuffers() {
        allocateRingBuffers();
    }

    private void uploadRingBuffers(CircleScene s) {
        for (int i = 0; i < s.mRingCount; i++) {
            mRingBuffers[i].position(0);
            mRingBuffers[i].put(s.mRingVertices[i]).position(0);
        }
    }


    private int loadShader(int t, String s) {
        int sh = GLES20.glCreateShader(t);
        GLES20.glShaderSource(sh, s);
        GLES20.glCompileShader(sh);
        return sh;
    }
}
