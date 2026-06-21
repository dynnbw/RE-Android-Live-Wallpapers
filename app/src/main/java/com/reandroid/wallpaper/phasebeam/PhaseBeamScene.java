package com.reandroid.wallpaper.phasebeam;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.util.Log;

import com.reandroid.utils.MathUtils;

import com.reandroid.wallpaper.R;
import com.reandroid.utils.AssetLoader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Random;

/**
 * PhaseBeam 壁纸场景逻辑层（纯 Java，无 GL 调用）。
 * 负责粒子动画、HSL 色彩调整、背景网格数据管理。
 */
final class PhaseBeamScene {
    private static final String TAG = "PhaseBeamScene";

    static final String PREFS_NAME = "phasebeam";
    static final String KEY_ENABLED = "phasebeam_recolor_enabled";
    static final String KEY_HUE = "phasebeam_hue";
    static final String KEY_SATURATION = "phasebeam_saturation";
    static final String KEY_BRIGHTNESS = "phasebeam_brightness";
    static final String KEY_THEME = "theme";

    private int mDotCount = 28;
    private boolean mParamsDirty;

    private static final float ZX_PARTICLE_SPEED = 0.0000780f;
    private static final float ZX_BEAM_SPEED = 0.00005f;
    private static final float YZ_PARTICLE_SPEED = 0.00011f;
    private static final float YZ_BEAM_SPEED = 0.000080f;

    private final Context mContext;
    private SharedPreferences mPrefs;
    private SharedPreferences mPluginPrefs;

    float mScaleSize = 1.0f;
    float mXOffset = 0.5f;
    float mOldOffset = 0.5f;

    float mHue = 0.0f;
    float mSaturation = 1.0f;
    float mBrightness = 1.0f;
    boolean mRecolorEnabled = false;
    boolean mCanScroll = true;
    String mTheme = "phasebeam";
    float mSpeedMultiplier = 1.0f;

    final float[] mAdjust = new float[] { -1.0f, 1.0f, 1.0f };
    final float[] mOldAdjust = new float[] { -1.0f, 1.0f, 1.0f };

    boolean mDirtyBackground = true;
    boolean mDirtyParticles = true;
    boolean mDirtyTexture = true;
    boolean mNeedViewport = true;

    long mLastTimeMs = 0L;

    // Background mesh data
    float[] mBgRawVertices;
    float[] mBgBaseColors;
    int mBgVertexCount;

    // Particle position/offset/adjust arrays (dynamically allocated)
    float[] mDotPositions;
    float[] mDotOffsets;
    float[] mDotAdjusts;
    float[] mBeamPositions;
    float[] mBeamOffsets;
    float[] mBeamAdjusts;

    // Background buffers (NIO, created by Scene, read by GL)
    FloatBuffer mBgPositionBuffer;
    FloatBuffer mBgOffsetBuffer;
    FloatBuffer mBgRealColorBuffer;
    FloatBuffer mBgAdjustBuffer;

    // Particle buffers
    FloatBuffer mDotPositionBuffer;
    FloatBuffer mDotOffsetBuffer;
    FloatBuffer mDotAdjustBuffer;

    FloatBuffer mBeamPositionBuffer;
    FloatBuffer mBeamOffsetBuffer;
    FloatBuffer mBeamAdjustBuffer;

    private final Random mRandom = new Random();

    PhaseBeamScene(Context context) {
        mContext = context;
        allocateArrays();
    }

    /**
     * 初始化设置和显示参数
     * @param resources 资源管理器
     */
    void init(Resources resources) {
        ensurePrefs();
        mScaleSize = resources.getDisplayMetrics().densityDpi / 240.0f;
        mCanScroll = resources.getBoolean(R.bool.scrolling_enabled);
        readPrefs(resources);
    }

    void setOffset(float xOffset) {
        if (mCanScroll) {
            mXOffset = xOffset;
        }
    }

    void reloadPreferences(Resources resources) {
        readPrefs(resources);
        mDirtyBackground = true;
        mDirtyParticles = true;
        mDirtyTexture = true;
    }

    void ensurePrefs() {
        if (mPluginPrefs != null) return;
        if (mPrefs == null && mContext != null) {
            mPrefs = mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }
    }

    SharedPreferences getPrefs() {
        return mPluginPrefs != null ? mPluginPrefs : mPrefs;
    }

    public void setPluginPrefs(SharedPreferences prefs) {
        mPluginPrefs = prefs;
        int newCount = MathUtils.clamp(prefs.getInt("phasebeam_dot_count", 28), 10, 60);
        if (newCount != mDotCount) {
            mDotCount = newCount;
            mParamsDirty = true;
        }
    }

    boolean consumeParamsDirty() { boolean v = mParamsDirty; mParamsDirty = false; return v; }
    int getDotCount() { return mDotCount; }

    void allocateArrays() {
        int c = mDotCount;
        mDotPositions = new float[c * 3];
        mDotOffsets = new float[c];
        mDotAdjusts = new float[c * 3];
        mBeamPositions = new float[c * 3];
        mBeamOffsets = new float[c];
        mBeamAdjusts = new float[c * 3];
    }

