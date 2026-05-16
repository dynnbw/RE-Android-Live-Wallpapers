package com.reandroid.wallpaper.weatherwallpapers.windmill;

import android.content.Context;
import android.content.SharedPreferences;
import android.opengl.Matrix;

import androidx.preference.PreferenceManager;

import com.reandroid.wallpaper.weatherwallpapers.AnimationController;
import com.reandroid.wallpaper.weatherwallpapers.CloudRenderer;
import com.reandroid.wallpaper.weatherwallpapers.FogIceRenderer;
import com.reandroid.wallpaper.weatherwallpapers.PrecipitationRenderer;
import com.reandroid.wallpaper.weatherwallpapers.SkyRenderer;
import com.reandroid.wallpaper.weatherwallpapers.ThunderRenderer;
import com.reandroid.wallpaper.weatherwallpapers.WeatherFlagManager;
import com.reandroid.wallpaper.weatherwallpapers.WeatherStateManager;
import com.reandroid.weather.WeatherCondition;

/**
 * Windmill 壁纸场景逻辑层（纯 Java，仅 Matrix.perspectiveM 用于投影矩阵）。
 */
final class WindmillScene {

    static final int MAX_FRAME = 2000;
    private static final float FOV = 45.0f;

    final float[] mProjectionMatrix = new float[16];
    final AnimationController mAnimationController = new AnimationController(MAX_FRAME);

    WeatherCondition mCondition = WeatherCondition.D1_CLEAR;
    boolean mIsNight = false;

    float mOffset = 1.25f;
    float mLandscape = 1.0f;
    float mFillScaleY = 1.0f;
    int mFrameCnt = 0;

    WeatherStateManager mWeatherStateManager;
    final WeatherFlagManager mWeatherFlagManager = new WeatherFlagManager();

    WindmillScene() {
    }

    void initMemory() {
        // Renderers are initialized externally by GL
    }

    void onCreate(Context appContext) {
        SharedPreferences prefs = appContext != null
                ? PreferenceManager.getDefaultSharedPreferences(appContext)
                : null;
        mWeatherStateManager = new WeatherStateManager(appContext, prefs);
    }

    void start(boolean isPreview) {
        if (mWeatherStateManager != null) {
            mWeatherStateManager.start(isPreview);
        }
    }

    void stop() {
        if (mWeatherStateManager != null) {
            mWeatherStateManager.stop();
        }
    }

    void updateWeatherFlags() {
        mWeatherFlagManager.update(mCondition, mIsNight);
    }

    void updateProjection(int width, int height) {
        mLandscape = width < height ? 1.0f : 2.0f;
        float aspect = (float) width / (float) height;
        float refAspect = 9.0f / 16.0f;
        mFillScaleY = aspect < refAspect ? (refAspect / aspect) : 1.0f;
        float fov = FOV;
        if (width < height) {
            if (aspect < refAspect) {
                float scale = aspect / refAspect;
                double fovRad = Math.toRadians(FOV);
                fov = (float) Math.toDegrees(2.0d * Math.atan(Math.tan(fovRad / 2.0d) * scale));
            }
        }
        Matrix.perspectiveM(mProjectionMatrix, 0, fov, aspect, 0.1f, 40.0f);
    }
}
