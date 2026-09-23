/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.reandroid.wallpaper.fall;

import android.content.SharedPreferences;
import android.opengl.Matrix;
import android.util.Log;

import com.reandroid.settings.WallpaperSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

final class FallScene {
    private static final String TAG = "FallScene";

    static final int DEFAULT_LEAVES_COUNT = 14;
    static final int DEFAULT_RANDOM_DROPS = 10;
    static final float LEAF_SIZE = 0.55f;
    private static final int MESH_RESOLUTION = 48;

    /**
     * 世界尺寸：**定值**，不随屏幕变。
     *
     * <p>取自 AOSP 原版 {@code fall.rs} —— 真实尺寸被原作者注释掉了：
     * <pre>
     *   float width  = 2;      //g_glWidth;
     *   float height = 3.333;  //g_glHeight;
     * </pre>
     * 世界恒为 2 × 3.333（长宽比 0.6），屏幕差异靠"铺满 + 裁切"适配，**永不拉伸**。
     * 移植时这里被换成了 {@code 2*height/width}，世界形状跟着设备走，背景于是被拉伸。
     */
    private static final float WORLD_HEIGHT = 3.333f;

    /** 世界宽度恒为 ±1（即宽 2）。 */
    private static final float WORLD_HALF_WIDTH = 1.0f;

    private static final int DEFAULT_WATER_MESH_DROPS = 10;
    // Drop array sized dynamically — no hard limit

    static final class Leaf {
        float x;
        float y;
        float scale;
        float angle;
        float spin;
        float altitude;
        float deltaX;
        float deltaY;
        int leafTextureIndex;
        boolean rippled;

        void init(Random random, int leafTexCount, boolean startAboveWater, float glHeight) {
            leafTextureIndex = random.nextInt(Math.max(1, leafTexCount));
            // x 的参考空间恒为 ±1（正交投影的 left/right 不随屏幕变），所以 ±2 是对的；
            // y 会随屏幕变（glHeight = 2*h/w），必须用真值。
            x = (random.nextFloat() - 0.5f) * 4.0f;
            y = (random.nextFloat() - 0.5f) * glHeight;
            scale = 0.4f + random.nextFloat() * 0.1f;
            angle = random.nextFloat() * 360.0f;
            spin = (random.nextFloat() - 0.5f) * 0.016f;
            altitude = startAboveWater ? 0.7f : -1.0f;
            deltaX = (random.nextFloat() - 0.5f) * 0.02f;
            deltaY = -(0.036f + random.nextFloat() * 0.008f);
            rippled = !startAboveWater;
        }
    }

    static final class Drop {
        float ampS;
        float ampE;
        float spread;
        float x;
        float y;

        void init() {
            ampS = 0.0f;
            ampE = 0.0f;
            spread = 1.0f;
        }

        void updateLegacy(float dt) {
            if (ampS > 0.0f) {
                spread += 30.0f * dt;
                ampE = ampS * (float) Math.exp(-0.02f * spread) / (1.0f + 0.01f * spread);
            }
        }

        void activateLegacy(float meshX, float meshY, float amplitude) {
            x = meshX;
            y = meshY;
            ampS = amplitude;
            spread = 0.0f;
            ampE = amplitude;
        }
    }

    static final class SceneData {
        private final float[] projectionMatrix = new float[16];
        private final float[] viewMatrix = new float[16];
        private Leaf[] leaves;
        private float[] waterMeshVertices;
        private float[] waterMeshTexCoords;
        private short[] waterMeshIndices;
        private int waterMeshVertexCount;
        private int waterMeshIndexCount;
        private float xOffset = 0.5f;

        // GPU-computed ripple: per-drop (x, y, ampE, spread), dynamically sized
        private float[] dropData = new float[0];
        private int activeDropCount;
        private float glHeight;
        private float bgScale;
        private float meshScaleX;
        private float meshScaleY;
        private float dxMul;
        private int rotate;

