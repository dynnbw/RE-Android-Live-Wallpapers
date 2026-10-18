package com.reandroid.wallpaper.bluesea;

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
import java.util.HashMap;
import java.util.Map;

/**
 * BlueSea 的 VK 渲染器 —— 壁纸与预览共用。
 *
 * <p>GLES 版每个精灵一次 draw。这里按**纹理资产**分批：同一张贴图的所有精灵写进同一个
 * 顶点批（顶点里带 alpha），一次 draw 画完。纹理槽位按资产固定，只在首次使用时上传，
 * 不会因为每帧精灵顺序变化而反复重传（那是把帧率打到个位数的主因）。
 */
final class BlueSeaVkDelegate extends VkPassRendererDelegate {

    /** 背景 + 气泡 + 21 个水母配置的本体/光晕贴图，留足余量。 */
    private static final int MAX_SLOTS = 96;
    /** 每个槽位最多 512 个顶点 = 85 个精灵（每个 6 顶点）。 */
    private static final int MAX_SLOT_VERTICES = 512;
    private static final int FLOATS_PER_VERTEX = 5; // x, y, u, v, alpha
    private static final int VERTICES_PER_SPRITE = 6; // 两个三角形
    private static final int PUSH_BYTES = 16 * 4;

    private static final String BG_ASSET = "bluesea/drawable/bluesea_bg.png";
    private static final String PARTICLE_ASSET = "bluesea/drawable/bluesea_particle.png";

    private final SharedPreferences mPrefs;
    private BlueSeaScene mScene;
    private VkPassScene mPassScene;

    private VkPass[] mPasses;
    private ByteBuffer[] mSlotBuffers;
    private int[] mSlotSpriteCounts;
    private final String[] mSlotAssets = new String[MAX_SLOTS];
    private final Map<String, Integer> mAssetSlots = new HashMap<>();

    private final float[] mPush = new float[16];
    private int mWidth;
    private int mHeight;

    BlueSeaVkDelegate(Context context, SharedPreferences prefs) {
        super(context);
        mPrefs = prefs;
    }

    @Override
    public String logTag() {
        return "BlueSeaVK";
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
            mScene = new BlueSeaScene(width, height);
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
        if (mScene != null) mScene.setOffset(xOffset);
    }

    @Override
    public void onTouchEvent(MotionEvent event) {
        if (mScene != null) mScene.onTouchEvent(event);
    }

    @Override
    protected void buildScene(VkPassScene scene) {
        mPassScene = scene;
        scene.setClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        mPasses = new VkPass[MAX_SLOTS];
        mSlotBuffers = new ByteBuffer[MAX_SLOTS];
        mSlotSpriteCounts = new int[MAX_SLOTS];
        for (int i = 0; i < MAX_SLOTS; i++) {
            String buffer = "batch" + i;
            String texture = "tex" + i;
            scene.createBuffer(buffer, FLOATS_PER_VERTEX * 4, MAX_SLOT_VERTICES, true);
            scene.createTexture(texture);
            VkPass pass = scene.add(new VkPass(
                    "shaders/bluesea_vk.vert.spv",
                    "shaders/bluesea_vk.frag.spv",
                    new int[] {2, 2, 1}, FLOATS_PER_VERTEX * 4,
                    VkPass.TOPOLOGY_TRIANGLE_LIST, VkPass.BLEND_ALPHA))
                    .buffer(buffer, 0)
                    .pushConstants(PUSH_BYTES);
            pass.texture(0, texture);
            mPasses[i] = pass;
            mSlotBuffers[i] = ByteBuffer
                    .allocateDirect(MAX_SLOT_VERTICES * FLOATS_PER_VERTEX * 4)
                    .order(ByteOrder.nativeOrder());
        }
        System.arraycopy(CLIP_CORRECTION, 0, mPush, 0, 16);
    }

