package com.reandroid.wallpaper.nexus;

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
 * Nexus 的 Vulkan 渲染器 —— 壁纸与预览共用。
 *
 * <p>GLES 版逐脉冲调用两次 draw（脉冲纹理 + 光晕纹理），每次都有自己的 uColor；
 * 通用 pass 渲染器一次 draw 只有一个 push constant，所以这里按图元类型拆成三个 pass：
 * 背景、全部脉冲、全部光晕。加法混合（{@code SRC_ALPHA / ONE}）与绘制顺序无关，
 * 把同一张纹理的四边形合批后结果与 GLES 逐次绘制相同。
 *
 * <p>顶点直接按 GLES 的模型/正交投影在 CPU 侧算成裁剪坐标，再由顶点着色器做
 * Vulkan 的裁剪空间修正（y 取反、z 映射到 [0,w]）。这样每个脉冲可以有自己的
 * 缩放、偏移与 UV 旋转，而无需为每次 draw 单独建 pipeline。
 */
final class NexusVkDelegate extends VkPassRendererDelegate {

    private static final String TAG = "NexusVK";

    // 纹理与缓冲名（buildScene 里声明）
    private static final String BG_BUFFER = "bg";
    private static final String PULSE_BUFFER = "pulse";
    private static final String GLOW_BUFFER = "glow";
    private static final String BG_TEXTURE = "bgTexture";
    private static final String PULSE_TEXTURE = "pulseTexture";
    private static final String GLOW_TEXTURE = "glowTexture";

    // 背景：x, y, u, v（裁剪坐标在 CPU 侧算好）
    private static final int BG_FLOATS_PER_VERTEX = 4;
    private static final int BG_STRIDE_BYTES = BG_FLOATS_PER_VERTEX * 4;
    /** TRIANGLE_STRIP 的四个角，与 GLES 的 drawTexturedRect 相同。 */
    private static final int BG_VERTICES = 4;

    // 脉冲/光晕：x, y, u, v, r, g, b, a
    private static final int PULSE_FLOATS_PER_VERTEX = 8;
    private static final int PULSE_STRIDE_BYTES = PULSE_FLOATS_PER_VERTEX * 4;
    /** 通用 pass 不支持索引缓冲，一个四边形展开成两个三角形。 */
    private static final int VERTICES_PER_QUAD = 6;

    /** layout.json 里两个数量设置的上限；超过时只画得下这么多。 */
    private static final int MAX_PULSES = 100;

    private static final int MAX_EXTRAS = 200;
    private static final int MAX_QUADS = MAX_PULSES + MAX_EXTRAS;
    private static final int MAX_PASS_VERTICES = MAX_QUADS * VERTICES_PER_QUAD;

    private static final String BG_VERT = "shaders/nexus_vk_bg.vert.spv";
    private static final String BG_FRAG = "shaders/nexus_vk_bg.frag.spv";
    private static final String PULSE_VERT = "shaders/nexus_vk_pulse.vert.spv";
    private static final String PULSE_FRAG = "shaders/nexus_vk_pulse.frag.spv";

    private static final String KEY_CUSTOM_BACKGROUND = "pref_custom_background_uri";
    private static final String KEY_BACKGROUND_PRESET = "nexus_background_preset";
    private static final String DEFAULT_BACKGROUND_PRESET = "pyramid_background";

    private static final String ASSET_PULSE = "nexus/drawable/pulse.png";
    private static final String ASSET_GLOW = "nexus/drawable/glow.png";

    private static final long SETTINGS_SYNC_INTERVAL_MS = 1000L;

    /** 与 NexusGL 相同的两套新增配色单色表（index 0 占位）。 */
    private static final float[] SINGLE_COLORS = {
        0f, 0f, 0f, // 占位，index 0 不用
        0.9f, 0.1f, 0.1f, // 1 红
        0.1f, 0.9f, 0.1f, // 2 绿
        0.1f, 0.1f, 0.9f, // 3 蓝
        0.9f, 0.9f, 0.9f, // 4 白
        0.3f, 0.9f, 0.9f, // 5 青
        0.9f, 0.3f, 0.9f, // 6 紫
        0.95f, 0.7f, 0.75f, // 7 粉
    };

