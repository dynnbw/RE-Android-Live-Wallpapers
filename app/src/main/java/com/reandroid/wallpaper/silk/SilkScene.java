package com.reandroid.wallpaper.silk;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Silk wallpaper ("丝语流年") scene logic — pure Java.
 * <p>
 * Port of the vivo CSilk engine (Silk/CoralSea/CoffeeTime share one binary;
 * the only differences are originColor and the two textures). Three animated
 * ribbon meshes wave along the x axis with an accelerating sine (freq*x²/LEN),
 * each rotated around its anchor and fading in/out along its length.
 */
final class SilkScene {

    static final String PREFS_NAME = "silk";
    static final String KEY_THEME = "silk_theme";
    static final String KEY_SPEED = "silk_speed";

    static final int RIBBON_COUNT = 3;
    static final int COLUMNS = 65;          // i = 0..64
    static final int VERTICES = COLUMNS * 2; // 130 (row y1 + row y2)

    static final String THEME_SILK = "silk";
    static final String THEME_CORALSEA = "coralsea";
    static final String THEME_COFFEETIME = "coffeetime";

    /** Per-ribbon static config, decoded from CRenderManager::init constants. */
    static final class RibbonConfig {
        final float newPosX, newPosY;
        final float rotBaseDeg, rotSpeedDeg;
        final float len;              // grid length (x step = len * 0.015625)
        final float colorDiv;         // color gradient normalization
        final float amp, freq;
        final float angleDeltaPerSec; // per-frame delta x60 (60fps baseline)
        final float flashBase, flashAmp;
        final boolean decay;
        final float originAlpha;
        final float decayLen;         // fragment 'length'
        final float divFactor;        // fragment DIVISION_FACTOR (NOT 1/len for ribbon 2)

        RibbonConfig(float newPosX, float newPosY, float rotBaseDeg, float rotSpeedDeg,
                     float len, float colorDiv, float amp, float freq,
                     float angleDeltaPerSec, float flashBase, float flashAmp,
                     boolean decay, float originAlpha, float decayLen, float divFactor) {
            this.newPosX = newPosX;
            this.newPosY = newPosY;
            this.rotBaseDeg = rotBaseDeg;
            this.rotSpeedDeg = rotSpeedDeg;
            this.len = len;
            this.colorDiv = colorDiv;
            this.amp = amp;
            this.freq = freq;
            this.angleDeltaPerSec = angleDeltaPerSec;
            this.flashBase = flashBase;
            this.flashAmp = flashAmp;
            this.decay = decay;
            this.originAlpha = originAlpha;
            this.decayLen = decayLen;
            this.divFactor = divFactor;
        }
    }

    /** Authoritative values from the original binary (verified against .so.c). */
    static final RibbonConfig[] RIBBONS = {
            new RibbonConfig(0f, 1200f, 40f, 5f, 2400f, 180f, 0.1f, 0.0036f,
                    0.36f, 0.3f, 0.2f, true, 0.4f, 2400f, 1f / 2400f),
            new RibbonConfig(-1240f, 800f, 12f, 2f, 3600f, 260f, 0.02f, 0.001f,
                    0.54f, 0.5f, 0.5f, true, 0.8f, 3600f, 1f / 1060f),
            new RibbonConfig(320f, 2200f, 35f, 6f, 2400f, 220f, 0.08f, 0.003f,
                    0.36f, 0.3f, 0.5f, false, 0.5f, 2400f, 1f / 2400f),
    };

    /** Theme → originColor rgb. Alpha is the ribbon's own originAlpha. */
    static final String[] THEME_IDS = {THEME_SILK, THEME_CORALSEA, THEME_COFFEETIME};

    static float[] themeOriginColor(String theme) {
        switch (theme == null ? THEME_SILK : theme) {
            case THEME_CORALSEA:
                return new float[]{0.68f, 0.28f, 0.48f};
            case THEME_COFFEETIME:
                return new float[]{0.54f, 0.4f, 0.22f};
            default:
                return new float[]{0.1f, 0.5f, 0.54f};
        }
    }

    static String themeBackgroundAsset(String theme) {
        return "silk/drawable/background_" + (theme == null ? THEME_SILK : theme) + ".png";
    }

    static String themeSilkAsset(String theme) {
        return "silk/drawable/silk_" + (theme == null ? THEME_SILK : theme) + ".png";
    }

    // ==================== Shared static geometry ====================