        float[] getProjectionMatrix() { return projectionMatrix; }
        float[] getViewMatrix() { return viewMatrix; }
        Leaf[] getLeaves() { return leaves; }
        float[] getWaterMeshVertices() { return waterMeshVertices; }
        float[] getWaterMeshTexCoords() { return waterMeshTexCoords; }
        short[] getWaterMeshIndices() { return waterMeshIndices; }
        int getWaterMeshVertexCount() { return waterMeshVertexCount; }
        int getWaterMeshIndexCount() { return waterMeshIndexCount; }
        float getXOffset() { return xOffset; }
        float[] getDropData() { return dropData; }
        int getActiveDropCount() { return activeDropCount; }
        float getGlHeight() { return glHeight; }
        float getBgScale() { return bgScale; }
        float getMeshScaleX() { return meshScaleX; }
        float getMeshScaleY() { return meshScaleY; }
        float getDxMul() { return dxMul; }
        int getRotate() { return rotate; }
    }

    private final Random mRandom = new Random();
    private final SceneData mSceneData = new SceneData();

    private int mWidth;
    private int mHeight;
    private long mLastTimeMs;
    private float mDeltaTime;
    private int mLeafCount = DEFAULT_LEAVES_COUNT;
    private int mLastLeafCount = DEFAULT_LEAVES_COUNT;
    private int mLeafTextureCount = DEFAULT_LEAVES_COUNT;
    private int mRotate = 0;
    private int mMeshWidth;
    private int mMeshHeight;
    /**
     * 可见高度（正交投影的上下跨度）。定值，见 {@link #worldHeight()}。
     *
     * <p>默认值只在 resize() 之前被读到，作用是不让初值为 0，
     * **不是可以照抄的目标值**。
     */
    private float mGlHeight = WORLD_HEIGHT;

    /** 当前屏幕上可见的世界半宽/半高。铺满+裁切之后它与世界半宽/半高不一定相等。 */
    private float mVisibleHalfW = WORLD_HALF_WIDTH;
    private float mVisibleHalfH = WORLD_HEIGHT * 0.5f;
    private float mBackgroundScale = 0.75f;
    private Drop[] mDrops;
    private Drop[] mWaterDrops;
    private int mWaterDropCount = DEFAULT_WATER_MESH_DROPS;
    private int mLastWaterDropCount = DEFAULT_WATER_MESH_DROPS;
    private boolean mInitialized = false;
    private boolean mMeshBuffersDirty = true;
    private boolean mWaterTexCoordsDirty = true;
    private float[] mVkLeafData = new float[0];
    private int mVkLeafFloatCount = 0;
    private volatile SharedPreferences mPrefs;

    /** 日夜变换：按当前时刻在四条天空色带之间插值。见 {@link FallDayNightSystem}。 */
    private final FallDayNightSystem mDayNightSystem = new FallDayNightSystem();
    /** 预览模式（把一天压进 {@link #PREVIEW_CYCLE_MS}）。 */
    private boolean mIsPreview;
    /**
     * 预览把一整天压进这么长。与 grass 取同一个值 —— 两款壁纸的预览节奏应当一致。
     */
    private static final long PREVIEW_CYCLE_MS = 30000L;

    FallScene(int width, int height) {
        mWidth = width;
        mHeight = height;
        mLastTimeMs = System.currentTimeMillis();
        // Defer prepareNonGLResources() to first update() so setPluginPrefs() is available
    }

    /** Called by FallGL.start() or first update() to initialize non-GL resources. */
    void ensureResources() {
        prepareNonGLResources();
    }

    /**
     * 预览模式（设置页里把一天压进 {@value #PREVIEW_CYCLE_MS} 毫秒）。
     *
     * <p>不这么做的话，设置页预览永远停在"现在"这一刻 —— 想看夜晚就得等到晚上。
     */
    void setPreview(boolean preview) {
        mIsPreview = preview;
        mDayNightSystem.setPreview(preview);
    }

    private float mLeafSizeMultiplier = 1.0f;
    private float mFallSpeedMultiplier = 1.0f;

    float getLeafSizeMultiplier() { return effectiveLeafSizeMultiplier(); }

    /** 设置里的倍率 × 朝向补偿（竖屏时补偿恒为 1）。 */
    private float effectiveLeafSizeMultiplier() {
        return mLeafSizeMultiplier * leafOrientationScale();
    }

