package com.reandroid.wallpaper.holospiral;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.opengl.Matrix;
import android.util.Log;

import com.reandroid.plugin.VkPass;
import com.reandroid.plugin.VkPassFrame;
import com.reandroid.plugin.VkPassRendererDelegate;
import com.reandroid.plugin.VkPassScene;
import com.reandroid.utils.AssetLoader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Holo Spiral 的 VK 渲染器 —— 壁纸与预览共用。
 *
 * <p>GLES 版每帧两次绘制：全屏渐变背景（三角形条带、不混合）和螺旋点阵
 * （{@code GL_POINTS} + {@code gl_PointSize} + {@code gl_PointCoord} 采样
 * points_red_green.png）。这里按同样的先后声明三个 pass —— 背景、外圈、内圈，
 * 外圈与内圈都是点精灵，混合因子与 GLES 的
 * {@code glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)} 一致。
 *
 * <p>几何与颜色都由 {@link HoloSpiralScene} 生成（7 float/顶点：x, y, z, r, g, b, a），
 * 与 GLES 共用同一份逻辑。矩阵保持 GL 的约定不变，y 翻转和 z 映射放在顶点着色器里做，
 * 这样距离衰减仍按 GL 裁剪空间的 z 计算，点大小与 alpha 与 GLES 逐帧相同。
 */
final class HoloSpiralVkDelegate extends VkPassRendererDelegate {

    private static final String TAG = "HoloSpiralVK";

    /** 与 HoloSpiralGL 相同的近远平面。 */
    private static final float NEAR_PLANE = 1.0f;

    private static final float FAR_PLANE = 55.0f;

    /** 顶点布局由 Scene 定：x, y, z, r, g, b, a。 */
    private static final int FLOATS_PER_VERTEX = HoloSpiralScene.FLOATS_PER_VERTEX;

    private static final int STRIDE_BYTES = FLOATS_PER_VERTEX * 4;

    private static final int BG_VERTICES = 4;

    /** layout.json 里两个点数的上限，缓冲按它分配；越界值按上限截断。 */
    private static final int MAX_INNER_POINTS = 300;

    private static final int MAX_OUTER_POINTS = 150;

    /** mat4 + uMaxPointSize + uFarPlane。 */
    private static final int PUSH_BYTES = (16 + 2) * 4;

    private static final String VERTEX_SHADER = "shaders/holospiral_vk_geometry.vert.spv";
    private static final String FRAGMENT_SHADER = "shaders/holospiral_vk_geometry.frag.spv";

    private static final String POINT_TEXTURE = "points";
    private static final String POINT_TEXTURE_ASSET =
            "holospiral/drawable/points_red_green.png";

    private SharedPreferences mPrefs;
    private HoloSpiralScene mScene;

    private VkPass mBgPass;
    private VkPass mOuterPass;
    private VkPass mInnerPass;

    private ByteBuffer mBgVertices;
    private ByteBuffer mOuterVertices;
    private ByteBuffer mInnerVertices;
    private int mOuterCount;
    private int mInnerCount;
    private boolean mGeometryBuilt;

    private final float[] mProjection = new float[16];
    private final float[] mModelView = new float[16];
    private final float[] mTempModelView = new float[16];
    private final float[] mMvp = new float[16];
    private final float[] mPush = new float[18];

    private volatile float mXOffset;
    private volatile int mWidth;
    private volatile int mHeight;

    HoloSpiralVkDelegate(Context context, SharedPreferences prefs) {
        super(context);
        mPrefs = prefs;
    }