    /** 原版 nexus.rs 的 4 色循环。 */
    private static final float[] ORIGINAL_COLORS = {
        1.0f, 0.0f, 0.0f, 0.8f,
        0.0f, 0.6f, 0.0f, 0.8f,
        0.0f, 0.4f, 0.8f, 0.8f,
        1.0f, 0.8f, 0.0f, 0.8f,
    };

    /** "多彩"模式的 8 色循环。 */
    private static final float[] MULTI_COLORS = {
        1.0f, 0.0f, 0.0f, 0.8f, // 红
        0.0f, 0.8f, 0.0f, 0.8f, // 绿
        0.0f, 0.4f, 0.9f, 0.8f, // 蓝
        1.0f, 0.8f, 0.0f, 0.8f, // 黄
        0.9f, 0.3f, 0.9f, 0.8f, // 品红
        0.9f, 0.9f, 0.9f, 0.8f, // 白
        0.3f, 0.9f, 0.9f, 0.8f, // 青
        0.95f, 0.7f, 0.75f, 0.8f, // 粉
    };

    private volatile SharedPreferences mPrefs;
    private volatile NexusScene mScene;
    private volatile VkPassScene mPassScene;

    private VkPass mBgPass;
    private VkPass mPulsePass;
    private VkPass mGlowPass;

    private ByteBuffer mBgVertices;
    private ByteBuffer mPulseVertices;
    private ByteBuffer mGlowVertices;
    private int mPulseVertexCount;
    private int mGlowVertexCount;

    private final float[] mBgUv = new float[8];
    private final float[] mColor = new float[4];

    private volatile int mWidth;
    private volatile int mHeight;

    /** 背景图片的宽高比；加载失败时沿用上一次的值（与 GLES 的 Manager 一致）。 */
    private float mBackgroundAspect = 1.0f;

    private String mLastBackgroundUri;
    private String mLastBackgroundPreset;

    private boolean mStaticTexturesUploaded;
    private volatile boolean mSettingsSyncRequested;
    private long mLastSettingsSyncMs;

    /** 预览里点画面直接触发脉冲；桌面上等系统发 tap 命令。由宿主通过 setPreview 告知。 */
    private boolean mPreview;

    NexusVkDelegate(Context context, SharedPreferences prefs) {
        super(context);
        mPrefs = prefs;
    }

    @Override
    public void onPreviewChanged(boolean preview) {
        mPreview = preview;
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

        NexusScene scene = mScene;
        if (scene == null) {
            scene = new NexusScene(width, height);
            scene.setPluginPrefs(mPrefs);
            // 与 NexusGL.initGL 的顺序一致：先应用设置，再按设置的数量初始化脉冲。
            scene.applySettings(scene.loadSettings(context.getResources()));
            scene.resize(width, height);
            scene.updateProjection(width, height);
            loadUvs(scene);
            scene.initPulses(width, height, SystemClock.uptimeMillis());
            mScene = scene;
        } else {
            synchronized (scene) {
                scene.resize(width, height);
            }
        }
    }

    @Override
    public void onPluginPrefsChanged(SharedPreferences prefs) {
        mPrefs = prefs;
        NexusScene scene = mScene;
        if (scene != null) {
            synchronized (scene) {
                scene.setPluginPrefs(prefs);
            }
        }
        // 设置的实际应用放到渲染线程，避免与 updateFrame 读取脉冲状态交错。
        mSettingsSyncRequested = true;
    }

    @Override
    public void onSceneOffset(float xOffset) {
        NexusScene scene = mScene;
        if (scene == null) return;
        synchronized (scene) {
            scene.setOffset(xOffset);
        }
    }

    @Override
    public void onTouchEvent(MotionEvent event) {
        // 桌面同时会收到 tap 命令，只有预览才从触摸事件注入脉冲。
        if (mPreview && event != null && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            handleTap((int) event.getX(), (int) event.getY());
        }
    }

