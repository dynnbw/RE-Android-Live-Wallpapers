package com.reandroid.wallpaper.nightsky;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.view.MotionEvent;

import java.nio.FloatBuffer;

import com.reandroid.utils.AssetLoader;
import com.reandroid.gles.GLESScene;
import com.reandroid.gles.GLESWallpaper;
import com.reandroid.utils.MathUtils;

public class NightSkyGL extends GLESScene {
    private static final long TRAIL_LOOKBACK_SCALE = 5L;
    private static final long TRAIL_LOOKBACK_MAX_MS = 90L * 60L * 1000L;
    private static final float TRAIL_SPEED_LENGTH_BOOST_MAX = 4.0f;

    // ---- Scene (non-GL logic) ----
    private final Context mContext;
    private final NightSkyScene mScene;

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

    private boolean initialized;

    public NightSkyGL(int width, int height, Context context) {
        super(width, height);
        mContext = context.getApplicationContext();
        mScene = new NightSkyScene();
    }

    @Override
    protected void onCreate() {
        if (initialized) return;
        initialized = true;

        Context context = GLESWallpaper.getAppContext();
        mScene.init(context);
    }

    @Override
    public void start() {
        mScene.refreshRuntimeSettingsIfNeeded(System.currentTimeMillis(), mWidth, mHeight);
        if (mScene.sensorEnabled) {
            mScene.sensorController.start();
        } else {
            mScene.sensorController.stop();
        }
        mScene.locationController.start(GLESWallpaper.getAppContext());
    }

    @Override
    public void stop() {
        mScene.sensorController.stop();
        mScene.locationController.stop();
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

        mScene.sphereUvBuffer = null;
        mScene.starParamBuffer = null;
        mScene.starColorBuffer = null;
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        mScene.updateProjectionMatrix(width, height);
    }

    @Override
    public void onTouchEvent(MotionEvent event) {
        mScene.touchTimeController.onTouchEvent(event);
    }

