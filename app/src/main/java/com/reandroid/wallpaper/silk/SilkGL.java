package com.reandroid.wallpaper.silk;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.util.Log;

import com.reandroid.gles.GLESScene;
import com.reandroid.utils.AssetLoader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Silk wallpaper ("丝语流年") GLES renderer — port of the vivo CSilk engine.
 * <p>
 * Pipeline: full-screen background quad (theme background.png, no blending),
 * then 3 animated ribbons (theme silk.png) with premultiplied blending.
 * One parameterized program replaces the original's 3 per-ribbon programs.
 */
public class SilkGL extends GLESScene {
    private static final String TAG = "SilkGL";

    private final Context mContext;
    private final SilkScene mScene;

    // Ribbon program
    private int mProgram;
    private int mAttrPos, mAttrColor, mAttrCoord;
    private int mUniformRotateFlash, mUniformNewPos;
    private int mUniformOriginColor, mUniformOriginAlpha;
    private int mUniformDecayLen, mUniformDivFactor, mUniformDecay, mUniformSampler;

    // Background program
    private int mBgProgram, mBgAttrPos, mBgAttrCoord, mBgSampler;

    // Buffers
    private final int[] mPosVbo = new int[SilkScene.RIBBON_COUNT];
    private final int[] mColorVbo = new int[SilkScene.RIBBON_COUNT];
    private int mUvVbo;
    private int mIndexVbo;
    private final FloatBuffer[] mPosBuf = new FloatBuffer[SilkScene.RIBBON_COUNT];
    private final FloatBuffer[] mColorBuf = new FloatBuffer[SilkScene.RIBBON_COUNT];
    private final FloatBuffer mQuadPos;
    private final FloatBuffer mQuadUv;

    private int mBackgroundTexture;
    private int mSilkTexture;
    private String mLoadedTheme;
    private boolean mInitialized;
    private long mLastFrameMs;

    public SilkGL(int width, int height, Context context) {
        super(width, height);
        mContext = context;
        mScene = new SilkScene(context);

        // Background quad: positions (-1,1),(1,1),(1,-1),(-1,-1), UV (0,0),(1,0),(1,1),(0,1)
        mQuadPos = createFloatBuffer(new float[]{
                -1f, 1f, 1f, 1f, 1f, -1f, -1f, -1f});
        mQuadUv = createFloatBuffer(new float[]{
                0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f});
    }

    public void setPluginPrefs(SharedPreferences prefs) {
        mScene.setPluginPrefs(prefs);
    }

    /**
     * Non-GL setup only. GL resources are created lazily in {@link #initGL()}
     * on the GL thread — onCreate may run before the EGL context is current
     * (engine path) and is called twice (init + setResources), so creating
     * programs here would fail or leak.
     */
    @Override
    protected void onCreate() {
        if (mContext == null) return;
        mScene.ensurePrefs();
        mScene.reloadPrefs();
    }

