package com.reandroid.wallpaper.weatherwallpapers.ocean;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.Matrix;

import com.reandroid.plugin.VkPass;
import com.reandroid.plugin.VkPassFrame;
import com.reandroid.plugin.VkPassRendererDelegate;
import com.reandroid.plugin.VkPassScene;
import com.reandroid.utils.AssetLoader;
import com.reandroid.wallpaper.weatherwallpapers.CloudRenderer;
import com.reandroid.wallpaper.weatherwallpapers.FogIceRenderer;
import com.reandroid.wallpaper.weatherwallpapers.FrostRenderer;
import com.reandroid.wallpaper.weatherwallpapers.PrecipitationRenderer;
import com.reandroid.wallpaper.weatherwallpapers.SkyRenderer;
import com.reandroid.wallpaper.weatherwallpapers.ThunderRenderer;
import com.reandroid.wallpaper.weatherwallpapers.WeatherStateManager;
import com.reandroid.weather.WeatherCondition;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Ocean Weather 的 VK 渲染器 —— 壁纸与预览共用。
 *
 * <p>GLES 版每帧按固定顺序发出上百个精灵 draw（雪、雨、浪、云各自有自己的贴图帧）。
 * 这里复用同一批 {@code *Renderer} 逻辑类，只把 Drawer 换成顶点批：每张贴图一个
 * pass，同一张贴图的精灵写进同一个顶点缓冲，一次 draw 画完。云的几种绘制序列
 * 按天气分支拆成独立槽位，保证交叉淡入的叠放顺序与 GLES 一致。
 *
 * <p>GLES 的投影、平移、旋转、缩放全部在 CPU 侧按同样的矩阵组合烘进顶点；
 * 顶点着色器只做 GL → Vulkan 的裁剪空间修正（{@link #CLIP_CORRECTION}）。
 * 混合为 ONE / ONE_MINUS_SRC_ALPHA，贴图按 GLES 的 premultiplied 解码语义上传。
 */
final class OceanWeatherVkDelegate extends VkPassRendererDelegate {

    private static final String OCEAN_DRAWABLE = "ocean/drawable/";
    private static final String COMMON_DRAWABLE = "weatherwallpapers/common/drawable/";

    private static final int MAX_WAVES = 32;
    private static final int RAINDROP_FRAMES = 25;

    /** x, y, z, w（GL 裁剪空间） + u, v + r, g, b, a。 */
    private static final int FLOATS_PER_VERTEX = 10;

    private static final int VERTEX_STRIDE_BYTES = FLOATS_PER_VERTEX * 4;
    private static final int VERTICES_PER_SPRITE = 6;
    private static final int PUSH_BYTES = 16 * 4;

    private static final int QUAD_FULL = 0;
    private static final int QUAD_RECT_ONE_TO_TWO = 1;
    private static final int QUAD_RECT_ONE_TO_FOUR = 2;

    /** 与 ocean/data/*.csv 的四个角一致，顺序同 GLES 的 TRIANGLE_STRIP。 */
    private static final float[] QUAD_FULL_XY = {
        -8.0f, 8.0f, -8.0f, -8.0f, 8.0f, 8.0f, 8.0f, -8.0f,
    };

    private static final float[] QUAD_RECT_ONE_TO_TWO_XY = {
        -4.0f, 2.0f, -4.0f, -2.0f, 4.0f, 2.0f, 4.0f, -2.0f,
    };
    private static final float[] QUAD_RECT_ONE_TO_FOUR_XY = {
        -4.0f, 1.0f, -4.0f, -1.0f, 4.0f, 1.0f, 4.0f, -1.0f,
    };

    /** 四个角的 UV，顺序与 quad 顶点一致。 */
    private static final float[] QUAD_U = {0.0f, 0.0f, 1.0f, 1.0f};

    private static final float[] QUAD_V = {0.0f, 1.0f, 0.0f, 1.0f};

    private final boolean mPreview;

    private OceanWeatherScene mScene;
    private VkPassScene mPassScene;
    private boolean mSceneStarted;
    /** 贴图很多（134 张），推迟到渲染线程的第一帧再解码/上传，避免卡住主线程。 */
    private boolean mTexturesUploaded;

    private volatile int mWidth;
    private volatile int mHeight;

    // ---- 与 OceanWeatherGL 相同的逻辑渲染器 ----
    private final SkyRenderer mSkyRenderer = new SkyRenderer();
    private final CloudRenderer mCloudRenderer = new CloudRenderer();
    private final OceanWaveRenderer mWaveRenderer = new OceanWaveRenderer();
    private final OceanWaterSurfaceRenderer mWaterSurfaceRenderer = new OceanWaterSurfaceRenderer();
    private final FogIceRenderer mFogIceRenderer = new FogIceRenderer();
    private final PrecipitationRenderer mPrecipitationRenderer = new PrecipitationRenderer();
    private final ThunderRenderer mThunderRenderer = new ThunderRenderer();
    private final FrostRenderer mFrostRenderer = new FrostRenderer();
    private final DrawerBridge mDrawerBridge = new DrawerBridge();
    private final SkyRenderer.Drawer mSkyDrawer = mDrawerBridge;
    private final CloudRenderer.Drawer mCloudDrawer = mDrawerBridge;
    private final OceanWaveRenderer.Drawer mWaveDrawer = mDrawerBridge;
    private final OceanWaterSurfaceRenderer.Drawer mWaterDrawer = mDrawerBridge;
    private final FogIceRenderer.Drawer mFogIceDrawer = mDrawerBridge;
    private final PrecipitationRenderer.Drawer mPrecipitationDrawer = mDrawerBridge;
    private final ThunderRenderer.Drawer mThunderDrawer = mDrawerBridge;
    private final FrostRenderer.Drawer mFrostDrawer = mDrawerBridge;

    // ---- 贴图 id（1 起；0 表示无效，与 GLES 的 texture == 0 跳过一致）----
    private int mSkyA, mSkyB, mSkyC, mSkyD, mSkyG, mSkyStars;
    private int mCloudA01, mCloudA02, mCloudA03, mCloudB01, mCloudB02, mCloudB03;
    private int mCloudLightA1, mCloudLightA2, mCloudLightA3;
    private int mCloudLightB1, mCloudLightB2, mCloudLightB3;
    private int mSun1, mSun2, mSun3, mSun4, mStar, mMeteor, mMoon;
    private int mWatercover1, mWatercover2, mWatercover3, mWatercover4;
    private int mNightcover, mCapCover;
    private int mFog01, mFog02, mIce;
    private int mRain1, mRain2, mRain3, mRain4, mWaterdrop, mCloudcover;
    private int mFrostC, mFrostE, mFrostF;
    private int mSnow1, mSnow2, mSnow3, mSnow4;
    private int mSkyFlash, mLightning1, mLightning2, mLightning3;
    private int mWaveBack;
    private int[] mWave;
    private int[] mRaindrop1;
    private int[] mRaindrop2;

    /** 云的两套绘制序列共用贴图，但叠放槽位不同，按天气分支各用一组 id。 */
    private int mCloudA01Clear, mCloudA02Clear, mCloudB01Clear, mCloudB02Clear;

    private int mCloudA02Storm, mCloudA03Storm, mCloudB02Storm, mCloudB03Storm;

    // ---- 资源与 pass 槽位 ----
    private final ArrayList<Asset> mAssets = new ArrayList<>();
    private final HashMap<String, Integer> mAssetIds = new HashMap<>();
    private final ArrayList<SlotSpec> mSlotSpecs = new ArrayList<>();
    private final ArrayList<Slot> mSlots = new ArrayList<>();
    private final HashMap<Integer, ArrayList<Slot>> mSlotsByAsset = new HashMap<>();
    private final HashMap<Integer, Integer> mOccurrences = new HashMap<>();

    private final float[] mPush = new float[16];
    private final float[] mModelMatrix = new float[16];
    private final float[] mMvpMatrix = new float[16];

    OceanWeatherVkDelegate(Context context, boolean preview) {
        super(context);
        mPreview = preview;
        initAssetIds();
        buildSlotSpecs();
    }

    @Override
    public String logTag() {
        return "OceanWeatherVK";
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
        if (width > 0 && height > 0) {
            mWidth = width;
            mHeight = height;
        }
        if (mScene == null) {
            mScene = new OceanWeatherScene();
            mSkyRenderer.initMemory();
            mCloudRenderer.initMemory();
            mPrecipitationRenderer.initMemory();
            mThunderRenderer.initMemory();
            mScene.onCreate(context);
        }
    }

    @Override
    public void onSceneOffset(float xOffset) {
        if (mScene != null) {
            // 与 OceanWeatherGL.setOffset() 相同。
            mScene.mOffset = 2.5f * xOffset;
        }
    }

    @Override
    public long createRenderer() {
        // 场景的 start() 放在第一帧（渲染线程），与 GLES 的延迟启动一致；
        // 这里只建 native 资源。
        return super.createRenderer();
    }

    @Override
    public void destroyRenderer() {
        if (mSceneStarted) {
            mScene.stop();
            mSceneStarted = false;
        }
        super.destroyRenderer();
        mPassScene = null;
        mTexturesUploaded = false;
    }

    @Override
    protected void buildScene(VkPassScene scene) {
        mPassScene = scene;
        scene.setClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        System.arraycopy(CLIP_CORRECTION, 0, mPush, 0, 16);

        // 一张贴图一个纹理槽；多个 pass 可以绑定同一个槽（云的几套序列会这样）。
        // 云的分支专用 id 是同一张贴图的别名，只用一份纹理。
        // 像素推迟到第一帧再传（见 updateFrame），这里只建空槽。
        boolean[] created = new boolean[mAssets.size() + 1];
        for (int i = 0; i < mAssets.size(); i++) {
            int ownerId = mAssets.get(i).textureOwnerId;
            if (created[ownerId]) {
                continue;
            }
            created[ownerId] = true;
            scene.createTexture(textureName(ownerId));
        }
        mTexturesUploaded = false;

        mSlots.clear();
        mSlotsByAsset.clear();
        for (int i = 0; i < mSlotSpecs.size(); i++) {
            SlotSpec spec = mSlotSpecs.get(i);
            String bufferName = "ocean_buf_" + i;
            int maxVertices = spec.capacity * VERTICES_PER_SPRITE;
            scene.createBuffer(bufferName, VERTEX_STRIDE_BYTES, maxVertices, true);

            ByteBuffer buffer = ByteBuffer.allocateDirect(maxVertices * VERTEX_STRIDE_BYTES)
                    .order(ByteOrder.nativeOrder());

            VkPass pass = scene.add(new VkPass(
                            "shaders/ocean_vk.vert.spv",
                            "shaders/ocean_vk.frag.spv",
                            new int[] {4, 2, 4},
                            VERTEX_STRIDE_BYTES,
                            VkPass.TOPOLOGY_TRIANGLE_LIST,
                            VkPass.BLEND_PREMULTIPLIED))
                    .buffer(bufferName, 0)
                    .pushConstants(PUSH_BYTES)
                    // GLES 的 glBlendFunc(ONE, ONE_MINUS_SRC_ALPHA) 对颜色和 alpha 都生效。
                    .blendFactors(
                            VkPass.FACTOR_ONE, VkPass.FACTOR_ONE_MINUS_SRC_ALPHA,
                            VkPass.FACTOR_ONE, VkPass.FACTOR_ONE_MINUS_SRC_ALPHA);
            pass.texture(0, textureName(spec.assetId));

            Slot slot = new Slot(spec.assetId, spec.capacity, bufferName, buffer, pass);
            mSlots.add(slot);
            ArrayList<Slot> slots = mSlotsByAsset.get(spec.assetId);
            if (slots == null) {
                slots = new ArrayList<>();
                mSlotsByAsset.put(spec.assetId, slots);
            }
            slots.add(slot);
        }
    }

    @Override
    protected void updateFrame(VkPassFrame frame, long timeMs) {
        if (mScene == null || mWidth <= 0 || mHeight <= 0) {
            return;
        }

        if (!mSceneStarted) {
            mScene.start(mPreview);
            mSceneStarted = true;
        }

        // GLES 在第一次 draw 时才解码/上传全部贴图（渲染线程）。这里同样推迟：
        // buildScene 可能由主线程触发，134 张图在那里解码会卡住界面。
        if (!mTexturesUploaded) {
            uploadAllTextures();
            mTexturesUploaded = true;
        }

        // GLES 只在 resize/init 时算投影；这里每帧重算一次，结果相同，
        // 且避免主线程 resize 与渲染线程读矩阵的竞争。
        mScene.updateProjection(mWidth, mHeight);

        long deltaMs = mScene.mAnimationController.updateDelta(timeMs);

        WeatherStateManager wsm = mScene.mWeatherStateManager;
        if (wsm != null) {
            wsm.update(timeMs, mPreview);
            mScene.mCondition = wsm.getCondition();
            mScene.mNightWeight = wsm.nightWeight();
        }

        float frameDuration = wsm != null && wsm.shouldFastAnimate() ? 16.666f : 40.0f;
        mScene.mAnimationController.setFrameDurationMs(frameDuration);
        mScene.mAnimationController.advanceFrame(deltaMs);
        mScene.mWeatherFlagManager.update(mScene.mCondition);

        if (mPreview) {
            mScene.mOffset = 1.25f;
        }

        resetSlots();
        drawObjects();
        flushSlots(frame);
    }

    // ---- 与 OceanWeatherGL.drawObjects() 相同的绘制顺序 ----

    private void drawObjects() {
        int frameCnt = mScene.mAnimationController.getFrameCount();
        WeatherCondition condition = mScene.mCondition;
        float nightWeight = mScene.mNightWeight;
        float offset = mScene.mOffset;
        float landscape = mScene.mLandscape;
        float fillScaleY = mScene.mFillScaleY;

        boolean clearOn = mScene.mWeatherFlagManager.isClearOn();
        boolean rainOn = mScene.mWeatherFlagManager.isRainOn();
        boolean snowOn = mScene.mWeatherFlagManager.isSnowOn();
        boolean thunderOn = mScene.mWeatherFlagManager.isThunderOn();

        clearOn = mSkyRenderer.drawSkyAndCelestial(
                mSkyDrawer,
                condition,
                nightWeight,
                frameCnt,
                offset,
                landscape,
                fillScaleY,
                mSkyA,
                mSkyB,
                mSkyC,
                mSkyD,
                mSkyG,
                mSkyStars,
                mSun1,
                mSun2,
                mSun3,
                mMoon,
                mStar,
                mMeteor,
                clearOn);

        prepareCloudIds(condition, nightWeight);
        thunderOn = mCloudRenderer.drawClouds(
                mCloudDrawer,
                condition,
                nightWeight,
                frameCnt,
                offset,
                landscape,
                mCloudA01,
                mCloudA02,
                mCloudA03,
                mCloudB01,
                mCloudB02,
                mCloudB03,
                mCloudLightA1,
                mCloudLightA2,
                mCloudLightA3,
                mCloudLightB1,
                mCloudLightB2,
                mCloudLightB3,
                thunderOn);

        mWaveRenderer.drawWaves(mWaveDrawer, frameCnt, offset, landscape, mWaveBack, mWave);

        clearOn = mSkyRenderer.drawSunlight(
                mSkyDrawer, condition, nightWeight, frameCnt, offset, landscape, mSun4, clearOn);

        mWaterSurfaceRenderer.drawWaterCover(
                mWaterDrawer,
                condition,
                nightWeight,
                offset,
                landscape,
                mWatercover1,
                mWatercover2,
                mWatercover3,
                mWatercover4,
                mNightcover,
                mCapCover);

        mFogIceRenderer.drawFogIce(
                mFogIceDrawer,
                condition,
                nightWeight,
                landscape,
                mFog01,
                mFog02,
                mIce,
                FogIceRenderer.Config.OCEAN);

        rainOn = mPrecipitationRenderer.drawRain(
                mPrecipitationDrawer,
                condition,
                frameCnt,
                landscape,
                fillScaleY,
                mRain1,
                mRain2,
                mRain3,
                mRain4,
                mCloudcover,
                mWaterdrop,
                mRaindrop1,
                mRaindrop2,
                rainOn);

        mFrostRenderer.drawFrost(mFrostDrawer, condition, landscape, mFrostC, mFrostE, mFrostF);

        snowOn = mPrecipitationRenderer.drawSnow(
                mPrecipitationDrawer,
                condition,
                frameCnt,
                offset,
                landscape,
                mSnow1,
                mSnow2,
                mSnow3,
                mSnow4,
                snowOn);

        thunderOn = mThunderRenderer.drawThunder(
                mThunderDrawer,
                condition,
                frameCnt,
                offset,
                landscape,
                mSkyFlash,
                mLightning1,
                mLightning2,
                mLightning3,
                thunderOn);

        mScene.mWeatherFlagManager.setClearOn(clearOn);
        mScene.mWeatherFlagManager.setRainOn(rainOn);
        mScene.mWeatherFlagManager.setSnowOn(snowOn);
        mScene.mWeatherFlagManager.setThunderOn(thunderOn);
    }

    /**
     * 云的两套序列共用同一批贴图，但槽位顺序不同：白天是 A/A/B/B/B/A，
     * 阴雨是 A/A/A/B×7。这里按 GLES 的分支条件选对应的贴图 id。
     */
    private void prepareCloudIds(WeatherCondition condition, float nightWeight) {
        boolean clearBranch = (condition == WeatherCondition.D1_CLEAR
                        || condition == WeatherCondition.D8_ICE_COLD)
                && nightWeight < 1.0f;
        if (clearBranch) {
            mCloudA01 = mCloudA01Clear;
            mCloudA02 = mCloudA02Clear;
            mCloudA03 = 0;
            mCloudB01 = mCloudB01Clear;
            mCloudB02 = mCloudB02Clear;
            mCloudB03 = 0;
        } else {
            mCloudA01 = 0;
            mCloudA02 = mCloudA02Storm;
            mCloudA03 = mCloudA03Storm;
            mCloudB01 = 0;
            mCloudB02 = mCloudB02Storm;
            mCloudB03 = mCloudB03Storm;
        }
    }

    // ---- Drawer 桥接 ----

    private final class DrawerBridge
            implements SkyRenderer.Drawer,
                    CloudRenderer.Drawer,
                    OceanWaveRenderer.Drawer,
                    OceanWaterSurfaceRenderer.Drawer,
                    FogIceRenderer.Drawer,
                    PrecipitationRenderer.Drawer,
                    ThunderRenderer.Drawer,
                    FrostRenderer.Drawer {
        @Override
        public void drawSprite(
                int t, float x, float y, float z, float sx, float sy, float r, float a) {
            addSprite(t, x, y, z, sx, sy, r, a, QUAD_FULL);
        }

        @Override
        public void drawSpriteRectOneToTwo(
                int t, float x, float y, float z, float sx, float sy, float r, float a) {
            addSprite(t, x, y, z, sx, sy, r, a, QUAD_RECT_ONE_TO_TWO);
        }

        @Override
        public void drawSpriteRectOneToFour(
                int t, float x, float y, float z, float sx, float sy, float r, float a) {
            addSprite(t, x, y, z, sx, sy, r, a, QUAD_RECT_ONE_TO_FOUR);
        }

        @Override
        public void drawSpriteColoredRectOneToTwo(
                int t,
                float x,
                float y,
                float z,
                float sx,
                float sy,
                float r,
                float cr,
                float cg,
                float cb,
                float a) {
            addSprite(t, x, y, z, sx, sy, r, cr, cg, cb, a, QUAD_RECT_ONE_TO_TWO);
        }
    }

    private void addSprite(
            int assetId,
            float x,
            float y,
            float z,
            float scaleX,
            float scaleY,
            float rotation,
            float alpha,
            int quadType) {
        addSprite(assetId, x, y, z, scaleX, scaleY, rotation, alpha, alpha, alpha, alpha, quadType);
    }

    private void addSprite(
            int assetId,
            float x,
            float y,
            float z,
            float scaleX,
            float scaleY,
            float rotation,
            float r,
            float g,
            float b,
            float a,
            int quadType) {
        if (assetId <= 0) {
            return;
        }
        ArrayList<Slot> slots = mSlotsByAsset.get(assetId);
        if (slots == null || slots.isEmpty()) {
            return;
        }

        Slot slot;
        if (slots.size() == 1) {
            slot = slots.get(0);
        } else {
            // 同一张贴图在同一条绘制序列里多次出现（云），按出现次序落到相邻槽位。
            Integer boxed = mOccurrences.get(assetId);
            int occurrence = boxed == null ? 0 : boxed;
            slot = slots.get(Math.min(occurrence, slots.size() - 1));
            mOccurrences.put(assetId, occurrence + 1);
        }

        if (slot.count >= slot.capacity) {
            return;
        }

        float[] quad;
        if (quadType == QUAD_RECT_ONE_TO_TWO) {
            quad = QUAD_RECT_ONE_TO_TWO_XY;
        } else if (quadType == QUAD_RECT_ONE_TO_FOUR) {
            quad = QUAD_RECT_ONE_TO_FOUR_XY;
        } else {
            quad = QUAD_FULL_XY;
        }

        // GLES: translate(x, y, z) * rotateZ * scale(sx, sy, 1)，再乘投影。
        Matrix.setIdentityM(mModelMatrix, 0);
        Matrix.translateM(mModelMatrix, 0, x, y, z);
        Matrix.rotateM(mModelMatrix, 0, rotation, 0.0f, 0.0f, 1.0f);
        Matrix.scaleM(mModelMatrix, 0, scaleX, scaleY, 1.0f);
        Matrix.multiplyMM(mMvpMatrix, 0, mScene.mProjectionMatrix, 0, mModelMatrix, 0);

        ByteBuffer buffer = slot.buffer;
        int floatIndex = slot.count * VERTICES_PER_SPRITE * FLOATS_PER_VERTEX;
        // TRIANGLE_STRIP 的 0,1,2,3 展开成两个三角形：0,1,2 与 2,1,3。
        floatIndex =
                putVertex(buffer, floatIndex, quad[0], quad[1], QUAD_U[0], QUAD_V[0], r, g, b, a);
        floatIndex =
                putVertex(buffer, floatIndex, quad[2], quad[3], QUAD_U[1], QUAD_V[1], r, g, b, a);
        floatIndex =
                putVertex(buffer, floatIndex, quad[4], quad[5], QUAD_U[2], QUAD_V[2], r, g, b, a);
        floatIndex =
                putVertex(buffer, floatIndex, quad[4], quad[5], QUAD_U[2], QUAD_V[2], r, g, b, a);
        floatIndex =
                putVertex(buffer, floatIndex, quad[2], quad[3], QUAD_U[1], QUAD_V[1], r, g, b, a);
        putVertex(buffer, floatIndex, quad[6], quad[7], QUAD_U[3], QUAD_V[3], r, g, b, a);
        slot.count++;
    }

    private int putVertex(
            ByteBuffer buffer,
            int floatIndex,
            float localX,
            float localY,
            float u,
            float v,
            float r,
            float g,
            float b,
            float a) {
        float[] m = mMvpMatrix;
        // 局部 z = 0，所以只取第 0、1、3 列与平移。
        float x = m[0] * localX + m[4] * localY + m[12];
        float y = m[1] * localX + m[5] * localY + m[13];
        float z = m[2] * localX + m[6] * localY + m[14];
        float w = m[3] * localX + m[7] * localY + m[15];

        int base = floatIndex * 4;
        buffer.putFloat(base, x);
        buffer.putFloat(base + 4, y);
        buffer.putFloat(base + 8, z);
        buffer.putFloat(base + 12, w);
        buffer.putFloat(base + 16, u);
        buffer.putFloat(base + 20, v);
        buffer.putFloat(base + 24, r);
        buffer.putFloat(base + 28, g);
        buffer.putFloat(base + 32, b);
        buffer.putFloat(base + 36, a);
        return floatIndex + FLOATS_PER_VERTEX;
    }

    private void resetSlots() {
        for (int i = 0; i < mSlots.size(); i++) {
            mSlots.get(i).count = 0;
        }
        mOccurrences.clear();
    }

    private void flushSlots(VkPassFrame frame) {
        for (int i = 0; i < mSlots.size(); i++) {
            Slot slot = mSlots.get(i);
            int vertexCount = slot.count * VERTICES_PER_SPRITE;
            frame.setVertexCount(slot.pass, vertexCount);
            if (vertexCount > 0) {
                slot.buffer.position(0);
                frame.upload(slot.bufferName, slot.buffer, vertexCount * VERTEX_STRIDE_BYTES);
            }
            frame.pushConstants(slot.pass, mPush);
        }
    }

    // ---- 资源声明 ----

    private int asset(String path) {
        Integer existing = mAssetIds.get(path);
        if (existing != null) {
            return existing;
        }
        int id = mAssets.size() + 1;
        mAssets.add(new Asset(path, id));
        mAssetIds.put(path, id);
        return id;
    }

    /**
     * 同一张贴图再要一个逻辑 id（用于云的晴天/阴雨两套槽位序列）。
     * 纹理仍然共用，只有 pass 槽位不同。
     */
    private int aliasAsset(String path) {
        int owner = asset(path);
        mAssets.add(new Asset(path, owner));
        return mAssets.size();
    }

    private String textureName(int assetId) {
        return "ocean_tex_" + mAssets.get(assetId - 1).textureOwnerId;
    }

    /** 与 GLES loadTexture() 的调用表一致。 */
    private void initAssetIds() {
        mSkyA = asset(OCEAN_DRAWABLE + "a_sky.jpg");
        mSkyB = asset(OCEAN_DRAWABLE + "b_sky.jpg");
        mSkyC = asset(OCEAN_DRAWABLE + "c_sky.jpg");
        mSkyD = asset(OCEAN_DRAWABLE + "d_sky.png");
        mSkyG = asset(OCEAN_DRAWABLE + "g_sky.jpg");
        mSkyStars = asset(COMMON_DRAWABLE + "d_sky_stars.png");

        mCloudA01 = asset(COMMON_DRAWABLE + "cloud_a_01.png");
        mCloudA02 = asset(COMMON_DRAWABLE + "cloud_a_02.png");
        mCloudA03 = asset(COMMON_DRAWABLE + "cloud_a_03.png");
        mCloudB01 = asset(COMMON_DRAWABLE + "cloud_b_01.png");
        mCloudB02 = asset(COMMON_DRAWABLE + "cloud_b_02.png");
        mCloudB03 = asset(COMMON_DRAWABLE + "cloud_b_03.png");
        mCloudLightA1 = asset(COMMON_DRAWABLE + "cloud_a_light_01.png");
        mCloudLightA2 = asset(COMMON_DRAWABLE + "cloud_a_light_02.png");
        mCloudLightA3 = asset(COMMON_DRAWABLE + "cloud_a_light_03.png");
        mCloudLightB1 = asset(COMMON_DRAWABLE + "cloud_b_light_01.png");
        mCloudLightB2 = asset(COMMON_DRAWABLE + "cloud_b_light_02.png");
        mCloudLightB3 = asset(COMMON_DRAWABLE + "cloud_b_light_03.png");

        mSun1 = asset(COMMON_DRAWABLE + "a_sun_01.png");
        mSun2 = asset(COMMON_DRAWABLE + "a_sun_02.png");
        mSun3 = asset(COMMON_DRAWABLE + "a_sun_03.png");
        mSun4 = asset(OCEAN_DRAWABLE + "ocean_a_sun_04.png");
        mStar = asset(COMMON_DRAWABLE + "d_star.png");
        mMeteor = asset(OCEAN_DRAWABLE + "ocean_d_meteor.png");
        mMoon = asset(OCEAN_DRAWABLE + "ocean_d_moon.png");

        mWatercover1 = asset(OCEAN_DRAWABLE + "a_watercover_01.png");
        mWatercover2 = asset(OCEAN_DRAWABLE + "a_watercover_02.png");
        mWatercover3 = asset(OCEAN_DRAWABLE + "a_watercover_03.png");
        mWatercover4 = asset(OCEAN_DRAWABLE + "a_watercover_04.png");
        mNightcover = asset(COMMON_DRAWABLE + "nightcover_01.png");
        mCapCover = asset(OCEAN_DRAWABLE + "a_cap_01.png");

        mFog01 = asset(OCEAN_DRAWABLE + "fog_01.png");
        mFog02 = asset(COMMON_DRAWABLE + "fog_02.png");
        mIce = asset(COMMON_DRAWABLE + "ice.png");

        mRain1 = asset(COMMON_DRAWABLE + "c_rain_01.png");
        mRain2 = asset(COMMON_DRAWABLE + "c_rain_02.png");
        mRain3 = asset(COMMON_DRAWABLE + "c_rain_03.png");
        mRain4 = asset(COMMON_DRAWABLE + "c_rain_04.png");
        mWaterdrop = asset(COMMON_DRAWABLE + "c_waterdrop.png");
        mCloudcover = asset(OCEAN_DRAWABLE + "ocean_c_cloudcover.png");

        mFrostC = asset(OCEAN_DRAWABLE + "c_frost.png");
        mFrostE = asset(COMMON_DRAWABLE + "e_frost.png");
        mFrostF = asset(COMMON_DRAWABLE + "f_frost.png");

        mSnow1 = asset(COMMON_DRAWABLE + "e_snow_01.png");
        mSnow2 = asset(COMMON_DRAWABLE + "e_snow_02.png");
        mSnow3 = asset(COMMON_DRAWABLE + "e_snow_03.png");
        mSnow4 = asset(COMMON_DRAWABLE + "e_snow_04.png");

        mSkyFlash = asset(COMMON_DRAWABLE + "g_sky_flash.png");
        mLightning1 = asset(COMMON_DRAWABLE + "g_lightning_01.png");
        mLightning2 = asset(COMMON_DRAWABLE + "g_lightning_02.png");
        mLightning3 = asset(COMMON_DRAWABLE + "g_lightning_03.png");

        mWaveBack = asset(OCEAN_DRAWABLE + "sand.png");
        mWave = new int[MAX_WAVES];
        for (int i = 0; i < MAX_WAVES; i++) {
            mWave[i] = asset(String.format(OCEAN_DRAWABLE + "sea_a_%02d.png", i + 1));
        }

        mRaindrop1 = new int[RAINDROP_FRAMES];
        mRaindrop2 = new int[RAINDROP_FRAMES];
        for (int i = 0; i < RAINDROP_FRAMES; i++) {
            mRaindrop1[i] = asset(String.format(COMMON_DRAWABLE + "waterdrop_a_%d.png", i));
            mRaindrop2[i] = asset(String.format(COMMON_DRAWABLE + "waterdrop_b_%d.png", i));
        }

        // 云的分支专用 id：贴图相同，槽位序列不同。
        mCloudA01Clear = aliasAsset(COMMON_DRAWABLE + "cloud_a_01.png");
        mCloudA02Clear = aliasAsset(COMMON_DRAWABLE + "cloud_a_02.png");
        mCloudB01Clear = aliasAsset(COMMON_DRAWABLE + "cloud_b_01.png");
        mCloudB02Clear = aliasAsset(COMMON_DRAWABLE + "cloud_b_02.png");
        mCloudA02Storm = aliasAsset(COMMON_DRAWABLE + "cloud_a_02.png");
        mCloudA03Storm = aliasAsset(COMMON_DRAWABLE + "cloud_a_03.png");
        mCloudB02Storm = aliasAsset(COMMON_DRAWABLE + "cloud_b_02.png");
        mCloudB03Storm = aliasAsset(COMMON_DRAWABLE + "cloud_b_03.png");

        // 清空构造期使用的默认值；每帧由 prepareCloudIds() 重新选。
        mCloudA01 = 0;
        mCloudA02 = 0;
        mCloudA03 = 0;
        mCloudB01 = 0;
        mCloudB02 = 0;
        mCloudB03 = 0;
    }

    /**
     * 槽位声明顺序 = GLES 的绘制顺序。一张贴图出现多次且中间夹着别的贴图时
     * （只有云会这样），就声明多个槽位并按出现次序分配。
     */
    private void buildSlotSpecs() {
        // 天空：白天贴图先画，夜里那张（skyD）后画；白天不会用 skyD。
        addSlot(mSkyA);
        addSlot(mSkyB);
        addSlot(mSkyC);
        addSlot(mSkyG);
        addSlot(mSkyD);

        addSlot(mSkyStars);

        addSlot(mSun1);
        addSlot(mSun2);
        addSlot(mSun3);

        addSlot(mMoon);
        addSlot(mStar, 7);
        addSlot(mMeteor);

        // 晴天/冰寒分支：A, A, B, B, B, A。
        addCloudSequence(mCloudA01Clear, mCloudB01Clear);
        // 冰寒用 A02/B02，但走同一套晴天序列。
        addCloudSequence(mCloudA02Clear, mCloudB02Clear);
        // 阴雨分支：A×3, B×7。
        addStormCloudSequence(mCloudA02Storm, mCloudB02Storm);
        addStormCloudSequence(mCloudA03Storm, mCloudB03Storm);

        addSlot(mCloudLightA1, 20);
        addSlot(mCloudLightA2, 20);
        addSlot(mCloudLightA3, 20);
        addSlot(mCloudLightB1, 20);
        addSlot(mCloudLightB2, 20);
        addSlot(mCloudLightB3, 20);

        addSlot(mWaveBack);
        for (int i = 0; i < MAX_WAVES; i++) {
            // 交叉淡入时同一帧可能被画两次，留两个位置。
            addSlot(mWave[i], 2);
        }

        addSlot(mSun4);

        addSlot(mWatercover2);
        addSlot(mWatercover3);
        addSlot(mWatercover4);
        addSlot(mWatercover1);
        addSlot(mCapCover);
        addSlot(mNightcover);

        addSlot(mFog01);
        addSlot(mFog02);
        addSlot(mIce);

        addSlot(mRain1);
        addSlot(mRain2);
        addSlot(mRain3);
        addSlot(mRain4);
        addSlot(mCloudcover);
        addSlot(mWaterdrop);

        for (int i = 0; i < RAINDROP_FRAMES; i++) {
            addSlot(mRaindrop1[i], 8);
        }
        for (int i = 0; i < RAINDROP_FRAMES; i++) {
            addSlot(mRaindrop2[i], 8);
        }

        addSlot(mFrostC);
        addSlot(mFrostE);
        addSlot(mFrostF);

        addSlot(mSnow1, 10);
        addSlot(mSnow2, 150);
        addSlot(mSnow4, 40);
        addSlot(mSnow3, 400);

        addSlot(mSkyFlash, 40);
        addSlot(mLightning1, 40);
        addSlot(mLightning2, 40);
        addSlot(mLightning3, 40);
    }

    private void addCloudSequence(int cloudA, int cloudB) {
        addSlot(cloudA, 2);
        addSlot(cloudA, 2);
        addSlot(cloudB, 2);
        addSlot(cloudB, 2);
        addSlot(cloudB, 2);
        addSlot(cloudA, 2);
    }

    private void addStormCloudSequence(int cloudA, int cloudB) {
        addSlot(cloudA, 2);
        addSlot(cloudA, 2);
        addSlot(cloudA, 2);
        for (int i = 0; i < 7; i++) {
            addSlot(cloudB, 2);
        }
    }

    private void addSlot(int assetId) {
        addSlot(assetId, 1);
    }

    private void addSlot(int assetId, int capacity) {
        mSlotSpecs.add(new SlotSpec(assetId, capacity));
    }

    // ---- 纹理上传 ----

    private void uploadAllTextures() {
        if (mPassScene == null) {
            return;
        }
        boolean[] uploaded = new boolean[mAssets.size() + 1];
        for (int i = 0; i < mAssets.size(); i++) {
            Asset asset = mAssets.get(i);
            if (uploaded[asset.textureOwnerId]) {
                continue;
            }
            uploaded[asset.textureOwnerId] = true;
            uploadTexture(mPassScene, textureName(asset.textureOwnerId), asset.path);
        }
    }

    /**
     * GLES 用 {@code inPremultiplied = true} 解码后直接上传；VK 的通用上传接口收的是
     * {@code getPixels()} 的直通 ARGB，所以这里补上同一次预乘。
     */
    private void uploadTexture(VkPassScene scene, String name, String path) {
        Bitmap bitmap = AssetLoader.decodeBitmap(context, path);
        if (bitmap == null) {
            return;
        }
        Bitmap argb = bitmap.getConfig() == Bitmap.Config.ARGB_8888
                ? bitmap
                : bitmap.copy(Bitmap.Config.ARGB_8888, false);
        if (argb != bitmap) {
            bitmap.recycle();
        }
        if (argb == null) {
            return;
        }

        int width = argb.getWidth();
        int height = argb.getHeight();
        int[] pixels = new int[width * height];
        argb.getPixels(pixels, 0, width, 0, 0, width, height);
        argb.recycle();

        premultiply(pixels);
        scene.setTexture(name, pixels, width, height);
    }

    private static void premultiply(int[] pixels) {
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            int alpha = (pixel >>> 24) & 0xff;
            if (alpha == 255) {
                continue;
            }
            if (alpha == 0) {
                pixels[i] = 0;
                continue;
            }
            int r = (pixel >> 16) & 0xff;
            int g = (pixel >> 8) & 0xff;
            int b = pixel & 0xff;
            pixels[i] = (alpha << 24)
                    | (mulDiv255Round(r, alpha) << 16)
                    | (mulDiv255Round(g, alpha) << 8)
                    | mulDiv255Round(b, alpha);
        }
    }

    /** 与 Android 预乘位图的 SkMulDiv255Round 取整一致。 */
    private static int mulDiv255Round(int color, int alpha) {
        int product = color * alpha + 128;
        return (product + (product >> 8)) >> 8;
    }

    // ---- 内部结构 ----

    private static final class Asset {
        final String path;
        /** 真正持有纹理上传的 id；别名指向原 id。 */
        final int textureOwnerId;

        Asset(String path, int textureOwnerId) {
            this.path = path;
            this.textureOwnerId = textureOwnerId;
        }
    }

    private static final class SlotSpec {
        final int assetId;
        final int capacity;

        SlotSpec(int assetId, int capacity) {
            this.assetId = assetId;
            this.capacity = capacity;
        }
    }

    private static final class Slot {
        final int assetId;
        final int capacity;
        final String bufferName;
        final ByteBuffer buffer;
        final VkPass pass;
        int count;

        Slot(int assetId, int capacity, String bufferName, ByteBuffer buffer, VkPass pass) {
            this.assetId = assetId;
            this.capacity = capacity;
            this.bufferName = bufferName;
            this.buffer = buffer;
            this.pass = pass;
        }
    }

    /** GL 裁剪空间 → Vulkan 裁剪空间（y 翻转、z 从 [-w, w] 映射到 [0, w]）。与其它 VK 壁纸一致。 */
    private static final float[] CLIP_CORRECTION = {
        1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 0.5f, 0, 0, 0, 0.5f, 1,
    };
}