    /**
     * 实际会用到的叶子贴图数（绿叶开 20、关 14，见 prepareNonGLResources）。
     * GL 侧据此只加载需要的那几张，别把用不到的也传上显存。
     */
    int getLeafTextureCount() { return mLeafTextureCount; }

    /** Plugin path: use host-provided prefs instead of WallpaperSettings. */
    void setPluginPrefs(SharedPreferences prefs) {
        mPrefs = prefs;
        mLeafSizeMultiplier = prefs.getInt("fall_leaf_size", 100) / 100.0f;
        mFallSpeedMultiplier = prefs.getInt("fall_speed", 100) / 100.0f;
    }

    void setLeafTextureCount(int leafTextureCount) {
        if (leafTextureCount > 0) {
            mLeafTextureCount = leafTextureCount;
        }
    }

    void setOffset(float xOffset) {
        mSceneData.xOffset = xOffset;
    }

    /**
     * 按当前屏幕尺寸刷新朝向与世界高度。
     *
     * <p><b>世界高度恒按竖屏方向取</b>（长边 / 短边），横屏时靠
     * {@link #updateProjectionMatrix()} 把世界转 90° 摆正。于是竖屏和横屏的世界形状
     * 一致（约 2 × 3.8），叶片的**像素**尺寸与背景贴图的长宽比都不会变
     * —— 这正是"保留竖屏叶子大小"要的。
     *
     * <p>这里原先是三处赋值、两种公式：{@code resize()} 用 2*height/width（不交换），
     * {@code prepareNonGLResources()} 与 {@code addDrop()} 交换。竖屏下两者相等，
     * 横屏差 4.9 倍，谁最后写谁赢 —— 桌面是后者，于是世界被横向拉 4 倍。
     */
    private void updateOrientation() {
        mRotate = mWidth > mHeight ? 1 : 0;
        mGlHeight = WORLD_HEIGHT;
    }

    /**
     * 世界恒为 2 × {@link #WORLD_HEIGHT}，与屏幕无关。
     *
     * <p>屏幕比例由 {@link #updateProjectionMatrix()} 用"铺满 + 裁切"适配 ——
     * 被裁掉的是画面范围，不是比例。
     */
    static float worldHeight() {
        return WORLD_HEIGHT;
    }

    /**
     * 叶片的朝向补偿 —— 与波纹同一个系数，见 {@link #rippleScale()}。
     *
     * <p>两者本来各用各的（叶片按"短边/屏幕宽"、波纹按每世界单位像素），
     * 结果横屏叶子比竖屏小 25%。现在统一：同一个世界尺寸的东西，
     * 在两个朝向下都落在同样的物理尺寸上。竖屏恒为 1。
     */
    private float leafOrientationScale() {
        return rippleScale();
    }

    /**
     * 朝向系数：让世界尺寸的东西（叶片、波纹）**在两个朝向下落在同样的物理尺寸上**。
     * 竖屏恒为 1，方形屏幕也是 1。
     *
     * <p>屏幕尺寸 = 世界尺寸 × 每世界单位像素，而铺满 + 裁切之后每世界单位像素随朝向变
     * （本机横屏 1147、竖屏 720，差 1.59 倍）—— 同一张叶片、同一个波纹半径，横屏看着
     * 就大一半以上。
     *
     * <p>系数取"同一块屏幕竖过来时会是多少"与当前值的比，不含任何硬编码参考值。
     */
    private float rippleScale() {
        if (mWidth <= 0 || mHeight <= 0) {
            return 1.0f;
        }
        return pixelsPerWorldUnit(Math.min(mWidth, mHeight), Math.max(mWidth, mHeight))
                / pixelsPerWorldUnit(mWidth, mHeight);
    }

    /** 铺满 + 裁切之后，每世界单位对应多少像素（两轴相等）。 */
    private static float pixelsPerWorldUnit(int width, int height) {
        float screenAspect = (float) width / height;
        float worldAspect = (2.0f * WORLD_HALF_WIDTH) / WORLD_HEIGHT;
        float halfW = screenAspect < worldAspect
                ? WORLD_HEIGHT * 0.5f * screenAspect
                : WORLD_HALF_WIDTH;
        return width / (2.0f * halfW);
    }