    @Override
    protected void updateFrame(VkPassFrame frame, long timeMs) {
        if (mScene == null) return;
        mScene.update(timeMs);
        for (int i = 0; i < MAX_SLOTS; i++) mSlotSpriteCounts[i] = 0;

        // 背景
        float scrollOffset = -mScene.mXOffset * mWidth * 1.5f;
        float bgWidth = (mHeight > mWidth) ? (mHeight * 1.5f) : (mWidth * 2.5f);
        float bgHeight = (mHeight > mWidth) ? mHeight : (mWidth * 5.0f / 3.0f);
        addSprite(BG_ASSET, scrollOffset + bgWidth / 2.0f, bgHeight / 2.0f,
                bgWidth, bgHeight, 1.0f);

        // 水母：远平面先画
        for (int plane = 2; plane >= 0; plane--) {
            for (BlueSeaScene.JellyState jelly : mScene.mJellies) {
                if (jelly.plane != plane) continue;
                float drawX = mScene.jellyDrawX(jelly, timeMs);
                float drawY = mScene.jellyDrawY(jelly, timeMs);
                float size = jelly.config.size * mScene.mScale * mScene.mJellySizeScale;
                float scale = mScene.computeSwimScale(jelly, timeMs);
                if (drawX + size < 0 || drawX - size > mWidth) continue;

                addSprite(jelly.config.imageAsset,
                        drawX, drawY, size * scale, size * scale, 1.0f);
                float glowAlpha = mScene.computeGlowAlpha(jelly, timeMs);
                if (glowAlpha > 0.0f) {
                    addSprite(jelly.config.glowAsset,
                            drawX, drawY, size * scale, size * scale, glowAlpha);
                }
            }
        }

        // 气泡
        float particleScroll = -mScene.mXOffset * mWidth * 1.8f;
        for (BlueSeaScene.Particle particle : mScene.mParticles) {
            float drawX = particle.x + particleScroll;
            if (drawX + particle.size < 0 || drawX - particle.size > mWidth) continue;
            addSprite(PARTICLE_ASSET,
                    drawX, particle.y, particle.size, particle.size, particle.alpha);
        }

        for (int i = 0; i < MAX_SLOTS; i++) {
            int sprites = mSlotSpriteCounts[i];
            if (sprites == 0) {
                frame.setVertexCount(mPasses[i], 0);
                continue;
            }
            int floats = sprites * VERTICES_PER_SPRITE * FLOATS_PER_VERTEX;
            mSlotBuffers[i].position(0);
            frame.upload("batch" + i, mSlotBuffers[i], floats * 4);
            frame.setVertexCount(mPasses[i], sprites * VERTICES_PER_SPRITE);
            frame.pushConstants(mPasses[i], mPush);
        }
    }

    private void addSprite(String asset, float x, float y,
                           float width, float height, float alpha) {
        if (asset == null) return;
        int slot = slotFor(asset);
        if (slot < 0) return;
        int sprites = mSlotSpriteCounts[slot];
        if (sprites >= MAX_SLOT_VERTICES / VERTICES_PER_SPRITE) return;

        float left = x - width / 2.0f;
        float right = x + width / 2.0f;
        float top = y - height / 2.0f;
        float bottom = y + height / 2.0f;
        // 屏幕坐标 → GL NDC（y 向下），与 GLES 的 ortho 投影一致
        left = left / mWidth * 2.0f - 1.0f;
        right = right / mWidth * 2.0f - 1.0f;
        top = 1.0f - top / mHeight * 2.0f;
        bottom = 1.0f - bottom / mHeight * 2.0f;

        ByteBuffer buffer = mSlotBuffers[slot];
        int offset = sprites * VERTICES_PER_SPRITE * FLOATS_PER_VERTEX;
        // 两个三角形：0,1,2 与 2,1,3
        offset = putVertex(buffer, offset, left, top, 0.0f, 1.0f, alpha);
        offset = putVertex(buffer, offset, right, top, 1.0f, 1.0f, alpha);
        offset = putVertex(buffer, offset, left, bottom, 0.0f, 0.0f, alpha);
        offset = putVertex(buffer, offset, left, bottom, 0.0f, 0.0f, alpha);
        offset = putVertex(buffer, offset, right, top, 1.0f, 1.0f, alpha);
        putVertex(buffer, offset, right, bottom, 1.0f, 0.0f, alpha);
        mSlotSpriteCounts[slot] = sprites + 1;
    }

    private static int putVertex(ByteBuffer buffer, int floatIndex,
                                 float x, float y, float u, float v, float alpha) {
        buffer.putFloat(floatIndex * 4, x);
        buffer.putFloat((floatIndex + 1) * 4, y);
        buffer.putFloat((floatIndex + 2) * 4, u);
        buffer.putFloat((floatIndex + 3) * 4, v);
        buffer.putFloat((floatIndex + 4) * 4, alpha);
        return floatIndex + FLOATS_PER_VERTEX;
    }

    /** 资产 → 固定槽位；首次使用时顺手把贴图传上去。 */
    private int slotFor(String asset) {
        Integer existing = mAssetSlots.get(asset);
        if (existing != null) return existing;
        for (int i = 0; i < MAX_SLOTS; i++) {
            if (mSlotAssets[i] != null) continue;
            if (!uploadTexture(i, asset)) return -1;
            mSlotAssets[i] = asset;
            mAssetSlots.put(asset, i);
            return i;
        }
        return -1;
    }

    private boolean uploadTexture(int slot, String asset) {
        Bitmap bitmap = AssetLoader.decodeBitmap(context, asset);
        if (bitmap == null) return false;
        Bitmap argb = bitmap.getConfig() == Bitmap.Config.ARGB_8888
                ? bitmap
                : bitmap.copy(Bitmap.Config.ARGB_8888, false);
        if (argb != bitmap) bitmap.recycle();
        int width = argb.getWidth();
        int height = argb.getHeight();
        int[] pixels = new int[width * height];
        argb.getPixels(pixels, 0, width, 0, 0, width, height);
        argb.recycle();
        mPassScene.setTexture("tex" + slot, pixels, width, height);
        return true;
    }

    private static final float[] CLIP_CORRECTION = {
        1, 0, 0, 0,
        0, -1, 0, 0,
        0, 0, 0.5f, 0,
        0, 0, 0.5f, 1,
    };
}
