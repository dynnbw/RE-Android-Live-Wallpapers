package com.reandroid.wallpaper.phasebeam;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.util.Log;

import com.reandroid.wallpaper.R;
import com.reandroid.wallpaper.gles.GLESScene;
import com.reandroid.wallpaper.gles.RawResourceLoader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Random;

/**
 * Phase Beam - 从RenderScript完整移植到OpenGL ES 2.0
 * 保持与原始RenderScript实现一致的视觉效果与动画逻辑
 */
public class PhaseBeamGL extends GLESScene implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "PhaseBeamGL";

    public static final String PREFS_NAME = "phasebeam";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_HUE = "hue";
    public static final String KEY_SATURATION = "saturation";
    public static final String KEY_BRIGHTNESS = "brightness";

    private static final int DOT_COUNT = 28;

    private static final float ZX_PARTICLE_SPEED = 0.0000780f;
    private static final float ZX_BEAM_SPEED = 0.00005f;
    private static final float YZ_PARTICLE_SPEED = 0.00011f;
    private static final float YZ_BEAM_SPEED = 0.000080f;

    private final Context mContext;
    private SharedPreferences mPrefs;

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

    private float mScaleSize = 1.0f;
    private float mXOffset = 0.5f;
    private float mOldOffset = 0.5f;

    private float mHue = 0.0f;
    private float mSaturation = 1.0f;
    private float mBrightness = 1.0f;
    private boolean mRecolorEnabled = false;
    private boolean mCanScroll = true;

    private final float[] mAdjust = new float[] { -1.0f, 1.0f, 1.0f };
    private final float[] mOldAdjust = new float[] { -1.0f, 1.0f, 1.0f };

    private boolean mDirtyBackground = true;
    private boolean mDirtyParticles = true;
    private boolean mDirtyTexture = true;
    private boolean mNeedViewport = true;

    private long mLastTimeMs = 0L;

    private FloatBuffer mBgPositionBuffer;
    private FloatBuffer mBgOffsetBuffer;
    private FloatBuffer mBgRealColorBuffer;
    private FloatBuffer mBgAdjustBuffer;
    private int mBgVertexCount;

    private FloatBuffer mDotPositionBuffer;
    private FloatBuffer mDotOffsetBuffer;
    private FloatBuffer mDotAdjustBuffer;

    private FloatBuffer mBeamPositionBuffer;
    private FloatBuffer mBeamOffsetBuffer;
    private FloatBuffer mBeamAdjustBuffer;

    private final float[] mDotPositions = new float[DOT_COUNT * 3];
    private final float[] mDotOffsets = new float[DOT_COUNT];
    private final float[] mDotAdjusts = new float[DOT_COUNT * 3];

    private final float[] mBeamPositions = new float[DOT_COUNT * 3];
    private final float[] mBeamOffsets = new float[DOT_COUNT];
    private final float[] mBeamAdjusts = new float[DOT_COUNT * 3];

    private final Random mRandom = new Random();

    private float[] mBgRawVertices;
    private float[] mBgBaseColors;

    public PhaseBeamGL(int width, int height, Context context) {
        super(width, height);
        mContext = context;
    }

    @Override
    protected void onCreate() {
        if (mResources == null) return;
        ensurePrefs();
        mScaleSize = mResources.getDisplayMetrics().densityDpi / 240.0f;
        mCanScroll = mResources.getBoolean(R.bool.scrolling_enabled);
        readPrefs();
    }

    @Override
    public void start() {
        ensurePrefs();
        if (mPrefs != null) {
            mPrefs.registerOnSharedPreferenceChangeListener(this);
        }
    }

    @Override
    public void stop() {
        if (mPrefs != null) {
            mPrefs.unregisterOnSharedPreferenceChangeListener(this);
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
        mNeedViewport = true;
    }

    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        if (mCanScroll) {
            mXOffset = xOffset;
        }
    }

    @Override
    public void drawFrame(long timeMs) {
        initGLIfNeeded();
        if (!mInitialized) return;

        if (mNeedViewport) {
            GLES20.glViewport(0, 0, mWidth, mHeight);
            mNeedViewport = false;
        }

        long now = timeMs;
        if (mLastTimeMs == 0L) {
            mLastTimeMs = now;
        }
        float delta = Math.max(1.0f, (now - mLastTimeMs));
        mLastTimeMs = now;

        float newOffset = mXOffset * 2.0f;
        float speedbump = (newOffset != mOldOffset) ? 0.25f : 1.0f;
        float timeScale = (delta / 66.0f) * speedbump;

        if (mDirtyTexture) {
            reloadTextures();
        }
        if (mDirtyBackground || adjustChanged() || newOffset != mOldOffset) {
            updateBackgroundBuffers(newOffset);
            mDirtyBackground = false;
        }
        if (mDirtyParticles || adjustChanged()) {
            updateParticleAdjusts();
            mDirtyParticles = false;
        }

        updateParticles(timeScale, newOffset);

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        drawBackground();
        drawParticles();

        mOldOffset = newOffset;
        System.arraycopy(mAdjust, 0, mOldAdjust, 0, mAdjust.length);
    }

    private void initGLIfNeeded() {
        if (mInitialized || mResources == null) return;

        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE);

        String bgVs = RawResourceLoader.readRawText(mResources, R.raw.phasebeam_bg_vs);
        String bgFs = RawResourceLoader.readRawText(mResources, R.raw.phasebeam_bg_fs);
        String dotVs = RawResourceLoader.readRawText(mResources, R.raw.phasebeam_dot_vs);
        String dotFs = RawResourceLoader.readRawText(mResources, R.raw.phasebeam_dot_fs);
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

        loadBackgroundMesh();
        positionParticles();
        updateBackgroundBuffers(mXOffset * 2.0f);
        updateParticleBuffers();
        reloadTextures();

        mInitialized = true;
    }

    private void reloadTextures() {
        int[] tex = new int[] { mTexDot, mTexBeam };
        GLES20.glDeleteTextures(tex.length, tex, 0);

        int dotRes = mRecolorEnabled ? R.drawable.phasebeam_dot_grey : R.drawable.phasebeam_dot;
        int beamRes = mRecolorEnabled ? R.drawable.phasebeam_beam_grey : R.drawable.phasebeam_beam;
        mTexDot = loadTexture(dotRes);
        mTexBeam = loadTexture(beamRes);
        mDirtyTexture = false;
    }

    private void ensurePrefs() {
        if (mPrefs == null && mContext != null) {
            mPrefs = mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }
    }

    private void readPrefs() {
        if (mResources == null || mPrefs == null) return;
        mRecolorEnabled = mPrefs.getBoolean(KEY_ENABLED, mResources.getBoolean(R.bool.recolor_enabled));
        mHue = mPrefs.getFloat(KEY_HUE, Float.parseFloat(mResources.getString(R.string.hue)));
        mSaturation = mPrefs.getFloat(KEY_SATURATION, Float.parseFloat(mResources.getString(R.string.saturation)));
        mBrightness = mPrefs.getFloat(KEY_BRIGHTNESS, Float.parseFloat(mResources.getString(R.string.brightness)));
        updateAdjust();
    }

    public void reloadPreferences() {
        readPrefs();
        mDirtyBackground = true;
        mDirtyParticles = true;
        mDirtyTexture = true;
    }

    private void updateAdjust() {
        if (mRecolorEnabled) {
            mAdjust[0] = mHue;
            mAdjust[1] = mSaturation;
            mAdjust[2] = mBrightness;
        } else {
            mAdjust[0] = -1.0f;
            mAdjust[1] = 1.0f;
            mAdjust[2] = 1.0f;
        }
    }

    private boolean adjustChanged() {
        return mAdjust[0] != mOldAdjust[0]
                || mAdjust[1] != mOldAdjust[1]
                || mAdjust[2] != mOldAdjust[2];
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (KEY_ENABLED.equals(key) || KEY_HUE.equals(key) || KEY_SATURATION.equals(key)
                || KEY_BRIGHTNESS.equals(key)) {
            readPrefs();
            mDirtyBackground = true;
            mDirtyParticles = true;
            mDirtyTexture = true;
        }
    }

    private void loadBackgroundMesh() {
        if (mResources == null) return;
        float[] mesh = RawResourceLoader.readRawFloatArray(mResources, R.raw.phasebeam_bg_mesh);
        int count = mesh.length / 5;
        mBgVertexCount = count;
        mBgRawVertices = new float[count * 3];
        mBgBaseColors = new float[count * 3];
        for (int i = 0; i < count; i++) {
            int src = i * 5;
            int v = i * 3;
            mBgRawVertices[v] = mesh[src];
            mBgRawVertices[v + 1] = mesh[src + 1];
            mBgRawVertices[v + 2] = 0.0f;
            mBgBaseColors[v] = mesh[src + 2];
            mBgBaseColors[v + 1] = mesh[src + 3];
            mBgBaseColors[v + 2] = mesh[src + 4];
        }
    }

    private void updateBackgroundBuffers(float newOffset) {
        if (mBgVertexCount == 0) return;

        float[] positions = new float[mBgVertexCount * 3];
        float[] offsets = new float[mBgVertexCount];
        float[] realColors = new float[mBgVertexCount * 4];
        float[] adjusts = new float[mBgVertexCount * 3];

        for (int i = 0; i < mBgVertexCount; i++) {
            int v = i * 3;
            positions[v] = mBgRawVertices[v];
            positions[v + 1] = mBgRawVertices[v + 1];
            positions[v + 2] = mBgRawVertices[v + 2];

            offsets[i] = -mXOffset / 2.0f;

            float r = mBgBaseColors[v];
            float g = mBgBaseColors[v + 1];
            float b = mBgBaseColors[v + 2];

            if (mRecolorEnabled) {
                float grey = 0.3f * r + 0.59f * g + 0.11f * b;
                realColors[i * 4] = grey;
                realColors[i * 4 + 1] = grey;
                realColors[i * 4 + 2] = grey;
                realColors[i * 4 + 3] = 1.0f;
            } else {
                realColors[i * 4] = r;
                realColors[i * 4 + 1] = g;
                realColors[i * 4 + 2] = b;
                realColors[i * 4 + 3] = 1.0f;
            }

            adjusts[i * 3] = mAdjust[0];
            adjusts[i * 3 + 1] = mAdjust[1];
            adjusts[i * 3 + 2] = mAdjust[2];
        }

        mBgPositionBuffer = toFloatBuffer(positions);
        mBgOffsetBuffer = toFloatBuffer(offsets);
        mBgRealColorBuffer = toFloatBuffer(realColors);
        mBgAdjustBuffer = toFloatBuffer(adjusts);
    }

    private void positionParticles() {
        for (int i = 0; i < DOT_COUNT; i++) {
            int idx = i * 3;
            mDotPositions[idx] = rand(0.0f, 3.0f);
            mDotPositions[idx + 1] = rand(-1.25f, 1.25f);

            float z;
            if (i < 3) {
                z = 14.0f;
            } else if (i < 7) {
                z = 25.0f;
            } else if (i < 4) {
                z = rand(10.0f, 20.0f);
            } else if (i == 10) {
                z = 24.0f;
                mDotPositions[idx] = 1.0f;
            } else {
                z = rand(6.0f, 14.0f);
            }
            mDotPositions[idx + 2] = z;
            mDotOffsets[i] = 0.0f;
            setAdjust(mDotAdjusts, i, mAdjust);
        }

        for (int i = 0; i < DOT_COUNT; i++) {
            int idx = i * 3;
            float z;
            if (i < 20) {
                z = rand(4.0f, 10.0f) / 2.0f;
            } else {
                z = rand(4.0f, 35.0f) / 2.0f;
            }
            mBeamPositions[idx] = rand(-1.25f, 1.25f);
            mBeamPositions[idx + 1] = rand(-1.05f, 1.205f);
            mBeamPositions[idx + 2] = z;
            mBeamOffsets[i] = 0.0f;
            setAdjust(mBeamAdjusts, i, mAdjust);
        }
    }

    private void updateParticleAdjusts() {
        for (int i = 0; i < DOT_COUNT; i++) {
            setAdjust(mDotAdjusts, i, mAdjust);
            setAdjust(mBeamAdjusts, i, mAdjust);
        }
        updateParticleBuffers();
    }

    private void updateParticleBuffers() {
        mDotPositionBuffer = toFloatBuffer(mDotPositions);
        mDotOffsetBuffer = toFloatBuffer(mDotOffsets);
        mDotAdjustBuffer = toFloatBuffer(mDotAdjusts);

        mBeamPositionBuffer = toFloatBuffer(mBeamPositions);
        mBeamOffsetBuffer = toFloatBuffer(mBeamOffsets);
        mBeamAdjustBuffer = toFloatBuffer(mBeamAdjusts);
    }

    private void updateParticles(float timeScale, float newOffset) {
        for (int i = 0; i < DOT_COUNT; i++) {
            int idx = i * 3;
            float x = mBeamPositions[idx];
            float y = mBeamPositions[idx + 1];
            float z = mBeamPositions[idx + 2];

            if (x / z > 0.5f) {
                x = -1.0f;
            }
            if (y > 1.15f) {
                y = -1.15f;
                x = rand(-1.25f, 1.25f);
            } else {
                y += YZ_BEAM_SPEED * z * timeScale;
            }
            x += ZX_BEAM_SPEED * z * timeScale;

            mBeamPositions[idx] = x;
            mBeamPositions[idx + 1] = y;
            mBeamOffsets[i] = newOffset;
        }

        for (int i = 0; i < DOT_COUNT; i++) {
            int idx = i * 3;
            float x = mDotPositions[idx];
            float y = mDotPositions[idx + 1];
            float z = mDotPositions[idx + 2];

            if (x / z > 0.5f) {
                x = -1.0f;
            }

            if (y > 1.25f) {
                y = -1.25f;
                x = rand(0.0f, 3.0f);
            } else {
                y += YZ_PARTICLE_SPEED * z * timeScale;
            }

            x += ZX_PARTICLE_SPEED * z * timeScale;

            mDotPositions[idx] = x;
            mDotPositions[idx + 1] = y;
            mDotOffsets[i] = newOffset;
        }

        mBeamPositionBuffer = toFloatBuffer(mBeamPositions);
        mBeamOffsetBuffer = toFloatBuffer(mBeamOffsets);
        mDotPositionBuffer = toFloatBuffer(mDotPositions);
        mDotOffsetBuffer = toFloatBuffer(mDotOffsets);
    }

    private void drawBackground() {
        GLES20.glUseProgram(mBgProgram);

        GLES20.glEnableVertexAttribArray(mBgPositionLoc);
        GLES20.glEnableVertexAttribArray(mBgOffsetLoc);
        GLES20.glEnableVertexAttribArray(mBgRealColorLoc);
        GLES20.glEnableVertexAttribArray(mBgAdjustLoc);

        GLES20.glVertexAttribPointer(mBgPositionLoc, 3, GLES20.GL_FLOAT, false, 0, mBgPositionBuffer);
        GLES20.glVertexAttribPointer(mBgOffsetLoc, 1, GLES20.GL_FLOAT, false, 0, mBgOffsetBuffer);
        GLES20.glVertexAttribPointer(mBgRealColorLoc, 4, GLES20.GL_FLOAT, false, 0, mBgRealColorBuffer);
        GLES20.glVertexAttribPointer(mBgAdjustLoc, 3, GLES20.GL_FLOAT, false, 0, mBgAdjustBuffer);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mBgVertexCount);

        GLES20.glDisableVertexAttribArray(mBgPositionLoc);
        GLES20.glDisableVertexAttribArray(mBgOffsetLoc);
        GLES20.glDisableVertexAttribArray(mBgRealColorLoc);
        GLES20.glDisableVertexAttribArray(mBgAdjustLoc);
    }

    private void drawParticles() {
        GLES20.glUseProgram(mDotProgram);

        GLES20.glUniform1i(mDotTexLoc, 0);
        GLES20.glUniform1f(mDotScaleLoc, mScaleSize);

        GLES20.glEnableVertexAttribArray(mDotPositionLoc);
        GLES20.glEnableVertexAttribArray(mDotOffsetLoc);
        GLES20.glEnableVertexAttribArray(mDotAdjustLoc);

        // Draw beams
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTexBeam);
        GLES20.glVertexAttribPointer(mDotPositionLoc, 3, GLES20.GL_FLOAT, false, 0, mBeamPositionBuffer);
        GLES20.glVertexAttribPointer(mDotOffsetLoc, 1, GLES20.GL_FLOAT, false, 0, mBeamOffsetBuffer);
        GLES20.glVertexAttribPointer(mDotAdjustLoc, 3, GLES20.GL_FLOAT, false, 0, mBeamAdjustBuffer);
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, DOT_COUNT);

        // Draw dots
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTexDot);
        GLES20.glVertexAttribPointer(mDotPositionLoc, 3, GLES20.GL_FLOAT, false, 0, mDotPositionBuffer);
        GLES20.glVertexAttribPointer(mDotOffsetLoc, 1, GLES20.GL_FLOAT, false, 0, mDotOffsetBuffer);
        GLES20.glVertexAttribPointer(mDotAdjustLoc, 3, GLES20.GL_FLOAT, false, 0, mDotAdjustBuffer);
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, DOT_COUNT);

        GLES20.glDisableVertexAttribArray(mDotPositionLoc);
        GLES20.glDisableVertexAttribArray(mDotOffsetLoc);
        GLES20.glDisableVertexAttribArray(mDotAdjustLoc);
    }

    private int loadTexture(int resId) {
        Bitmap bitmap = BitmapFactory.decodeResource(mResources, resId);
        if (bitmap == null) {
            Log.e(TAG, "Failed to decode texture: " + resId);
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

    private float rand(float min, float max) {
        return min + mRandom.nextFloat() * (max - min);
    }

    private void setAdjust(float[] target, int index, float[] adjust) {
        int base = index * 3;
        target[base] = adjust[0];
        target[base + 1] = adjust[1];
        target[base + 2] = adjust[2];
    }

    private FloatBuffer toFloatBuffer(float[] data) {
        ByteBuffer bb = ByteBuffer.allocateDirect(data.length * 4);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(data);
        fb.position(0);
        return fb;
    }


    private int createProgram(String vertexSource, String fragmentSource) {
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        if (vertexShader == 0 || fragmentShader == 0) return 0;

        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);

        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] == 0) {
            Log.e(TAG, "Program link failed: " + GLES20.glGetProgramInfoLog(program));
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
