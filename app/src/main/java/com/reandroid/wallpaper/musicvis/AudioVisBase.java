package com.reandroid.wallpaper.musicvis;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

/**
 * Abstract base for pure-logic music visualization scenes.
 * Contains shared AudioCapture lifecycle, HSL recolor state, and preference utilities.
 * Subclasses implement getAudioType() and getAudioCaptureSize().
 */
public abstract class AudioVisBase implements SharedPreferences.OnSharedPreferenceChangeListener {

    protected Context mContext;
    protected int mWidth;
    protected int mHeight;

    // Audio
    protected AudioCapture mAudioCapture;
    protected int[] mVizData;
    protected int[] mAnalyzer;
    protected float[] mPcmSmoothed;
    protected int mFftSize = 512;
    protected boolean mFftSizeChanged;

    // Rendering mode
    protected boolean mUseTriangleStrip = true;
    protected boolean mHasPrefInit = false;

    // HSL recolor
    public boolean mRecolorEnabled;
    public boolean mRecolorDynamic;
    public float mHue;
    public float mSaturation = 1f;
    public float mBrightness = 1f;
    protected int mPrefHue;
    public final float[] mBgColor = new float[3];

    // Shared prefs (injected by engine or read from default source)
    protected SharedPreferences mPluginPrefs;

    protected AudioVisBase(int width, int height, Context context) {
        mWidth = width;
        mHeight = height;
        mContext = context;
    }

    /**
     * Read common shared preferences into the HSL / render-mode fields.
     * Subclasses may call this from their own start() or readPrefs().
     */
    protected void readPrefs(SharedPreferences p) {
        mFftSize = safeParseInt(p.getString("musicvis_fft_size", "512"), 512);
        mUseTriangleStrip = p.getBoolean("musicvis_use_triangle_strip", true);
        mRecolorEnabled = p.getBoolean("musicvis_recolor", false);
        mRecolorDynamic = "dynamic".equals(p.getString("musicvis_recolor_mode", "static"));
        mPrefHue = safeGetInt(p, "musicvis_hue", 0);
        if (!mRecolorDynamic) {
            mHue = mPrefHue / 255f;
        }
        mSaturation = safeGetInt(p, "musicvis_saturation", 255) / 255f;
        mBrightness = safeGetInt(p, "musicvis_brightness", 255) / 255f;
        String hex = p.getString("musicvis_bg_color", "#000000");
        try {
            int c = Color.parseColor(hex);
            mBgColor[0] = Color.red(c) / 255f;
            mBgColor[1] = Color.green(c) / 255f;
            mBgColor[2] = Color.blue(c) / 255f;
        } catch (Exception e) {
            mBgColor[0] = mBgColor[1] = mBgColor[2] = 0f;
        }
    }

    // ---- utility methods ----

    protected static int safeParseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }

    protected static int safeGetInt(SharedPreferences p, String k, int d) {
        try { return p.getInt(k, d); } catch (ClassCastException e) { return d; }
    }

    // ---- lifecycle ----

    @Override
    public void onSharedPreferenceChanged(SharedPreferences p, String key) {}

    public void start() {}

    public void stop() {}

    public void release() {
        if (mAudioCapture != null) {
            mAudioCapture.release();
            mAudioCapture = null;
        }
    }

    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {}

    public void setPluginPrefs(SharedPreferences p) {
        mPluginPrefs = p;
    }

    // ---- abstract ----

    /** @return AudioCapture.TYPE_PCM or TYPE_FFT */
    protected abstract int getAudioType();

    /** @return desired capture size in samples */
    protected abstract int getAudioCaptureSize();
}