    void resize(int width, int height) {
        mWidth = width;
        mHeight = height;
        updateOrientation();
        updateProjectionMatrix();
        createWaterMesh();
        mMeshBuffersDirty = true;
        mWaterTexCoordsDirty = true;
    }

    void update(long timeMs) {
        if (!mInitialized) {
            prepareNonGLResources();
        }

        long nowMs = System.currentTimeMillis();
        mDeltaTime = Math.min((nowMs - mLastTimeMs) * 0.001f, 0.2f);
        mLastTimeMs = nowMs;

        ensureWaterDropCount();
        updateDrops();
        updateLeaves();
        updateWaterMesh(nowMs);
        updateSkyWeights();
    }

    /**
     * 天空四条色带的权重。
     *
     * <p><b>开关关掉时一次天文计算都不做</b>——直接把权重钉在黄昏那一条上，
     * 连定位查询都不会发生。这是"基线不变"的兜底：关掉开关的画面与加这套之前逐位相同。
     */
    private void updateSkyWeights() {
        if (!isDayNightEnabled()) {
            mDayNightSystem.resetToDusk();
            return;
        }
        mDayNightSystem.updateWeights(sceneClockMs());
    }

    /**
     * 算日夜该用哪个时刻。
     *
     * <p>实机是真实时间；预览走压缩时间轴，否则设置页里根本等不到天黑。
     */
    private long sceneClockMs() {
        long realMs = System.currentTimeMillis();
        if (!mIsPreview) {
            return realMs;
        }
        return mDayNightSystem.compressedClockMs(realMs, PREVIEW_CYCLE_MS);
    }

    /** 天空色带权重 {@code [夜, 晨, 昏, 昼]}，恒和为 1。GL 侧每帧取一次。 */
    float[] getSkyWeights() {
        return mDayNightSystem.getWeights();
    }

    void addDrop(int x, int y) {
        // Lazy-init water drops on first touch (before first frame renders)
        if (mWaterDrops == null || mWaterDropCount <= 0) {
            mWaterDropCount = Math.max(1, getMaxDrops());
            mWaterDrops = new Drop[mWaterDropCount];
            for (int i = 0; i < mWaterDropCount; i++) {
                mWaterDrops[i] = new Drop();
                mWaterDrops[i].init();
            }
            if (mMeshWidth <= 1 || mMeshHeight <= 1) {
                updateOrientation();
                createWaterMesh();
            }
        }

        int minIndex = 0;
        float minAmp = Float.MAX_VALUE;
        for (int i = 0; i < mWaterDropCount; i++) {
            float score = mWaterDrops[i].ampE;
            if (score < minAmp) {
                minAmp = score;
                minIndex = i;
            }
        }

        /*
         * 像素 → 世界。必须按**可见**矩形换算：铺满 + 裁切之后，屏幕边缘对应的
         * 是 mVisibleHalfW/H，而不是世界半宽/半高。以前这里写死 ±1 与 ±glHeight/2，
         * 投影一改成裁切，横屏点击就整体偏掉了。
         */
        float posX = (((float) x / (float) mWidth) * 2.0f - 1.0f) * mVisibleHalfW;
        float posY = (1.0f - (float) y / (float) mHeight) * 2.0f * mVisibleHalfH
                - mVisibleHalfH;
        if (mRotate == 0) {
            posX += mSceneData.xOffset * 2.0f;
        }

        float scaleX = (mMeshWidth - 1) * 0.5f;
        float scaleY = (mMeshHeight - 1) * 0.5f;
        float dropX = (posX + 1.0f) * scaleX;
        float dropY = ((posY / (mGlHeight * 0.5f)) + 1.0f) * scaleY;

        Drop drop = mWaterDrops[minIndex];
        drop.activateLegacy(dropX, dropY, 1.2f);
        mWaterTexCoordsDirty = true;
    }

    SceneData getSceneData() {
        return mSceneData;
    }

