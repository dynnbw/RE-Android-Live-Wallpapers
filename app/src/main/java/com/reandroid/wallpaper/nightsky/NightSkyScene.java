package com.reandroid.wallpaper.nightsky;

import android.content.Context;
import android.content.SharedPreferences;
import android.opengl.Matrix;
import android.view.Surface;
import android.view.WindowManager;

import androidx.preference.PreferenceManager;

import com.reandroid.gles.GLESWallpaper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

final class NightSkyScene {

    private SharedPreferences mPluginPrefs;

    static final float MILKY_BRIGHTNESS = 0.9f;
    static final String PREF_CAMERA_FOCAL_MM = "nightsky_camera_focal_mm";
    static final String PREF_USE_SENSOR = "nightsky_use_sensor";
    static final String PREF_AUTO_TIME_ACCEL = "nightsky_auto_time_accel";
    static final String PREF_TIME_ACCEL_SPEED = "nightsky_time_accel_speed";
    static final int DEFAULT_CAMERA_FOCAL_MM = 35;
    static final int MIN_CAMERA_FOCAL_MM = 6;
    static final int MAX_CAMERA_FOCAL_MM = 75;
    static final int DEFAULT_TIME_ACCEL_SPEED = 360;
    static final int MIN_TIME_ACCEL_SPEED = 180;
    static final int MAX_TIME_ACCEL_SPEED = 1440;
    static final float PAGE_ROTATION_DEG = 45.0f;
    static final long PREF_POLL_INTERVAL_MS = 1000L;
    static final float VIRTUAL_SENSOR_WIDTH_MM = 36.0f;

    final float[] proj = new float[16];
    final float[] currentViewRot = new float[16];

    final NightSkySensorController sensorController = new NightSkySensorController();
    final NightSkyLocationController locationController = new NightSkyLocationController();
    final NightSkyTouchTimeController touchTimeController = new NightSkyTouchTimeController();

    NightSkyCatalog catalog;
    FloatBuffer starParamBuffer;
    FloatBuffer starColorBuffer;
    FloatBuffer visibleStarParamBuffer;
    FloatBuffer visibleStarColorBuffer;
    int visibleStarCount;

    final float[] tmpHorizon = new float[3];
    final float[] tmpView = new float[4];
    final float[] tmpViewRotated = new float[4];
    final float[] tmpClip = new float[4];

    FloatBuffer sphereUvBuffer;
    int sphereVertexCount;

    float cameraFovDeg = focalMmToFovDeg(DEFAULT_CAMERA_FOCAL_MM);
    boolean sensorEnabled = true;
    float scrollPages;
    long lastPrefPollMs = Long.MIN_VALUE;

    NightSkyScene() {
        Matrix.setIdentityM(currentViewRot, 0);
    }

    void setPluginPrefs(SharedPreferences p) {
        mPluginPrefs = p;
    }

    void init(Context context) {
        sensorController.init(context);
        locationController.refresh(context, true);
        touchTimeController.init();
        catalog = NightSkyCatalogLoader.load(context);
        starParamBuffer = catalog.newStarParamBuffer();
        starColorBuffer = catalog.newStarColorBuffer();
    }

    void setScroll(float xPixels, int viewWidth) {
        if (viewWidth > 0) {
            scrollPages = xPixels / (float) viewWidth;
        } else {
            scrollPages = xPixels;
        }
    }

    void updateProjectionMatrix(int width, int height) {
        if (width <= 0 || height <= 0) return;
        float aspect = width / (float) height;
        Matrix.perspectiveM(proj, 0, cameraFovDeg, aspect, 0.1f, 20.0f);
    }

    void refreshRuntimeSettingsIfNeeded(long nowMs, int width, int height) {
        if (lastPrefPollMs != Long.MIN_VALUE && (nowMs - lastPrefPollMs) < PREF_POLL_INTERVAL_MS) {
            return;
        }
        lastPrefPollMs = nowMs;
        try {
            Context context = GLESWallpaper.getAppContext();
            if (context == null) return;
            SharedPreferences prefs = mPluginPrefs != null ? mPluginPrefs : PreferenceManager.getDefaultSharedPreferences(context);
            int focalMm = prefs.getInt(PREF_CAMERA_FOCAL_MM, DEFAULT_CAMERA_FOCAL_MM);
            focalMm = Math.max(MIN_CAMERA_FOCAL_MM, Math.min(MAX_CAMERA_FOCAL_MM, focalMm));
            float newFov = focalMmToFovDeg(focalMm);
            if (Math.abs(newFov - cameraFovDeg) > 0.001f) {
                cameraFovDeg = newFov;
                updateProjectionMatrix(width, height);
            }
            boolean useSensor = prefs.getBoolean(PREF_USE_SENSOR, true);
            if (useSensor != sensorEnabled) {
                sensorEnabled = useSensor;
                if (sensorEnabled) sensorController.start();
                else sensorController.stop();
            }
            boolean autoTimeAccel = prefs.getBoolean(PREF_AUTO_TIME_ACCEL, false);
            touchTimeController.setAutoAccelerating(autoTimeAccel);
            int accelSpeed = parseIntPreference(
                    prefs.getString(PREF_TIME_ACCEL_SPEED, String.valueOf(DEFAULT_TIME_ACCEL_SPEED)),
                    DEFAULT_TIME_ACCEL_SPEED);
            accelSpeed = Math.max(MIN_TIME_ACCEL_SPEED, Math.min(MAX_TIME_ACCEL_SPEED, accelSpeed));
            touchTimeController.setAccelerationScale(accelSpeed);
            locationController.refresh(context, false);
        } catch (Throwable ignored) {
        }
    }

