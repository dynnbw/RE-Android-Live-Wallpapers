package com.reandroid.wallpaper.microbes;

import static com.reandroid.wallpaper.microbes.MicrobesScene.DEAD_COUNT;
import static com.reandroid.wallpaper.microbes.MicrobesScene.DECOR_COUNT;
import static com.reandroid.wallpaper.microbes.MicrobesScene.FOOD_COUNT;
import static com.reandroid.wallpaper.microbes.MicrobesScene.MICROBE_MAX;
import static com.reandroid.wallpaper.microbes.MicrobesScene.TOUCH_DRAG_THRESHOLD_PX;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.MotionEvent;

import com.reandroid.plugin.VkPass;
import com.reandroid.plugin.VkPassFrame;
import com.reandroid.plugin.VkPassRendererDelegate;
import com.reandroid.plugin.VkPassScene;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Microbes 的 Vulkan 渲染器 —— 壁纸与预览共用。
 *
 * GLES 版是四个 GL_POINTS pass，绘制顺序 decoration -> dead -> food -> microbe；
 * 这里用同样的顺序、同样的每帧数据与着色器公式，四个 pass 一一对应。
 * 点精灵靠顶点着色器的 gl_PointSize 画出来，Vulkan 的 gl_PointCoord 与 GLES 一样以左上角为原点。
 *
 * 坐标仍按 GLES 的 NDC 计算（uTrans = 2/宽、2/高、滚动偏移、-1），
 * 再在每个顶点着色器里做 Vulkan 裁剪空间修正：y 取反，z 从 [-w,w] 映射到 [0,w]。
 */
final class MicrobesVkDelegate extends VkPassRendererDelegate {

    private static final String TAG = "MicrobesVK";

    /** aPosition(3) + miscInfo(3) + aColor(3)。 */
    private static final int MICROBE_FLOATS = 9;

    private static final int MICROBE_STRIDE = MICROBE_FLOATS * 4;
    private static final int FOOD_STRIDE = 3 * 4;
    private static final int DEAD_STRIDE = 4 * 4;
    private static final int DECOR_STRIDE = 3 * 4;

    /** vec4 uTrans + float time。 */
    private static final int PUSH_BYTES = 5 * 4;

    /** vec4 uTrans + float time + float uSizeScale。 */
    private static final int DECOR_PUSH_BYTES = 6 * 4;

    private static final String MICROBE_BUFFER = "microbes";
    private static final String FOOD_BUFFER = "food";
    private static final String DEAD_BUFFER = "dead";
    private static final String DECOR_BUFFER = "decor";

    private SharedPreferences mPrefs;
    private MicrobesScene mScene;

    private VkPass mMicrobePass;
    private VkPass mFoodPass;
    private VkPass mDeadPass;
    private VkPass mDecorPass;

    private ByteBuffer mMicrobeVertices;
    private ByteBuffer mFoodVertices;
    private ByteBuffer mDeadVertices;
    private ByteBuffer mDecorVertices;

    /** 前 5 个 float 四个 pass 共用，装饰额外用第 6 个。 */
    private final float[] mPush = new float[6];

    private int mWidth;
    private int mHeight;
    private int mScrollPixels;
    private long mLastFrameMs = -1L;