    @Override
    public void onCommand(String action, int x, int y, int z, Bundle extras) {
        if (WallpaperManager.COMMAND_TAP.equals(action)
                || WallpaperManager.COMMAND_SECONDARY_TAP.equals(action)
                || WallpaperManager.COMMAND_DROP.equals(action)) {
            handleTap(x, y);
        }
    }

    private void handleTap(int x, int y) {
        NexusScene scene = mScene;
        if (scene == null) return;
        synchronized (scene) {
            // 与 NexusGL.handleTap 相同的门槛与竖屏坐标修正。
            if (scene.mPulseController.getExtras() == null
                    || scene.MAX_EXTRAS <= 0
                    || scene.PULSE_SIZE <= 0) {
                return;
            }
            if (mWidth < mHeight) {
                x = (int) (x + (scene.mXOffset * mWidth / scene.mWorldScaleX));
            }
            scene.addTap(x, y, SystemClock.uptimeMillis());
        }
    }

    private void loadUvs(NexusScene scene) {
        scene.mUv0 = AssetLoader.readFloatArray(context, "nexus/data/nexus_uv_0.csv");
        scene.mUv90 = AssetLoader.readFloatArray(context, "nexus/data/nexus_uv_90.csv");
        scene.mUv180 = AssetLoader.readFloatArray(context, "nexus/data/nexus_uv_180.csv");
        scene.mUv270 = AssetLoader.readFloatArray(context, "nexus/data/nexus_uv_270.csv");
    }

    // ─────────────────────────── 声明场景 ───────────────────────────

    @Override
    protected void buildScene(VkPassScene scene) {
        mPassScene = scene;
        // native 渲染器可能被销毁后重建：新场景里的纹理是空的，全部重新上传。
        mStaticTexturesUploaded = false;
        mLastBackgroundUri = null;
        mLastBackgroundPreset = null;
        mPulseVertexCount = 0;
        mGlowVertexCount = 0;

        scene.setClearColor(0f, 0f, 0f, 1f);

        scene.createBuffer(BG_BUFFER, BG_STRIDE_BYTES, BG_VERTICES, true);
        scene.createBuffer(PULSE_BUFFER, PULSE_STRIDE_BYTES, MAX_PASS_VERTICES, true);
        scene.createBuffer(GLOW_BUFFER, PULSE_STRIDE_BYTES, MAX_PASS_VERTICES, true);

        // GLES 的三张纹理都是 LINEAR + CLAMP_TO_EDGE，正好是 createTexture 的默认值。
        scene.createTexture(BG_TEXTURE);
        scene.createTexture(PULSE_TEXTURE);
        scene.createTexture(GLOW_TEXTURE);

        mBgVertices = ByteBuffer.allocateDirect(BG_VERTICES * BG_STRIDE_BYTES)
                .order(ByteOrder.nativeOrder());
        mPulseVertices = ByteBuffer.allocateDirect(MAX_PASS_VERTICES * PULSE_STRIDE_BYTES)
                .order(ByteOrder.nativeOrder());
        mGlowVertices = ByteBuffer.allocateDirect(MAX_PASS_VERTICES * PULSE_STRIDE_BYTES)
                .order(ByteOrder.nativeOrder());

        // 背景不混合（GLES 里 glDisable(GL_BLEND)）。
        mBgPass = scene.add(new VkPass(
                        BG_VERT,
                        BG_FRAG,
                        new int[] {2, 2},
                        BG_STRIDE_BYTES,
                        VkPass.TOPOLOGY_TRIANGLE_STRIP,
                        VkPass.BLEND_NONE))
                .buffer(BG_BUFFER, 0);
        mBgPass.texture(0, BG_TEXTURE);

        // 脉冲与光晕：glBlendFunc(SRC_ALPHA, ONE) 对颜色和 alpha 通道同时生效，
        // 通用 BLEND_ADDITIVE 预设给 alpha 用的是 ONE，这里显式覆盖。
        mPulsePass = scene.add(new VkPass(
                        PULSE_VERT,
                        PULSE_FRAG,
                        new int[] {2, 2, 4},
                        PULSE_STRIDE_BYTES,
                        VkPass.TOPOLOGY_TRIANGLE_LIST,
                        VkPass.BLEND_ADDITIVE))
                .buffer(PULSE_BUFFER, 0)
                .blendFactors(
                        VkPass.FACTOR_SRC_ALPHA,
                        VkPass.FACTOR_ONE,
                        VkPass.FACTOR_SRC_ALPHA,
                        VkPass.FACTOR_ONE);
        mPulsePass.texture(0, PULSE_TEXTURE);

        mGlowPass = scene.add(new VkPass(
                        PULSE_VERT,
                        PULSE_FRAG,
                        new int[] {2, 2, 4},
                        PULSE_STRIDE_BYTES,
                        VkPass.TOPOLOGY_TRIANGLE_LIST,
                        VkPass.BLEND_ADDITIVE))
                .buffer(GLOW_BUFFER, 0)
                .blendFactors(
                        VkPass.FACTOR_SRC_ALPHA,
                        VkPass.FACTOR_ONE,
                        VkPass.FACTOR_SRC_ALPHA,
                        VkPass.FACTOR_ONE);
        mGlowPass.texture(0, GLOW_TEXTURE);
    }

