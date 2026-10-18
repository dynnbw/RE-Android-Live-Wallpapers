package com.reandroid.wallpaper.galaxy4;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;

import com.reandroid.plugin.VkPass;
import com.reandroid.plugin.VkPassFrame;
import com.reandroid.plugin.VkPassRendererDelegate;
import com.reandroid.plugin.VkPassScene;
import com.reandroid.utils.AssetLoader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Galaxy4 的 VK 渲染器 —— 壁纸与预览共用。
 *
 * <p>四个 pass 全部由通用 {@code VkPassRenderer} 执行，这里只声明它们并每帧喂数据：
 * 背景四边形、云、环绕星星、静态星星。顶点布局/混合/纹理绑定与旧的
 * {@code galaxy4vk_jni.cpp} 完全一致。
 */
final class Galaxy4VkDelegate extends VkPassRendererDelegate {

    private static final int PUSH_BYTES = 19 * 4; // mat4 + alpha + size + opacity
    private static final int MAX_CLOUDS = 100;
    private static final int MAX_STARS = 20000;
    private static final int STATIC_STARS = 50;

    private final SharedPreferences mPrefs;
    private Galaxy4Scene mScene;

    private VkPass mBgPass;
    private VkPass mCloudPass;
    private VkPass mStarPass;
    private VkPass mStaticPass;

    private int mWidth;
    private int mHeight;
    private float[] mCloudVertices = new float[0];
    private final float[] mPush = new float[19];
    private final float[] mMvpCorrected = new float[16];

    Galaxy4VkDelegate(Context context, SharedPreferences prefs) {
        super(context);
        mPrefs = prefs;
    }

    @Override
    public String logTag() {
        return "Galaxy4VK";
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
        mWidth = width;
        mHeight = height;
        if (mScene == null) {
            mScene = new Galaxy4Scene(width, height, context);
            if (mPrefs != null) mScene.setPluginPrefs(mPrefs);
        } else {
            mScene.resize(width, height);
        }
    }

    @Override
    public void onPluginPrefsChanged(SharedPreferences prefs) {
        if (mScene != null) mScene.setPluginPrefs(prefs);
    }

    @Override
    protected void buildScene(VkPassScene scene) {
        scene.setClearColor(0.0f, 0.0f, 0.0f, 1.0f);

        scene.createBuffer("bgQuad", 5 * 4, 4, true);
        scene.createBuffer("clouds", 4 * 4, MAX_CLOUDS, true);
        scene.createBuffer("stars", 3 * 4, MAX_STARS, true);
        scene.createBuffer("staticStars", 3 * 4, STATIC_STARS, true);

        scene.createTexture("bg");
        scene.createTexture("cloud");
        scene.createTexture("static1");
        scene.createTexture("static2");
        uploadTextures(scene);

        mBgPass = scene.add(new VkPass(
                "shaders/galaxy4vk_bg.vert.spv",
                "shaders/galaxy4vk_bg.frag.spv",
                new int[] {3, 2}, 5 * 4,
                VkPass.TOPOLOGY_TRIANGLE_STRIP, VkPass.BLEND_NONE))
                .buffer("bgQuad", 4)
                .pushConstants(PUSH_BYTES);
        mBgPass.texture(0, "bg");

        mCloudPass = scene.add(new VkPass(
                "shaders/galaxy4vk_cloud.vert.spv",
                "shaders/galaxy4vk_cloud.frag.spv",
                new int[] {3, 1}, 4 * 4,
                VkPass.TOPOLOGY_POINT_LIST, VkPass.BLEND_ADDITIVE))
                .buffer("clouds", 0)
                .pushConstants(PUSH_BYTES);
        mCloudPass.texture(0, "cloud");

        mStarPass = scene.add(new VkPass(
                "shaders/galaxy4vk_star.vert.spv",
                "shaders/galaxy4vk_star.frag.spv",
                new int[] {3}, 3 * 4,
                VkPass.TOPOLOGY_POINT_LIST, VkPass.BLEND_ADDITIVE))
                .buffer("stars", 0)
                .pushConstants(PUSH_BYTES);

        mStaticPass = scene.add(new VkPass(
                "shaders/galaxy4vk_static.vert.spv",
                "shaders/galaxy4vk_static.frag.spv",
                new int[] {2, 1}, 3 * 4,
                VkPass.TOPOLOGY_POINT_LIST, VkPass.BLEND_ADDITIVE))
                .buffer("staticStars", STATIC_STARS)
                .pushConstants(PUSH_BYTES);
        mStaticPass.texture(0, "static1").texture(1, "static2");
    }