    float[] buildLeafDataForVK() {
        Leaf[] leaves = mSceneData.leaves;
        int leafCount = leaves != null ? leaves.length : 0;
        int required = leafCount * 6;
        if (required <= 0) {
            mVkLeafFloatCount = 0;
            return mVkLeafData;
        }
        if (mVkLeafData.length < required) {
            mVkLeafData = new float[required];
        }
        for (int i = 0; i < leafCount; i++) {
            Leaf leaf = leaves[i];
            int base = i * 6;
            mVkLeafData[base] = leaf.x;
            mVkLeafData[base + 1] = leaf.y;
            mVkLeafData[base + 2] = leaf.scale * effectiveLeafSizeMultiplier();
            mVkLeafData[base + 3] = leaf.angle;
            mVkLeafData[base + 4] = leaf.altitude;
            mVkLeafData[base + 5] = leaf.leafTextureIndex;
        }
        mVkLeafFloatCount = required;
        return mVkLeafData;
    }

    int getVKLeafCount() {
        return mVkLeafFloatCount / 6;
    }

    boolean consumeMeshBufferRebuildRequested() {
        boolean value = mMeshBuffersDirty;
        mMeshBuffersDirty = false;
        return value;
    }

    boolean consumeWaterTexCoordsDirty() {
        boolean value = mWaterTexCoordsDirty;
        mWaterTexCoordsDirty = false;
        return value;
    }

    private void prepareNonGLResources() {
        if (mInitialized) {
            return;
        }
        mInitialized = true;
        // 叶子按 glHeight 布点，必须先把朝向定下来
        updateOrientation();

        mLeafTextureCount = isGreenLeaves() ? 20 : 14;
        mLeafCount = getLeafCount();
        mLastLeafCount = mLeafCount;
        mSceneData.leaves = new Leaf[mLeafCount];
        for (int i = 0; i < mLeafCount; i++) {
            mSceneData.leaves[i] = new Leaf();
            mSceneData.leaves[i].init(mRandom, mLeafTextureCount, false, mGlHeight);
        }

        mDrops = new Drop[DEFAULT_RANDOM_DROPS];
        for (int i = 0; i < DEFAULT_RANDOM_DROPS; i++) {
            mDrops[i] = new Drop();
            mDrops[i].init();
        }

        mWaterDropCount = Math.max(1, getMaxDrops());
        mLastWaterDropCount = mWaterDropCount;
        mWaterDrops = new Drop[mWaterDropCount];
        for (int i = 0; i < mWaterDropCount; i++) {
            mWaterDrops[i] = new Drop();
            mWaterDrops[i].init();
        }

        createWaterMesh();
        Matrix.setLookAtM(mSceneData.viewMatrix, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0);
        updateProjectionMatrix();
        Log.d(TAG, "FallScene 初始化完成");
    }

    /**
     * 把定值世界映射到屏幕：**铺满 + 裁切**，两轴每世界单位对应的像素恒相等。
     *
     * <p>世界长宽比是 0.6，屏幕几乎不会正好相等，于是取"能盖住屏幕的最大矩形"：
     * 屏幕比世界窄就按高度对齐、横向裁掉两侧；比世界宽就按宽度对齐、纵向裁掉上下。
     * 无论哪种，被裁掉的是**画面范围**而不是比例 —— 这正是"背景不应该拉伸"。
     */
    private void updateProjectionMatrix() {
        float screenAspect = mHeight > 0 ? (float) mWidth / mHeight : 1.0f;
        float worldAspect = (2.0f * WORLD_HALF_WIDTH) / WORLD_HEIGHT;

        float halfW;
        float halfH;
        if (screenAspect < worldAspect) {
            halfH = WORLD_HEIGHT * 0.5f;
            halfW = halfH * screenAspect;
        } else {
            halfW = WORLD_HALF_WIDTH;
            halfH = halfW / screenAspect;
        }

        mVisibleHalfW = halfW;
        mVisibleHalfH = halfH;
        Matrix.orthoM(mSceneData.projectionMatrix, 0,
                -halfW, halfW, -halfH, halfH, 0.1f, 10.0f);
    }