    // ─────────────────────────── 每帧 ───────────────────────────

    @Override
    protected void updateFrame(VkPassFrame frame, long timeMs) {
        NexusScene scene = mScene;
        if (scene == null || mWidth <= 0 || mHeight <= 0 || mPassScene == null) {
            setEmptyFrame(frame);
            return;
        }

        ensureStaticTextures();

        synchronized (scene) {
            if (mSettingsSyncRequested
                    || timeMs - mLastSettingsSyncMs > SETTINGS_SYNC_INTERVAL_MS) {
                mSettingsSyncRequested = false;
                mLastSettingsSyncMs = timeMs;
                scene.applySettings(scene.loadSettings(context.getResources()));
            }
            // GLES 每帧检查背景是否变了；这里也放在渲染线程上做。
            syncBackgroundIfChanged();

            scene.mRotate = mWidth > mHeight;
            buildBackground(scene);
            buildPulses(scene, timeMs);
        }

        mBgVertices.position(0);
        frame.upload(BG_BUFFER, mBgVertices, BG_VERTICES * BG_STRIDE_BYTES);
        frame.setVertexCount(mBgPass, BG_VERTICES);

        if (mPulseVertexCount > 0) {
            mPulseVertices.position(0);
            frame.upload(PULSE_BUFFER, mPulseVertices, mPulseVertexCount * PULSE_STRIDE_BYTES);
        }
        frame.setVertexCount(mPulsePass, mPulseVertexCount);

        if (mGlowVertexCount > 0) {
            mGlowVertices.position(0);
            frame.upload(GLOW_BUFFER, mGlowVertices, mGlowVertexCount * PULSE_STRIDE_BYTES);
        }
        frame.setVertexCount(mGlowPass, mGlowVertexCount);
    }

    private void setEmptyFrame(VkPassFrame frame) {
        if (mBgPass != null) frame.setVertexCount(mBgPass, 0);
        if (mPulsePass != null) frame.setVertexCount(mPulsePass, 0);
        if (mGlowPass != null) frame.setVertexCount(mGlowPass, 0);
    }

    // ─────────────────────────── 背景几何 ───────────────────────────

    private void buildBackground(NexusScene scene) {
        float scaleX = scene.mWorldScaleX;
        float scaleY = scene.mWorldScaleY;
        // GLES 的顺序是 scaleM 之后 translateM，即模型矩阵为 S*T：
        // 平移量也会被世界缩放放大。
        float tx = scene.mRotate ? 0f : -(scene.mXOffset * mWidth);

        float right = scene.mRotate ? mHeight * 2f : mWidth * 2f;
        float bottom = scene.mRotate ? mWidth : mHeight;
        buildBackgroundUv(right, bottom);

        mBgVertices.clear();
        // 顶点顺序与 GLES 一致：左上、右上、左下、右下（TRIANGLE_STRIP）。
        putBgVertex(0f, 0f, mBgUv[0], mBgUv[1], scaleX, scaleY, tx);
        putBgVertex(right, 0f, mBgUv[2], mBgUv[3], scaleX, scaleY, tx);
        putBgVertex(0f, bottom, mBgUv[4], mBgUv[5], scaleX, scaleY, tx);
        putBgVertex(right, bottom, mBgUv[6], mBgUv[7], scaleX, scaleY, tx);
    }