    @Override
    protected void updateFrame(VkPassFrame frame, long timeMs) {
        if (mScene == null) return;
        mScene.update(timeMs);
        Galaxy4Scene.SceneData data = mScene.getSceneData();

        float maxDim = Math.max(mWidth, mHeight);
        float scaleX = maxDim / Math.max(1, mWidth);
        float scaleY = maxDim / Math.max(1, mHeight);
        float[] bg = {
            -scaleX, -scaleY, 0, 0, 1,
             scaleX, -scaleY, 0, 1, 1,
            -scaleX,  scaleY, 0, 0, 0,
             scaleX,  scaleY, 0, 1, 0,
        };
        frame.upload("bgQuad", floats(bg));

        int cloudCount = data.getSpaceCloudCount();
        float[] clouds = data.getSpaceClouds();
        if (mCloudVertices.length < cloudCount * 4) {
            mCloudVertices = new float[cloudCount * 4];
        }
        for (int i = 0; i < cloudCount; i++) {
            mCloudVertices[i * 4] = clouds[i * 3];
            mCloudVertices[i * 4 + 1] = clouds[i * 3 + 1];
            mCloudVertices[i * 4 + 2] = clouds[i * 3 + 2];
            mCloudVertices[i * 4 + 3] = 1.0f;
        }
        frame.setVertexCount(mCloudPass, cloudCount);
        if (cloudCount > 0) {
            frame.upload("clouds", floats(mCloudVertices, cloudCount * 4));
        }

        int starCount = data.getBgStarCount();
        frame.setVertexCount(mStarPass, starCount);
        if (starCount > 0) {
            frame.upload("stars", floats(data.getBgStars(), starCount * 3));
        }

        frame.upload("staticStars", floats(data.getStaticStars(), STATIC_STARS * 3));

        // 与旧 native 一样，投影矩阵先乘 Vulkan 的裁剪修正（y 翻转 + z 0..1）
        multiply(CLIP_CORRECTION, data.getMvpMatrix(), mMvpCorrected);

        float time = data.getTimeSeconds();
        float size = data.getParticleSize();
        float opacity = data.getParticleOpacity();
        push(frame, mBgPass, CLIP_CORRECTION, 0.0f, 0.0f, 0.0f);
        push(frame, mCloudPass, mMvpCorrected, time, size, opacity);
        push(frame, mStarPass, mMvpCorrected, time, size, opacity);
        push(frame, mStaticPass, mMvpCorrected, time, 0.0f, 0.0f);
    }

    private void push(VkPassFrame frame, VkPass pass, float[] mvp,
                      float time, float size, float opacity) {
        System.arraycopy(mvp, 0, mPush, 0, 16);
        mPush[16] = time;
        mPush[17] = size;
        mPush[18] = opacity;
        frame.pushConstants(pass, mPush);
    }

    private void uploadTextures(VkPassScene scene) {
        uploadTexture(scene, "bg", "galaxy4/drawable/galaxy4_bg.jpg");
        uploadTexture(scene, "cloud", "galaxy4/drawable/galaxy4_cloud.png");
        uploadTexture(scene, "static1", "galaxy4/drawable/galaxy4_staticstar.png");
        uploadTexture(scene, "static2", "galaxy4/drawable/galaxy4_staticstar2.png");
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

    private static ByteBuffer floats(float[] values) {
        return floats(values, values.length);
    }

    private static ByteBuffer floats(float[] values, int count) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(count * 4)
                .order(ByteOrder.nativeOrder());
        buffer.asFloatBuffer().put(values, 0, count);
        return buffer;
    }

    /** 列主序 4×4 乘法，与旧 native 的 multiplyMat4 一致。 */
    private static void multiply(float[] a, float[] b, float[] out) {
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0.0f;
                for (int k = 0; k < 4; k++) {
                    sum += a[k * 4 + row] * b[col * 4 + k];
                }
                out[col * 4 + row] = sum;
            }
        }
    }

    private static final float[] CLIP_CORRECTION = {
        1, 0, 0, 0,
        0, -1, 0, 0,
        0, 0, 0.5f, 0,
        0, 0, 0.5f, 1,
    };
}