    /**
     * 水面网格纵向该切多少个区间 —— 世界是定值，所以这也**与屏幕无关**。
     *
     * <p>取"两轴每区间对应的世界长度相等"：横向 {@code wIntervals} 个区间铺满世界宽 2，
     * 纵向就该有 {@code wIntervals * WORLD_HEIGHT / 2} 个区间铺满世界高。
     * 这样 {@code addDrop()} 在网格单位里算的等距线投到屏幕上才是圆。
     */
    static int meshYIntervals(int wIntervals) {
        return Math.max(2, Math.round(
                wIntervals * WORLD_HEIGHT / (2.0f * WORLD_HALF_WIDTH)));
    }

    /**
     * 水面网格。顶点本身只提供"采样密度"，但网格**区间数**决定了
     * {@code addDrop()} 里的距离度量 —— 所以两个轴的区间必须对应同样多的屏幕像素，
     * 否则波纹的等距线投到屏幕上就是椭圆。
     *
     * <pre>
     *   X 每区间像素 = width  / Ux
     *   Y 每区间像素 = height / (Uy * yScale)
     * </pre>
     *
     * <p>（Y 这一侧：Uy 个区间铺满 glHeight 个世界单位，而屏幕上只看得见其中
     * {@code yScale} 那么多，所以每个区间落到 {@code height/(Uy*yScale)} 像素。）
     *
     * <p>令两者相等即得 {@code Uy = Ux * height / (width * yScale)}。
     *
     * <p><b>用的是屏幕宽高，不是 mGlHeight。</b> 网格区间铺满多少世界单位会被
     * glHeight 约掉 —— 它只决定可见范围，不决定每区间多少像素。
     *
     * <p>旧写法 {@code (int)(MESH_RESOLUTION * glHeight / 2) + 2} 隐含假设
     * "网格纵向跨度 == 可见纵向跨度"，既漏了 yScale（竖屏差 11%），
     * 又与横向密度差了 4%（常数取了 48 而不是 50），合起来 14%。
     */
    private void createWaterMesh() {
        int wResolution = MESH_RESOLUTION + 2;
        int hResolution = meshYIntervals(wResolution);

        List<Float> vertices = new ArrayList<>();
        List<Float> texCoords = new ArrayList<>();
        for (int y = 0; y <= hResolution; y++) {
            float yOffset = (((float) y / hResolution) * 2.0f - 1.0f) * mGlHeight / 2.0f;
            for (int x = 0; x <= wResolution; x++) {
                float xPos = ((float) x / wResolution) * 2.0f - 1.0f;
                vertices.add(xPos);
                vertices.add(yOffset);
                vertices.add(0.0f);
                texCoords.add((float) x / wResolution);
                texCoords.add((float) y / hResolution);
            }
        }

        List<Integer> indices = new ArrayList<>();
        for (int y = 0; y < hResolution; y++) {
            int yOffset = y * (wResolution + 1);
            for (int x = 0; x < wResolution; x++) {
                int index = yOffset + x;
                int nextRow = index + wResolution + 1;
                indices.add(index);
                indices.add(index + 1);
                indices.add(nextRow);
                indices.add(index + 1);
                indices.add(nextRow + 1);
                indices.add(nextRow);
            }
        }

        mSceneData.waterMeshVertexCount = vertices.size() / 3;
        mSceneData.waterMeshIndexCount = indices.size();
        mMeshWidth = wResolution + 1;
        mMeshHeight = hResolution + 1;

        mSceneData.waterMeshVertices = new float[vertices.size()];
        for (int i = 0; i < vertices.size(); i++) {
            mSceneData.waterMeshVertices[i] = vertices.get(i);
        }

        mSceneData.waterMeshTexCoords = new float[texCoords.size()];
        for (int i = 0; i < texCoords.size(); i++) {
            mSceneData.waterMeshTexCoords[i] = texCoords.get(i);
        }

        mSceneData.waterMeshIndices = new short[indices.size()];
        for (int i = 0; i < indices.size(); i++) {
            mSceneData.waterMeshIndices[i] = (short) (int) indices.get(i);
        }
    }

