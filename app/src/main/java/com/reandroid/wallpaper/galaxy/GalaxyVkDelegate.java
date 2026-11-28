package com.reandroid.wallpaper.galaxy;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.Surface;

import com.reandroid.plugin.VkPass;
import com.reandroid.plugin.VkPassFrame;
import com.reandroid.plugin.VkPassRendererDelegate;
import com.reandroid.plugin.VkPassScene;
import com.reandroid.utils.AssetLoader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Galaxy 的 VK 渲染器 —— 壁纸与预览共用。
 *
 * <p>三个 pass：背景（全屏三角形，无顶点缓冲）、粒子（每帧上传的 7 float 顶点）、
 * 中心光晕（全屏四边形，无顶点缓冲）。全部由通用 {@code VkPassRenderer} 执行。
 */
final class GalaxyVkDelegate extends VkPassRendererDelegate {

    /** light2 贴图的热切换每秒查一次设置，不是每帧。 */
    private static final long SETTINGS_SYNC_INTERVAL_MS = 1000L;

    private static final int MAX_PARTICLES = 20000;
    private static final int FLOATS_PER_VERTEX = 7; // angle, dist, z, r, g, b, size
    private static final int PUSH_BYTES = 20 * 4; // mat4 + alpha + twist + light scale

    private final SharedPreferences mPrefs;
    private GalaxyScene mScene;
    private VkPassScene mPassScene;

    private VkPass mBgPass;
    private VkPass mParticlePass;
    private VkPass mLightPass;

    private ByteBuffer mParticleBuffer;
    private final float[] mPush = new float[20];

    private boolean mUseLight2;
    private long mLastLightSyncCheckMs;
    private int mSurfaceWidth;

    GalaxyVkDelegate(Context context, SharedPreferences prefs) {
        super(context);
        mPrefs = prefs;
    }

    @Override
    public String logTag() {
        return "GalaxyVK";
    }

    @Override
    public Object getScene() {
        return mScene;
    }

    @Override
    public boolean isReady() {
        return mScene != null;
    }

    @Override
    public void ensureScene(int width, int height) {
        if (width <= 0 || height <= 0) return;
        if (mScene == null) {
            mScene = new GalaxyScene(width, height, context);
            if (mPrefs != null) mScene.setPluginPrefs(mPrefs);
        } else {
            mScene.resize(width, height);
        }
    }

    @Override
    public void onSurfaceCreated(Surface surface, int width, int height) {
        mSurfaceWidth = width;
        super.onSurfaceCreated(surface, width, height);
    }

    @Override
    public void onSurfaceChanged(Surface surface, int width, int height) {
        mSurfaceWidth = width;
        super.onSurfaceChanged(surface, width, height);
    }

    @Override
    public void onPluginPrefsChanged(SharedPreferences prefs) {
        if (mScene != null) mScene.setPluginPrefs(prefs);
    }

    @Override
    public void onSceneOffset(float xOffset) {
        if (mScene != null) mScene.setOffset(xOffset);
    }

    /** 光晕贴图（light1 / light2）切换后要重传。 */
    @Override
    public void syncTexturesIfNeeded() {
        if (mScene == null) return;
        long now = SystemClock.uptimeMillis();
        if (now - mLastLightSyncCheckMs < SETTINGS_SYNC_INTERVAL_MS) return;
        mLastLightSyncCheckMs = now;
        boolean desired = light2Enabled();
        if (desired == mUseLight2) return;
        mUseLight2 = desired;
        if (mPassScene != null) uploadLightTexture(mPassScene);
    }

