package com.reandroid.wallpaper.weatherwallpapers;

import android.content.Context;

import com.reandroid.astronomy.DayNightResolver;
import com.reandroid.astronomy.DeviceLocation;
import com.reandroid.weather.WeatherCondition;
import com.reandroid.weather.WeatherManager;
import com.reandroid.weather.WeatherState;

import java.util.TimeZone;

public class WeatherStateManager {
    private static final long PREVIEW_STEP_MS = 3000L;

    private static final WeatherCondition[] PREVIEW_ORDER = {
            WeatherCondition.D1_CLEAR,
            WeatherCondition.D2_CLOUDY,
            WeatherCondition.D3_DREARY,
            WeatherCondition.D4_FOG,
            WeatherCondition.D5_RAIN_SHOWERS,
            WeatherCondition.D6_THUNDERSTORMS,
            WeatherCondition.D7_FLURRIES_SNOW,
            WeatherCondition.D8_ICE_COLD,
            WeatherCondition.D9_SLEET
    };

    private final WeatherManager mWeatherManager;

    /**
     * 昼夜改由设备位置就地算（与 grass 同一套）。
     *
     * <p>早先这里读 {@code last_sunrise}/{@code last_sunset}，拿不到就退回硬编码的
     * 06:00/18:00 —— 而那份读法一直是死分支：它读插件设置文件，那两个键却被写进
     * 应用的默认设置文件。也就是说这两款壁纸从来只有后一半在生效，一整年都按本地
     * 钟表的 6 点/18 点切换。
     */
    private final DayNightResolver mDayNight = new DayNightResolver();
    private final DeviceLocation mDeviceLocation = new DeviceLocation();
    private float[] mResolvedLocation;

    private WeatherCondition mCondition = WeatherCondition.D1_CLEAR;
    private boolean mIsNight = false;

    private boolean mPreviewActive = false;
    private int mPreviewIndex = 0;
    private long mPreviewNextMs = 0L;

    public WeatherStateManager(Context appContext) {
        mWeatherManager = appContext != null ? new WeatherManager(appContext, this::onWeatherUpdated) : null;
    }

    public synchronized void start(boolean preview) {
        if (!preview && mWeatherManager != null) {
            mPreviewActive = false;
            mPreviewNextMs = 0L;
            mWeatherManager.start();
            return;
        }
        initPreviewCycle();
    }

    public void stop() {
        if (mWeatherManager != null) {
            mWeatherManager.stop();
        }
    }

    public synchronized void update(long timeMs, boolean preview) {
        if (preview) {
            updatePreviewCycle(timeMs);
        }
        refreshLocation();
        mIsNight = mDayNight.isNight(System.currentTimeMillis());
    }

    public synchronized WeatherCondition getCondition() {
        return mCondition;
    }

    public synchronized boolean isNight() {
        return mIsNight;
    }

    public synchronized boolean shouldFastAnimate() {
        return mCondition == WeatherCondition.D7_FLURRIES_SNOW || mCondition == WeatherCondition.D9_SLEET;
    }

    private synchronized void onWeatherUpdated(WeatherState state) {
        if (state == null) {
            return;
        }
        mCondition = state.condition;
        // state.isNight 不再采用：那是接口所在城市的昼夜，和本机位置不是一回事
    }

    /**
     * 位置变了才重建太阳计算器。
     *
     * <p>{@link DeviceLocation} 结果没变时返回同一个数组实例，所以这个引用比较
     * 在 5 分钟节流内恒为真 —— 每帧跑的就只有一次引用比较。
     *
     * <p>解析成 null 也当成一次变化：那是"调试覆盖被清除了、又没有真实定位"，
     * 该回到按时区反推的兜底，而不是继续用手填的经纬度。
     */
    private void refreshLocation() {
        float[] resolved = mDeviceLocation.resolve();
        if (resolved == mResolvedLocation) {
            return;
        }
        mResolvedLocation = resolved;
        TimeZone zone = TimeZone.getDefault();
        if (resolved != null) {
            mDayNight.setLocation(resolved[0], resolved[1]);
            mDayNight.setTimeZone(zone);
        } else {
            mDayNight.applyFallbackLocation(zone);
        }
    }

    private void initPreviewCycle() {
        mPreviewActive = true;
        mPreviewIndex = 0;
        mPreviewNextMs = 0L;
        mCondition = WeatherCondition.D1_CLEAR;
    }

    private void updatePreviewCycle(long timeMs) {
        if (!mPreviewActive) {
            return;
        }
        if (mPreviewNextMs == 0L) {
            mPreviewNextMs = timeMs + PREVIEW_STEP_MS;
            return;
        }
        if (timeMs < mPreviewNextMs) {
            return;
        }

        if (mPreviewIndex >= PREVIEW_ORDER.length - 1) {
            mPreviewIndex = 0;
            mPreviewActive = false;
            mCondition = PREVIEW_ORDER[0];
            return;
        }

        mPreviewIndex++;
        mCondition = PREVIEW_ORDER[mPreviewIndex];
        mPreviewNextMs = timeMs + PREVIEW_STEP_MS;
    }
}