    /**
     * 260 floats (130 Vec2), exact port of CSilk::initDatas UV layout.
     * The original loop runs i = 0..63 (64 columns); the tail vertices
     * (64 and 129) are written separately after the loop (offsets 2128 / 2648):
     * row 0 (y1, verts 0..64):   A[i] = (1-i%2, 0); vert 64 = (0,1)   (tail Vec2)
     * row 1 (y2, verts 65..129): B[i] = (i%2, 1);   vert 129 = (1,0)  (tail Vec2)
     */
    static float[] buildUv() {
        float[] uv = new float[VERTICES * 2];
        for (int i = 0; i < COLUMNS - 1; i++) {   // i = 0..63
            uv[i * 2] = 1 - (i % 2);
            uv[i * 2 + 1] = 0f;
            int j = (i + COLUMNS) * 2;
            uv[j] = i % 2;
            uv[j + 1] = 1f;
        }
        // Tail Vec2 writes after the loop: vert 64 = (0,1), vert 129 = (1,0)
        uv[64 * 2] = 0f;
        uv[64 * 2 + 1] = 1f;
        uv[129 * 2] = 1f;
        uv[129 * 2 + 1] = 0f;
        return uv;
    }

    /**
     * 384 bytes: 64 segments, per segment [i, i+1, i+65, i+65, i+66, i+1]
     * (GL_UNSIGNED_BYTE). Loop runs i = 0..63 only — a 65th segment would
     * emit index 130, out of bounds for the 130-vertex buffers.
     */
    static byte[] buildIndices() {
        byte[] idx = new byte[(COLUMNS - 1) * 6];
        for (int i = 0; i < COLUMNS - 1; i++) {
            int b = i * 6;
            idx[b] = (byte) i;
            idx[b + 1] = (byte) (i + 1);
            idx[b + 2] = (byte) (i + COLUMNS);
            idx[b + 3] = (byte) (i + COLUMNS);
            idx[b + 4] = (byte) (i + COLUMNS + 1);
            idx[b + 5] = (byte) (i + 1);
        }
        return idx;
    }

    // ==================== Per-frame output (reused) ====================

    final float[][] mPositions = new float[RIBBON_COUNT][VERTICES * 2];
    final float[][] mColors = new float[RIBBON_COUNT][VERTICES];
    final float[] mFlash = new float[RIBBON_COUNT];
    final float[] mRotSin = new float[RIBBON_COUNT];
    final float[] mRotCos = new float[RIBBON_COUNT];

    // ==================== Animation state ====================

    private final float[] mPhase = new float[RIBBON_COUNT];
    private final float[] mAngle = new float[RIBBON_COUNT];
    private final float[] mT = new float[RIBBON_COUNT]; // rotation clock

    // ==================== Prefs ====================

    private final Context mContext;
    private SharedPreferences mPrefs;
    private SharedPreferences mPluginPrefs;
    String mTheme = THEME_SILK;
    float mSpeedMultiplier = 1f;

    SilkScene(Context context) {
        mContext = context;
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

    void setPluginPrefs(SharedPreferences prefs) {
        mPluginPrefs = prefs;
        reloadPrefs();
    }

    void reloadPrefs() {
        SharedPreferences prefs = getPrefs();
        if (prefs == null) return;
        mTheme = prefs.getString(KEY_THEME, THEME_SILK);
        int speed = prefs.getInt(KEY_SPEED, 100);
        mSpeedMultiplier = speed / 100f;
    }

    /**
     * Advance animation state and fill vertex arrays (exact port of CSilk::fillVertexArray).
     * dtEff is the clamped frame delta (seconds) already scaled by the speed preference.
     */
    void update(float dtEff) {
        for (int r = 0; r < RIBBON_COUNT; r++) {
            RibbonConfig c = RIBBONS[r];
            float[] pos = mPositions[r];
            float[] col = mColors[r];
            float phase = mPhase[r];

            for (int i = 0; i < COLUMNS; i++) {
                float x = i * c.len * 0.015625f;
                float s = (float) Math.sin(c.freq * x * x / c.len + phase);
                float y1 = (c.amp * x + 30f) * s;
                float y2 = ((c.amp + 0.02f) * x + 30f + c.colorDiv) * s;
                float color = Math.min(Math.abs(y2 - y1) / c.colorDiv, 1f);

                pos[i * 2] = x;
                pos[i * 2 + 1] = y1;
                int j = (i + COLUMNS) * 2;
                pos[j] = x;
                pos[j + 1] = y2;
                col[i] = color;
                col[i + COLUMNS] = color;
            }

            float angle = mAngle[r];
            phase -= (c.angleDeltaPerSec + ((float) Math.sin(angle) + 1f) * 0.18f) * dtEff;
            angle += c.angleDeltaPerSec * dtEff;
            mPhase[r] = phase;
            mAngle[r] = angle;
            mFlash[r] = c.flashBase + c.flashAmp * ((float) Math.sin(angle) + 1f) * 0.5f;

            float t = mT[r] + 0.6f * dtEff;
            mT[r] = t;
            float rot = (c.rotBaseDeg + c.rotSpeedDeg * (float) Math.sin(t)) * 0.0174532925f;
            mRotSin[r] = (float) Math.sin(rot);
            mRotCos[r] = (float) Math.cos(rot);
        }
    }
}