    @Override
    public String logTag() {
        return TAG;
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
            HoloSpiralScene scene = new HoloSpiralScene();
            scene.setPluginPrefs(mPrefs);
            mScene = scene;
        }
    }

    @Override
    public void onPluginPrefsChanged(SharedPreferences prefs) {
        // 场景还没建时也要记住，ensureScene 建场景时按最新的设置读
        mPrefs = prefs;
        HoloSpiralScene scene = mScene;
        if (scene == null) return;
        // 设置会被渲染线程逐帧读取，换参数与读参数要串行化。
        synchronized (scene) {
            scene.setPluginPrefs(prefs);
        }
    }

    @Override
    public void onSceneOffset(float xOffset) {
        mXOffset = xOffset;
    }

    @Override
    protected void buildScene(VkPassScene scene) {
        scene.setClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        scene.createBuffer("bg", STRIDE_BYTES, BG_VERTICES, true);
        scene.createBuffer("outer", STRIDE_BYTES, MAX_OUTER_POINTS, true);
        scene.createBuffer("inner", STRIDE_BYTES, MAX_INNER_POINTS, true);
        // GLES 用的是 GL_LINEAR + GL_CLAMP_TO_EDGE，正好是 createTexture 的默认值，
        // 这里显式写出来，免得以后默认值改了这里悄悄跟着变。
        scene.createTexture(
                POINT_TEXTURE, VkPassScene.FILTER_LINEAR, VkPassScene.ADDRESS_CLAMP_TO_EDGE);
        uploadPointTexture(scene);

        mBgVertices = ByteBuffer.allocateDirect(BG_VERTICES * STRIDE_BYTES)
                .order(ByteOrder.nativeOrder());
        mOuterVertices = ByteBuffer.allocateDirect(MAX_OUTER_POINTS * STRIDE_BYTES)
                .order(ByteOrder.nativeOrder());
        mInnerVertices = ByteBuffer.allocateDirect(MAX_INNER_POINTS * STRIDE_BYTES)
                .order(ByteOrder.nativeOrder());
        mGeometryBuilt = false;

        mBgPass = scene.add(new VkPass(
                        "shaders/holospiral_vk_bg.vert.spv",
                        "shaders/holospiral_vk_bg.frag.spv",
                        new int[] {3, 4},
                        STRIDE_BYTES,
                        VkPass.TOPOLOGY_TRIANGLE_STRIP,
                        VkPass.BLEND_NONE))
                .buffer("bg", BG_VERTICES);

        // 外圈先画、内圈后画，与 GLES 的 drawSpiral 调用顺序一致。
        mOuterPass = createSpiralPass(scene, "outer");
        mInnerPass = createSpiralPass(scene, "inner");
    }

    private VkPass createSpiralPass(VkPassScene scene, String buffer) {
        VkPass pass = scene.add(new VkPass(
                        VERTEX_SHADER,
                        FRAGMENT_SHADER,
                        new int[] {3, 4},
                        STRIDE_BYTES,
                        VkPass.TOPOLOGY_POINT_LIST,
                        VkPass.BLEND_ALPHA))
                .buffer(buffer, 0)
                .pushConstants(PUSH_BYTES)
                // GLES 的 glBlendFunc(SRC_ALPHA, ONE_MINUS_SRC_ALPHA) 对 alpha 通道同样生效；
                // 通用 BLEND_ALPHA 预设给 srcAlpha 用的是 ONE。
                .blendFactors(
                        VkPass.FACTOR_SRC_ALPHA,
                        VkPass.FACTOR_ONE_MINUS_SRC_ALPHA,
                        VkPass.FACTOR_SRC_ALPHA,
                        VkPass.FACTOR_ONE_MINUS_SRC_ALPHA);
        pass.texture(0, POINT_TEXTURE);
        return pass;
    }

    @Override
    protected void updateFrame(VkPassFrame frame, long timeMs) {
        HoloSpiralScene scene = mScene;
        if (scene == null || mWidth <= 0 || mHeight <= 0) return;

        synchronized (scene) {
            if (scene.consumeGeometryDirty() || !mGeometryBuilt) {
                rebuildGeometry(scene);
            }

            // 只用到时间差，uptimeMillis 的基准不影响相位。
            float dt = scene.tickTime(timeMs);

            float aspect = (float) mWidth / (float) mHeight;
            Matrix.perspectiveM(mProjection, 0, scene.fov, aspect, NEAR_PLANE, FAR_PLANE);
            buildModelView(scene);

            mBgVertices.position(0);
            frame.upload("bg", mBgVertices, BG_VERTICES * STRIDE_BYTES);
            frame.setVertexCount(mBgPass, BG_VERTICES);

            // 外圈绕 z 反向转、内圈正向转，角度与 GLES 同源。
            uploadSpiral(frame, mOuterPass, "outer", mOuterVertices, mOuterCount,
                    -scene.getOuterRotateAngle(), scene.maxPointSize);
            uploadSpiral(frame, mInnerPass, "inner", mInnerVertices, mInnerCount,
                    scene.getInnerRotateAngle(), scene.maxPointSize);

            // 先按当前角度画、画完再推进（与 GLES 同序，提前推进会让相位差一帧）。
            scene.advanceRotateAngles(dt);
        }
    }

    /** 与 HoloSpiralGL.initTransforms / drawGeometry 相同的模型视图链。 */
    private void buildModelView(HoloSpiralScene scene) {
        Matrix.setIdentityM(mModelView, 0);
        Matrix.translateM(mModelView, 0, -3.0f, -5.0f, -18.0f);
        Matrix.rotateM(mModelView, 0, 20.0f, 0.0f, 1.0f, 0.0f);
        Matrix.rotateM(mModelView, 0, -10.0f, 1.0f, 0.0f, 0.0f);
        Matrix.rotateM(mModelView, 0, mXOffset * -scene.spiralRotateSpeed, 0.0f, 1.0f, 0.0f);
    }

    private void uploadSpiral(VkPassFrame frame, VkPass pass, String buffer,
            ByteBuffer vertices, int count, float rotationZ, float maxPointSize) {
        if (count <= 0) {
            frame.setVertexCount(pass, 0);
            return;
        }
        System.arraycopy(mModelView, 0, mTempModelView, 0, mModelView.length);
        Matrix.rotateM(mTempModelView, 0, rotationZ, 0.0f, 0.0f, 1.0f);
        Matrix.multiplyMM(mMvp, 0, mProjection, 0, mTempModelView, 0);

        System.arraycopy(mMvp, 0, mPush, 0, 16);
        mPush[16] = maxPointSize;
        mPush[17] = FAR_PLANE;

        vertices.position(0);
        frame.upload(buffer, vertices, count * STRIDE_BYTES);
        frame.setVertexCount(pass, count);
        frame.pushConstants(pass, mPush);
    }

    /**
     * 按 HoloSpiralScene 的当前参数重建三份顶点数据。
     *
     * <p>与 GLES 一样，只有设置变化（含配色）才会走到这里；点数按 layout.json 的上限截断，
     * 免得越界的设置把缓冲写穿。
     */
    private void rebuildGeometry(HoloSpiralScene scene) {
        float[] background = scene.buildBackgroundData();
        mBgVertices.clear();
        mBgVertices.asFloatBuffer().put(background);

        mOuterCount = Math.min(scene.numOuterPoints, MAX_OUTER_POINTS);
        mInnerCount = Math.min(scene.numInnerPoints, MAX_INNER_POINTS);
        if (mOuterCount < 0) mOuterCount = 0;
        if (mInnerCount < 0) mInnerCount = 0;

        float[] outer = scene.buildSpiralData(
                mOuterCount,
                HoloSpiralScene.OUTER_SPIRAL_DEPTH,
                scene.outerRadius,
                HoloSpiralScene.SEPARATION_DEG,
                scene.outerColor,
                scene.outerColor);
        mOuterVertices.clear();
        mOuterVertices.asFloatBuffer().put(outer);

        float[] inner = scene.buildSpiralData(
                mInnerCount,
                HoloSpiralScene.INNER_SPIRAL_DEPTH,
                scene.innerRadius,
                HoloSpiralScene.SEPARATION_DEG,
                scene.innerColorPrimary,
                scene.innerColorSecondary);
        mInnerVertices.clear();
        mInnerVertices.asFloatBuffer().put(inner);

        mGeometryBuilt = true;
    }

    private void uploadPointTexture(VkPassScene scene) {
        Bitmap bitmap;
        try {
            bitmap = AssetLoader.decodeBitmap(context, POINT_TEXTURE_ASSET);
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to decode point texture", e);
            return;
        }
        if (bitmap == null) {
            Log.e(TAG, "Failed to decode point texture");
            return;
        }

        Bitmap argb = bitmap.getConfig() == Bitmap.Config.ARGB_8888
                ? bitmap
                : bitmap.copy(Bitmap.Config.ARGB_8888, false);
        if (argb != bitmap) bitmap.recycle();
        int width = argb.getWidth();
        int height = argb.getHeight();
        int[] pixels = new int[width * height];
        argb.getPixels(pixels, 0, width, 0, 0, width, height);
        argb.recycle();
        scene.setTexture(POINT_TEXTURE, pixels, width, height);
    }
}
