package com.reandroid.wallpaper.phasebeam;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.opengl.GLES30;
import android.opengl.GLUtils;
import android.util.Log;

import com.reandroid.utils.AssetLoader;
import com.reandroid.gles.GLESScene;

/**
 * Phase Beam - 从RenderScript完整移植到OpenGL ES 2.0
 * 保持与原始RenderScript实现一致的视觉效果与动画逻辑
 */
public class PhaseBeamGL extends GLESScene {
    private static final String TAG = "PhaseBeamGL";

    // 重新导出的常量，供外部使用（设置界面）
    public static final String PREFS_NAME = PhaseBeamScene.PREFS_NAME;
    public static final String KEY_ENABLED = PhaseBeamScene.KEY_ENABLED;
    public static final String KEY_COLOR = PhaseBeamScene.KEY_COLOR;
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
        mScene = new PhaseBeamScene();
    }

    @Override
    protected void onCreate() {
        if (mResources == null) return;
        mScene.init(mResources);
    }

    @Override
    public void release() {
        int[] tex = new int[] { mTexDot, mTexBeam };
        GLES30.glDeleteTextures(tex.length, tex, 0);
        mTexDot = 0;
        mTexBeam = 0;

        if (mBgProgram != 0) {
            GLES30.glDeleteProgram(mBgProgram);
            mBgProgram = 0;
        }
        if (mDotProgram != 0) {
            GLES30.glDeleteProgram(mDotProgram);
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
        // Full re-read: engine re-injection means "everything changed"
        mScene.reloadPreferences(mResources);
        mScene.loadBackgroundMesh(mContext,
                "sunbeam".equals(mScene.mTheme)
                        ? "phasebeam/data/sunbeam_bg_mesh.csv"
                        : "waterbeam".equals(mScene.mTheme)
                            ? "phasebeam/data/water_bg_mesh.csv"
                            : "phasebeam/data/phasebeam_bg_mesh.csv");
    }

    @Override
    public void drawFrame(long timeMs) {
        initGLIfNeeded();
        if (!mInitialized) return;

        if (mScene.mNeedViewport) {
            GLES30.glViewport(0, 0, mWidth, mHeight);
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

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);

        drawBackground();
        drawParticles();

        mScene.mOldOffset = newOffset;
        System.arraycopy(mScene.mAdjust, 0, mScene.mOldAdjust, 0, mScene.mAdjust.length);
    }

    private void initGLIfNeeded() {
        if (mInitialized || mResources == null) return;

        GLES30.glClearColor(0f, 0f, 0f, 1f);
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE);

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

        mBgPositionLoc = GLES30.glGetAttribLocation(mBgProgram, "ATTRIB_position");
        mBgOffsetLoc = GLES30.glGetAttribLocation(mBgProgram, "ATTRIB_offsetX");
        mBgRealColorLoc = GLES30.glGetAttribLocation(mBgProgram, "ATTRIB_realColor");
        mBgAdjustLoc = GLES30.glGetAttribLocation(mBgProgram, "ATTRIB_adjust");

        mDotPositionLoc = GLES30.glGetAttribLocation(mDotProgram, "ATTRIB_position");
        mDotOffsetLoc = GLES30.glGetAttribLocation(mDotProgram, "ATTRIB_offsetX");
        mDotAdjustLoc = GLES30.glGetAttribLocation(mDotProgram, "ATTRIB_adjust");
        mDotTexLoc = GLES30.glGetUniformLocation(mDotProgram, "UNI_Tex0");
        mDotScaleLoc = GLES30.glGetUniformLocation(mDotProgram, "UNI_scaleSize");

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
        GLES30.glDeleteTextures(tex.length, tex, 0);

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
        GLES30.glUseProgram(mBgProgram);

        GLES30.glEnableVertexAttribArray(mBgPositionLoc);
        GLES30.glEnableVertexAttribArray(mBgOffsetLoc);
        GLES30.glEnableVertexAttribArray(mBgRealColorLoc);
        GLES30.glEnableVertexAttribArray(mBgAdjustLoc);

        GLES30.glVertexAttribPointer(mBgPositionLoc, 3, GLES30.GL_FLOAT, false, 0, mScene.mBgPositionBuffer);
        GLES30.glVertexAttribPointer(mBgOffsetLoc, 1, GLES30.GL_FLOAT, false, 0, mScene.mBgOffsetBuffer);
        GLES30.glVertexAttribPointer(mBgRealColorLoc, 4, GLES30.GL_FLOAT, false, 0, mScene.mBgRealColorBuffer);
        GLES30.glVertexAttribPointer(mBgAdjustLoc, 3, GLES30.GL_FLOAT, false, 0, mScene.mBgAdjustBuffer);

        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, mScene.mBgVertexCount);

        GLES30.glDisableVertexAttribArray(mBgPositionLoc);
        GLES30.glDisableVertexAttribArray(mBgOffsetLoc);
        GLES30.glDisableVertexAttribArray(mBgRealColorLoc);
        GLES30.glDisableVertexAttribArray(mBgAdjustLoc);
    }

    private void drawParticles() {
        GLES30.glUseProgram(mDotProgram);

        GLES30.glUniform1i(mDotTexLoc, 0);
        GLES30.glUniform1f(mDotScaleLoc, mScene.mScaleSize);

        GLES30.glEnableVertexAttribArray(mDotPositionLoc);
        GLES30.glEnableVertexAttribArray(mDotOffsetLoc);
        GLES30.glEnableVertexAttribArray(mDotAdjustLoc);

        // Draw beams
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mTexBeam);
        GLES30.glVertexAttribPointer(mDotPositionLoc, 3, GLES30.GL_FLOAT, false, 0, mScene.mBeamPositionBuffer);
        GLES30.glVertexAttribPointer(mDotOffsetLoc, 1, GLES30.GL_FLOAT, false, 0, mScene.mBeamOffsetBuffer);
        GLES30.glVertexAttribPointer(mDotAdjustLoc, 3, GLES30.GL_FLOAT, false, 0, mScene.mBeamAdjustBuffer);
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, mScene.getDotCount());

        // Draw dots
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mTexDot);
        GLES30.glVertexAttribPointer(mDotPositionLoc, 3, GLES30.GL_FLOAT, false, 0, mScene.mDotPositionBuffer);
        GLES30.glVertexAttribPointer(mDotOffsetLoc, 1, GLES30.GL_FLOAT, false, 0, mScene.mDotOffsetBuffer);
        GLES30.glVertexAttribPointer(mDotAdjustLoc, 3, GLES30.GL_FLOAT, false, 0, mScene.mDotAdjustBuffer);
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, mScene.getDotCount());

        GLES30.glDisableVertexAttribArray(mDotPositionLoc);
        GLES30.glDisableVertexAttribArray(mDotOffsetLoc);
        GLES30.glDisableVertexAttribArray(mDotAdjustLoc);
    }

    private int loadTexture(String assetPath) {
        Bitmap bitmap = AssetLoader.decodeBitmap(mContext, assetPath);
        if (bitmap == null) {
            Log.e(TAG, "Failed to decode texture: " + assetPath);
            return 0;
        }
        int[] tex = new int[1];
        GLES30.glGenTextures(1, tex, 0);
        int textureId = tex[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0);
        bitmap.recycle();
        return textureId;
    }

}