    private void readPrefs(Resources resources) {
        if (resources == null) return;
        SharedPreferences p = getPrefs();
        if (p == null) return;
        int newCount = MathUtils.clamp(p.getInt("phasebeam_dot_count", 28), 10, 60);
        if (newCount != mDotCount) {
            mDotCount = newCount;
            mParamsDirty = true;
        }
        mTheme = p.getString(KEY_THEME, "phasebeam");
        mSpeedMultiplier = "sunbeam".equals(mTheme) ? 3.0f : 1.0f;
        mRecolorEnabled = p.getBoolean(KEY_ENABLED, resources.getBoolean(R.bool.recolor_enabled));
        // SeekBar stores int (0–1000); convert to float. Fall back to legacy float values.
        mHue = readFloatFromIntOrFloat(p, KEY_HUE, 0f, 1f, 0);
        mSaturation = readFloatFromIntOrFloat(p, KEY_SATURATION, 0f, 1f, 255);
        mBrightness = readFloatFromIntOrFloat(p, KEY_BRIGHTNESS, 0.5f, 1.5f, 128);
        updateAdjust();
    }

    /** Reads a float from int (SeekBar) or legacy float value, converting int to range. */
    private static float readFloatFromIntOrFloat(SharedPreferences p, String key,
                                                  float min, float max, int defaultProgress) {
        try {
            if (p.contains(key)) {
                // Try as int first (SeekBar), then float (legacy)
                try { return min + (max - min) * p.getInt(key, defaultProgress) / 255f; }
                catch (ClassCastException e) { return p.getFloat(key, min); }
            }
        } catch (Exception e) { Log.w(TAG, "Failed to read preference: " + key, e); }
        return min + (max - min) * defaultProgress / 255f;
    }

    void updateAdjust() {
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

    boolean adjustChanged() {
        return mAdjust[0] != mOldAdjust[0]
                || mAdjust[1] != mOldAdjust[1]
                || mAdjust[2] != mOldAdjust[2];
    }

    /**
     * 从 raw 资源加载背景网格
     * @param resources 资源管理器
     */
    void loadBackgroundMesh(Context context, String assetPath) {
        if (context == null || assetPath == null) return;
        float[] mesh = AssetLoader.readFloatArray(context, assetPath);
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

    /**
     * 更新背景缓冲区（位置、偏移、颜色、HSL调整）
     * @param newOffset 新偏移值
     */
    void updateBackgroundBuffers(float newOffset) {
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

        mBgPositionBuffer = createFloatBuffer(positions);
        mBgOffsetBuffer = createFloatBuffer(offsets);
        mBgRealColorBuffer = createFloatBuffer(realColors);
        mBgAdjustBuffer = createFloatBuffer(adjusts);
    }

    /**
     * 初始化粒子位置
     */
    void positionParticles() {
        for (int i = 0; i < mDotCount; i++) {
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

        for (int i = 0; i < mDotCount; i++) {
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

    /**
     * 更新所有粒子的 HSL 调整值
     */
    void updateParticleAdjusts() {
        for (int i = 0; i < mDotCount; i++) {
            setAdjust(mDotAdjusts, i, mAdjust);
            setAdjust(mBeamAdjusts, i, mAdjust);
        }
        updateParticleBuffers();
    }

    /**
     * 从当前数据数组创建 NIO 缓冲区
     */
    void updateParticleBuffers() {
        mDotPositionBuffer = createFloatBuffer(mDotPositions);
        mDotOffsetBuffer = createFloatBuffer(mDotOffsets);
        mDotAdjustBuffer = createFloatBuffer(mDotAdjusts);

        mBeamPositionBuffer = createFloatBuffer(mBeamPositions);
        mBeamOffsetBuffer = createFloatBuffer(mBeamOffsets);
        mBeamAdjustBuffer = createFloatBuffer(mBeamAdjusts);
    }

    /**
     * 更新粒子动画（位置、环绕）
     * @param timeScale 时间缩放因子
     * @param newOffset 新偏移值
     */
    void updateParticles(float timeScale, float newOffset) {
        final float sm = mSpeedMultiplier;

        for (int i = 0; i < mDotCount; i++) {
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
                y += YZ_BEAM_SPEED * z * timeScale * sm;
            }
            x += ZX_BEAM_SPEED * z * timeScale * sm;

            mBeamPositions[idx] = x;
            mBeamPositions[idx + 1] = y;
            mBeamOffsets[i] = newOffset;
        }

        for (int i = 0; i < mDotCount; i++) {
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
                y += YZ_PARTICLE_SPEED * z * timeScale * sm;
            }

            x += ZX_PARTICLE_SPEED * z * timeScale * sm;

            mDotPositions[idx] = x;
            mDotPositions[idx + 1] = y;
            mDotOffsets[i] = newOffset;
        }

        mBeamPositionBuffer = createFloatBuffer(mBeamPositions);
        mBeamOffsetBuffer = createFloatBuffer(mBeamOffsets);
        mDotPositionBuffer = createFloatBuffer(mDotPositions);
        mDotOffsetBuffer = createFloatBuffer(mDotOffsets);
    }

    // ---- Utility methods ----

    float rand(float min, float max) {
        return min + mRandom.nextFloat() * (max - min);
    }

    void setAdjust(float[] target, int index, float[] adjust) {
        int base = index * 3;
        target[base] = adjust[0];
        target[base + 1] = adjust[1];
        target[base + 2] = adjust[2];
    }

    static FloatBuffer createFloatBuffer(float[] data) {
        ByteBuffer bb = ByteBuffer.allocateDirect(data.length * 4);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(data);
        fb.position(0);
        return fb;
    }
}