    @Override
    protected void buildScene(VkPassScene scene) {
        mPassScene = scene;
        scene.setClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        scene.createBuffer("particles", FLOATS_PER_VERTEX * 4, MAX_PARTICLES, true);
        scene.createTexture("bg");
        scene.createTexture("light");
        scene.createTexture("flares");
        uploadBackgroundTexture(scene);
        mUseLight2 = light2Enabled();
        uploadLightTexture(scene);
        uploadTexture(scene, "flares", "galaxy/drawable/galaxy_flares.png");

        mBgPass = scene.add(new VkPass(
                        "shaders/galaxyvk_bg.vert.spv",
                        "shaders/galaxyvk_bg.frag.spv",
                        new int[] {},
                        0,
                        VkPass.TOPOLOGY_TRIANGLE_LIST,
                        VkPass.BLEND_NONE))
                .buffer("", 3);
        mBgPass.texture(0, "bg");

        mParticlePass = scene.add(new VkPass(
                        "shaders/galaxyvk_particles.vert.spv",
                        "shaders/galaxyvk_particles.frag.spv",
                        new int[] {3, 4},
                        FLOATS_PER_VERTEX * 4,
                        VkPass.TOPOLOGY_POINT_LIST,
                        VkPass.BLEND_ADDITIVE))
                .buffer("particles", 0)
                .pushConstants(PUSH_BYTES);
        mParticlePass.texture(0, "flares");

        mLightPass = scene.add(new VkPass(
                        "shaders/galaxyvk_light.vert.spv",
                        "shaders/galaxyvk_light.frag.spv",
                        new int[] {},
                        0,
                        VkPass.TOPOLOGY_TRIANGLE_STRIP,
                        VkPass.BLEND_ADDITIVE))
                .buffer("", 4)
                .pushConstants(PUSH_BYTES);
        mLightPass.texture(0, "light");

        mParticleBuffer = ByteBuffer.allocateDirect(MAX_PARTICLES * FLOATS_PER_VERTEX * 4)
                .order(ByteOrder.nativeOrder());
    }

    @Override
    protected void updateFrame(VkPassFrame frame, long timeMs) {
        if (mScene == null) return;
        mScene.update(timeMs);
        GalaxyScene.SceneData data = mScene.getSceneData();

        int count = Math.min(Math.max(data.getParticleCount(), 0), MAX_PARTICLES);
        float[] positions = data.getParticlePositions();
        float[] colors = data.getParticleColors();
        if (count > 0 && positions != null && colors != null) {
            FloatBuffer floats = mParticleBuffer.asFloatBuffer();
            for (int i = 0; i < count; i++) {
                int p = i * 3;
                int c = i * 4;
                int o = i * FLOATS_PER_VERTEX;
                floats.put(o, positions[p]);
                floats.put(o + 1, positions[p + 1]);
                floats.put(o + 2, positions[p + 2]);
                floats.put(o + 3, colors[c]);
                floats.put(o + 4, colors[c + 1]);
                floats.put(o + 5, colors[c + 2]);
                floats.put(o + 6, colors[c + 3]);
            }
            mParticleBuffer.position(0);
            frame.upload("particles", mParticleBuffer, count * FLOATS_PER_VERTEX * 4);
        }
        frame.setVertexCount(mParticlePass, count);

        System.arraycopy(data.getMvpMatrix(), 0, mPush, 0, 16);
        mPush[16] = data.getParticleAlphaMultiplier();
        mPush[17] = data.getTwist();
        // 与 GLES 的 drawLights 同一套尺寸公式：光晕随 galaxy_radius 缩放
        float baseSize = mScene.getGalaxyRadius() * 512.0f / 300.0f;
        if (mSurfaceWidth > 0) {
            mPush[18] = (baseSize / mSurfaceWidth) * 1.1f;
            mPush[19] = (baseSize / mSurfaceWidth) * 1.2f;
        } else {
            mPush[18] = 0.56f;
            mPush[19] = 0.61f;
        }
        frame.pushConstants(mParticlePass, mPush);
        frame.pushConstants(mLightPass, mPush);
    }

    /** 与 GLES 的 readLight2FromPrefs 同源：直接读插件自己的 prefs。 */
    private boolean light2Enabled() {
        return mPrefs != null && mPrefs.getBoolean("pref_galaxy_use_light2", false);
    }

    private void uploadBackgroundTexture(VkPassScene scene) {
        uploadTexture(scene, "bg", "galaxy/drawable/galaxy_space.jpg");
    }

    private void uploadLightTexture(VkPassScene scene) {
        String path = mUseLight2 ? "galaxy/drawable/light2.png" : "galaxy/drawable/light1.jpg";
        uploadTexture(scene, "light", path);
    }

    private void uploadTexture(VkPassScene scene, String name, String path) {
        Bitmap bitmap = AssetLoader.decodeBitmap(context, path);
        if (bitmap == null) return;
        Bitmap argb = bitmap.getConfig() == Bitmap.Config.ARGB_8888
                ? bitmap
                : bitmap.copy(Bitmap.Config.ARGB_8888, false);
        if (argb != bitmap) bitmap.recycle();
        int width = argb.getWidth();
        int height = argb.getHeight();
        int[] pixels = new int[width * height];
        argb.getPixels(pixels, 0, width, 0, 0, width, height);
        argb.recycle();
        scene.setTexture(name, pixels, width, height);
    }
}