    void buildVisibleStarBuffers(float latRad, float lstRad, float[] viewRot) {
        visibleStarCount = 0;
        if (catalog == null || catalog.starCount <= 0) return;
        ensureVisibleBufferCapacity(catalog.starCount);
        visibleStarParamBuffer.clear();
        visibleStarColorBuffer.clear();
        for (int i = 0; i < catalog.starCount; i++) {
            int pp = i * 4;
            int cc = i * 3;
            if (!isStarInFrustum(catalog.starParams[pp], catalog.starParams[pp + 1], latRad, lstRad, viewRot)) continue;
            visibleStarParamBuffer.put(catalog.starParams, pp, 4);
            visibleStarColorBuffer.put(catalog.starColors, cc, 3);
            visibleStarCount++;
        }
        visibleStarParamBuffer.position(0);
        visibleStarColorBuffer.position(0);
    }

    private void ensureVisibleBufferCapacity(int starCapacity) {
        int pf = Math.max(1, starCapacity) * 4;
        int cf = Math.max(1, starCapacity) * 3;
        if (visibleStarParamBuffer == null || visibleStarParamBuffer.capacity() < pf)
            visibleStarParamBuffer = ByteBuffer.allocateDirect(pf * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        if (visibleStarColorBuffer == null || visibleStarColorBuffer.capacity() < cf)
            visibleStarColorBuffer = ByteBuffer.allocateDirect(cf * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    private boolean isStarInFrustum(float raDeg, float decDeg, float latRad, float lstRad, float[] viewRot) {
        float raRad = (float) (raDeg * NightSkyMath.DEG_TO_RAD);
        float decRad = (float) (decDeg * NightSkyMath.DEG_TO_RAD);
        NightSkyMath.equatorialToHorizon(raRad, decRad, latRad, lstRad, tmpHorizon);
        tmpView[0] = tmpHorizon[0] * 5.0f; tmpView[1] = tmpHorizon[1] * 5.0f; tmpView[2] = tmpHorizon[2] * 5.0f; tmpView[3] = 1.0f;
        Matrix.multiplyMV(tmpViewRotated, 0, viewRot, 0, tmpView, 0);
        Matrix.multiplyMV(tmpClip, 0, proj, 0, tmpViewRotated, 0);
        float w = tmpClip[3];
        if (w <= 0.0001f) return false;
        float x = tmpClip[0] / w; float y = tmpClip[1] / w; float z = tmpClip[2] / w;
        return x >= -1.05f && x <= 1.05f && y >= -1.05f && y <= 1.05f && z >= -1.0f && z <= 1.0f;
    }

    void buildSphereGrid(int lonSteps, int latSteps) {
        int triCount = lonSteps * latSteps * 2;
        int vertexCount = triCount * 3;
        float[] uv = new float[vertexCount * 2];
        int p = 0;
        for (int y = 0; y < latSteps; y++) {
            float v0 = y / (float) latSteps, v1 = (y + 1) / (float) latSteps;
            for (int x = 0; x < lonSteps; x++) {
                float u0 = x / (float) lonSteps, u1 = (x + 1) / (float) lonSteps;
                p = putUv(uv, p, u0, v0); p = putUv(uv, p, u0, v1); p = putUv(uv, p, u1, v1);
                p = putUv(uv, p, u0, v0); p = putUv(uv, p, u1, v1); p = putUv(uv, p, u1, v0);
            }
        }
        sphereVertexCount = vertexCount;
        sphereUvBuffer = ByteBuffer.allocateDirect(uv.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        sphereUvBuffer.put(uv).position(0);
    }

    private static int putUv(float[] out, int start, float u, float v) { out[start] = u; out[start + 1] = v; return start + 2; }

    static float focalMmToFovDeg(float focalMm) {
        float f = Math.max(1.0f, focalMm);
        double fovRad = 2.0 * Math.atan(VIRTUAL_SENSOR_WIDTH_MM / (2.0 * f));
        return (float) (fovRad * 180.0 / Math.PI);
    }

    static int parseIntPreference(String value, int fallback) {
        if (value == null) return fallback;
        try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { return fallback; }
    }

    static int getDisplayRotation(Context context) {
        if (context == null) return Surface.ROTATION_0;
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        return wm != null ? wm.getDefaultDisplay().getRotation() : Surface.ROTATION_0;
    }
}
