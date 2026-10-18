package com.reandroid.wallpaper.aurora2;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.view.MotionEvent;

import com.reandroid.plugin.VkPass;
import com.reandroid.plugin.VkPassFrame;
import com.reandroid.plugin.VkPassRendererDelegate;
import com.reandroid.plugin.VkPassScene;
import com.reandroid.utils.AssetLoader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Aurora 2 的 VK 渲染器 —— 壁纸与预览共用。
 *
 * <p>GLES 版每层一次 draw，层与层之间换 glBlendFunc；这里把每张贴图预声明成一个 pass，
 * pass 的声明顺序就是 GLES 的绘制顺序。极光、背景、闪耀星这几层会读写目标 alpha，
 * 所以 draw 顺序和 alpha 通道的混合因子都不能省。
 *
 * <p>顶点在 Java 侧按 GLES 的正交投影烘焙成 GL NDC，顶点着色器只做 CLIP_CORRECTION
 * 的 y 翻转与 z 重映射。贴图在 buildScene 里一次性解码 —— 此时 native 设备还没创建，
 * setTexture 只把像素挂进 pending，surface 创建时会一次性上传。
 *
 * <p>流星有 52 帧独立贴图，GLES 每帧绑一张。通用 pass 渲染器的描述符池按 pass 分配，
 * 上限 64 个 set，52 个流星 pass 会挤掉后面的树和淡入淡出层；这里把那 52 帧在 CPU 侧
 * 拼成一张图集，仍然用一个 pass 画，采样结果与原贴图一致（每格四周复制 1px 边，
 * 线性过滤在格子边缘不会采到邻帧）。
 */
final class Aurora2VkDelegate extends VkPassRendererDelegate {

    private static final int FLOATS_PER_VERTEX = 5; // x, y, u, v, alpha
    private static final int VERTEX_BYTES = FLOATS_PER_VERTEX * 4;
    private static final int PUSH_BYTES = 16 * 4;

    /** 与 Aurora2GL 一致：40 段、每段左右两个顶点。 */
    private static final int AURORA_STRIPS = 40;
    private static final int AURORA_VERTICES = AURORA_STRIPS * 2;

    /** 与 Aurora2Scene 的两组星数量一致（那边是 private，这里按容量兜底）。 */
    private static final int STAR_GROUP_1_COUNT = 50;
    private static final int STAR_GROUP_2_COUNT = 30;

    private static final int STRIP_QUAD_VERTICES = 4;
    private static final int LIST_QUAD_VERTICES = 6;

    /** 与 Aurora2Scene 的横竖屏逻辑尺寸一致（那边是 private）。 */
    private static final float LANDSCAPE_WIDTH = 1280.0f;
    private static final float LANDSCAPE_HEIGHT = 800.0f;
    private static final float PORTRAIT_WIDTH = 800.0f;
    private static final float PORTRAIT_HEIGHT = 1280.0f;

    /** 52 帧 × (256+2) × (128+2) 排进 7×8 格，尺寸远低于 Vulkan 的 4096 上限。 */
    private static final int ATLAS_COLUMNS = 7;
    private static final int ATLAS_ROWS = 8;
    private static final int ATLAS_BORDER = 1;

    private static final String BUFFER_SHINE = "shine";
    private static final String BUFFER_AURORA_MAIN = "aurora_main";
    private static final String BUFFER_AURORA_FADE = "aurora_fade";
    private static final String BUFFER_BACKGROUND = "background";
    private static final String BUFFER_BACKGROUND_FADE = "background_fade";
    private static final String BUFFER_STARS1 = "stars1";
    private static final String BUFFER_STARS2 = "stars2";
    private static final String BUFFER_SHOOTING = "shooting";
    private static final String BUFFER_TREE = "tree";

    private static final String SHOOTING_ATLAS_TEXTURE = "tex_shooting_atlas";

