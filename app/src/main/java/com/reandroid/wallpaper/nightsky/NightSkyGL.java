package com.reandroid.wallpaper.nightsky;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.WindowManager;

import com.reandroid.gles.GLESScene;
import com.reandroid.gles.GLESWallpaper;
import com.reandroid.gles.RawResourceLoader;
import com.reandroid.wallpaper.R;

import androidx.preference.PreferenceManager;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class NightSkyGL extends GLESScene {
    private static final float MILKY_BRIGHTNESS = 0.9f;
    private static final String PREF_CAMERA_FOCAL_MM = "nightsky_camera_focal_mm";
    private static final String PREF_USE_SENSOR = "nightsky_use_sensor";
    private static final String PREF_AUTO_TIME_ACCEL = "nightsky_auto_time_accel";
    private static final String PREF_TIME_ACCEL_SPEED = "nightsky_time_accel_speed";
    private static final int DEFAULT_CAMERA_FOCAL_MM = 35;
    private static final int MIN_CAMERA_FOCAL_MM = 6;
    private static final int MAX_CAMERA_FOCAL_MM = 75;
    private static final int DEFAULT_TIME_ACCEL_SPEED = 360;
    private static final int MIN_TIME_ACCEL_SPEED = 180;
    private static final int MAX_TIME_ACCEL_SPEED = 1440;
    private static final float PAGE_ROTATION_DEG = 45.0f;
    private static final long PREF_POLL_INTERVAL_MS = 1000L;
    private static final float VIRTUAL_SENSOR_WIDTH_MM = 36.0f;
    private static final long TRAIL_LOOKBACK_SCALE = 5L;
    private static final long TRAIL_LOOKBACK_MAX_MS = 90L * 60L * 1000L;
    private static final float TRAIL_SPEED_LENGTH_BOOST_MAX = 4.0f;

    private final float[] proj = new float[16];
    private final float[] currentViewRot = new float[16];

    private final NightSkySensorController sensorController = new NightSkySensorController();
    private final NightSkyLocationController locationController = new NightSkyLocationController();
    private final NightSkyTouchTimeController touchTimeController = new NightSkyTouchTimeController();
    private final NightSkyTrailRenderer trailRenderer = new NightSkyTrailRenderer();

    private int programSphere;
    private int programStar;
    private int programTrail;

    private int sphereAUv = -1;
    private int sphereUProj = -1;
    private int sphereUViewRot = -1;
    private int sphereULat = -1;
    private int sphereULst = -1;
    private int sphereUTex = -1;
    private int sphereUBrightness = -1;

    private int starAStar = -1;
    private int starAColor = -1;
    private int starUProj = -1;
    private int starUViewRot = -1;
    private int starULat = -1;
    private int starULst = -1;
    private int starUTime = -1;
    private int starUTrail = -1;

    private int milkyTex;

    private FloatBuffer sphereUvBuffer;
    private int sphereVertexCount;

    private NightSkyCatalog catalog;
    private FloatBuffer starParamBuffer;
    private FloatBuffer starColorBuffer;
    private FloatBuffer visibleStarParamBuffer;
    private FloatBuffer visibleStarColorBuffer;
    private int visibleStarCount;

    private final float[] tmpHorizon = new float[3];
    private final float[] tmpView = new float[4];
    private final float[] tmpViewRotated = new float[4];
    private final float[] tmpClip = new float[4];

    private boolean initialized;
    private float cameraFovDeg = focalMmToFovDeg(DEFAULT_CAMERA_FOCAL_MM);
    private boolean sensorEnabled = true;
    private float scrollPages;
    private long lastPrefPollMs = Long.MIN_VALUE;

    public NightSkyGL(int width, int height) {
        super(width, height);
        Matrix.setIdentityM(currentViewRot, 0);
    }

    @Override
    protected void onCreate() {
        if (initialized) {
            return;
        }
        initialized = true;

        Context context = GLESWallpaper.getAppContext();
        sensorController.init(context);
        locationController.refresh(context, true);
        touchTimeController.init();

        catalog = NightSkyCatalogLoader.load(context);
        starParamBuffer = catalog.newStarParamBuffer();
        starColorBuffer = catalog.newStarColorBuffer();
    }

    @Override
    public void start() {
        refreshRuntimeSettingsIfNeeded(System.currentTimeMillis());
        if (sensorEnabled) {
            sensorController.start();
        } else {
            sensorController.stop();
        }
        locationController.start(GLESWallpaper.getAppContext());
    }

    @Override
    public void stop() {
        sensorController.stop();
        locationController.stop();
    }

    @Override
    public void release() {
        deleteProgram(programSphere);
        deleteProgram(programStar);
        deleteProgram(programTrail);
        programSphere = 0;
        programStar = 0;
        programTrail = 0;
        sphereAUv = -1;
        sphereUProj = -1;
        sphereUViewRot = -1;
        sphereULat = -1;
        sphereULst = -1;
        sphereUTex = -1;
        sphereUBrightness = -1;
        starAStar = -1;
        starAColor = -1;
        starUProj = -1;
        starUViewRot = -1;
        starULat = -1;
        starULst = -1;
        starUTime = -1;
        starUTrail = -1;

        deleteTexture(milkyTex);
        milkyTex = 0;

        sphereUvBuffer = null;
        starParamBuffer = null;
        starColorBuffer = null;
        visibleStarParamBuffer = null;
        visibleStarColorBuffer = null;
        visibleStarCount = 0;
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        updateProjectionMatrix();
    }

    @Override
    public void onTouchEvent(MotionEvent event) {
        touchTimeController.onTouchEvent(event);
    }

    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        if (mWidth > 0) {
            scrollPages = xPixels / (float) mWidth;
        } else {
            scrollPages = xOffset;
        }
    }

    @Override
    public void drawFrame(long timeMs) {
        if (mWidth <= 0 || mHeight <= 0) {
            return;
        }
        if (programSphere == 0) {
            initGL();
        }

        touchTimeController.advance();
        refreshRuntimeSettingsIfNeeded(timeMs);

        Context context = GLESWallpaper.getAppContext();
        if (sensorEnabled) {
            sensorController.setDisplayRotation(getDisplayRotation(context));
            sensorController.copyViewMatrix(currentViewRot);
        } else {
            Matrix.setIdentityM(currentViewRot, 0);
            // Align fallback camera to look at zenith instead of nadir.
            Matrix.rotateM(currentViewRot, 0, 90.0f, 1.0f, 0.0f, 0.0f);
            Matrix.rotateM(currentViewRot, 0, scrollPages * PAGE_ROTATION_DEG, 0.0f, 0.0f, 1.0f);
        }

        float latitudeRad = (float) (locationController.getLatitudeDeg() * NightSkyMath.DEG_TO_RAD);
        float lstRad = (float) NightSkyMath.computeLocalSiderealTimeRad(
                touchTimeController.getAstronomyTimeMs(),
                locationController.getLongitudeDeg()
        );
        long baseTrailLookbackMs = touchTimeController.getAcceleratingRealElapsedMs() * TRAIL_LOOKBACK_SCALE;
        float speedScale = touchTimeController.getCurrentTimeScale();
        float speedNorm = NightSkyMath.clamp(
            (speedScale - MIN_TIME_ACCEL_SPEED) / (MAX_TIME_ACCEL_SPEED - MIN_TIME_ACCEL_SPEED),
            0.0f,
            1.0f
        );
        float trailLengthBoost = 1.0f + (TRAIL_SPEED_LENGTH_BOOST_MAX - 1.0f) * speedNorm;
        if (!touchTimeController.isAccelerating()) {
            trailLengthBoost = 1.0f;
        }
        long trailLookbackMs = Math.min(
            (long) (baseTrailLookbackMs * trailLengthBoost),
            TRAIL_LOOKBACK_MAX_MS
        );
        float timeSec = touchTimeController.getAstronomyTimeMs() / 1000.0f;

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, mWidth, mHeight);
        GLES20.glClearColor(0.015f, 0.02f, 0.03f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        drawMilkySphere(latitudeRad, lstRad, currentViewRot);

        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE);
        buildVisibleStarBuffers(latitudeRad, lstRad, currentViewRot);
        drawStars(visibleStarParamBuffer, visibleStarColorBuffer, visibleStarCount, latitudeRad, lstRad, timeSec, currentViewRot, false);

        trailRenderer.draw(
                catalog,
                latitudeRad,
                locationController.getLongitudeDeg(),
                touchTimeController.getAstronomyTimeMs(),
            timeMs,
                currentViewRot,
                proj,
            mWidth,
            mHeight,
                trailLookbackMs,
                touchTimeController.isAccelerating()
        );

        GLES20.glDisable(GLES20.GL_BLEND);
    }

    private void initGL() {
        Resources res = getResources();
        if (res == null) {
            return;
        }

        programSphere = createProgram(
                RawResourceLoader.readRawText(res, R.raw.nightsky_sphere_vs),
                RawResourceLoader.readRawText(res, R.raw.nightsky_sphere_fs)
        );
        programStar = createProgram(
                RawResourceLoader.readRawText(res, R.raw.nightsky_star_vs),
                RawResourceLoader.readRawText(res, R.raw.nightsky_star_fs)
        );
        programTrail = createProgram(
            RawResourceLoader.readRawText(res, R.raw.nightsky_trail_vs),
            RawResourceLoader.readRawText(res, R.raw.nightsky_trail_fs)
        );
        cacheSphereLocations();
        cacheStarLocations();
        trailRenderer.init(programTrail);

        buildSphereGrid(96, 48);
        updateProjectionMatrix();

        milkyTex = loadTextureFromResource(R.drawable.nightsky_milkyway);

        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
    }

    private void refreshRuntimeSettingsIfNeeded(long nowMs) {
        if (lastPrefPollMs != Long.MIN_VALUE && (nowMs - lastPrefPollMs) < PREF_POLL_INTERVAL_MS) {
            return;
        }
        lastPrefPollMs = nowMs;

        try {
            Context context = GLESWallpaper.getAppContext();
            if (context == null) {
                return;
            }
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            int focalMm = prefs.getInt(PREF_CAMERA_FOCAL_MM, DEFAULT_CAMERA_FOCAL_MM);
            focalMm = Math.max(MIN_CAMERA_FOCAL_MM, Math.min(MAX_CAMERA_FOCAL_MM, focalMm));
            float newFov = focalMmToFovDeg(focalMm);
            if (Math.abs(newFov - cameraFovDeg) > 0.001f) {
                cameraFovDeg = newFov;
                updateProjectionMatrix();
            }

            boolean useSensor = prefs.getBoolean(PREF_USE_SENSOR, true);
            if (useSensor != sensorEnabled) {
                sensorEnabled = useSensor;
                if (sensorEnabled) {
                    sensorController.start();
                } else {
                    sensorController.stop();
                }
            }

            boolean autoTimeAccel = prefs.getBoolean(PREF_AUTO_TIME_ACCEL, false);
            touchTimeController.setAutoAccelerating(autoTimeAccel);

            int accelSpeed = parseIntPreference(
                    prefs.getString(PREF_TIME_ACCEL_SPEED, String.valueOf(DEFAULT_TIME_ACCEL_SPEED)),
                    DEFAULT_TIME_ACCEL_SPEED
            );
            accelSpeed = Math.max(MIN_TIME_ACCEL_SPEED, Math.min(MAX_TIME_ACCEL_SPEED, accelSpeed));
            touchTimeController.setAccelerationScale(accelSpeed);

            locationController.refresh(context, false);
        } catch (Throwable ignored) {
        }
    }

    private static int parseIntPreference(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private void updateProjectionMatrix() {
        if (mWidth <= 0 || mHeight <= 0) {
            return;
        }
        float aspect = mWidth / (float) mHeight;
        Matrix.perspectiveM(proj, 0, cameraFovDeg, aspect, 0.1f, 20.0f);
    }

    private static float focalMmToFovDeg(float focalMm) {
        float f = Math.max(1.0f, focalMm);
        double fovRad = 2.0 * Math.atan(VIRTUAL_SENSOR_WIDTH_MM / (2.0 * f));
        return (float) (fovRad * 180.0 / Math.PI);
    }

    private void drawMilkySphere(float latRad, float lstRad, float[] viewRot) {
        if (programSphere == 0 || milkyTex == 0 || sphereUvBuffer == null || sphereVertexCount <= 0) {
            return;
        }

        GLES20.glUseProgram(programSphere);
        GLES20.glUniformMatrix4fv(sphereUProj, 1, false, proj, 0);
        GLES20.glUniformMatrix4fv(sphereUViewRot, 1, false, viewRot, 0);
        GLES20.glUniform1f(sphereULat, latRad);
        GLES20.glUniform1f(sphereULst, lstRad);
        GLES20.glUniform1f(sphereUBrightness, MILKY_BRIGHTNESS);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, milkyTex);
        GLES20.glUniform1i(sphereUTex, 0);

        sphereUvBuffer.position(0);
        GLES20.glEnableVertexAttribArray(sphereAUv);
        GLES20.glVertexAttribPointer(sphereAUv, 2, GLES20.GL_FLOAT, false, 2 * 4, sphereUvBuffer);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, sphereVertexCount);
        GLES20.glDisableVertexAttribArray(sphereAUv);
    }

    private void drawStars(
            FloatBuffer params,
            FloatBuffer colors,
            int count,
            float latRad,
            float lstRad,
            float timeSec,
            float[] viewRot,
            boolean trailPass
    ) {
        if (programStar == 0 || params == null || colors == null || count <= 0) {
            return;
        }

        GLES20.glUseProgram(programStar);
        GLES20.glUniformMatrix4fv(starUProj, 1, false, proj, 0);
        GLES20.glUniformMatrix4fv(starUViewRot, 1, false, viewRot, 0);
        GLES20.glUniform1f(starULat, latRad);
        GLES20.glUniform1f(starULst, lstRad);
        GLES20.glUniform1f(starUTime, timeSec);
        GLES20.glUniform1f(starUTrail, trailPass ? 1.0f : 0.0f);

        params.position(0);
        colors.position(0);
        GLES20.glEnableVertexAttribArray(starAStar);
        GLES20.glVertexAttribPointer(starAStar, 4, GLES20.GL_FLOAT, false, 4 * 4, params);
        GLES20.glEnableVertexAttribArray(starAColor);
        GLES20.glVertexAttribPointer(starAColor, 3, GLES20.GL_FLOAT, false, 3 * 4, colors);

        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, count);

        GLES20.glDisableVertexAttribArray(starAStar);
        GLES20.glDisableVertexAttribArray(starAColor);
    }

    private void buildVisibleStarBuffers(float latRad, float lstRad, float[] viewRot) {
        visibleStarCount = 0;
        if (catalog == null || catalog.starCount <= 0) {
            return;
        }

        ensureVisibleBufferCapacity(catalog.starCount);
        visibleStarParamBuffer.clear();
        visibleStarColorBuffer.clear();

        for (int i = 0; i < catalog.starCount; i++) {
            int pp = i * 4;
            int cc = i * 3;
            if (!isStarInFrustum(catalog.starParams[pp], catalog.starParams[pp + 1], latRad, lstRad, viewRot)) {
                continue;
            }
            visibleStarParamBuffer.put(catalog.starParams, pp, 4);
            visibleStarColorBuffer.put(catalog.starColors, cc, 3);
            visibleStarCount++;
        }

        visibleStarParamBuffer.position(0);
        visibleStarColorBuffer.position(0);
    }

    private void ensureVisibleBufferCapacity(int starCapacity) {
        int paramFloatCount = Math.max(1, starCapacity) * 4;
        int colorFloatCount = Math.max(1, starCapacity) * 3;

        if (visibleStarParamBuffer == null || visibleStarParamBuffer.capacity() < paramFloatCount) {
            visibleStarParamBuffer = ByteBuffer.allocateDirect(paramFloatCount * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer();
        }
        if (visibleStarColorBuffer == null || visibleStarColorBuffer.capacity() < colorFloatCount) {
            visibleStarColorBuffer = ByteBuffer.allocateDirect(colorFloatCount * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer();
        }
    }

    private boolean isStarInFrustum(float raDeg, float decDeg, float latRad, float lstRad, float[] viewRot) {
        float raRad = (float) (raDeg * NightSkyMath.DEG_TO_RAD);
        float decRad = (float) (decDeg * NightSkyMath.DEG_TO_RAD);

        NightSkyMath.equatorialToHorizon(raRad, decRad, latRad, lstRad, tmpHorizon);

        tmpView[0] = tmpHorizon[0] * 5.0f;
        tmpView[1] = tmpHorizon[1] * 5.0f;
        tmpView[2] = tmpHorizon[2] * 5.0f;
        tmpView[3] = 1.0f;

        Matrix.multiplyMV(tmpViewRotated, 0, viewRot, 0, tmpView, 0);
        Matrix.multiplyMV(tmpClip, 0, proj, 0, tmpViewRotated, 0);

        float w = tmpClip[3];
        if (w <= 0.0001f) {
            return false;
        }

        float x = tmpClip[0] / w;
        float y = tmpClip[1] / w;
        float z = tmpClip[2] / w;
        return x >= -1.05f && x <= 1.05f
                && y >= -1.05f && y <= 1.05f
                && z >= -1.0f && z <= 1.0f;
    }

    private void cacheSphereLocations() {
        if (programSphere == 0) {
            return;
        }
        sphereAUv = GLES20.glGetAttribLocation(programSphere, "aUv");
        sphereUProj = GLES20.glGetUniformLocation(programSphere, "uProj");
        sphereUViewRot = GLES20.glGetUniformLocation(programSphere, "uViewRot");
        sphereULat = GLES20.glGetUniformLocation(programSphere, "uLatitudeRad");
        sphereULst = GLES20.glGetUniformLocation(programSphere, "uLSTRad");
        sphereUTex = GLES20.glGetUniformLocation(programSphere, "uMilkyTex");
        sphereUBrightness = GLES20.glGetUniformLocation(programSphere, "uMilkyBrightness");
    }

    private void cacheStarLocations() {
        if (programStar == 0) {
            return;
        }
        starAStar = GLES20.glGetAttribLocation(programStar, "aStar");
        starAColor = GLES20.glGetAttribLocation(programStar, "aColor");
        starUProj = GLES20.glGetUniformLocation(programStar, "uProj");
        starUViewRot = GLES20.glGetUniformLocation(programStar, "uViewRot");
        starULat = GLES20.glGetUniformLocation(programStar, "uLatitudeRad");
        starULst = GLES20.glGetUniformLocation(programStar, "uLSTRad");
        starUTime = GLES20.glGetUniformLocation(programStar, "uTimeSec");
        starUTrail = GLES20.glGetUniformLocation(programStar, "uTrailMode");
    }

    private void buildSphereGrid(int lonSteps, int latSteps) {
        int triCount = lonSteps * latSteps * 2;
        int vertexCount = triCount * 3;
        float[] uv = new float[vertexCount * 2];
        int p = 0;
        for (int y = 0; y < latSteps; y++) {
            float v0 = y / (float) latSteps;
            float v1 = (y + 1) / (float) latSteps;
            for (int x = 0; x < lonSteps; x++) {
                float u0 = x / (float) lonSteps;
                float u1 = (x + 1) / (float) lonSteps;

                p = putUv(uv, p, u0, v0);
                p = putUv(uv, p, u0, v1);
                p = putUv(uv, p, u1, v1);

                p = putUv(uv, p, u0, v0);
                p = putUv(uv, p, u1, v1);
                p = putUv(uv, p, u1, v0);
            }
        }
        sphereVertexCount = vertexCount;
        sphereUvBuffer = ByteBuffer.allocateDirect(uv.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        sphereUvBuffer.put(uv).position(0);
    }

    private int putUv(float[] out, int start, float u, float v) {
        out[start] = u;
        out[start + 1] = v;
        return start + 2;
    }

    private int loadTextureFromResource(int resId) {
        Resources res = getResources();
        if (res == null) {
            return 0;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inScaled = false;
        Bitmap bitmap = BitmapFactory.decodeResource(res, resId, opts);
        if (bitmap == null) {
            return 0;
        }

        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        bitmap.recycle();
        return tex[0];
    }

    private int getDisplayRotation(Context context) {
        if (context == null) {
            return Surface.ROTATION_0;
        }
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        return wm != null ? wm.getDefaultDisplay().getRotation() : Surface.ROTATION_0;
    }

    private int createProgram(String vertexSource, String fragmentSource) {
        int vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        if (vs == 0 || fs == 0) {
            return 0;
        }

        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);

        int[] link = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, link, 0);
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        if (link[0] == 0) {
            GLES20.glDeleteProgram(program);
            return 0;
        }
        return program;
    }

    private int compileShader(int type, String source) {
        if (source == null) {
            return 0;
        }
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);

        int[] ok = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    private void deleteProgram(int program) {
        if (program != 0) {
            GLES20.glDeleteProgram(program);
        }
    }

    private void deleteTexture(int tex) {
        if (tex != 0) {
            int[] a = new int[] { tex };
            GLES20.glDeleteTextures(1, a, 0);
        }
    }
}
