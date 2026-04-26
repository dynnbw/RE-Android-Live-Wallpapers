package com.reandroid.wallpaper.nightsky;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.opengl.Matrix;
import android.os.SystemClock;
import android.view.Surface;

final class NightSkySensorController implements SensorEventListener {
    private static final long MOTION_IDLE_TIMEOUT_MS = 3000L;
    private static final float ROTATION_DELTA_THRESHOLD = 0.01f;

    private final float[] viewRot = new float[16];
    private final float[] sensorRot3 = new float[9];
    private final float[] sensorRemap3 = new float[9];
    private final float[] lastQuat = new float[4];

    private SensorManager sensorManager;
    private Sensor rotationVector;
    private volatile int displayRotation = Surface.ROTATION_0;
    private boolean hasLastQuat;
    private long lastMotionUptimeMs;
    private int currentSamplingDelay;
    private boolean started;

    NightSkySensorController() {
        Matrix.setIdentityM(viewRot, 0);
    }

    void init(Context context) {
        if (context == null) {
            return;
        }
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        }
    }

    void start() {
        if (sensorManager != null && rotationVector != null) {
            started = true;
            hasLastQuat = false;
            lastMotionUptimeMs = SystemClock.uptimeMillis();
            updateSamplingPeriod(SensorManager.SENSOR_DELAY_GAME);
        }
    }

    void stop() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        started = false;
        hasLastQuat = false;
        currentSamplingDelay = 0;
    }

    void copyViewMatrix(float[] out16) {
        synchronized (viewRot) {
            System.arraycopy(viewRot, 0, out16, 0, 16);
        }
    }

    void setDisplayRotation(int rotation) {
        displayRotation = rotation;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null || event.values == null || event.values.length < 3) {
            return;
        }
        if (event.sensor == null || event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) {
            return;
        }

        long nowUptime = SystemClock.uptimeMillis();
        float delta = computeRotationDelta(event.values);
        if (delta > ROTATION_DELTA_THRESHOLD) {
            lastMotionUptimeMs = nowUptime;
            if (currentSamplingDelay != SensorManager.SENSOR_DELAY_GAME) {
                updateSamplingPeriod(SensorManager.SENSOR_DELAY_GAME);
            }
        } else if (currentSamplingDelay == SensorManager.SENSOR_DELAY_GAME
                && (nowUptime - lastMotionUptimeMs) >= MOTION_IDLE_TIMEOUT_MS) {
            updateSamplingPeriod(SensorManager.SENSOR_DELAY_NORMAL);
        }

        SensorManager.getRotationMatrixFromVector(sensorRot3, event.values);

        int axisX;
        int axisY;
        switch (displayRotation) {
            case Surface.ROTATION_90:
                axisX = SensorManager.AXIS_Y;
                axisY = SensorManager.AXIS_MINUS_X;
                break;
            case Surface.ROTATION_180:
                axisX = SensorManager.AXIS_MINUS_X;
                axisY = SensorManager.AXIS_MINUS_Y;
                break;
            case Surface.ROTATION_270:
                axisX = SensorManager.AXIS_MINUS_Y;
                axisY = SensorManager.AXIS_X;
                break;
            case Surface.ROTATION_0:
            default:
                axisX = SensorManager.AXIS_X;
                axisY = SensorManager.AXIS_Y;
                break;
        }
        SensorManager.remapCoordinateSystem(sensorRot3, axisX, axisY, sensorRemap3);

        float[] view = new float[16];
        Matrix.setIdentityM(view, 0);
        // sensorRemap3 is device->world, so use transpose for world->device.
        view[0] = sensorRemap3[0];
        view[1] = sensorRemap3[1];
        view[2] = sensorRemap3[2];
        view[4] = sensorRemap3[3];
        view[5] = sensorRemap3[4];
        view[6] = sensorRemap3[5];
        view[8] = sensorRemap3[6];
        view[9] = sensorRemap3[7];
        view[10] = sensorRemap3[8];

        synchronized (viewRot) {
            System.arraycopy(view, 0, viewRot, 0, 16);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private float computeRotationDelta(float[] values) {
        float x = values[0];
        float y = values[1];
        float z = values[2];
        float w;
        if (values.length >= 4) {
            w = values[3];
        } else {
            float ww = 1.0f - (x * x + y * y + z * z);
            w = ww > 0.0f ? (float) Math.sqrt(ww) : 0.0f;
        }

        float len = (float) Math.sqrt(x * x + y * y + z * z + w * w);
        if (len > 1e-6f) {
            x /= len;
            y /= len;
            z /= len;
            w /= len;
        }

        if (!hasLastQuat) {
            lastQuat[0] = x;
            lastQuat[1] = y;
            lastQuat[2] = z;
            lastQuat[3] = w;
            hasLastQuat = true;
            return 1.0f;
        }

        float dot = Math.abs(lastQuat[0] * x + lastQuat[1] * y + lastQuat[2] * z + lastQuat[3] * w);
        dot = Math.min(1.0f, Math.max(0.0f, dot));

        lastQuat[0] = x;
        lastQuat[1] = y;
        lastQuat[2] = z;
        lastQuat[3] = w;
        return 1.0f - dot;
    }

    private void updateSamplingPeriod(int delay) {
        if (!started || sensorManager == null || rotationVector == null) {
            return;
        }
        if (delay == currentSamplingDelay && currentSamplingDelay != 0) {
            return;
        }
        sensorManager.unregisterListener(this, rotationVector);
        sensorManager.registerListener(this, rotationVector, delay);
        currentSamplingDelay = delay;
    }
}