    public void setPluginPrefs(SharedPreferences p) {
        if (mScene != null) mScene.setPluginPrefs(p);
    }

    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        mScene.setScroll(xPixels, mWidth);
    }

    @Override
    public void drawFrame(long timeMs) {
        if (mWidth <= 0 || mHeight <= 0) return;
        if (programSphere == 0) initGL();

        mScene.touchTimeController.advance();
        mScene.refreshRuntimeSettingsIfNeeded(timeMs, mWidth, mHeight);

        Context context = GLESWallpaper.getAppContext();
        if (mScene.sensorEnabled) {
            mScene.sensorController.setDisplayRotation(NightSkyScene.getDisplayRotation(context));
            mScene.sensorController.copyViewMatrix(mScene.currentViewRot);
        } else {
            Matrix.setIdentityM(mScene.currentViewRot, 0);
            Matrix.rotateM(mScene.currentViewRot, 0, 90.0f, 1.0f, 0.0f, 0.0f);
            Matrix.rotateM(mScene.currentViewRot, 0, mScene.scrollPages * NightSkyScene.PAGE_ROTATION_DEG, 0.0f, 0.0f, 1.0f);
        }

        float latitudeRad = (float) (mScene.locationController.getLatitudeDeg() * NightSkyMath.DEG_TO_RAD);
        float lstRad = (float) NightSkyMath.computeLocalSiderealTimeRad(
                mScene.touchTimeController.getAstronomyTimeMs(),
                mScene.locationController.getLongitudeDeg());
        long baseTrailLookbackMs = mScene.touchTimeController.getAcceleratingSimElapsedMs() / 6;
        float speedScale = mScene.touchTimeController.getCurrentTimeScale();
        float speedNorm = MathUtils.clamp(
            (speedScale - NightSkyScene.MIN_TIME_ACCEL_SPEED) / (NightSkyScene.MAX_TIME_ACCEL_SPEED - NightSkyScene.MIN_TIME_ACCEL_SPEED),
            0.0f, 1.0f);
        float trailLengthBoost = 1.0f + (TRAIL_SPEED_LENGTH_BOOST_MAX - 1.0f) * speedNorm;
        if (!mScene.touchTimeController.isAccelerating()) trailLengthBoost = 1.0f;
        long trailLookbackMs = Math.min((long) (baseTrailLookbackMs * trailLengthBoost), TRAIL_LOOKBACK_MAX_MS);
        float timeSec = mScene.touchTimeController.getAstronomyTimeMs() / 1000.0f;

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, mWidth, mHeight);
        GLES20.glClearColor(0.015f, 0.02f, 0.03f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        drawMilkySphere(latitudeRad, lstRad, mScene.currentViewRot);

        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE);
        // 星空:参数/颜色缓冲静态(RA/Dec 不变),顶点 shader 完成全部坐标变换。
        // 不再每帧 CPU 重复计算/剔除(原实现每帧 9000 星 × 6 trig + 2 矩阵乘),
        // GPU 处理 9000 点精灵开销可忽略,片元仅计算可见星。
        drawStars(mScene.starParamBuffer, mScene.starColorBuffer, mScene.catalog.starCount,
                latitudeRad, lstRad, timeSec, mScene.currentViewRot, false);

        trailRenderer.draw(mScene.catalog, latitudeRad,
                mScene.locationController.getLongitudeDeg(),
                mScene.touchTimeController.getAstronomyTimeMs(),
                timeMs, mScene.currentViewRot, mScene.proj,
                mWidth, mHeight, trailLookbackMs,
                mScene.touchTimeController.isAccelerating());

        GLES20.glDisable(GLES20.GL_BLEND);
    }

    private void initGL() {
        if (mContext == null) return;

        programSphere = createProgram(
                AssetLoader.readText(mContext, "nightsky/shaders/GLES/nightsky_sphere_vs.glsl"),
                AssetLoader.readText(mContext, "nightsky/shaders/GLES/nightsky_sphere_fs.glsl"));
        programStar = createProgram(
                AssetLoader.readText(mContext, "nightsky/shaders/GLES/nightsky_star_vs.glsl"),
                AssetLoader.readText(mContext, "nightsky/shaders/GLES/nightsky_star_fs.glsl"));
        programTrail = createProgram(
            AssetLoader.readText(mContext, "nightsky/shaders/GLES/nightsky_trail_vs.glsl"),
            AssetLoader.readText(mContext, "nightsky/shaders/GLES/nightsky_trail_fs.glsl"));
        cacheSphereLocations();
        cacheStarLocations();
        trailRenderer.init(programTrail);

        mScene.buildSphereGrid(96, 48);
        mScene.updateProjectionMatrix(mWidth, mHeight);

        milkyTex = loadTextureFromAsset("nightsky/drawable/nightsky_milkyway.jpg");

        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
    }

    private void drawMilkySphere(float latRad, float lstRad, float[] viewRot) {
        if (programSphere == 0 || milkyTex == 0 || mScene.sphereUvBuffer == null || mScene.sphereVertexCount <= 0) return;
        GLES20.glUseProgram(programSphere);
        GLES20.glUniformMatrix4fv(sphereUProj, 1, false, mScene.proj, 0);
        GLES20.glUniformMatrix4fv(sphereUViewRot, 1, false, viewRot, 0);
        GLES20.glUniform1f(sphereULat, latRad);
        GLES20.glUniform1f(sphereULst, lstRad);
        GLES20.glUniform1f(sphereUBrightness, NightSkyScene.MILKY_BRIGHTNESS);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, milkyTex);
        GLES20.glUniform1i(sphereUTex, 0);
        mScene.sphereUvBuffer.position(0);
        GLES20.glEnableVertexAttribArray(sphereAUv);
        GLES20.glVertexAttribPointer(sphereAUv, 2, GLES20.GL_FLOAT, false, 2 * 4, mScene.sphereUvBuffer);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mScene.sphereVertexCount);
        GLES20.glDisableVertexAttribArray(sphereAUv);
    }

    private void drawStars(FloatBuffer params, FloatBuffer colors, int count,
                           float latRad, float lstRad, float timeSec, float[] viewRot, boolean trailPass) {
        if (programStar == 0 || params == null || colors == null || count <= 0) return;
        GLES20.glUseProgram(programStar);
        GLES20.glUniformMatrix4fv(starUProj, 1, false, mScene.proj, 0);
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

    private void cacheSphereLocations() {
        if (programSphere == 0) return;
        sphereAUv = GLES20.glGetAttribLocation(programSphere, "aUv");
        sphereUProj = GLES20.glGetUniformLocation(programSphere, "uProj");
        sphereUViewRot = GLES20.glGetUniformLocation(programSphere, "uViewRot");
        sphereULat = GLES20.glGetUniformLocation(programSphere, "uLatitudeRad");
        sphereULst = GLES20.glGetUniformLocation(programSphere, "uLSTRad");
        sphereUTex = GLES20.glGetUniformLocation(programSphere, "uMilkyTex");
        sphereUBrightness = GLES20.glGetUniformLocation(programSphere, "uMilkyBrightness");
    }

    private void cacheStarLocations() {
        if (programStar == 0) return;
        starAStar = GLES20.glGetAttribLocation(programStar, "aStar");
        starAColor = GLES20.glGetAttribLocation(programStar, "aColor");
        starUProj = GLES20.glGetUniformLocation(programStar, "uProj");
        starUViewRot = GLES20.glGetUniformLocation(programStar, "uViewRot");
        starULat = GLES20.glGetUniformLocation(programStar, "uLatitudeRad");
        starULst = GLES20.glGetUniformLocation(programStar, "uLSTRad");
        starUTime = GLES20.glGetUniformLocation(programStar, "uTimeSec");
        starUTrail = GLES20.glGetUniformLocation(programStar, "uTrailMode");
    }

    private int loadTextureFromAsset(String assetPath) {
        if (mContext == null) return 0;
        Bitmap bitmap = AssetLoader.decodeBitmap(mContext, assetPath);
        if (bitmap == null) return 0;
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



    private void deleteProgram(int program) { if (program != 0) GLES20.glDeleteProgram(program); }

    private void deleteTexture(int tex) {
        if (tex != 0) { int[] a = new int[] { tex }; GLES20.glDeleteTextures(1, a, 0); }
    }
}
