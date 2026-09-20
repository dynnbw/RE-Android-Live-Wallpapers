package com.reandroid.wallpaper.silk;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES30;
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
        mScene = new SilkScene();

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
        mAttrPos = GLES30.glGetAttribLocation(mProgram, "a_position");
        mAttrColor = GLES30.glGetAttribLocation(mProgram, "a_color");
        mAttrCoord = GLES30.glGetAttribLocation(mProgram, "a_coord");
        mUniformRotateFlash = GLES30.glGetUniformLocation(mProgram, "rotateAngleFlash");
        mUniformNewPos = GLES30.glGetUniformLocation(mProgram, "uNewPos");
        mUniformOriginColor = GLES30.glGetUniformLocation(mProgram, "uOriginColor");
        mUniformOriginAlpha = GLES30.glGetUniformLocation(mProgram, "uOriginAlpha");
        mUniformDecayLen = GLES30.glGetUniformLocation(mProgram, "uDecayLen");
        mUniformDivFactor = GLES30.glGetUniformLocation(mProgram, "uDivFactor");
        mUniformDecay = GLES30.glGetUniformLocation(mProgram, "uDecay");
        mUniformSampler = GLES30.glGetUniformLocation(mProgram, "CC_Texture0");

        String bgVs = AssetLoader.readText(mContext, "silk/shaders/GLES/silk_bg_vs.glsl");
        String bgFs = AssetLoader.readText(mContext, "silk/shaders/GLES/silk_bg_fs.glsl");
        mBgProgram = createProgram(bgVs, bgFs);
        if (mBgProgram == 0) {
            Log.e(TAG, "Background shader program creation failed");
            return;
        }
        mBgAttrPos = GLES30.glGetAttribLocation(mBgProgram, "aPosition");
        mBgAttrCoord = GLES30.glGetAttribLocation(mBgProgram, "aTexCoor");
        mBgSampler = GLES30.glGetUniformLocation(mBgProgram, "sTexture");

        // Static geometry buffers
        int[] bufs = new int[SilkScene.RIBBON_COUNT * 2 + 2];
        GLES30.glGenBuffers(bufs.length, bufs, 0);
        for (int r = 0; r < SilkScene.RIBBON_COUNT; r++) {
            mPosVbo[r] = bufs[r * 2];
            mColorVbo[r] = bufs[r * 2 + 1];
        }
        mUvVbo = bufs[SilkScene.RIBBON_COUNT * 2];
        mIndexVbo = bufs[SilkScene.RIBBON_COUNT * 2 + 1];

        for (int r = 0; r < SilkScene.RIBBON_COUNT; r++) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, mPosVbo[r]);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, SilkScene.VERTICES * 2 * 4, null,
                    GLES30.GL_DYNAMIC_DRAW);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, mColorVbo[r]);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, SilkScene.VERTICES * 4, null,
                    GLES30.GL_DYNAMIC_DRAW);
        }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, mUvVbo);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, SilkScene.VERTICES * 2 * 4,
                createFloatBuffer(SilkScene.buildUv()), GLES30.GL_STATIC_DRAW);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, mIndexVbo);
        ByteBuffer idx = ByteBuffer.allocateDirect((SilkScene.COLUMNS - 1) * 6)
                .order(ByteOrder.nativeOrder()).put(SilkScene.buildIndices());
        idx.position(0);
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, (SilkScene.COLUMNS - 1) * 6, idx,
                GLES30.GL_STATIC_DRAW);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0);

        for (int r = 0; r < SilkScene.RIBBON_COUNT; r++) {
            mPosBuf[r] = createFloatBuffer(new float[SilkScene.VERTICES * 2]);
            mColorBuf[r] = createFloatBuffer(new float[SilkScene.VERTICES]);
        }

        loadTextures(mScene.mTheme);
        mInitialized = true;
    }

    /** Load theme textures on the GL thread. */
    private void loadTextures(String theme) {
        if (mBackgroundTexture != 0) GLES30.glDeleteTextures(1, new int[]{mBackgroundTexture}, 0);
        if (mSilkTexture != 0) GLES30.glDeleteTextures(1, new int[]{mSilkTexture}, 0);
        mBackgroundTexture = 0;
        mSilkTexture = 0;

        // Background: RGB, NEAREST/NEAREST, CLAMP_TO_EDGE (original params)
        Bitmap bg = AssetLoader.decodeBitmap(mContext, SilkScene.themeBackgroundAsset(theme));
        int[] tex = new int[1];
        GLES30.glGenTextures(1, tex, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0]);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bg, 0);
        bg.recycle();
        mBackgroundTexture = tex[0];

        // Silk ribbon: must be decoded premultiplied for the GL_ONE blend
        // (AssetLoader.decodeBitmap forces inPremultiplied=false).
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPremultiplied = true;
        Bitmap silk = AssetLoader.decodeBitmapWithOptions(mContext,
                SilkScene.themeSilkAsset(theme), opts);
        GLES30.glGenTextures(1, tex, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0]);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, silk, 0);
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
        // 只钳制上限；速度倍率由 Scene 自己乘（见 SilkScene.update）
        mScene.update(Math.min(dt, 0.1f));

        // Theme changed in prefs → reload textures on the GL thread
        if (!mScene.mTheme.equals(mLoadedTheme)) {
            loadTextures(mScene.mTheme);
        }

        GLES30.glViewport(0, 0, mWidth, mHeight);
        GLES30.glClearColor(0f, 0f, 0f, 0f);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);

        drawBackground();
        drawRibbons();
    }

    private void drawBackground() {
        GLES30.glUseProgram(mBgProgram);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mBackgroundTexture);
        GLES30.glUniform1i(mBgSampler, 0);

        GLES30.glEnableVertexAttribArray(mBgAttrPos);
        GLES30.glVertexAttribPointer(mBgAttrPos, 2, GLES30.GL_FLOAT, false, 0, mQuadPos);
        GLES30.glEnableVertexAttribArray(mBgAttrCoord);
        GLES30.glVertexAttribPointer(mBgAttrCoord, 2, GLES30.GL_FLOAT, false, 0, mQuadUv);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_FAN, 0, 4);
        GLES30.glDisableVertexAttribArray(mBgAttrPos);
        GLES30.glDisableVertexAttribArray(mBgAttrCoord);
    }

    private void drawRibbons() {
        GLES30.glUseProgram(mProgram);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mSilkTexture);
        GLES30.glUniform1i(mUniformSampler, 0);

        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA);

        SilkScene.RibbonConfig[] ribbons = SilkScene.RIBBONS;
        for (int r = 0; r < SilkScene.RIBBON_COUNT; r++) {
            SilkScene.RibbonConfig c = ribbons[r];

            GLES30.glUniform3f(mUniformRotateFlash, mScene.mRotSin[r], mScene.mRotCos[r],
                    mScene.mFlash[r]);
            GLES30.glUniform4f(mUniformNewPos, c.newPosX, c.newPosY, 0f, 0f);
            float[] oc = SilkScene.themeOriginColor(mScene.mTheme);
            // Premultiplied origin term: a = 1 so rgb·uOriginAlpha stays premultiplied
            // consistently with the texture term (the shader multiplies by uOriginAlpha).
            GLES30.glUniform4f(mUniformOriginColor, oc[0], oc[1], oc[2], 1f);
            GLES30.glUniform1f(mUniformOriginAlpha, c.originAlpha);
            GLES30.glUniform1f(mUniformDecayLen, c.decayLen);
            GLES30.glUniform1f(mUniformDivFactor, c.divFactor);
            GLES30.glUniform1f(mUniformDecay, c.decay ? 1f : 0f);

            // Position (dynamic)
            FloatBuffer posBuf = mPosBuf[r];
            posBuf.position(0);
            posBuf.put(mScene.mPositions[r]);
            posBuf.position(0);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, mPosVbo[r]);
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0,
                    SilkScene.VERTICES * 2 * 4, posBuf);
            GLES30.glVertexAttribPointer(mAttrPos, 2, GLES30.GL_FLOAT, false, 0, 0);

            // Color (dynamic)
            FloatBuffer colorBuf = mColorBuf[r];
            colorBuf.position(0);
            colorBuf.put(mScene.mColors[r]);
            colorBuf.position(0);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, mColorVbo[r]);
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0,
                    SilkScene.VERTICES * 4, colorBuf);
            GLES30.glVertexAttribPointer(mAttrColor, 1, GLES30.GL_FLOAT, false, 0, 0);

            // UV (static, shared)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, mUvVbo);
            GLES30.glVertexAttribPointer(mAttrCoord, 2, GLES30.GL_FLOAT, false, 0, 0);

            GLES30.glEnableVertexAttribArray(mAttrPos);
            GLES30.glEnableVertexAttribArray(mAttrColor);
            GLES30.glEnableVertexAttribArray(mAttrCoord);

            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, mIndexVbo);
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, (SilkScene.COLUMNS - 1) * 6,
                    GLES30.GL_UNSIGNED_BYTE, 0);

            GLES30.glDisableVertexAttribArray(mAttrPos);
            GLES30.glDisableVertexAttribArray(mAttrColor);
            GLES30.glDisableVertexAttribArray(mAttrCoord);
        }
        GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0);
    }

    @Override
    public void release() {
        int[] bufs = new int[SilkScene.RIBBON_COUNT * 2 + 2];
        System.arraycopy(mPosVbo, 0, bufs, 0, SilkScene.RIBBON_COUNT);
        System.arraycopy(mColorVbo, 0, bufs, SilkScene.RIBBON_COUNT, SilkScene.RIBBON_COUNT);
        bufs[SilkScene.RIBBON_COUNT * 2] = mUvVbo;
        bufs[SilkScene.RIBBON_COUNT * 2 + 1] = mIndexVbo;
        GLES30.glDeleteBuffers(bufs.length, bufs, 0);
        if (mBackgroundTexture != 0) {
            GLES30.glDeleteTextures(1, new int[]{mBackgroundTexture}, 0);
        }
        if (mSilkTexture != 0) {
            GLES30.glDeleteTextures(1, new int[]{mSilkTexture}, 0);
        }
        if (mProgram != 0) GLES30.glDeleteProgram(mProgram);
        if (mBgProgram != 0) GLES30.glDeleteProgram(mBgProgram);
        mProgram = 0;
        mBgProgram = 0;
        mBackgroundTexture = 0;
        mSilkTexture = 0;
        mLoadedTheme = null;
        mInitialized = false;
        mLastFrameMs = 0;
    }
}