    /** GLES: glBlendFunc(SRC_ALPHA, ZERO) —— shine 覆盖写入。 */
    private static final int[] BLEND_OVERWRITE = {
            VkPass.FACTOR_SRC_ALPHA, VkPass.FACTOR_ZERO,
            VkPass.FACTOR_SRC_ALPHA, VkPass.FACTOR_ZERO,
    };
    /** GLES: glBlendFunc(SRC_ALPHA, SRC_ALPHA) —— 极光，外区域压黑。 */
    private static final int[] BLEND_AURORA = {
            VkPass.FACTOR_SRC_ALPHA, VkPass.FACTOR_SRC_ALPHA,
            VkPass.FACTOR_SRC_ALPHA, VkPass.FACTOR_SRC_ALPHA,
    };
    /** GLES: glBlendFunc(SRC_ALPHA, DST_ALPHA) —— 背景加色叠在极光之上。 */
    private static final int[] BLEND_BACKGROUND = {
            VkPass.FACTOR_SRC_ALPHA, VkPass.FACTOR_DST_ALPHA,
            VkPass.FACTOR_SRC_ALPHA, VkPass.FACTOR_DST_ALPHA,
    };
    /** GLES: glBlendFunc(SRC_ALPHA, ONE_MINUS_SRC_ALPHA)。 */
    private static final int[] BLEND_STANDARD = {
            VkPass.FACTOR_SRC_ALPHA, VkPass.FACTOR_ONE_MINUS_SRC_ALPHA,
            VkPass.FACTOR_SRC_ALPHA, VkPass.FACTOR_ONE_MINUS_SRC_ALPHA,
    };

    private static final int[] AURORA_TEXTURE_IDS = {
            Aurora2Scene.TEXTURE_AURORA_01,
            Aurora2Scene.TEXTURE_AURORA_02,
            Aurora2Scene.TEXTURE_AURORA_03,
            Aurora2Scene.TEXTURE_AURORA_04,
            Aurora2Scene.TEXTURE_AURORA_01_WSVGA,
            Aurora2Scene.TEXTURE_AURORA_03_WSVGA,
    };

    private static final int[] BACKGROUND_TEXTURE_IDS = {
            Aurora2Scene.TEXTURE_BG_01,
            Aurora2Scene.TEXTURE_BG_02,
            Aurora2Scene.TEXTURE_BG_03,
            Aurora2Scene.TEXTURE_BG_04,
    };

    private final SharedPreferences mPrefs;
    private final boolean mPreview;

    private final ArrayList<VkPass> mAllPasses = new ArrayList<>();
    private final boolean[] mTextureReady = new boolean[Aurora2Scene.TEXTURE_COUNT];

    private Aurora2Scene mScene;
    private VkPassScene mPassScene;
    private boolean mVertical;

    private VkPass mShinePass;
    private VkPass mStars1Pass;
    private VkPass mStars2Pass;
    private VkPass mShootingPass;
    private VkPass mTreePass;
    private final VkPass[] mAuroraMainPass = new VkPass[Aurora2Scene.TEXTURE_COUNT];
    private final VkPass[] mAuroraFadePass = new VkPass[Aurora2Scene.TEXTURE_COUNT];
    private final VkPass[] mBackgroundMainPass = new VkPass[Aurora2Scene.TEXTURE_COUNT];
    private final VkPass[] mBackgroundFadePass = new VkPass[Aurora2Scene.TEXTURE_COUNT];

    private ByteBuffer mShineBuffer;
    private ByteBuffer mAuroraMainBuffer;
    private ByteBuffer mAuroraFadeBuffer;
    private ByteBuffer mBackgroundBuffer;
    private ByteBuffer mBackgroundFadeBuffer;
    private ByteBuffer mStars1Buffer;
    private ByteBuffer mStars2Buffer;
    private ByteBuffer mShootingBuffer;
    private ByteBuffer mTreeBuffer;

    private boolean mShootingAtlasReady;
    private int mAtlasWidth;
    private int mAtlasHeight;
    private int mAtlasCellWidth;
    private int mAtlasCellHeight;
    private int mAtlasFrameWidth;
    private int mAtlasFrameHeight;

    private final float[] mPush = new float[16];

