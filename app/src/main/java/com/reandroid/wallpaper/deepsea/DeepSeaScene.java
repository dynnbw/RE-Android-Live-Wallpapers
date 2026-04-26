package com.reandroid.wallpaper.deepsea;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import androidx.annotation.Nullable;

import com.reandroid.gles.GLESWallpaper;

/**
 * DeepSea 壁纸场景逻辑层（非 GL 调用）。
 * 负责生命周期、系统监听、偏好同步，以及将状态写入 DeepSeaContainer。
 */
final class DeepSeaScene implements SharedPreferences.OnSharedPreferenceChangeListener, SensorEventListener {

    private DeepSeaContainer mContainer;
    private SharedPreferences mPrefs;
    private SensorManager mSensorManager;
    private Sensor mGyro;
    private Bitmap mCachedBackground;
    private boolean mReceiversRegistered;
    private BroadcastReceiver mBatteryReceiver;
    private BroadcastReceiver mScreenReceiver;

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

        // 主动刷新缓存图片，保证预览页和首帧显示一致。
        applyCachedBackground(mPrefs);
        onSharedPreferenceChanged(mPrefs, null);
    }

    void start() {
        registerReceivers();
        if (mContainer != null) {
            mContainer.screenOn();
        }
    }

    void stop() {
        unregisterReceivers();
    }

    void release() {
        unregisterReceivers();

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
            mContainer.onSharedPreferenceChanged(sharedPreferences, DeepSeaGL.KEY_BACKGROUND_IMAGE_TYPE);
            return;
        }

        mContainer.onSharedPreferenceChanged(sharedPreferences, key);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (mContainer == null || event == null || event.values == null || event.values.length < 3) {
            return;
        }
        float z = event.values[2];
        if (z > 2.5f || z < -2.5f) {
            if (z > 0.0f) {
                z += 10.0f;
            }
            float force = -(z < 0.0f ? z - 10.0f : z);
            mContainer.setByShake(force);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
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

    private void registerReceivers() {
        if (mReceiversRegistered) {
            return;
        }
        Context context = GLESWallpaper.getAppContext();
        if (context == null) {
            return;
        }

        if (mSensorManager == null) {
            mSensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            if (mSensorManager != null) {
                mGyro = mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            }
        }

        if (mSensorManager != null && mGyro != null) {
            mSensorManager.registerListener(this, mGyro, SensorManager.SENSOR_DELAY_GAME);
        }

        mBatteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (mContainer == null || intent == null) {
                    return;
                }
                int plugged = intent.getIntExtra("plugged", 0);
                int level = intent.getIntExtra("level", 0);
                int state;
                if (plugged > 0 || level > 30) {
                    state = 0;
                } else if (level > 15) {
                    state = 1;
                } else {
                    state = 2;
                }
                mContainer.setStateByBattery(state);
            }
        };

        mScreenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (mContainer == null || intent == null) {
                    return;
                }
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    mContainer.screenOff();
                } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    mContainer.screenOn();
                }
            }
        };

        context.registerReceiver(mBatteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        IntentFilter screenFilter = new IntentFilter();
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        context.registerReceiver(mScreenReceiver, screenFilter);

        mReceiversRegistered = true;
    }

    private void unregisterReceivers() {
        if (!mReceiversRegistered) {
            return;
        }
        Context context = GLESWallpaper.getAppContext();
        if (context == null) {
            return;
        }

        if (mSensorManager != null) {
            mSensorManager.unregisterListener(this);
        }

        if (mBatteryReceiver != null) {
            context.unregisterReceiver(mBatteryReceiver);
            mBatteryReceiver = null;
        }
        if (mScreenReceiver != null) {
            context.unregisterReceiver(mScreenReceiver);
            mScreenReceiver = null;
        }

        mReceiversRegistered = false;
    }

    private void recycleCachedBackground() {
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