    /** Create all GL resources. Must run on the GL thread with a current context. */
    private void initGL() {
        String vs = AssetLoader.readText(mContext, "silk/shaders/GLES/silk_vs.glsl");
        String fs = AssetLoader.readText(mContext, "silk/shaders/GLES/silk_fs.glsl");
        mProgram = createProgram(vs, fs);
        if (mProgram == 0) {
            Log.e(TAG, "Ribbon shader program creation failed");
            return;
        }
        mAttrPos = GLES20.glGetAttribLocation(mProgram, "a_position");
        mAttrColor = GLES20.glGetAttribLocation(mProgram, "a_color");
        mAttrCoord = GLES20.glGetAttribLocation(mProgram, "a_coord");
        mUniformRotateFlash = GLES20.glGetUniformLocation(mProgram, "rotateAngleFlash");
        mUniformNewPos = GLES20.glGetUniformLocation(mProgram, "uNewPos");
        mUniformOriginColor = GLES20.glGetUniformLocation(mProgram, "uOriginColor");
        mUniformOriginAlpha = GLES20.glGetUniformLocation(mProgram, "uOriginAlpha");
        mUniformDecayLen = GLES20.glGetUniformLocation(mProgram, "uDecayLen");
        mUniformDivFactor = GLES20.glGetUniformLocation(mProgram, "uDivFactor");
        mUniformDecay = GLES20.glGetUniformLocation(mProgram, "uDecay");
        mUniformSampler = GLES20.glGetUniformLocation(mProgram, "CC_Texture0");

        String bgVs = AssetLoader.readText(mContext, "silk/shaders/GLES/silk_bg_vs.glsl");
        String bgFs = AssetLoader.readText(mContext, "silk/shaders/GLES/silk_bg_fs.glsl");
        mBgProgram = createProgram(bgVs, bgFs);
        if (mBgProgram == 0) {
            Log.e(TAG, "Background shader program creation failed");
            return;
        }
        mBgAttrPos = GLES20.glGetAttribLocation(mBgProgram, "aPosition");
        mBgAttrCoord = GLES20.glGetAttribLocation(mBgProgram, "aTexCoor");
        mBgSampler = GLES20.glGetUniformLocation(mBgProgram, "sTexture");

        // Static geometry buffers
        int[] bufs = new int[SilkScene.RIBBON_COUNT * 2 + 2];
        GLES20.glGenBuffers(bufs.length, bufs, 0);
        for (int r = 0; r < SilkScene.RIBBON_COUNT; r++) {
            mPosVbo[r] = bufs[r * 2];
            mColorVbo[r] = bufs[r * 2 + 1];
        }
        mUvVbo = bufs[SilkScene.RIBBON_COUNT * 2];
        mIndexVbo = bufs[SilkScene.RIBBON_COUNT * 2 + 1];

        for (int r = 0; r < SilkScene.RIBBON_COUNT; r++) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mPosVbo[r]);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, SilkScene.VERTICES * 2 * 4, null,
                    GLES20.GL_DYNAMIC_DRAW);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mColorVbo[r]);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, SilkScene.VERTICES * 4, null,
                    GLES20.GL_DYNAMIC_DRAW);
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mUvVbo);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, SilkScene.VERTICES * 2 * 4,
                createFloatBuffer(SilkScene.buildUv()), GLES20.GL_STATIC_DRAW);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, mIndexVbo);
        ByteBuffer idx = ByteBuffer.allocateDirect((SilkScene.COLUMNS - 1) * 6)
                .order(ByteOrder.nativeOrder()).put(SilkScene.buildIndices());
        idx.position(0);
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, (SilkScene.COLUMNS - 1) * 6, idx,
                GLES20.GL_STATIC_DRAW);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);

        for (int r = 0; r < SilkScene.RIBBON_COUNT; r++) {
            mPosBuf[r] = createFloatBuffer(new float[SilkScene.VERTICES * 2]);
            mColorBuf[r] = createFloatBuffer(new float[SilkScene.VERTICES]);
        }

        loadTextures(mScene.mTheme);
        mInitialized = true;
    }

    /** Load theme textures on the GL thread. */
    private void loadTextures(String theme) {
        if (mBackgroundTexture != 0) GLES20.glDeleteTextures(1, new int[]{mBackgroundTexture}, 0);
        if (mSilkTexture != 0) GLES20.glDeleteTextures(1, new int[]{mSilkTexture}, 0);
        mBackgroundTexture = 0;
        mSilkTexture = 0;

        // Background: RGB, NEAREST/NEAREST, CLAMP_TO_EDGE (original params)
        Bitmap bg = AssetLoader.decodeBitmap(mContext, SilkScene.themeBackgroundAsset(theme));
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bg, 0);
        bg.recycle();
        mBackgroundTexture = tex[0];

        // Silk ribbon: must be decoded premultiplied for the GL_ONE blend
        // (AssetLoader.decodeBitmap forces inPremultiplied=false).
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPremultiplied = true;
        Bitmap silk = AssetLoader.decodeBitmapWithOptions(mContext,
                SilkScene.themeSilkAsset(theme), opts);
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, silk, 0);
        silk.recycle();
        mSilkTexture = tex[0];

        mLoadedTheme = theme;
    }

    @Override
    public void drawFrame(long timeMs) {
        if (!mInitialized) {
            initGL();   // GL thread, context is current here
        }
        if (!mInitialized) return;

        float dt = mLastFrameMs == 0 ? 0.016f : (timeMs - mLastFrameMs) / 1000f;
        mLastFrameMs = timeMs;
        float dtEff = Math.min(dt, 0.1f) * mScene.mSpeedMultiplier;
        mScene.update(dtEff);

        // Theme changed in prefs → reload textures on the GL thread
        if (!mScene.mTheme.equals(mLoadedTheme)) {
            loadTextures(mScene.mTheme);
        }

        GLES20.glViewport(0, 0, mWidth, mHeight);
        GLES20.glClearColor(0f, 0f, 0f, 0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        drawBackground();
        drawRibbons();
    }

    private void drawBackground() {
        GLES20.glUseProgram(mBgProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mBackgroundTexture);
        GLES20.glUniform1i(mBgSampler, 0);

        GLES20.glEnableVertexAttribArray(mBgAttrPos);
        GLES20.glVertexAttribPointer(mBgAttrPos, 2, GLES20.GL_FLOAT, false, 0, mQuadPos);
        GLES20.glEnableVertexAttribArray(mBgAttrCoord);
        GLES20.glVertexAttribPointer(mBgAttrCoord, 2, GLES20.GL_FLOAT, false, 0, mQuadUv);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4);
        GLES20.glDisableVertexAttribArray(mBgAttrPos);
        GLES20.glDisableVertexAttribArray(mBgAttrCoord);
    }

    private void drawRibbons() {
        GLES20.glUseProgram(mProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mSilkTexture);
        GLES20.glUniform1i(mUniformSampler, 0);

        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        SilkScene.RibbonConfig[] ribbons = SilkScene.RIBBONS;
        for (int r = 0; r < SilkScene.RIBBON_COUNT; r++) {
            SilkScene.RibbonConfig c = ribbons[r];

            GLES20.glUniform3f(mUniformRotateFlash, mScene.mRotSin[r], mScene.mRotCos[r],
                    mScene.mFlash[r]);
            GLES20.glUniform4f(mUniformNewPos, c.newPosX, c.newPosY, 0f, 0f);
            float[] oc = SilkScene.themeOriginColor(mScene.mTheme);
            // Premultiplied origin term: a = 1 so rgb·uOriginAlpha stays premultiplied
            // consistently with the texture term (the shader multiplies by uOriginAlpha).
            GLES20.glUniform4f(mUniformOriginColor, oc[0], oc[1], oc[2], 1f);
            GLES20.glUniform1f(mUniformOriginAlpha, c.originAlpha);
            GLES20.glUniform1f(mUniformDecayLen, c.decayLen);
            GLES20.glUniform1f(mUniformDivFactor, c.divFactor);
            GLES20.glUniform1f(mUniformDecay, c.decay ? 1f : 0f);

            // Position (dynamic)
            FloatBuffer posBuf = mPosBuf[r];
            posBuf.position(0);
            posBuf.put(mScene.mPositions[r]);
            posBuf.position(0);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mPosVbo[r]);
            GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0,
                    SilkScene.VERTICES * 2 * 4, posBuf);
            GLES20.glVertexAttribPointer(mAttrPos, 2, GLES20.GL_FLOAT, false, 0, 0);

            // Color (dynamic)
            FloatBuffer colorBuf = mColorBuf[r];
            colorBuf.position(0);
            colorBuf.put(mScene.mColors[r]);
            colorBuf.position(0);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mColorVbo[r]);
            GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0,
                    SilkScene.VERTICES * 4, colorBuf);
            GLES20.glVertexAttribPointer(mAttrColor, 1, GLES20.GL_FLOAT, false, 0, 0);

            // UV (static, shared)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mUvVbo);
            GLES20.glVertexAttribPointer(mAttrCoord, 2, GLES20.GL_FLOAT, false, 0, 0);

            GLES20.glEnableVertexAttribArray(mAttrPos);
            GLES20.glEnableVertexAttribArray(mAttrColor);
            GLES20.glEnableVertexAttribArray(mAttrCoord);

            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, mIndexVbo);
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, (SilkScene.COLUMNS - 1) * 6,
                    GLES20.GL_UNSIGNED_BYTE, 0);

            GLES20.glDisableVertexAttribArray(mAttrPos);
            GLES20.glDisableVertexAttribArray(mAttrColor);
            GLES20.glDisableVertexAttribArray(mAttrCoord);
        }
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
    }

    @Override
    public void release() {
        int[] bufs = new int[SilkScene.RIBBON_COUNT * 2 + 2];
        System.arraycopy(mPosVbo, 0, bufs, 0, SilkScene.RIBBON_COUNT);
        System.arraycopy(mColorVbo, 0, bufs, SilkScene.RIBBON_COUNT, SilkScene.RIBBON_COUNT);
        bufs[SilkScene.RIBBON_COUNT * 2] = mUvVbo;
        bufs[SilkScene.RIBBON_COUNT * 2 + 1] = mIndexVbo;
        GLES20.glDeleteBuffers(bufs.length, bufs, 0);
        if (mBackgroundTexture != 0) {
            GLES20.glDeleteTextures(1, new int[]{mBackgroundTexture}, 0);
        }
        if (mSilkTexture != 0) {
            GLES20.glDeleteTextures(1, new int[]{mSilkTexture}, 0);
        }
        if (mProgram != 0) GLES20.glDeleteProgram(mProgram);
        if (mBgProgram != 0) GLES20.glDeleteProgram(mBgProgram);
        mProgram = 0;
        mBgProgram = 0;
        mBackgroundTexture = 0;
        mSilkTexture = 0;
        mLoadedTheme = null;
        mInitialized = false;
        mLastFrameMs = 0;
    }
}