    /** 与 NexusGL.buildBackgroundUv 相同：按宽高比做居中裁切。 */
    private void buildBackgroundUv(float drawWidth, float drawHeight) {
        float viewAspect = drawHeight > 0f ? drawWidth / drawHeight : 1f;
        float bgAspect = mBackgroundAspect > 0f ? mBackgroundAspect : 1f;

        float u0 = 0f;
        float v0 = 0f;
        float u1 = 1f;
        float v1 = 1f;

        if (viewAspect > bgAspect) {
            float scale = bgAspect / viewAspect;
            float pad = (1f - scale) * 0.5f;
            v0 = pad;
            v1 = 1f - pad;
        } else if (viewAspect < bgAspect) {
            float scale = viewAspect / bgAspect;
            float pad = (1f - scale) * 0.5f;
            u0 = pad;
            u1 = 1f - pad;
        }

        mBgUv[0] = u0;
        mBgUv[1] = v0;
        mBgUv[2] = u1;
        mBgUv[3] = v0;
        mBgUv[4] = u0;
        mBgUv[5] = v1;
        mBgUv[6] = u1;
        mBgUv[7] = v1;
    }

    private void putBgVertex(
            float worldX, float worldY, float u, float v, float scaleX, float scaleY, float tx) {
        // 模型 S*T：x' = sx*(x + tx)，y' = sy*y。
        float x = scaleX * (worldX + tx);
        float y = scaleY * worldY;
        mBgVertices
                .putFloat(2f * x / mWidth - 1f)
                .putFloat(1f - 2f * y / mHeight)
                .putFloat(u)
                .putFloat(v);
    }

    // ─────────────────────────── 脉冲 ───────────────────────────

    private void buildPulses(NexusScene scene, long timeMs) {
        mPulseVertexCount = 0;
        mGlowVertexCount = 0;
        mPulseVertices.clear();
        mGlowVertices.clear();

        int now = (int) timeMs;
        drawPulseSet(scene, scene.mPulseController.getPulses(), scene.MAX_PULSES, now);
        drawPulseSet(scene, scene.mPulseController.getExtras(), scene.MAX_EXTRAS, now);
    }

    private void drawPulseSet(
            NexusScene scene, NexusPulseController.Pulse[] pulses, int count, int now) {
        if (pulses == null) return;
        int limit = Math.min(count, pulses.length);
        for (int i = 0; i < limit; i++) {
            NexusPulseController.Pulse pulse = pulses[i];
            if (pulse == null) continue;
            int delta = now - pulse.startTime;
            if (pulse.active == 0 || delta < 0) continue;
            drawPulse(scene, pulse, now, delta);
        }
    }

