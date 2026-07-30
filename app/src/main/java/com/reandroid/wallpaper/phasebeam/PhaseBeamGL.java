package com.reandroid.wallpaper.phasebeam;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.util.Log;

import com.reandroid.utils.AssetLoader;
import com.reandroid.gles.GLESScene;

/**
 * Phase Beam - 从RenderScript完整移植到OpenGL ES 2.0
 * 保持与原始RenderScript实现一致的视觉效果与动画逻辑
 */
public class PhaseBeamGL extends GLESScene implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "PhaseBeamGL";

    // 重新导出的常量，供外部使用（设置界面）
    public static final String PREFS_NAME = PhaseBeamScene.PREFS_NAME;
    public static final String KEY_ENABLED = PhaseBeamScene.KEY_ENABLED;
    public static final String KEY_HUE = PhaseBeamScene.KEY_HUE;
    public static final String KEY_SATURATION = PhaseBeamScene.KEY_SATURATION;
    public static final String KEY_BRIGHTNESS = PhaseBeamScene.KEY_BRIGHTNESS;
    public static final String KEY_THEME = PhaseBeamScene.KEY_THEME;

    // ---- 场景逻辑层（非 GL）----
    private final Context mContext;
    private final PhaseBeamScene mScene;

    private int mBgProgram;
    private int mDotProgram;

    private int mTexDot;
    private int mTexBeam;

    private int mBgPositionLoc;
    private int mBgOffsetLoc;
    private int mBgRealColorLoc;
    private int mBgAdjustLoc;

    private int mDotPositionLoc;
    private int mDotOffsetLoc;
    private int mDotAdjustLoc;
    private int mDotTexLoc;
    private int mDotScaleLoc;

    private boolean mInitialized;

    public PhaseBeamGL(int width, int height, Context context) {
        super(width, height);
        mContext = context.getApplicationContext();
        mScene = new PhaseBeamScene(context);
    }

    @Override
    protected void onCreate() {
        if (mResources == null) return;
        mScene.init(mResources);
    }

    @Override
    public void start() {
        mScene.ensurePrefs();
        SharedPreferences prefs = mScene.getPrefs();
        if (prefs != null) {
            prefs.registerOnSharedPreferenceChangeListener(this);
        }
    }

    @Override
    public void stop() {
        SharedPreferences prefs = mScene.getPrefs();
        if (prefs != null) {
            prefs.unregisterOnSharedPreferenceChangeListener(this);
        }
    }

    @Override
    public void release() {
        int[] tex = new int[] { mTexDot, mTexBeam };
        GLES20.glDeleteTextures(tex.length, tex, 0);
        mTexDot = 0;
        mTexBeam = 0;

        if (mBgProgram != 0) {
            GLES20.glDeleteProgram(mBgProgram);
            mBgProgram = 0;
        }
        if (mDotProgram != 0) {
            GLES20.glDeleteProgram(mDotProgram);
            mDotProgram = 0;
        }

        mInitialized = false;
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        mScene.mNeedViewport = true;
    }

    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        mScene.setOffset(xOffset);
    }

    /** Called by settings UI to reload preferences at runtime */
    public void reloadPreferences() {
        mScene.reloadPreferences(mResources);
    }

    public void setPluginPrefs(SharedPreferences prefs) {
        mScene.setPluginPrefs(prefs);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (PhaseBeamScene.KEY_ENABLED.equals(key) || PhaseBeamScene.KEY_HUE.equals(key)
                || PhaseBeamScene.KEY_SATURATION.equals(key)
                || PhaseBeamScene.KEY_BRIGHTNESS.equals(key)
                || PhaseBeamScene.KEY_THEME.equals(key)
                || "phasebeam_dot_count".equals(key)) {
            mScene.reloadPreferences(mResources);
            if (PhaseBeamScene.KEY_THEME.equals(key)) {
                mScene.mDirtyTexture = true;
                mScene.mDirtyBackground = true;
                mScene.mDirtyParticles = true;
                // Reload mesh with new theme
                mScene.loadBackgroundMesh(mContext,
                        "sunbeam".equals(mScene.mTheme)
                                ? "phasebeam/data/sunbeam_bg_mesh.csv"
                                : "waterbeam".equals(mScene.mTheme)
                                    ? "phasebeam/data/water_bg_mesh.csv"
                                    : "phasebeam/data/phasebeam_bg_mesh.csv");
            }
        }
    }

    @Override
    public void drawFrame(long timeMs) {
        initGLIfNeeded();
        if (!mInitialized) return;

        if (mScene.mNeedViewport) {
            GLES20.glViewport(0, 0, mWidth, mHeight);
            mScene.mNeedViewport = false;
        }

        if (mScene.consumeParamsDirty()) {
            mScene.allocateArrays();
            mScene.positionParticles();
            mScene.updateParticleBuffers();
        }

        long now = timeMs;
        if (mScene.mLastTimeMs == 0L) {
            mScene.mLastTimeMs = now;
        }
        float delta = Math.max(1.0f, (now - mScene.mLastTimeMs));
        mScene.mLastTimeMs = now;

        float newOffset = mScene.mXOffset * 2.0f;
        float speedbump = (newOffset != mScene.mOldOffset) ? 0.25f : 1.0f;
        float timeScale = (delta / 66.0f) * speedbump;

        if (mScene.mDirtyTexture) {
            reloadTextures();
        }
        if (mScene.mDirtyBackground || mScene.adjustChanged() || newOffset != mScene.mOldOffset) {
            mScene.updateBackgroundBuffers(newOffset);
            mScene.mDirtyBackground = false;
        }
        if (mScene.mDirtyParticles || mScene.adjustChanged()) {
            mScene.updateParticleAdjusts();
            mScene.mDirtyParticles = false;
        }

        mScene.updateParticles(timeScale, newOffset);

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        drawBackground();
        drawParticles();

        mScene.mOldOffset = newOffset;
        System.arraycopy(mScene.mAdjust, 0, mScene.mOldAdjust, 0, mScene.mAdjust.length);
    }

    private void initGLIfNeeded() {
        if (mInitialized || mResources == null) return;

        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE);

        final String shaderPath = "phasebeam/shaders/GLES/";
        String bgVs = AssetLoader.readText(mContext, shaderPath + "phasebeam_bg_vs.glsl");
        String bgFs = AssetLoader.readText(mContext, shaderPath + "phasebeam_bg_fs.glsl");
        String dotVs = AssetLoader.readText(mContext, shaderPath + "phasebeam_dot_vs.glsl");
        String dotFs = AssetLoader.readText(mContext, shaderPath + "phasebeam_dot_fs.glsl");
        mBgProgram = createProgram(bgVs, bgFs);
        mDotProgram = createProgram(dotVs, dotFs);

        if (mBgProgram == 0 || mDotProgram == 0) {
            Log.e(TAG, "Shader program creation failed");
            return;
        }

        mBgPositionLoc = GLES20.glGetAttribLocation(mBgProgram, "ATTRIB_position");
        mBgOffsetLoc = GLES20.glGetAttribLocation(mBgProgram, "ATTRIB_offsetX");
        mBgRealColorLoc = GLES20.glGetAttribLocation(mBgProgram, "ATTRIB_realColor");
        mBgAdjustLoc = GLES20.glGetAttribLocation(mBgProgram, "ATTRIB_adjust");

        mDotPositionLoc = GLES20.glGetAttribLocation(mDotProgram, "ATTRIB_position");
        mDotOffsetLoc = GLES20.glGetAttribLocation(mDotProgram, "ATTRIB_offsetX");
        mDotAdjustLoc = GLES20.glGetAttribLocation(mDotProgram, "ATTRIB_adjust");
        mDotTexLoc = GLES20.glGetUniformLocation(mDotProgram, "UNI_Tex0");
        mDotScaleLoc = GLES20.glGetUniformLocation(mDotProgram, "UNI_scaleSize");

        mScene.loadBackgroundMesh(mContext,
                "sunbeam".equals(mScene.mTheme)
                        ? "phasebeam/data/sunbeam_bg_mesh.csv"
                        : "waterbeam".equals(mScene.mTheme)
                            ? "phasebeam/data/water_bg_mesh.csv"
                            : "phasebeam/data/phasebeam_bg_mesh.csv");
        if (mScene.consumeParamsDirty()) {
            mScene.allocateArrays();
        }
        mScene.positionParticles();
        mScene.updateBackgroundBuffers(mScene.mXOffset * 2.0f);
        mScene.updateParticleBuffers();
        reloadTextures();

        mInitialized = true;
    }

    private void reloadTextures() {
        int[] tex = new int[] { mTexDot, mTexBeam };
        GLES20.glDeleteTextures(tex.length, tex, 0);

        final String texPath = "phasebeam/drawable/";
        // Sunbeam and waterbeam use their original colored textures; phasebeam uses grey in recolor mode
        boolean useGrey = mScene.mRecolorEnabled && "phasebeam".equals(mScene.mTheme);
        String prefix = "sunbeam".equals(mScene.mTheme) ? "sunbeam"
                : "waterbeam".equals(mScene.mTheme) ? "water"
                : "phasebeam";
        String suffix = useGrey ? "_grey" : "";
        mTexDot = loadTexture(texPath + prefix + "_dot" + suffix + ".png");
        mTexBeam = loadTexture(texPath + prefix + "_beam" + suffix + ".png");
        mScene.mDirtyTexture = false;
    }

    private void drawBackground() {
        GLES20.glUseProgram(mBgProgram);

        GLES20.glEnableVertexAttribArray(mBgPositionLoc);
        GLES20.glEnableVertexAttribArray(mBgOffsetLoc);
        GLES20.glEnableVertexAttribArray(mBgRealColorLoc);
        GLES20.glEnableVertexAttribArray(mBgAdjustLoc);

        GLES20.glVertexAttribPointer(mBgPositionLoc, 3, GLES20.GL_FLOAT, false, 0, mScene.mBgPositionBuffer);
        GLES20.glVertexAttribPointer(mBgOffsetLoc, 1, GLES20.GL_FLOAT, false, 0, mScene.mBgOffsetBuffer);
        GLES20.glVertexAttribPointer(mBgRealColorLoc, 4, GLES20.GL_FLOAT, false, 0, mScene.mBgRealColorBuffer);
        GLES20.glVertexAttribPointer(mBgAdjustLoc, 3, GLES20.GL_FLOAT, false, 0, mScene.mBgAdjustBuffer);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mScene.mBgVertexCount);

        GLES20.glDisableVertexAttribArray(mBgPositionLoc);
        GLES20.glDisableVertexAttribArray(mBgOffsetLoc);
        GLES20.glDisableVertexAttribArray(mBgRealColorLoc);
        GLES20.glDisableVertexAttribArray(mBgAdjustLoc);
    }

    private void drawParticles() {
        GLES20.glUseProgram(mDotProgram);

        GLES20.glUniform1i(mDotTexLoc, 0);
        GLES20.glUniform1f(mDotScaleLoc, mScene.mScaleSize);

        GLES20.glEnableVertexAttribArray(mDotPositionLoc);
        GLES20.glEnableVertexAttribArray(mDotOffsetLoc);
        GLES20.glEnableVertexAttribArray(mDotAdjustLoc);

        // Draw beams
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTexBeam);
        GLES20.glVertexAttribPointer(mDotPositionLoc, 3, GLES20.GL_FLOAT, false, 0, mScene.mBeamPositionBuffer);
        GLES20.glVertexAttribPointer(mDotOffsetLoc, 1, GLES20.GL_FLOAT, false, 0, mScene.mBeamOffsetBuffer);
        GLES20.glVertexAttribPointer(mDotAdjustLoc, 3, GLES20.GL_FLOAT, false, 0, mScene.mBeamAdjustBuffer);
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, mScene.getDotCount());

        // Draw dots
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTexDot);
        GLES20.glVertexAttribPointer(mDotPositionLoc, 3, GLES20.GL_FLOAT, false, 0, mScene.mDotPositionBuffer);
        GLES20.glVertexAttribPointer(mDotOffsetLoc, 1, GLES20.GL_FLOAT, false, 0, mScene.mDotOffsetBuffer);
        GLES20.glVertexAttribPointer(mDotAdjustLoc, 3, GLES20.GL_FLOAT, false, 0, mScene.mDotAdjustBuffer);
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, mScene.getDotCount());

        GLES20.glDisableVertexAttribArray(mDotPositionLoc);
        GLES20.glDisableVertexAttribArray(mDotOffsetLoc);
        GLES20.glDisableVertexAttribArray(mDotAdjustLoc);
    }

    private int loadTexture(String assetPath) {
        Bitmap bitmap = AssetLoader.decodeBitmap(mContext, assetPath);
        if (bitmap == null) {
            Log.e(TAG, "Failed to decode texture: " + assetPath);
            return 0;
        }
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        int textureId = tex[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        bitmap.recycle();
        return textureId;
    }


}
