package com.reandroid.wallpaper.musicvis;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

import com.reandroid.plugin.ColorPrefs;

/**
 * Abstract base for pure-logic music visualization scenes.
 * Contains shared AudioCapture lifecycle, HSL recolor state, and preference utilities.
 * Subclasses implement getAudioType() and getAudioCaptureSize().
 */
public abstract class AudioVisBase {

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
    /** 取色器换算 HSV 的暂存数组(避免每帧分配,子类共用)。 */
    protected final float[] mHsvScratch = new float[3];
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
        // 取色器(颜色取代原来的 色调/饱和度/亮度 三个滑块):默认 #FF0000
        // 恰好等价于旧默认 hue=0 / saturation=1 / brightness=1,未设置该键时行为不变
        int recolorColor = ColorPrefs.getColor(p, "musicvis_recolor_color", 0xFFFF0000);
        Color.colorToHSV(recolorColor, mHsvScratch);
        mPrefHue = Math.round(mHsvScratch[0] / 360f * 255f);
        if (!mRecolorDynamic) {
            mHue = mHsvScratch[0] / 360f;
        }
        mSaturation = mHsvScratch[1];
        mBrightness = mHsvScratch[2];
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
        if (p != null) {
            readPrefs(p);
        }
    }

    // ---- abstract ----

    /** @return AudioCapture.TYPE_PCM or TYPE_FFT */
    protected abstract int getAudioType();

    /** @return desired capture size in samples */
    protected abstract int getAudioCaptureSize();
}
