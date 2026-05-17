package com.reandroid.wallpaper.deepsea;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;

import androidx.annotation.Nullable;

import com.reandroid.gles.GLESWallpaper;

/**
 * DeepSea 壁纸场景逻辑层（纯逻辑，不接触 Android 系统服务）。
 * 负责偏好解析、颜色计算、容器生命周期。
 */
final class DeepSeaScene implements SharedPreferences.OnSharedPreferenceChangeListener {

    DeepSeaContainer mContainer;
    private SharedPreferences mPrefs;
    Bitmap mCachedBackground;

    void onCreate() {
        Context context = GLESWallpaper.getAppContext();
        if (context == null) {
            return;
        }

        if (mContainer == null) {
            mContainer = new DeepSeaContainer(context);
            mContainer.initByteBuffer();
            mContainer.setViewMatrix();
            mContainer.setBackgroundImagePath(getCachePath(context));
        }

        if (mPrefs == null) {
            mPrefs = context.getSharedPreferences(DeepSeaGL.PREFS_NAME, Context.MODE_PRIVATE);
            mPrefs.registerOnSharedPreferenceChangeListener(this);
        }

        applyCachedBackground(mPrefs);
        onSharedPreferenceChanged(mPrefs, null);
    }

    void release() {
        if (mContainer != null) {
            mContainer.remove();
            mContainer = null;
        }
        if (mPrefs != null) {
            mPrefs.unregisterOnSharedPreferenceChangeListener(this);
            mPrefs = null;
        }
        recycleCachedBackground();
    }

    void resize(int width, int height) {
        if (mContainer != null) {
            mContainer.setProjectionMatrix(width, height);
        }
    }

    void drawFrame() {
        if (mContainer == null) {
            return;
        }
        if (!mContainer.isInitShader()) {
            mContainer.initShader();
        }
        mContainer.draw();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, @Nullable String key) {
        if (mContainer == null || sharedPreferences == null) {
            return;
        }

        if (key == null
                || DeepSeaGL.KEY_BACKGROUND_IMAGE_TYPE.equals(key)
                || DeepSeaGL.KEY_BACKGROUND_UPDATED.equals(key)) {
            applyCachedBackground(sharedPreferences);
        }

        if (DeepSeaGL.KEY_BACKGROUND_UPDATED.equals(key)) {
            applyPreferences(sharedPreferences, DeepSeaGL.KEY_BACKGROUND_IMAGE_TYPE);
            return;
        }

        applyPreferences(sharedPreferences, key);
    }

    private void applyPreferences(SharedPreferences prefs, String key) {
        DeepSeaContainer c = mContainer;
        if (key == null) {
            c.mUnitScaleVal = readIntPreference(prefs, "unitscale", 0);
            int unitNumber = readIntPreference(prefs, "unitnumber", 0);
            int backgroundBrightness = readIntPreference(prefs, "backgroundbrightness", 0);
            int backgroundLighting = readIntPreference(prefs, "backgroundlighting", 0);
            int particleColor = readIntPreference(prefs, "particlecolor", 8);
            int imageType = Integer.parseInt(prefs.getString("backgroundimagetype", "0"));
            c.setScale();
            c.mUnitNumberVal = unitNumber;
            c.setNumberOfUnit(unitNumber);
            c.setBrightness(backgroundBrightness);
            c.setLight(backgroundLighting);
            c.mNumberOfParticles = 35;
            c.mNumberOfParticles2 = 25;
            c.mWaterDropsInJellyfish.setNumberOfParticles(c.mNumberOfParticles);
            c.mWaterDropsInJellyfish2.setNumberOfParticles(c.mNumberOfParticles2);
            setColorByColorValue(c, particleColor);
            c.setBackgroundImageType(imageType);
        } else if (key.equals("unitscale")) {
            c.mUnitScaleVal = readIntPreference(prefs, key, 0);
            c.setScale();
        } else if (key.equals("unitnumber")) {
            int unitNumber = readIntPreference(prefs, key, 0);
            c.mUnitNumberVal = unitNumber;
            c.setNumberOfUnit(unitNumber);
        } else if (key.equals("backgroundbrightness")) {
            c.setBrightness(readIntPreference(prefs, key, 0));
        } else if (key.equals("backgroundlighting")) {
            c.setLight(readIntPreference(prefs, key, 0));
        } else if (key.equals("particlenumber")) {
            c.mNumberOfParticles = 35;
            c.mNumberOfParticles2 = 25;
            c.mWaterDropsInJellyfish.setNumberOfParticles(c.mNumberOfParticles);
            c.mWaterDropsInJellyfish2.setNumberOfParticles(c.mNumberOfParticles2);
        } else if (key.equals("particlecolor")) {
            setColorByColorValue(c, readIntPreference(prefs, key, 8));
        } else if (key.equals("backgroundimagetype")) {
            c.setBackgroundImageType(Integer.parseInt(prefs.getString(key, "0")));
        }
    }

    static void setColorByColorValue(DeepSeaContainer c, int colorIndex) {
        float[] rgb = MathHelper.getRGB(Palette.mValuesOfColor[colorIndex]);
        float[] rgb2 = MathHelper.getRGB(-5789785);
        c.mBlurColor[0] = rgb[0] - rgb2[0];
        c.mBlurColor[1] = rgb[1] - rgb2[1];
        c.mBlurColor[2] = rgb[2] - rgb2[2];
        c.mBlur.setAddColor(c.mBlurColor[0], c.mBlurColor[1], c.mBlurColor[2], 0.0f);
    }

    static int readIntPreference(SharedPreferences prefs, String key, int defaultValue) {
        try {
            return prefs.getInt(key, defaultValue);
        } catch (ClassCastException e) {
            String value = prefs.getString(key, null);
            if (value == null) return defaultValue;
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException ex) {
                return defaultValue;
            }
        }
    }

    private void applyCachedBackground(SharedPreferences prefs) {
        Context context = GLESWallpaper.getAppContext();
        if (context == null || mContainer == null) {
            return;
        }

        String type = prefs.getString(DeepSeaGL.KEY_BACKGROUND_IMAGE_TYPE, "0");
        if ("0".equals(type)) {
            recycleCachedBackground();
            mContainer.setCheckBitmap(null);
            return;
        }

        recycleCachedBackground();
        mCachedBackground = DeepSeaSettings.loadBitmap(getCachePath(context));
        mContainer.setCheckBitmap(mCachedBackground);
    }

    void recycleCachedBackground() {
        if (mCachedBackground != null) {
            mCachedBackground.recycle();
            mCachedBackground = null;
        }
    }

    private static String getCachePath(Context context) {
        if (context.getExternalCacheDir() != null) {
            return context.getExternalCacheDir().getAbsolutePath();
        }
        return context.getCacheDir().getAbsolutePath();
    }
}