    private void ensureWaterDropCount() {
        int desired = Math.max(1, getMaxDrops());
        if (desired == mLastWaterDropCount && mWaterDrops != null) {
            return;
        }

        // Preserve active drops when resizing the array
        Drop[] oldDrops = mWaterDrops;
        int oldCount = mWaterDropCount;
        mWaterDropCount = desired;
        mLastWaterDropCount = desired;
        mWaterDrops = new Drop[mWaterDropCount];
        for (int i = 0; i < mWaterDropCount; i++) {
            if (i < oldCount && oldDrops != null) {
                mWaterDrops[i] = oldDrops[i];
            } else {
                mWaterDrops[i] = new Drop();
                mWaterDrops[i].init();
            }
        }
        mWaterTexCoordsDirty = true;
    }

    private void updateDrops() {
        for (Drop drop : mDrops) {
            drop.updateLegacy(mDeltaTime);
        }
        if (mRandom.nextFloat() < 0.3f) {
            int index = mRandom.nextInt(DEFAULT_RANDOM_DROPS);
            Drop drop = mDrops[index];
            drop.ampS = 1.0f;
            drop.spread = 0.0f;
            drop.x = (mRandom.nextFloat() - 0.5f) * 2.0f;
            drop.y = (mRandom.nextFloat() - 0.5f) * 3.0f;
        }
    }

    private void updateLeaves() {
        int desiredCount = getLeafCount();
        if (desiredCount != mLastLeafCount && desiredCount > 0) {
            mLeafCount = desiredCount;
            mLastLeafCount = desiredCount;
            mSceneData.leaves = new Leaf[mLeafCount];
            for (int i = 0; i < mLeafCount; i++) {
                mSceneData.leaves[i] = new Leaf();
                mSceneData.leaves[i].init(mRandom, mLeafTextureCount, false, mGlHeight);
            }
        }

        for (Leaf leaf : mSceneData.leaves) {
            if (leaf.altitude <= 0.0f) {
                if (!leaf.rippled) {
                    genLeafDrop(leaf, 1.5f);
                    leaf.rippled = true;
                    leaf.spin *= 0.25f;
                }

                leaf.x += leaf.deltaX * mDeltaTime;
                leaf.y += leaf.deltaY * mDeltaTime * mFallSpeedMultiplier;
                leaf.angle += leaf.spin;

                float margin = LEAF_SIZE * mLeafSizeMultiplier * 0.6f;
                float screenBottom = -mGlHeight / 2.0f - margin;
                float screenTop = mGlHeight / 2.0f + margin;
                if (leaf.y < screenBottom || leaf.y > screenTop) {
                    leaf.init(mRandom, mLeafTextureCount, true, mGlHeight);
                }
            } else {
                leaf.altitude -= 0.15f * mDeltaTime;
                leaf.angle += leaf.spin * 2.0f;
            }
        }
    }

    private void genLeafDrop(Leaf leaf, float amplitude) {
        float posX = leaf.x;
        float posY = leaf.y;
        if (mRotate < 1) {
            posX += mSceneData.xOffset * 2.0f;
        }

        float scaleX = (mMeshWidth - 1) * 0.5f;
        float scaleY = (mMeshHeight - 1) * 0.5f;
        float meshX = (posX + 1.0f) * scaleX;
        float meshY = ((posY / (mGlHeight * 0.5f)) + 1.0f) * scaleY;

        int minIndex = 0;
        float minAmp = Float.MAX_VALUE;
        if (mWaterDrops == null || mWaterDropCount <= 0) {
            return;
        }
        for (int i = 0; i < mWaterDropCount; i++) {
            float score = mWaterDrops[i].ampE;
            if (score < minAmp) {
                minIndex = i;
                minAmp = score;
            }
        }

        Drop drop = mWaterDrops[minIndex];
        drop.activateLegacy(meshX, meshY, amplitude);
    }

