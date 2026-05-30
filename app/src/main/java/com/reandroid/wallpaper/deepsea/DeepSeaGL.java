package com.reandroid.wallpaper.deepsea;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import com.reandroid.gles.GLESScene;
import com.reandroid.gles.GLESWallpaper;

public class DeepSeaGL extends GLESScene implements SensorEventListener {
    public static final String PREFS_NAME = "deepsea";
    public static final String KEY_BACKGROUND_IMAGE_TYPE = "backgroundimagetype";
    public static final String KEY_BACKGROUND_UPDATED = "deepsea_background_updated";

    private final DeepSeaScene mScene;

    private SensorManager mSensorManager;
    private Sensor mGyro;
    private boolean mReceiversRegistered;
    private BroadcastReceiver mBatteryReceiver;
    private BroadcastReceiver mScreenReceiver;

    public DeepSeaGL(int width, int height) {
        super(width, height);
        mScene = new DeepSeaScene();
    }

    /** Plugin path: inject host-provided prefs into the scene. */
    public void setPluginPrefs(SharedPreferences prefs) {
        mScene.setPluginPrefs(prefs);
    }

    @Override
    protected void onCreate() {
        mScene.onCreate();
    }

    @Override
    public void start() {
        registerReceivers();
        if (mScene.mContainer != null) {
            mScene.mContainer.screenOn();
        }
    }

    @Override
    public void stop() {
        unregisterReceivers();
    }

    @Override
    public void release() {
        unregisterReceivers();
        mScene.release();
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        mScene.resize(width, height);
    }

    @Override
    public void drawFrame(long timeMs) {
        mScene.drawFrame();
    }

    // ---- SensorEventListener ----

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (mScene.mContainer == null || event == null || event.values == null || event.values.length < 3) {
            return;
        }
        float z = event.values[2];
        if (z > 2.5f || z < -2.5f) {
            if (z > 0.0f) z += 10.0f;
            float force = -(z < 0.0f ? z - 10.0f : z);
            mScene.mContainer.setByShake(force);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    // ---- Receiver management ----

    private void registerReceivers() {
        if (mReceiversRegistered) return;
        Context context = GLESWallpaper.getAppContext();
        if (context == null) return;

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
                if (mScene.mContainer == null || intent == null) return;
                int plugged = intent.getIntExtra("plugged", 0);
                int level = intent.getIntExtra("level", 0);
                int state;
                if (plugged > 0 || level > 30) state = 0;
                else if (level > 15) state = 1;
                else state = 2;
                mScene.mContainer.setStateByBattery(state);
            }
        };
        mScreenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (mScene.mContainer == null || intent == null) return;
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) mScene.mContainer.screenOff();
                else if (Intent.ACTION_SCREEN_ON.equals(action)) mScene.mContainer.screenOn();
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
        if (!mReceiversRegistered) return;
        Context context = GLESWallpaper.getAppContext();
        if (context == null) return;

        if (mSensorManager != null) mSensorManager.unregisterListener(this);
        if (mBatteryReceiver != null) { context.unregisterReceiver(mBatteryReceiver); mBatteryReceiver = null; }
        if (mScreenReceiver != null) { context.unregisterReceiver(mScreenReceiver); mScreenReceiver = null; }
        mReceiversRegistered = false;
    }
}