    private void drawPulse(NexusScene scene, NexusPulseController.Pulse pulse, int now, int delta) {
        float x = pulse.originX + (pulse.dx * scene.SPEED * delta);
        float y = pulse.originY + (pulse.dy * scene.SPEED * delta);

        float trail = scene.TRAIL_SIZE * scene.PULSE_SIZE;
        float size = scene.PULSE_SIZE;
        float halfPulse = scene.HALF_PULSE_SIZE;
        float halfGlow = scene.HALF_GLOW_SIZE;

        colorForPulse(scene.mMode, pulse.color, mColor);

        if (pulse.dx < 0) {
            float xx = x + trail;
            if (xx <= 0) {
                resetPulse(scene, pulse, now);
                return;
            }
            addPulseQuad(scene, pulse, x, y, xx, y + size, scene.mUv0);
            addGlowQuad(
                    scene,
                    pulse,
                    x + halfPulse - halfGlow,
                    y + halfPulse - halfGlow,
                    x + halfPulse + halfGlow,
                    y + halfPulse + halfGlow,
                    scene.mUv0);
        } else if (pulse.dx > 0) {
            x += size;
            float xx = x - trail;
            if (xx >= mWidth * 2f) {
                resetPulse(scene, pulse, now);
                return;
            }
            addPulseQuad(scene, pulse, xx, y, x, y + size, scene.mUv180);
            addGlowQuad(
                    scene,
                    pulse,
                    x - halfPulse - halfGlow,
                    y + halfPulse - halfGlow,
                    x - halfPulse + halfGlow,
                    y + halfPulse + halfGlow,
                    scene.mUv180);
        } else if (pulse.dy < 0) {
            float yy = y + trail;
            if (yy <= 0) {
                resetPulse(scene, pulse, now);
                return;
            }
            addPulseQuad(scene, pulse, x, y, x + size, yy, scene.mUv270);
            addGlowQuad(
                    scene,
                    pulse,
                    x + halfPulse - halfGlow,
                    y + halfPulse - halfGlow,
                    x + halfPulse + halfGlow,
                    y + halfPulse + halfGlow,
                    scene.mUv270);
        } else if (pulse.dy > 0) {
            y += size;
            float yy = y - trail;
            if (yy >= mHeight) {
                resetPulse(scene, pulse, now);
                return;
            }
            addPulseQuad(scene, pulse, x, yy, x + size, y, scene.mUv90);
            addGlowQuad(
                    scene,
                    pulse,
                    x + halfPulse - halfGlow,
                    y - halfPulse - halfGlow,
                    x + halfPulse + halfGlow,
                    y - halfPulse + halfGlow,
                    scene.mUv90);
        }
    }

    private void resetPulse(NexusScene scene, NexusPulseController.Pulse pulse, int now) {
        scene.mPulseController.resetPulse(
                pulse,
                pulse.pulseType,
                mWidth,
                mHeight,
                scene.PULSE_SIZE,
                scene.SPEED_DELTA_MIN,
                scene.SPEED_DELTA_MAX,
                scene.MAX_DELAY,
                now);
    }

    private void addPulseQuad(
            NexusScene scene,
            NexusPulseController.Pulse pulse,
            float left,
            float top,
            float right,
            float bottom,
            float[] uv) {
        mPulseVertexCount = addQuad(
                mPulseVertices, mPulseVertexCount, scene, pulse, left, top, right, bottom, uv);
    }

    private void addGlowQuad(
            NexusScene scene,
            NexusPulseController.Pulse pulse,
            float left,
            float top,
            float right,
            float bottom,
            float[] uv) {
        mGlowVertexCount = addQuad(
                mGlowVertices, mGlowVertexCount, scene, pulse, left, top, right, bottom, uv);
    }

    /**
     * 写入一个四边形（六个顶点）。
     *
     * <p>脉冲的模型矩阵在 GLES 里是 translateM 之后 scaleM，即 T*S：
     * 平移量不参与缩放，与背景的 S*T 不同。
     */
    private int addQuad(
            ByteBuffer buffer,
            int count,
            NexusScene scene,
            NexusPulseController.Pulse pulse,
            float left,
            float top,
            float right,
            float bottom,
            float[] uv) {
        if (count + VERTICES_PER_QUAD > MAX_PASS_VERTICES) {
            return count;
        }

        float scaleX = pulse.scale * scene.mWorldScaleX;
        float scaleY = pulse.scale * scene.mWorldScaleY;
        float tx = scene.mRotate ? 0f : -(scene.mXOffset * mWidth);

        float x0 = left * scaleX + tx;
        float x1 = right * scaleX + tx;
        float y0 = top * scaleY;
        float y1 = bottom * scaleY;

        // 展开成三角形列表：左上、右上、左下 + 左下、右上、右下。
        putPulseVertex(buffer, x0, y0, uv[0], uv[1]);
        putPulseVertex(buffer, x1, y0, uv[2], uv[3]);
        putPulseVertex(buffer, x0, y1, uv[4], uv[5]);
        putPulseVertex(buffer, x0, y1, uv[4], uv[5]);
        putPulseVertex(buffer, x1, y0, uv[2], uv[3]);
        putPulseVertex(buffer, x1, y1, uv[6], uv[7]);

        return count + VERTICES_PER_QUAD;
    }

