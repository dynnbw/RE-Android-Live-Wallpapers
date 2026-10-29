package com.reandroid.wallpaper.fireworks;

import static com.reandroid.wallpaper.fireworks.FireworksScene.*;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;

import com.reandroid.plugin.VkPass;
import com.reandroid.plugin.VkPassFrame;
import com.reandroid.plugin.VkPassRendererDelegate;
import com.reandroid.plugin.VkPassScene;
import com.reandroid.utils.AssetLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 烟花的 Vulkan 渲染器 —— 壁纸与预览共用。
 *
 * <p>粒子系统的物理、爆炸、拖尾与设置全部沿用 {@link FireworksScene}（纯逻辑层），
 * 这里只重做 {@link FireworksGL} 的绘制：两个 pass，先背景（常规 alpha 混合），
 * 再全部粒子（加法混合）。顺序与 blend 模式都照 GLES 版：
 * 背景 {@code SRC_ALPHA / ONE_MINUS_SRC_ALPHA}，粒子 {@code SRC_ALPHA / ONE}；
 * {@code glBlendFunc} 对颜色和 alpha 两路同时生效，所以 alpha 的因子也显式覆盖。
 *
 * <p>GLES 版把本帧所有粒子写进一个顶点批，最后用 {@code glDrawElements} 一次画完
 * （每颗粒子 4 顶点 6 索引）。通用 pass 渲染器没有索引缓冲，这里在 CPU 侧按同样的
 * 索引顺序（0,1,2 与 0,2,3）展开成 6 个顶点。顶点顺序不变，加法混合的结果与逐次绘制一致。
 *
 * <p>背景的滚动偏移、贴图宽高比与两张拼接的铺法都与 {@code FireworksGL.drawBackground}
 * 相同；触摸仍走 {@link #onCommand}（桌面）与 {@link #onTouchEvent}（预览，只在按下时）。
 *
 * <p>草地夜景变种（{@code pref_fireworks_grass_night}）没有移植：那一套是夜空渐变、
 * 星空与随风摆动的草叶，属于另一整个渲染器，不在本渲染器的范围内。
 */
final class FireworksVkDelegate extends VkPassRendererDelegate {

    private static final String TAG = "FireworksVK";

    // 缓冲与纹理名（buildScene 里声明）
    private static final String BG_BUFFER = "background";
    private static final String PARTICLE_BUFFER = "particles";
    private static final String BG_TEXTURE = "backgroundTexture";
    private static final String STAR_TEXTURE = "starTexture";

    private static final String BG_VERT = "shaders/fireworks_vk.vert.spv";
    private static final String BG_FRAG = "shaders/fireworks_vk.frag.spv";
    private static final String PARTICLE_VERT = "shaders/fireworks_vk_particle.vert.spv";
    private static final String PARTICLE_FRAG = "shaders/fireworks_vk_particle.frag.spv";

    // 与 FireworksGL 相同的设置键
    private static final String KEY_COUNT = "pref_fireworks_count";
    private static final String KEY_TAILS = "pref_fireworks_tails";
    private static final String KEY_ENHANCED = "pref_fireworks_enhanced";
    private static final String KEY_PALETTE = "pref_fireworks_palette";
    private static final String PALETTE_VIVID = "vivid";
    private static final String KEY_CUSTOM_BACKGROUND = "pref_custom_background_uri";

    private static final String ASSET_BACKGROUND = "fireworks/drawable/background.jpg";
    private static final String ASSET_STAR = "fireworks/drawable/star.png";

    /** 一个四边形展开成两个三角形；GLES 的索引缓冲就是按 0,1,2 与 0,2,3 的顺序。 */
    private static final int VERTICES_PER_QUAD = 6;

    // 背景：x, y, u, v（裁剪坐标在 CPU 侧算好）
    private static final int BG_FLOATS_PER_VERTEX = 4;
    private static final int BG_STRIDE_BYTES = BG_FLOATS_PER_VERTEX * 4;
    /** 背景横向铺两张，每张展开成两个三角形。 */
    private static final int BG_QUADS = 2;

    private static final int BG_VERTICES = BG_QUADS * VERTICES_PER_QUAD;

    // 粒子：x, y, u, v, r, g, b, a
    private static final int PARTICLE_FLOATS_PER_VERTEX = 8;
    private static final int PARTICLE_STRIDE_BYTES = PARTICLE_FLOATS_PER_VERTEX * 4;

    /**
     * 与 {@code FireworksGL.MAX_PARTICLES} 相同的上限：(30 + 45) 组 × 75 槽位 + 500 条拖尾。
     * 超出时同样丢弃后续粒子，保证两边的截断行为一致。
     */
    private static final int MAX_PARTICLES = 6125;

    private static final int MAX_PARTICLE_VERTICES = MAX_PARTICLES * VERTICES_PER_QUAD;

    /** 增强模式光尾的等效曝光时长（秒），与 FireworksGL.TRAIL_SECONDS 相同。 */
    private static final float TRAIL_SECONDS = 0.12f;

    private final Object mLock = new Object();

    /** 主线程写、渲染线程读。 */
    private volatile SharedPreferences mPrefs;

    private FireworksScene mScene;
    private VkPassScene mPassScene;

    private VkPass mBgPass;
    private VkPass mParticlePass;

    private ByteBuffer mBgVertices;
    private ByteBuffer mParticleVertices;
    private int mParticleVertexCount;

    private volatile int mWidth;
    private volatile int mHeight;

    /** 桌面滚动偏移；预览固定为 0。 */
    private volatile float mXOffset;

    /** 预览里点画面直接放一发；桌面上等系统发 tap 命令。由宿主通过 setPreview 告知。 */
    private volatile boolean mPreview;

    /** 待处理的点击（主线程写、渲染线程取走）。 */
    private volatile int mTapX;

    private volatile int mTapY;
    private volatile boolean mTapPending;

    // 设置的推送与 GLES 相同：主线程只写 volatile 字段，渲染线程比较后再重建场景。
    private volatile int mPrefCount = DEFAULT_COUNT;
    private volatile boolean mPrefTails = true;
    private volatile boolean mPrefEnhanced = false;
    private volatile boolean mPrefVivid = false;
    private int mAppliedCount = -1;
    private boolean mAppliedTails = false;
    private boolean mAppliedEnhanced = false;
    private boolean mAppliedVivid = false;

    /** 背景图片的宽高比；加载失败时沿用上一次的值（与 GLES 的 mBackgroundAspect 一致）。 */
    private float mBackgroundAspect = 1.0f;

    private String mLastBackgroundUri;
    private boolean mBackgroundLoaded;
    private boolean mStarUploaded;

    FireworksVkDelegate(Context context, SharedPreferences prefs) {
        super(context);
        setPluginPrefs(prefs);
    }

    @Override
    public String logTag() {
        return TAG;
    }

    /**
     * 设置页按方法名反射 {@code setPluginPrefs(SharedPreferences)} 把新设置推给预览。
     *
     * <p>{@link FireworksScene} 是纯逻辑层、没有这个方法（GLES 版的设置也保存在
     * {@link FireworksGL} 里），所以这里返回 delegate 自身。返回场景对象的话，设置页
     * 每改一次都得整块重建预览。
     */
    @Override
    public Object getScene() {
        return this;
    }

    @Override
    public boolean isReady() {
        return mScene != null;
    }

    /**
     * 把设置读进 volatile 字段。<b>可能在主线程调用</b>（设置变更监听器），
     * 所以这里只写字段，不碰场景 —— 重建粒子数组要等渲染线程上的 applyPendingSettings。
     */
    public void setPluginPrefs(SharedPreferences prefs) {
        mPrefs = prefs;
        if (prefs != null) {
            mPrefCount = prefs.getInt(KEY_COUNT, FireworksScene.DEFAULT_COUNT);
            mPrefTails = prefs.getBoolean(KEY_TAILS, true);
            mPrefEnhanced = prefs.getBoolean(KEY_ENHANCED, false);
            mPrefVivid = PALETTE_VIVID.equals(prefs.getString(KEY_PALETTE, "original"));
        }
    }

    @Override
    public void onPluginPrefsChanged(SharedPreferences prefs) {
        setPluginPrefs(prefs);
    }

    @Override
    public void onPreviewChanged(boolean preview) {
        mPreview = preview;
    }

    @Override
    public void onSceneOffset(float xOffset) {
        mXOffset = xOffset;
    }

    @Override
    public void ensureScene(int width, int height) {
        if (width <= 0 || height <= 0) return;
        synchronized (mLock) {
            mWidth = width;
            mHeight = height;
            FireworksScene scene = mScene;
            if (scene == null) {
                scene = new FireworksScene(width, height);
                // 与 FireworksGL.onCreate 相同：置位后再按当前设置初始化。
                // initialize() 自己不改这个标记，GLES 版也是在 onCreate 里先置位。
                scene.mInitialized = true;
                scene.initialize();
                mScene = scene;
            }
        }
    }

    /* ==================== 触摸 ==================== */

    @Override
    public void onCommand(String action, int x, int y, int z, Bundle extras) {
        // 桌面路径：系统发送 tap 命令。
        if (WallpaperManager.COMMAND_TAP.equals(action)) {
            mTapX = x;
            mTapY = y;
            mTapPending = true;
        }
    }

    @Override
    public void onTouchEvent(MotionEvent event) {
        // 应用内预览路径：预览只转发 onTouchEvent，不派发 tap 命令。
        // 仅在预览模式接管，桌面继续走 onCommand，避免双重触发。
        if (mPreview && event != null && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mTapX = (int) event.getX();
            mTapY = (int) event.getY();
            mTapPending = true;
        }
    }

    /* ==================== 声明场景 ==================== */

    @Override
    protected void buildScene(VkPassScene scene) {
        mPassScene = scene;
        // native 渲染器可能被销毁后重建：新场景里的纹理是空的，全部重新上传。
        mBackgroundLoaded = false;
        mLastBackgroundUri = null;
        mStarUploaded = false;
        mParticleVertexCount = 0;

        scene.setClearColor(0f, 0f, 0f, 1f);

        scene.createBuffer(BG_BUFFER, BG_STRIDE_BYTES, BG_VERTICES, true);
        scene.createBuffer(PARTICLE_BUFFER, PARTICLE_STRIDE_BYTES, MAX_PARTICLE_VERTICES, true);

        // 背景：GLES 的自定义背景与内置背景都是 LINEAR + CLAMP_TO_EDGE，正好是默认值。
        scene.createTexture(BG_TEXTURE);
        // 星星：GLES 的 loadTexture(star, true) 用 LINEAR + GL_REPEAT。
        scene.createTexture(
                STAR_TEXTURE,
                VkPassScene.FILTER_LINEAR,
                VkPassScene.FILTER_LINEAR,
                VkPassScene.ADDRESS_REPEAT);

        mBgVertices = ByteBuffer.allocateDirect(BG_VERTICES * BG_STRIDE_BYTES)
                .order(ByteOrder.nativeOrder());
        mParticleVertices = ByteBuffer.allocateDirect(MAX_PARTICLE_VERTICES * PARTICLE_STRIDE_BYTES)
                .order(ByteOrder.nativeOrder());

        // 背景：glBlendFunc(SRC_ALPHA, ONE_MINUS_SRC_ALPHA) 对 alpha 通道同样生效。
        mBgPass = scene.add(new VkPass(
                        BG_VERT,
                        BG_FRAG,
                        new int[] {2, 2},
                        BG_STRIDE_BYTES,
                        VkPass.TOPOLOGY_TRIANGLE_LIST,
                        VkPass.BLEND_ALPHA))
                .buffer(BG_BUFFER, 0)
                .blendFactors(
                        VkPass.FACTOR_SRC_ALPHA,
                        VkPass.FACTOR_ONE_MINUS_SRC_ALPHA,
                        VkPass.FACTOR_SRC_ALPHA,
                        VkPass.FACTOR_ONE_MINUS_SRC_ALPHA);
        mBgPass.texture(0, BG_TEXTURE);

        // 粒子：glBlendFunc(SRC_ALPHA, ONE)，加法混合做发光。
        mParticlePass = scene.add(new VkPass(
                        PARTICLE_VERT,
                        PARTICLE_FRAG,
                        new int[] {2, 2, 4},
                        PARTICLE_STRIDE_BYTES,
                        VkPass.TOPOLOGY_TRIANGLE_LIST,
                        VkPass.BLEND_ADDITIVE))
                .buffer(PARTICLE_BUFFER, 0)
                .blendFactors(
                        VkPass.FACTOR_SRC_ALPHA,
                        VkPass.FACTOR_ONE,
                        VkPass.FACTOR_SRC_ALPHA,
                        VkPass.FACTOR_ONE);
        mParticlePass.texture(0, STAR_TEXTURE);
    }

    /**
     * native 销毁后句柄不能再碰：基类动手之前先把场景引用清掉，
     * 免得设置回调还拿着旧句柄去上传纹理。
     */
    @Override
    public void destroyRenderer() {
        mPassScene = null;
        super.destroyRenderer();
    }

    /* ==================== 每帧 ==================== */

    @Override
    protected void updateFrame(VkPassFrame frame, long timeMs) {
        synchronized (mLock) {
            FireworksScene scene = mScene;
            if (scene == null
                    || !scene.mInitialized
                    || mWidth <= 0
                    || mHeight <= 0
                    || mPassScene == null) {
                zeroPasses(frame);
                return;
            }

            // 与 FireworksGL.drawFrame 相同的顺序：
            // 设置 → 时间 → 点击 → 背景 → 物理 → 粒子。
            applyPendingSettings();
            // 时间与 GLES 版一样自取 uptimeMillis（基类传进来的 timeMs 来源相同，
            // 但这里按 FireworksGL 的写法取，两边的粒子节奏才逐帧一致）。
            scene.mNow = (int) SystemClock.uptimeMillis();

            // 预览模式下无偏移，实际壁纸需叠加滚动偏移。
            if (mTapPending) {
                mTapPending = false;
                float tapOffset = mPreview ? 0.0f : mXOffset;
                int x = (int) (mTapX + tapOffset * mWidth);
                scene.addTap(x, mTapY);
            }

            float offset = mPreview ? 0.0f : mXOffset;
            float offsetX = offset * mWidth;

            syncBackgroundIfChanged();
            buildBackground(offsetX);

            scene.update();

            buildParticles(offsetX);
        }

        mBgVertices.position(0);
        frame.upload(BG_BUFFER, mBgVertices, BG_VERTICES * BG_STRIDE_BYTES);
        frame.setVertexCount(mBgPass, BG_VERTICES);

        if (mParticleVertexCount > 0) {
            mParticleVertices.position(0);
            frame.upload(
                    PARTICLE_BUFFER,
                    mParticleVertices,
                    mParticleVertexCount * PARTICLE_STRIDE_BYTES);
        }
        frame.setVertexCount(mParticlePass, mParticleVertexCount);

        ensureStaticTextures();
    }

    /** 场景还没建好时也要把每个 pass 的顶点数设成 0 —— 漏设会沿用上一帧的数值。 */
    private void zeroPasses(VkPassFrame frame) {
        if (mBgPass != null) frame.setVertexCount(mBgPass, 0);
        if (mParticlePass != null) frame.setVertexCount(mParticlePass, 0);
    }

    /** 只在渲染线程调用：applySettings 会重建粒子数组。 */
    private void applyPendingSettings() {
        int count = mPrefCount;
        boolean tails = mPrefTails;
        boolean enhanced = mPrefEnhanced;
        boolean vivid = mPrefVivid;
        if (count == mAppliedCount
                && tails == mAppliedTails
                && enhanced == mAppliedEnhanced
                && vivid == mAppliedVivid) {
            return;
        }
        mAppliedCount = count;
        mAppliedTails = tails;
        mAppliedEnhanced = enhanced;
        mAppliedVivid = vivid;
        mScene.applySettings(count, tails, enhanced, vivid);
    }

    /* ==================== 背景 ==================== */

    /**
     * 与 {@code FireworksGL.drawBackground} 相同：按宽高比铺两张，第二张接在第一张右边。
     * 偏移为 0 时正好铺满屏宽（或按比例超出），滚动时跟着平移。
     */
    private void buildBackground(float offsetX) {
        float bgWidth = mHeight * mBackgroundAspect;
        float startX = -offsetX;
        mBgVertices.clear();
        putBackgroundQuad(startX, 0.0f, startX + bgWidth, mHeight);
        putBackgroundQuad(startX + bgWidth, 0.0f, startX + bgWidth * 2.0f, mHeight);
    }

    /** 一个纹理矩形：4 个角按 GLES 的 TRIANGLE_FAN 顺序，再展开成两个三角形。 */
    private void putBackgroundQuad(float x0, float y0, float x1, float y1) {
        putBackgroundVertex(x0, y0, 0.0f, 0.0f);
        putBackgroundVertex(x0, y1, 0.0f, 1.0f);
        putBackgroundVertex(x1, y1, 1.0f, 1.0f);
        putBackgroundVertex(x0, y0, 0.0f, 0.0f);
        putBackgroundVertex(x1, y1, 1.0f, 1.0f);
        putBackgroundVertex(x1, y0, 1.0f, 0.0f);
    }

    private void putBackgroundVertex(float x, float y, float u, float v) {
        mBgVertices
                .putFloat(2f * x / mWidth - 1f)
                .putFloat(1f - 2f * y / mHeight)
                .putFloat(u)
                .putFloat(v);
    }

    /* ==================== 粒子 ==================== */

    /**
     * 与 {@code FireworksGL.draw} 相同：常规组 → 额外组 → 拖尾，按原顺序写进同一批。
     * 全批一次画完，加法混合与提交顺序无关，叠加结果与逐次绘制一致。
     */
    private void buildParticles(float offsetX) {
        mParticleVertices.clear();
        mParticleVertexCount = 0;
        for (int i = 0; i < mScene.mNormalGroups; i++) {
            drawFireworks(mScene.mNormal, i * STRIDE, offsetX);
        }
        for (int i = 0; i < mScene.mExtraGroups; i++) {
            drawFireworks(mScene.mExtras, i * STRIDE, offsetX);
        }
        for (int i = 0; i < MAX_TAILS; i++) {
            drawTails(mScene.mTails[i], offsetX);
        }
    }

    /** 与 {@code FireworksGL.drawFireworks} 相同。 */
    private void drawFireworks(FireworkParticle[] arr, int index, float offsetX) {
        if (mScene.mEnhanced) {
            drawFireworksEnhanced(arr, index, offsetX);
            return;
        }
        for (int i = 0; i < STRIDE; i++) {
            FireworkParticle p = arr[index + i];
            int delta = mScene.mNow - p.time;
            // 仅绘制激活且时间差为正的粒子
            if (p.active && delta >= 0) {
                float size = mScene.getSize(p.life);
                putFireworkParticle(p, p.life, p.posX - offsetX, p.posY, size);
            }
        }
    }

    /** 与 {@code FireworksGL.drawFireworksEnhanced} 相同：橙色外辉 + 白色核心的火箭与实时小火花。 */
    private void drawFireworksEnhanced(FireworkParticle[] arr, int index, float offsetX) {
        FireworkParticle leader = arr[index];
        if (leader != null && leader.active) {
            float x = leader.posX - offsetX;
            float y = leader.posY;
            float glow = mScene.getRocketGlowSize();
            putParticle(
                    x - glow * 0.5f,
                    y - glow * 0.5f,
                    x + glow * 0.5f,
                    y + glow * 0.5f,
                    1.0f,
                    0.686f,
                    0.275f,
                    0.32f);
            // 核心同样沿速度拉长 → 上升的火箭是一道彗尾
            putStreak(leader, x, y, mScene.getRocketCoreSize(), 1.0f);
        }
        for (int i = 1; i < STRIDE; i++) {
            FireworkParticle e = arr[index + i];
            if (e == null || !e.active || e.life <= 0.0f) continue;
            float t = e.maxLife > 0.0f ? e.life / e.maxLife : 0.0f;
            float a = t > 0.78f ? 1.0f : t / 0.78f;
            // 参考实现在这里还乘了 0.88,但它画的是不透明硬边圆点;
            // 软贴图本身已经衰减过一次,再压就只有"灰"没有"亮"
            a = (float) Math.pow(a, 0.6);
            if (e.twinkle) {
                a *= 0.55f + 0.45f * (float) Math.sin(e.life * 45.0f + e.phase);
                if (a < 0.0f) a = 0.0f;
            }
            if (a <= 0.0f) continue;
            putStreak(e, e.posX - offsetX, e.posY, e.size, a);
        }
    }

    /** 与 {@code FireworksGL.drawTails} 相同；闪光画完后同样把这条拖尾复位。 */
    private void drawTails(TailParticle tail, float offsetX) {
        if (tail == null) return;
        FireworkParticle root = tail.root;
        if (tail.life < 0.0f) return;

        if (tail.type == 0) {
            // 普通拖尾
            float size = mScene.getSize(tail.life);
            putFireworkParticle(root, tail.life, tail.posX - offsetX, tail.posY, size);
        } else if (tail.type == 1) {
            // 闪光:纯白、全不透明,直径随屏高等比缩放
            float size = mScene.getFlareSize();
            putParticle(
                    tail.posX - offsetX - size * 0.5f,
                    tail.posY - size * 0.5f,
                    tail.posX - offsetX + size * 0.5f,
                    tail.posY + size * 0.5f,
                    1.0f,
                    1.0f,
                    1.0f,
                    1.0f);
            // 闪光绘制后重置拖尾
            mScene.initTails(tail);
        }
    }

    /** 轴对齐四边形就是"沿 y"的特例，和拉伸四边形共用同一条写入路径。 */
    private void putParticle(
            float x0, float y0, float x1, float y1, float r, float g, float b, float a) {
        putQuadCorners(x0, y0, x0, y1, x1, y1, x1, y0, r, g, b, a);
    }

    /**
     * 写一个任意四边形的粒子（顶点按周长顺序，UV 的 u 轴沿"尾→头"方向）。
     * 四个角按 GLES 索引缓冲的顺序展开成两个三角形。
     */
    private void putQuadCorners(
            float x0,
            float y0,
            float x1,
            float y1,
            float x2,
            float y2,
            float x3,
            float y3,
            float r,
            float g,
            float b,
            float a) {
        if (mParticleVertexCount + VERTICES_PER_QUAD > MAX_PARTICLE_VERTICES) {
            // 上限已按场景最大配置算出,正常不会触发;留作防御
            return;
        }
        putParticleVertex(x0, y0, 0.0f, 0.0f, r, g, b, a);
        putParticleVertex(x1, y1, 0.0f, 1.0f, r, g, b, a);
        putParticleVertex(x2, y2, 1.0f, 1.0f, r, g, b, a);
        putParticleVertex(x0, y0, 0.0f, 0.0f, r, g, b, a);
        putParticleVertex(x2, y2, 1.0f, 1.0f, r, g, b, a);
        putParticleVertex(x3, y3, 1.0f, 0.0f, r, g, b, a);
        mParticleVertexCount += VERTICES_PER_QUAD;
    }

    private void putParticleVertex(
            float x, float y, float u, float v, float r, float g, float b, float a) {
        mParticleVertices
                .putFloat(2f * x / mWidth - 1f)
                .putFloat(1f - 2f * y / mHeight)
                .putFloat(u)
                .putFloat(v)
                .putFloat(r)
                .putFloat(g)
                .putFloat(b)
                .putFloat(a);
    }

    /**
     * 增强模式的光尾：把粒子沿速度方向拉长。
     *
     * <p>拉长量正比于速度，所以刚炸开时光尾最长、减速后自然收成一个点。
     * 与 {@code FireworksGL.putStreak} 相同。
     */
    private void putStreak(FireworkParticle p, float x, float y, float size, float a) {
        float vx = p.dx;
        float vy = p.dy;
        float speed = (float) Math.hypot(vx, vy);
        if (!mScene.mTailsEnabled || speed < 1.0f) {
            putParticle(
                    x - size * 0.5f,
                    y - size * 0.5f,
                    x + size * 0.5f,
                    y + size * 0.5f,
                    p.r / 255.0f,
                    p.g / 255.0f,
                    p.b / 255.0f,
                    a);
            return;
        }
        float ux = vx / speed;
        float uy = vy / speed;
        // 拉长量 = 速度 × 拖尾时长;上限防止极快的粒子拖成一条线。
        float stretch = Math.min(speed * TRAIL_SECONDS, size * 6.0f);
        float halfLen = (size + stretch) * 0.5f;
        float halfWid = size * 0.5f;
        float nx = -uy;
        float ny = ux;
        float r = p.r / 255.0f;
        float g = p.g / 255.0f;
        float b = p.b / 255.0f;
        putQuadCorners(
                x - ux * halfLen - nx * halfWid,
                y - uy * halfLen - ny * halfWid,
                x - ux * halfLen + nx * halfWid,
                y - uy * halfLen + ny * halfWid,
                x + ux * halfLen + nx * halfWid,
                y + uy * halfLen + ny * halfWid,
                x + ux * halfLen - nx * halfWid,
                y + uy * halfLen - ny * halfWid,
                r,
                g,
                b,
                a);
    }

    /**
     * 追加一颗"烟花粒子"（带自身颜色，透明度走 sqrt(|life|)，与 GLES 的 setParticleColor 一致）。
     */
    private void putFireworkParticle(FireworkParticle p, float life, float x, float y, float size) {
        float a = (float) Math.sqrt(Math.abs(life));
        float r = p != null ? p.r / 255.0f : 1.0f;
        float g = p != null ? p.g / 255.0f : 1.0f;
        float b = p != null ? p.b / 255.0f : 1.0f;
        putParticle(x - size * 0.5f, y - size * 0.5f, x + size * 0.5f, y + size * 0.5f, r, g, b, a);
    }

    /* ==================== 纹理 ==================== */

    private void ensureStaticTextures() {
        if (mStarUploaded || mPassScene == null) return;
        mStarUploaded = true;
        Bitmap bitmap = decodeAsset(ASSET_STAR);
        if (bitmap != null) {
            uploadBitmap(STAR_TEXTURE, bitmap);
        }
    }

    /** 背景纹理只在选择变化时重传，等价于 FireworksGL.checkAndReloadBackground。 */
    private void syncBackgroundIfChanged() {
        if (mPassScene == null) return;

        String uri = getCustomBackgroundUri();
        boolean changed;
        if (!mBackgroundLoaded) {
            changed = true;
        } else if (uri == null) {
            changed = mLastBackgroundUri != null;
        } else {
            changed = !uri.equals(mLastBackgroundUri);
        }
        if (!changed) return;

        boolean loaded = false;
        if (uri != null) {
            loaded = uploadCustomBackground(uri);
        }
        if (!loaded) {
            uploadAssetBackground();
        }

        mLastBackgroundUri = uri;
        mBackgroundLoaded = true;
    }

    private String getCustomBackgroundUri() {
        SharedPreferences prefs = mPrefs;
        if (prefs == null) return null;
        String uri = prefs.getString(KEY_CUSTOM_BACKGROUND, null);
        return (uri != null && !uri.isEmpty()) ? uri : null;
    }

    private void uploadAssetBackground() {
        Bitmap bitmap = decodeAsset(ASSET_BACKGROUND);
        if (bitmap == null) return;
        if (bitmap.getWidth() > 0 && bitmap.getHeight() > 0) {
            mBackgroundAspect = bitmap.getWidth() / (float) bitmap.getHeight();
        }
        uploadBitmap(BG_TEXTURE, bitmap);
    }

    private Bitmap decodeAsset(String assetPath) {
        try {
            return AssetLoader.decodeBitmap(context, assetPath);
        } catch (RuntimeException e) {
            Log.e(TAG, "纹理解码失败: " + assetPath, e);
            return null;
        }
    }

    private boolean uploadCustomBackground(String uriString) {
        InputStream stream = null;
        try {
            Uri uri = Uri.parse(uriString);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            stream = context.getContentResolver().openInputStream(uri);
            if (stream == null) return false;
            Bitmap bitmap = BitmapFactory.decodeStream(stream, null, options);
            if (bitmap == null) return false;
            if (bitmap.getWidth() > 0 && bitmap.getHeight() > 0) {
                mBackgroundAspect = bitmap.getWidth() / (float) bitmap.getHeight();
            }
            return uploadBitmap(BG_TEXTURE, bitmap);
        } catch (Exception e) {
            Log.e(TAG, "自定义背景加载失败", e);
            return false;
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private boolean uploadBitmap(String name, Bitmap bitmap) {
        if (bitmap == null || mPassScene == null) return false;
        Bitmap argb = bitmap.getConfig() == Bitmap.Config.ARGB_8888
                ? bitmap
                : bitmap.copy(Bitmap.Config.ARGB_8888, false);
        if (argb != bitmap) bitmap.recycle();
        if (argb == null) return false;

        int width = argb.getWidth();
        int height = argb.getHeight();
        int[] pixels = new int[width * height];
        argb.getPixels(pixels, 0, width, 0, 0, width, height);
        argb.recycle();
        // 行序与 GLES 的 GLUtils.texImage2D 相同：位图第一行就是纹理 v=0。
        mPassScene.setTexture(name, pixels, width, height);
        return true;
    }
}