    private void updateWaterMesh(long nowMs) {
        if (mWaterDrops == null || mWaterDropCount <= 0) {
            mSceneData.activeDropCount = 0;
            return;
        }

        // Update drop state (spread/ampE decay)
        for (Drop drop : mWaterDrops) {
            drop.spread += 30.0f * mDeltaTime;
            /*
             * spread 仍为 0 说明这一帧没有任何时间流逝（同一毫秒内被 update 两次），
             * 直接除会得到 Infinity 并作为顶点数据进入 shader —— 水面会消失或花屏。
             * 这种情况下保留上一帧的 ampE（新生成的 drop 是 0，也就是先不放波纹），
             * spread 一涨起来就照常。
             */
            if (drop.spread > 0.0f) {
                drop.ampE = drop.ampS / drop.spread;
            }
        }

        // Pack all active drops for GPU: (x, y, ampE, spread) per drop, no artificial limit
        mSceneData.activeDropCount = mWaterDropCount;
        float[] d = mSceneData.dropData;
        int needed = mWaterDropCount * 4;
        if (d.length < needed) {
            mSceneData.dropData = d = new float[needed];
        }
        for (int i = 0; i < mWaterDropCount; i++) {
            int off = i * 4;
            Drop drop = mWaterDrops[i];
            d[off]     = drop.x;
            d[off + 1] = drop.y;
            /*
             * 半径与幅度一起乘朝向系数 —— 这样波纹是**整体缩小**，形状不变。
             *
             * 半径（d.w）与幅度（d.z）同乘 f 时，shader 里
             *   amp = z*0.12*dist/(w*w)*sin(w-dist)
             * 在 mesh 距离 f·d 处的值恰好等于原来 d 处的值，即同一张波纹按 f 缩放。
             * 只缩半径不缩幅度的话，幅度会被 1/f² 放大。
             *
             * 位置不能动 —— 它也是 mesh 单位，本来就跟着世界走。
             */
            d[off + 2] = drop.ampE * rippleScale();
            d[off + 3] = drop.spread * rippleScale();
        }

        // Precompute shader parameters
        mSceneData.glHeight = mGlHeight;
        mSceneData.bgScale = mBackgroundScale;
        mSceneData.meshScaleX = (mMeshWidth - 1) * 0.5f;
        mSceneData.meshScaleY = (mMeshHeight - 1) * 0.5f;
        /*
         * 恒为 1。
         *
         * addDrop() 里 dxMul 只进 distance —— 它把 X 方向的度量压扁，两端的
         * `ret.x /= dxMul` 又抵消掉，所以**它只改变波纹的形状，不改变幅度**。
         * 网格两轴已经是等距的（见 createWaterMesh），任何非 1 的值都只会把圆
         * 拉成椭圆。原来横屏取 2.5 是在补旧网格 4.3 倍的各向异性，补不全，
         * 于是横屏波纹一直是扁的。
         *
         * 参数本身留着（JNI 的签名和 SPIR-V 都带着它），只是不再有非 1 的理由。
         */
        mSceneData.dxMul = 1.0f;
        mSceneData.rotate = mRotate;

        mWaterTexCoordsDirty = true;
    }

    // ---- Plugin-aware settings fallback ----

    private boolean isGreenLeaves() {
        if (mPrefs != null) return mPrefs.getBoolean(WallpaperSettings.KEY_FALL_GREEN_LEAVES, false);
        return WallpaperSettings.isGreenLeavesEnabled(false);
    }

    private int getLeafCount() {
        if (mPrefs != null) return mPrefs.getInt(WallpaperSettings.KEY_FALL_LEAF_COUNT, DEFAULT_LEAVES_COUNT);
        return WallpaperSettings.getFallLeafCount(DEFAULT_LEAVES_COUNT);
    }

    private int getMaxDrops() {
        if (mPrefs != null) return mPrefs.getInt(WallpaperSettings.KEY_FALL_MAX_DROPS, DEFAULT_WATER_MESH_DROPS);
        return WallpaperSettings.getFallMaxDrops(DEFAULT_WATER_MESH_DROPS);
    }

    /** 日夜变换开关（默认关：原版是一片固定的黄昏水面）。 */
    private boolean isDayNightEnabled() {
        if (mPrefs != null) return mPrefs.getBoolean(WallpaperSettings.KEY_FALL_DAY_NIGHT, false);
        return WallpaperSettings.isFallDayNightEnabled(false);
    }

    /** 滑动水波纹开关：滑动每 42px 触发一次点击水波纹（默认开启） */
    boolean isSwipeRippleEnabled() {
        if (mPrefs != null) return mPrefs.getBoolean(WallpaperSettings.KEY_FALL_SWIPE_RIPPLE, true);
        return WallpaperSettings.isFallSwipeRippleEnabled(true);
    }
}