    MicrobesVkDelegate(Context context, SharedPreferences prefs) {
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
            mScene = new MicrobesScene();
            mScene.setPluginPrefs(mPrefs);
            mScene.initScene(width, height);
            applyScrollOffset();
        } else {
            mScene.resize(width, height);
        }
    }

    @Override
    public void onPluginPrefsChanged(SharedPreferences prefs) {
        mPrefs = prefs;
        if (mScene != null) mScene.setPluginPrefs(prefs);
    }

    @Override
    public void onSceneOffset(float xOffset, int xPixels) {
        // GLES 的 setOffset 用的就是 xPixels，这里逐字照搬，不做比例换算。
        mScrollPixels = xPixels;
        applyScrollOffset();
    }

    private void applyScrollOffset() {
        if (mScene == null || mWidth <= 0) return;
        mScene.setScroll(mScrollPixels, mWidth);
    }

    @Override
    public void onTouchEvent(MotionEvent event) {
        MicrobesScene scene = mScene;
        if (event == null || scene == null) {
            return;
        }
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            scene.touchActive = true;
            scene.touchMoved = false;
            scene.touchStartX = event.getX();
            scene.touchStartY = event.getY();
            scene.motion(toWorldX(scene.touchStartX), toWorldY(scene.touchStartY));
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (!scene.touchMoved) {
                float dx = event.getX() - scene.touchStartX;
                float dy = event.getY() - scene.touchStartY;
                scene.touchMoved =
                        (dx * dx + dy * dy) > (TOUCH_DRAG_THRESHOLD_PX * TOUCH_DRAG_THRESHOLD_PX);
            }
            scene.motion(toWorldX(event.getX()), toWorldY(event.getY()));
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (!scene.touchMoved) {
                scene.touchTap(toWorldX(event.getX()), toWorldY(event.getY()));
            }
            scene.touchActive = false;
        }
    }

    private float toWorldX(float x) {
        return x - mScene.scrollXPx;
    }

    private float toWorldY(float y) {
        return mHeight - y;
    }

    @Override
    protected void buildScene(VkPassScene scene) {
        scene.setClearColor(0.0f, 0.0f, 0.0f, 1.0f);

        scene.createBuffer(MICROBE_BUFFER, MICROBE_STRIDE, MICROBE_MAX, true);
        scene.createBuffer(FOOD_BUFFER, FOOD_STRIDE, FOOD_COUNT, true);
        scene.createBuffer(DEAD_BUFFER, DEAD_STRIDE, DEAD_COUNT, true);
        scene.createBuffer(DECOR_BUFFER, DECOR_STRIDE, DECOR_COUNT, true);

        // 原版 glBlendFunc(GL_SRC_ALPHA, GL_ONE) 对颜色和 alpha 通道都生效，这里逐项照抄。
        mDecorPass = scene.add(pointPass(
                "shaders/microbes_vk_decor.vert.spv",
                "shaders/microbes_vk_decor.frag.spv",
                new int[] {3},
                DECOR_STRIDE,
                DECOR_BUFFER,
                DECOR_PUSH_BYTES));
        mDeadPass = scene.add(pointPass(
                "shaders/microbes_vk_dead.vert.spv",
                "shaders/microbes_vk_dead.frag.spv",
                new int[] {4},
                DEAD_STRIDE,
                DEAD_BUFFER,
                PUSH_BYTES));
        mFoodPass = scene.add(pointPass(
                "shaders/microbes_vk_food.vert.spv",
                "shaders/microbes_vk_food.frag.spv",
                new int[] {3},
                FOOD_STRIDE,
                FOOD_BUFFER,
                PUSH_BYTES));
        mMicrobePass = scene.add(pointPass(
                "shaders/microbes_vk_microbe.vert.spv",
                "shaders/microbes_vk_microbe.frag.spv",
                new int[] {3, 3, 3},
                MICROBE_STRIDE,
                MICROBE_BUFFER,
                PUSH_BYTES));

        mMicrobeVertices = newFloatBuffer(MICROBE_MAX * MICROBE_FLOATS);
        mFoodVertices = newFloatBuffer(FOOD_COUNT * 3);
        mDeadVertices = newFloatBuffer(DEAD_COUNT * 4);
        mDecorVertices = newFloatBuffer(DECOR_COUNT * 3);
    }

    private static VkPass pointPass(
            String vert, String frag, int[] attribSizes, int stride, String buffer, int pushBytes) {
        return new VkPass(
                        vert,
                        frag,
                        attribSizes,
                        stride,
                        VkPass.TOPOLOGY_POINT_LIST,
                        VkPass.BLEND_ADDITIVE)
                .buffer(buffer, 0)
                .pushConstants(pushBytes)
                .blendFactors(
                        VkPass.FACTOR_SRC_ALPHA,
                        VkPass.FACTOR_ONE,
                        VkPass.FACTOR_SRC_ALPHA,
                        VkPass.FACTOR_ONE);
    }

    @Override
    protected void updateFrame(VkPassFrame frame, long timeMs) {
        MicrobesScene scene = mScene;
        if (scene == null || mWidth <= 0 || mHeight <= 0) {
            return;
        }

        if (mLastFrameMs < 0L) {
            mLastFrameMs = timeMs;
        }
        float dt = Math.min(0.1f, Math.max(0.001f, (timeMs - mLastFrameMs) / 1000.0f));
        mLastFrameMs = timeMs;
        scene.timeSec += dt;

        scene.refreshRuntimeSettingsIfNeeded(timeMs);
        scene.updateScene(dt);

        float width = Math.max(1.0f, mWidth);
        float height = Math.max(1.0f, mHeight);
        float sx = 2.0f / width;
        float sy = 2.0f / height;
        float tx = 2.0f * scene.scrollXPx / width - 1.0f;
        float ty = -1.0f;
        // 原版运行于 480×800；按 (屏高/800) 等比放大点精灵尺寸
        float sizeScale = Math.max(1.0f, mHeight / 800.0f);

        mPush[0] = sx;
        mPush[1] = sy;
        mPush[2] = tx;
        mPush[3] = ty;
        mPush[4] = scene.timeSec;

        // 与 GLES 的绘制顺序一致：decoration -> dead -> food -> microbe
        uploadDecor(frame, scene, sizeScale);
        uploadDead(frame, scene, sizeScale);
        uploadFood(frame, scene);
        int microbeCount = uploadMicrobes(frame, scene, sizeScale);

        frame.pushConstants(mDecorPass, mPush);
        frame.pushConstants(mDeadPass, mPush);
        frame.pushConstants(mFoodPass, mPush);
        frame.pushConstants(mMicrobePass, mPush);

        // 每个 pass 每帧都要给顶点数；未设置的 pass 不会画
        frame.setVertexCount(mDecorPass, DECOR_COUNT);
        frame.setVertexCount(mDeadPass, DEAD_COUNT);
        frame.setVertexCount(mFoodPass, FOOD_COUNT);
        frame.setVertexCount(mMicrobePass, microbeCount);
    }

    private void uploadDecor(VkPassFrame frame, MicrobesScene scene, float sizeScale) {
        ByteBuffer buffer = mDecorVertices;
        buffer.clear();
        for (int i = 0; i < DECOR_COUNT; i++) {
            buffer.putFloat(scene.decorX[i]);
            buffer.putFloat(scene.decorY[i]);
            buffer.putFloat(scene.decorZ[i]);
        }
        buffer.position(0);
        frame.upload(DECOR_BUFFER, buffer, DECOR_COUNT * DECOR_STRIDE);

        // 装饰是极低 alpha 的软边光晕，与 GLES 一样限制最大 1.3×，否则渐变会糊成雾团
        mPush[5] = Math.min(sizeScale, 1.3f);
    }

    private void uploadDead(VkPassFrame frame, MicrobesScene scene, float sizeScale) {
        ByteBuffer buffer = mDeadVertices;
        buffer.clear();
        for (int i = 0; i < DEAD_COUNT; i++) {
            buffer.putFloat(scene.deadX[i]);
            buffer.putFloat(scene.deadY[i]);
            buffer.putFloat(scene.deadAngle[i]);
            // 尸体尺寸 = 死前能量，按屏高等比预乘
            buffer.putFloat(scene.deadSize[i] * sizeScale);
        }
        buffer.position(0);
        frame.upload(DEAD_BUFFER, buffer, DEAD_COUNT * DEAD_STRIDE);
    }

    private void uploadFood(VkPassFrame frame, MicrobesScene scene) {
        ByteBuffer buffer = mFoodVertices;
        buffer.clear();
        for (int i = 0; i < FOOD_COUNT; i++) {
            buffer.putFloat(scene.foodX[i]);
            buffer.putFloat(scene.foodY[i]);
            buffer.putFloat(scene.foodPhase[i]);
        }
        buffer.position(0);
        frame.upload(FOOD_BUFFER, buffer, FOOD_COUNT * FOOD_STRIDE);
    }

    private int uploadMicrobes(VkPassFrame frame, MicrobesScene scene, float sizeScale) {
        int count = scene.microbeCount;
        ByteBuffer buffer = mMicrobeVertices;
        buffer.clear();
        for (int i = 0; i < count; i++) {
            buffer.putFloat(scene.microbeX[i]);
            buffer.putFloat(scene.microbeY[i]);
            buffer.putFloat(scene.microbeAngle[i]);
            // miscInfo.x 是预乘屏高比例后的尺寸，miscInfo.y 保留原始能量
            buffer.putFloat(scene.microbeEnergy[i] * sizeScale);
            buffer.putFloat(scene.microbeEnergy[i]);
            buffer.putFloat(scene.microbePulseTs[i]);
            buffer.putFloat(scene.microbeC0[i]);
            buffer.putFloat(scene.microbeC1[i]);
            buffer.putFloat(scene.microbeC2[i]);
        }
        buffer.position(0);
        frame.upload(MICROBE_BUFFER, buffer, count * MICROBE_STRIDE);
        return count;
    }

    private static ByteBuffer newFloatBuffer(int floatCount) {
        return ByteBuffer.allocateDirect(floatCount * 4).order(ByteOrder.nativeOrder());
    }
}