    private void putPulseVertex(ByteBuffer buffer, float x, float y, float u, float v) {
        buffer.putFloat(2f * x / mWidth - 1f)
                .putFloat(1f - 2f * y / mHeight)
                .putFloat(u)
                .putFloat(v)
                .putFloat(mColor[0])
                .putFloat(mColor[1])
                .putFloat(mColor[2])
                .putFloat(mColor[3]);
    }

    /** 与 NexusGL.setColorForPulse 相同：单色模式取固定色，否则按调色板循环。 */
    private static void colorForPulse(int mode, int colorIndex, float[] out) {
        if (mode >= 1 && mode <= 7) {
            int i = mode * 3;
            out[0] = SINGLE_COLORS[i];
            out[1] = SINGLE_COLORS[i + 1];
            out[2] = SINGLE_COLORS[i + 2];
            out[3] = 0.8f;
            return;
        }
        float[] palette = mode == NexusScene.MODE_MULTI ? MULTI_COLORS : ORIGINAL_COLORS;
        int i = Math.floorMod(colorIndex, palette.length / 4) * 4;
        out[0] = palette[i];
        out[1] = palette[i + 1];
        out[2] = palette[i + 2];
        out[3] = palette[i + 3];
    }

    // ─────────────────────────── 纹理 ───────────────────────────

    private void ensureStaticTextures() {
        if (mStaticTexturesUploaded || mPassScene == null) return;
        mStaticTexturesUploaded = true;
        uploadAssetTexture(PULSE_TEXTURE, ASSET_PULSE);
        uploadAssetTexture(GLOW_TEXTURE, ASSET_GLOW);
    }

    /** 背景纹理只在选择变化时重传，等价于 NexusBackgroundManager.reloadIfChanged。 */
    private void syncBackgroundIfChanged() {
        if (mPassScene == null) return;

        SharedPreferences prefs = mPrefs;
        String customUri = getCustomBackgroundUri();
        String preset = prefs != null
                ? prefs.getString(KEY_BACKGROUND_PRESET, DEFAULT_BACKGROUND_PRESET)
                : DEFAULT_BACKGROUND_PRESET;

        boolean useCustom = customUri != null;
        boolean changed;
        if (useCustom) {
            changed = !customUri.equals(mLastBackgroundUri);
        } else {
            changed = mLastBackgroundUri != null
                    || mLastBackgroundPreset == null
                    || !preset.equals(mLastBackgroundPreset);
        }
        if (!changed) return;

        boolean loaded = false;
        if (useCustom) {
            loaded = uploadCustomBackground(customUri);
        }
        if (!loaded) {
            uploadAssetBackground(resolvePresetAssetPath(preset));
        }

        mLastBackgroundUri = customUri;
        mLastBackgroundPreset = preset;
    }

    private String getCustomBackgroundUri() {
        SharedPreferences prefs = mPrefs;
        if (prefs == null) return null;
        String uri = prefs.getString(KEY_CUSTOM_BACKGROUND, null);
        return uri != null && !uri.isEmpty() ? uri : null;
    }

    private static String resolvePresetAssetPath(String preset) {
        if ("pyramid_background1".equals(preset)) return "nexus/drawable/pyramid_background1.png";
        if ("pyramid_background2".equals(preset)) return "nexus/drawable/pyramid_background2.png";
        if ("pyramid_background3".equals(preset)) return "nexus/drawable/pyramid_background3.png";
        if ("pyramid_background4".equals(preset)) return "nexus/drawable/pyramid_background4.png";
        return "nexus/drawable/pyramid_background.png";
    }

    private void uploadAssetBackground(String assetPath) {
        Bitmap bitmap = decodeAsset(assetPath);
        if (bitmap == null) return;
        if (bitmap.getWidth() > 0 && bitmap.getHeight() > 0) {
            mBackgroundAspect = bitmap.getWidth() / (float) bitmap.getHeight();
        }
        uploadBitmap(BG_TEXTURE, bitmap);
    }

    private boolean uploadAssetTexture(String name, String assetPath) {
        Bitmap bitmap = decodeAsset(assetPath);
        if (bitmap == null) return false;
        return uploadBitmap(name, bitmap);
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
            Log.e(TAG, "Failed to load custom background", e);
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