    Aurora2VkDelegate(Context context, SharedPreferences prefs, boolean preview) {
        super(context);
        mPrefs = prefs;
        mPreview = preview;
    }

    @Override
    public String logTag() {
        return "Aurora2VK";
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
        mVertical = width < height;
        if (mScene == null) {
            // 与 Aurora2GL 的构造顺序一致：先按非预览建，再把预览标记推给场景。
            mScene = new Aurora2Scene(width, height, false);
            mScene.setPreview(mPreview);
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
    public void onSceneOffset(float xOffset) {
        if (mScene != null) mScene.setOffset(xOffset, 0.0f);
    }

    @Override
    public void onTouchEvent(MotionEvent event) {
        if (mScene != null) mScene.onTouchEvent(event);
    }

    @Override
    protected void buildScene(VkPassScene scene) {
        mPassScene = scene;
        mAllPasses.clear();
        Arrays.fill(mTextureReady, false);
        mShootingAtlasReady = false;
        scene.setClearColor(0.0f, 0.0f, 0.0f, 1.0f);

        // 流星帧走图集，其余贴图各占一个固定槽位。
        for (int id = 0; id < Aurora2Scene.TEXTURE_SHOOTING_START; id++) {
            if (Aurora2Scene.TEXTURE_NAMES[id] != null) {
                scene.createTexture(textureName(id));
            }
        }
        scene.createTexture(SHOOTING_ATLAS_TEXTURE);

        /*
         * buildScene 发生在 native 设备创建之前，setTexture 只会把像素挂进 pending；
         * surface 创建时 native 一次性上传全部贴图。若等到每帧再用 setTexture，
         * native 会把所有带 pending 的贴图重新上传一遍（pending 是刻意不清空的），
         * 背景这种 5MB 的贴图会被反复重建。
         */
        uploadTextures();

        scene.createBuffer(BUFFER_SHINE, VERTEX_BYTES, STRIP_QUAD_VERTICES, true);
        scene.createBuffer(BUFFER_AURORA_MAIN, VERTEX_BYTES, AURORA_VERTICES, true);
        scene.createBuffer(BUFFER_AURORA_FADE, VERTEX_BYTES, AURORA_VERTICES, true);
        scene.createBuffer(BUFFER_BACKGROUND, VERTEX_BYTES, STRIP_QUAD_VERTICES, true);
        scene.createBuffer(BUFFER_BACKGROUND_FADE, VERTEX_BYTES, STRIP_QUAD_VERTICES, true);
        scene.createBuffer(BUFFER_STARS1, VERTEX_BYTES,
                STAR_GROUP_1_COUNT * LIST_QUAD_VERTICES, true);
        scene.createBuffer(BUFFER_STARS2, VERTEX_BYTES,
                STAR_GROUP_2_COUNT * LIST_QUAD_VERTICES, true);
        scene.createBuffer(BUFFER_SHOOTING, VERTEX_BYTES, STRIP_QUAD_VERTICES, true);
        scene.createBuffer(BUFFER_TREE, VERTEX_BYTES, STRIP_QUAD_VERTICES, true);

        // 声明顺序 = GLES 的绘制顺序。
        mShinePass = addPass(scene, BUFFER_SHINE,
                textureName(Aurora2Scene.TEXTURE_SHINE_00),
                VkPass.TOPOLOGY_TRIANGLE_STRIP, BLEND_OVERWRITE);
        for (int id : AURORA_TEXTURE_IDS) {
            mAuroraMainPass[id] = addPass(scene, BUFFER_AURORA_MAIN, textureName(id),
                    VkPass.TOPOLOGY_TRIANGLE_STRIP, BLEND_AURORA);
        }
        for (int id : BACKGROUND_TEXTURE_IDS) {
            mBackgroundMainPass[id] = addPass(scene, BUFFER_BACKGROUND, textureName(id),
                    VkPass.TOPOLOGY_TRIANGLE_STRIP, BLEND_BACKGROUND);
        }
        mStars1Pass = addPass(scene, BUFFER_STARS1,
                textureName(Aurora2Scene.TEXTURE_SHINING_STAR),
                VkPass.TOPOLOGY_TRIANGLE_LIST, BLEND_STANDARD);
        mStars2Pass = addPass(scene, BUFFER_STARS2,
                textureName(Aurora2Scene.TEXTURE_SHINING_STAR),
                VkPass.TOPOLOGY_TRIANGLE_LIST, BLEND_STANDARD);
        mShootingPass = addPass(scene, BUFFER_SHOOTING, SHOOTING_ATLAS_TEXTURE,
                VkPass.TOPOLOGY_TRIANGLE_STRIP, BLEND_STANDARD);
        mTreePass = addPass(scene, BUFFER_TREE,
                textureName(Aurora2Scene.TEXTURE_TREE),
                VkPass.TOPOLOGY_TRIANGLE_STRIP, BLEND_STANDARD);
        for (int id : BACKGROUND_TEXTURE_IDS) {
            mBackgroundFadePass[id] = addPass(scene, BUFFER_BACKGROUND_FADE, textureName(id),
                    VkPass.TOPOLOGY_TRIANGLE_STRIP, BLEND_STANDARD);
        }
        for (int id : AURORA_TEXTURE_IDS) {
            mAuroraFadePass[id] = addPass(scene, BUFFER_AURORA_FADE, textureName(id),
                    VkPass.TOPOLOGY_TRIANGLE_STRIP, BLEND_STANDARD);
        }

        mShineBuffer = allocate(STRIP_QUAD_VERTICES);
        mAuroraMainBuffer = allocate(AURORA_VERTICES);
        mAuroraFadeBuffer = allocate(AURORA_VERTICES);
        mBackgroundBuffer = allocate(STRIP_QUAD_VERTICES);
        mBackgroundFadeBuffer = allocate(STRIP_QUAD_VERTICES);
        mStars1Buffer = allocate(STAR_GROUP_1_COUNT * LIST_QUAD_VERTICES);
        mStars2Buffer = allocate(STAR_GROUP_2_COUNT * LIST_QUAD_VERTICES);
        mShootingBuffer = allocate(STRIP_QUAD_VERTICES);
        mTreeBuffer = allocate(STRIP_QUAD_VERTICES);

        System.arraycopy(CLIP_CORRECTION, 0, mPush, 0, 16);
    }

    private VkPass addPass(VkPassScene scene, String bufferName, String textureName,
                           int topology, int[] factors) {
        VkPass pass = new VkPass(
                "shaders/aurora2_vk.vert.spv",
                "shaders/aurora2_vk.frag.spv",
                new int[] {2, 2, 1},
                VERTEX_BYTES,
                topology,
                VkPass.BLEND_ALPHA)
                .buffer(bufferName, 0)
                .pushConstants(PUSH_BYTES)
                .blendFactors(factors[0], factors[1], factors[2], factors[3]);
        pass.texture(0, textureName);
        scene.add(pass);
        mAllPasses.add(pass);
        return pass;
    }

    @Override
    protected void updateFrame(VkPassFrame frame, long timeMs) {
        if (mScene == null) return;
        mScene.update(timeMs);
        Aurora2Scene.SceneData data = mScene.getSceneData();

        for (int i = 0; i < mAllPasses.size(); i++) {
            VkPass pass = mAllPasses.get(i);
            frame.setVertexCount(pass, 0);
            frame.pushConstants(pass, mPush);
        }

        float logicalW = mVertical ? PORTRAIT_WIDTH : LANDSCAPE_WIDTH;
        float logicalH = mVertical ? PORTRAIT_HEIGHT : LANDSCAPE_HEIGHT;

        drawQuad(frame, mShinePass, mShineBuffer, BUFFER_SHINE,
                data.shine, logicalW, logicalH);

        Aurora2Scene.AuroraMeshState aurora = data.aurora;
        if (isDrawable(aurora)) {
            drawAurora(frame, mAuroraMainPass[aurora.textureId], mAuroraMainBuffer,
                    BUFFER_AURORA_MAIN, aurora, logicalW, logicalH);
        }

        Aurora2Scene.Sprite background = data.background;
        if (isDrawable(background)) {
            drawQuad(frame, mBackgroundMainPass[background.textureId], mBackgroundBuffer,
                    BUFFER_BACKGROUND, background, logicalW, logicalH);
        }

        drawStars(frame, mStars1Pass, mStars1Buffer, BUFFER_STARS1,
                data.stars1, STAR_GROUP_1_COUNT, logicalW, logicalH);
        drawStars(frame, mStars2Pass, mStars2Buffer, BUFFER_STARS2,
                data.stars2, STAR_GROUP_2_COUNT, logicalW, logicalH);

        drawShootingStar(frame, data.shootingStar, logicalW, logicalH);

        drawQuad(frame, mTreePass, mTreeBuffer, BUFFER_TREE,
                data.tree, logicalW, logicalH);

        if (data.fadeActive) {
            Aurora2Scene.Sprite fadeBackground = data.fadeBackground;
            if (isDrawable(fadeBackground)) {
                drawQuad(frame, mBackgroundFadePass[fadeBackground.textureId],
                        mBackgroundFadeBuffer, BUFFER_BACKGROUND_FADE,
                        fadeBackground, logicalW, logicalH);
            }
            Aurora2Scene.AuroraMeshState fadeAurora = data.fadeAurora;
            if (isDrawable(fadeAurora)) {
                drawAurora(frame, mAuroraFadePass[fadeAurora.textureId], mAuroraFadeBuffer,
                        BUFFER_AURORA_FADE, fadeAurora, logicalW, logicalH);
            }
        }
    }

    private void drawQuad(VkPassFrame frame, VkPass pass, ByteBuffer buffer,
                          String bufferName, Aurora2Scene.Sprite sprite,
                          float logicalW, float logicalH) {
        if (pass == null || !isDrawable(sprite) || !isTextureReady(sprite.textureId)) {
            return;
        }
        writeQuadStrip(buffer, sprite.x, sprite.y, sprite.width, sprite.height,
                sprite.alpha, 0.0f, 1.0f, 0.0f, 1.0f, logicalW, logicalH);
        frame.upload(bufferName, buffer, STRIP_QUAD_VERTICES * VERTEX_BYTES);
        frame.setVertexCount(pass, STRIP_QUAD_VERTICES);
    }

    private void drawAurora(VkPassFrame frame, VkPass pass, ByteBuffer buffer,
                            String bufferName, Aurora2Scene.AuroraMeshState aurora,
                            float logicalW, float logicalH) {
        if (pass == null || !isDrawable(aurora) || !isTextureReady(aurora.textureId)) {
            return;
        }
        writeAuroraMesh(buffer, aurora, logicalW, logicalH);
        frame.upload(bufferName, buffer, AURORA_VERTICES * VERTEX_BYTES);
        frame.setVertexCount(pass, AURORA_VERTICES);
    }

    private void drawStars(VkPassFrame frame, VkPass pass, ByteBuffer buffer,
                           String bufferName, Aurora2Scene.Sprite[] sprites,
                           int maxCount, float logicalW, float logicalH) {
        if (pass == null || sprites.length == 0) return;
        int vertexCount = 0;
        int count = Math.min(sprites.length, maxCount);
        for (int i = 0; i < count; i++) {
            Aurora2Scene.Sprite sprite = sprites[i];
            if (!isDrawable(sprite) || !isTextureReady(sprite.textureId)) continue;
            writeQuadList(buffer, vertexCount, sprite.x, sprite.y,
                    sprite.width, sprite.height, sprite.alpha,
                    0.0f, 1.0f, 0.0f, 1.0f, logicalW, logicalH);
            vertexCount += LIST_QUAD_VERTICES;
        }
        if (vertexCount == 0) return;
        frame.upload(bufferName, buffer, vertexCount * VERTEX_BYTES);
        frame.setVertexCount(pass, vertexCount);
    }

    private void drawShootingStar(VkPassFrame frame, Aurora2Scene.Sprite sprite,
                                  float logicalW, float logicalH) {
        if (!mShootingAtlasReady || !isDrawable(sprite)) return;
        int index = sprite.textureId - Aurora2Scene.TEXTURE_SHOOTING_START;
        if (index < 0 || index >= Aurora2Scene.SHOOTING_FRAME_COUNT) return;

        int cellX = (index % ATLAS_COLUMNS) * mAtlasCellWidth;
        int cellY = (index / ATLAS_COLUMNS) * mAtlasCellHeight;
        float u0 = (cellX + ATLAS_BORDER) / (float) mAtlasWidth;
        float u1 = (cellX + ATLAS_BORDER + mAtlasFrameWidth) / (float) mAtlasWidth;
        float vTop = (cellY + ATLAS_BORDER) / (float) mAtlasHeight;
        float vBottom = (cellY + ATLAS_BORDER + mAtlasFrameHeight) / (float) mAtlasHeight;

        writeQuadStrip(mShootingBuffer, sprite.x, sprite.y, sprite.width, sprite.height,
                sprite.alpha, u0, u1, vTop, vBottom, logicalW, logicalH);
        frame.upload(BUFFER_SHOOTING, mShootingBuffer, STRIP_QUAD_VERTICES * VERTEX_BYTES);
        frame.setVertexCount(mShootingPass, STRIP_QUAD_VERTICES);
    }

    /** 与 GLES 的 drawQuad 判据一致。 */
    private static boolean isDrawable(Aurora2Scene.Sprite sprite) {
        return sprite != null
                && sprite.textureId >= 0
                && sprite.textureId < Aurora2Scene.TEXTURE_COUNT
                && sprite.alpha > 0.001f
                && sprite.width > 0.0f
                && sprite.height > 0.0f;
    }

    /** 与 GLES 的 drawAurora 判据一致。 */
    private static boolean isDrawable(Aurora2Scene.AuroraMeshState aurora) {
        return aurora != null
                && aurora.textureId >= 0
                && aurora.textureId < Aurora2Scene.TEXTURE_COUNT
                && aurora.alpha > 0.001f
                && aurora.width > 0.0f
                && aurora.height > 0.0f;
    }

    private void uploadTextures() {
        for (int id = 0; id < Aurora2Scene.TEXTURE_SHOOTING_START; id++) {
            if (Aurora2Scene.TEXTURE_NAMES[id] != null) {
                mTextureReady[id] = uploadTexture(id);
            }
        }
        mShootingAtlasReady = buildShootingAtlas();
    }

    private boolean uploadTexture(int textureId) {
        String name = Aurora2Scene.TEXTURE_NAMES[textureId];
        if (name == null || mPassScene == null) return false;
        Bitmap bitmap = AssetLoader.decodeBitmap(context, "aurora2/drawable/" + name + ".png");
        if (bitmap == null) return false;
        Bitmap argb = toArgb8888(bitmap);
        if (argb == null) return false;
        int width = argb.getWidth();
        int height = argb.getHeight();
        int[] pixels = new int[width * height];
        argb.getPixels(pixels, 0, width, 0, 0, width, height);
        argb.recycle();
        mPassScene.setTexture(textureName(textureId), pixels, width, height);
        return true;
    }

    private boolean isTextureReady(int textureId) {
        return textureId >= 0 && textureId < mTextureReady.length && mTextureReady[textureId];
    }

    /**
     * 把 52 帧流星拼成一张图集。每格内容四周复制 1px，线性过滤在格子边缘取到的
     * 仍是本帧的边缘像素，因此与原版一张贴图 + CLAMP_TO_EDGE 的观感一致。
     */
    private boolean buildShootingAtlas() {
        if (mPassScene == null) return false;

        Bitmap[] frames = new Bitmap[Aurora2Scene.SHOOTING_FRAME_COUNT];
        int frameWidth = 0;
        int frameHeight = 0;
        for (int i = 0; i < frames.length; i++) {
            int textureId = Aurora2Scene.TEXTURE_SHOOTING_START + i;
            String name = Aurora2Scene.TEXTURE_NAMES[textureId];
            if (name == null) continue;
            Bitmap bitmap = AssetLoader.decodeBitmap(
                    context, "aurora2/drawable/" + name + ".png");
            if (bitmap == null) continue;
            Bitmap argb = toArgb8888(bitmap);
            if (argb == null) continue;
            if (frameWidth == 0) {
                frameWidth = argb.getWidth();
                frameHeight = argb.getHeight();
            }
            if (argb.getWidth() != frameWidth || argb.getHeight() != frameHeight) {
                argb.recycle();
                continue;
            }
            frames[i] = argb;
        }
        if (frameWidth <= 0 || frameHeight <= 0) {
            recycleAll(frames);
            return false;
        }

        mAtlasFrameWidth = frameWidth;
        mAtlasFrameHeight = frameHeight;
        mAtlasCellWidth = frameWidth + 2 * ATLAS_BORDER;
        mAtlasCellHeight = frameHeight + 2 * ATLAS_BORDER;
        mAtlasWidth = ATLAS_COLUMNS * mAtlasCellWidth;
        mAtlasHeight = ATLAS_ROWS * mAtlasCellHeight;

        int[] atlas = new int[mAtlasWidth * mAtlasHeight];
        for (int i = 0; i < frames.length; i++) {
            Bitmap frame = frames[i];
            if (frame == null) continue;
            int cellX = (i % ATLAS_COLUMNS) * mAtlasCellWidth;
            int cellY = (i / ATLAS_COLUMNS) * mAtlasCellHeight;
            blitShootingFrame(atlas, cellX, cellY, frame);
            frame.recycle();
        }

        mPassScene.setTexture(SHOOTING_ATLAS_TEXTURE, atlas, mAtlasWidth, mAtlasHeight);
        return true;
    }

    private void blitShootingFrame(int[] atlas, int cellX, int cellY, Bitmap frame) {
        int width = mAtlasFrameWidth;
        int height = mAtlasFrameHeight;
        int[] pixels = new int[width * height];
        frame.getPixels(pixels, 0, width, 0, 0, width, height);

        int contentX = cellX + ATLAS_BORDER;
        int contentY = cellY + ATLAS_BORDER;
        for (int y = 0; y < height; y++) {
            int srcRow = y * width;
            int dstRow = (contentY + y) * mAtlasWidth + contentX;
            System.arraycopy(pixels, srcRow, atlas, dstRow, width);
            atlas[dstRow - 1] = pixels[srcRow];
            atlas[dstRow + width] = pixels[srcRow + width - 1];
        }

        int cellWidth = width + 2 * ATLAS_BORDER;
        int firstContentRow = contentY * mAtlasWidth + cellX;
        System.arraycopy(atlas, firstContentRow,
                atlas, (contentY - 1) * mAtlasWidth + cellX, cellWidth);
        int lastContentRow = (contentY + height - 1) * mAtlasWidth + cellX;
        System.arraycopy(atlas, lastContentRow,
                atlas, (contentY + height) * mAtlasWidth + cellX, cellWidth);
    }

    private static Bitmap toArgb8888(Bitmap bitmap) {
        if (bitmap.getConfig() == Bitmap.Config.ARGB_8888) return bitmap;
        Bitmap converted = bitmap.copy(Bitmap.Config.ARGB_8888, false);
        bitmap.recycle();
        return converted;
    }

    private static void recycleAll(Bitmap[] bitmaps) {
        for (Bitmap bitmap : bitmaps) {
            if (bitmap != null) bitmap.recycle();
        }
    }

    private static String textureName(int textureId) {
        return "tex" + textureId;
    }

    private static ByteBuffer allocate(int vertices) {
        return ByteBuffer.allocateDirect(vertices * VERTEX_BYTES)
                .order(ByteOrder.nativeOrder());
    }

    /** 四个顶点按 GL_TRIANGLE_STRIP 的顺序写入（与 quad csv 一致）。 */
    private static void writeQuadStrip(ByteBuffer buffer,
                                       float centerX, float centerY,
                                       float width, float height, float alpha,
                                       float u0, float u1, float vTop, float vBottom,
                                       float logicalW, float logicalH) {
        float halfW = width / 2.0f;
        float halfH = height / 2.0f;
        putLogical(buffer, 0, centerX - halfW, centerY - halfH,
                u0, vBottom, alpha, logicalW, logicalH);
        putLogical(buffer, 1, centerX + halfW, centerY - halfH,
                u1, vBottom, alpha, logicalW, logicalH);
        putLogical(buffer, 2, centerX - halfW, centerY + halfH,
                u0, vTop, alpha, logicalW, logicalH);
        putLogical(buffer, 3, centerX + halfW, centerY + halfH,
                u1, vTop, alpha, logicalW, logicalH);
    }

    /** 两个三角形，顶点顺序与 GLES 每个精灵自己的 strip 相同。 */
    private static void writeQuadList(ByteBuffer buffer, int vertexOffset,
                                      float centerX, float centerY,
                                      float width, float height, float alpha,
                                      float u0, float u1, float vTop, float vBottom,
                                      float logicalW, float logicalH) {
        float halfW = width / 2.0f;
        float halfH = height / 2.0f;
        putLogical(buffer, vertexOffset, centerX - halfW, centerY - halfH,
                u0, vBottom, alpha, logicalW, logicalH);
        putLogical(buffer, vertexOffset + 1, centerX + halfW, centerY - halfH,
                u1, vBottom, alpha, logicalW, logicalH);
        putLogical(buffer, vertexOffset + 2, centerX - halfW, centerY + halfH,
                u0, vTop, alpha, logicalW, logicalH);
        putLogical(buffer, vertexOffset + 3, centerX - halfW, centerY + halfH,
                u0, vTop, alpha, logicalW, logicalH);
        putLogical(buffer, vertexOffset + 4, centerX + halfW, centerY - halfH,
                u1, vBottom, alpha, logicalW, logicalH);
        putLogical(buffer, vertexOffset + 5, centerX + halfW, centerY + halfH,
                u1, vTop, alpha, logicalW, logicalH);
    }

    private static void writeAuroraMesh(ByteBuffer buffer,
                                        Aurora2Scene.AuroraMeshState aurora,
                                        float logicalW, float logicalH) {
        float rippleSpeed = 360.0f / 240.0f;
        float rippleOffset = rippleSpeed * aurora.rippleFrame;
        int vertexIndex = 0;
        for (int y = 0; y < AURORA_STRIPS; y++) {
            float yRatio = ((float) y) / (AURORA_STRIPS - 1);
            double angle = ((double) (y * 6)) * Math.PI / 180.0 * 4.5
                    - (Math.PI / 180.0 * rippleOffset);
            float wave = (float) (Math.sin(angle) * 10.0);
            float drawY = aurora.y + yRatio * aurora.height;
            float texY = 1.0f - yRatio;
            putLogical(buffer, vertexIndex++, aurora.x + wave, drawY,
                    0.0f, texY, aurora.alpha, logicalW, logicalH);
            putLogical(buffer, vertexIndex++, aurora.x + aurora.width + wave, drawY,
                    1.0f, texY, aurora.alpha, logicalW, logicalH);
        }
    }

    /** 逻辑坐标 → GL NDC；y 方向与 GLES 的正交投影一致（y 向上）。 */
    private static void putLogical(ByteBuffer buffer, int vertexIndex,
                                   float x, float y, float u, float v, float alpha,
                                   float logicalW, float logicalH) {
        float ndcX = x / logicalW * 2.0f - 1.0f;
        float ndcY = y / logicalH * 2.0f - 1.0f;
        int base = vertexIndex * VERTEX_BYTES;
        buffer.putFloat(base, ndcX);
        buffer.putFloat(base + 4, ndcY);
        buffer.putFloat(base + 8, u);
        buffer.putFloat(base + 12, v);
        buffer.putFloat(base + 16, alpha);
    }

    private static final float[] CLIP_CORRECTION = {
        1, 0, 0, 0,
        0, -1, 0, 0,
        0, 0, 0.5f, 0,
        0, 0, 0.5f, 1,
    };
}
