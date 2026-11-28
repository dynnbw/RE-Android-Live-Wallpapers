package com.reandroid.wallpaper.fall;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.Surface;

import com.reandroid.plugin.VkRendererDelegate;
import com.reandroid.settings.WallpaperSettings;

/**
 * Fall 的 VK 渲染器 —— 壁纸与预览共用这一份。
 *
 * <p>原来写了两遍（{@code FallVKPluginEngine} 喂壁纸、{@code FallVKSurfaceView} 喂预览）。
 * 触摸手势（滑动波纹）是 fall 特有的，跟着内容一起搬到这边。
 */
final class FallVkDelegate implements VkRendererDelegate {

    private static final long SETTINGS_SYNC_INTERVAL_MS = 1000L;
    /** {@link #buildAlgaeParams} 的长度，与 fallvk_jni.cpp 里的偏移一一对应。 */
    private static final int ALGAE_PARAM_COUNT = 22;

    private final Context mContext;
    private final SharedPreferences mPrefs;

    private FallScene mScene;
    private long mRendererHandle;
    private int mLeafTextureCount = 1;
    private boolean mGreenLeavesEnabled;
    private long mLastAtlasSyncCheckMs;
    /**
     * native 侧 Vulkan 初始化是否成功。没有 Vulkan 物理设备时为 false
     * （Stack G11 / Android 9 实测就是这样），这时不能进 native 画帧。
     */
    private boolean mSurfaceReady;

    private int mSurfaceWidth;
    private int mSurfaceHeight;
    /** 预览里把一天压进 30 秒；壁纸路径用真实时间。 */
    private boolean mPreview;
    /** 多指分别计距、各自落点；见 {@link FallTouchTracker}。 */
    private final FallTouchTracker mTouchTracker = new FallTouchTracker();
    /** 每帧复用的蓝藻参数，见 {@link #buildAlgaeParams}。 */
    private final float[] mAlgaeParams = new float[ALGAE_PARAM_COUNT];

    FallVkDelegate(Context context, SharedPreferences prefs) {
        mContext = context.getApplicationContext();
        mPrefs = prefs;
    }

    @Override
    public String logTag() {
        return "FallVK";
    }

    @Override
    public Object getScene() {
        return mScene;
    }

    @Override
    public boolean isReady() {
        return mScene != null && mSurfaceReady;
    }

    @Override
    public void ensureScene(int width, int height) {
        if (width <= 0 || height <= 0) return;
        if (mScene == null) {
            mScene = new FallScene(width, height);
            mScene.setPreview(mPreview);
            mScene.setLeafTextureCount(mLeafTextureCount);
            if (mPrefs != null) mScene.setPluginPrefs(mPrefs);
        } else {
            mScene.resize(width, height);
        }
    }

    @Override
    public long createRenderer() {
        if (mRendererHandle != 0L) return mRendererHandle;
        mRendererHandle = FallVKNative.nCreateRenderer(mContext.getAssets());
        if (mRendererHandle != 0L) {
            mGreenLeavesEnabled = greenLeavesEnabled();
            mLeafTextureCount = Math.max(
                    1, FallVKNative.uploadTextures(mContext, mRendererHandle, mGreenLeavesEnabled));
            if (mScene != null) mScene.setLeafTextureCount(mLeafTextureCount);
        }
        return mRendererHandle;
    }

    @Override
    public void destroyRenderer() {
        if (mRendererHandle != 0L) {
            FallVKNative.nDestroyRenderer(mRendererHandle);
            mRendererHandle = 0L;
        }
    }

    @Override
    public void onSurfaceCreated(Surface surface, int width, int height) {
        mSurfaceWidth = width;
        mSurfaceHeight = height;
        mSurfaceReady = FallVKNative.nOnSurfaceCreated(mRendererHandle, surface, width, height);
    }

    @Override
    public void onSurfaceChanged(Surface surface, int width, int height) {
        mSurfaceWidth = width;
        mSurfaceHeight = height;
        FallVKNative.nOnSurfaceChanged(mRendererHandle, surface, width, height);
    }

    @Override
    public void onSurfaceDestroyed() {
        mSurfaceReady = false;
        FallVKNative.nOnSurfaceDestroyed(mRendererHandle);
    }

    /** 绿叶图集切换后要重传（同样每秒查一次设置）。 */
    @Override
    public void syncTexturesIfNeeded() {
        if (mRendererHandle == 0L) return;
        long now = SystemClock.uptimeMillis();
        if (now - mLastAtlasSyncCheckMs < SETTINGS_SYNC_INTERVAL_MS) return;
        mLastAtlasSyncCheckMs = now;
        boolean enabled = greenLeavesEnabled();
        if (enabled == mGreenLeavesEnabled) return;
        mLeafTextureCount =
                Math.max(1, FallVKNative.uploadTextures(mContext, mRendererHandle, enabled));
        mGreenLeavesEnabled = enabled;
        if (mScene != null) mScene.setLeafTextureCount(mLeafTextureCount);
    }

    @Override
    public void renderFrame() {
        if (!mSurfaceReady) return;
        long now = SystemClock.uptimeMillis();
        mScene.update(now);
        FallScene.SceneData data = mScene.getSceneData();
        float[] leafData = mScene.buildLeafDataForVK();
        int leafCount = mScene.getVKLeafCount();
        boolean meshRebuild = mScene.consumeMeshBufferRebuildRequested();
        float[] tint = mScene.getLeafTintRgb();
        float[] sky = mScene.getSkyWeights();
        float[] lighting = {
            mScene.getStarAmount(),
            (now % 30000L) * 0.001f,
            mSurfaceWidth > 0 ? mSurfaceHeight / (float) mSurfaceWidth : 1f,
            tint[0],
            tint[1],
            tint[2],
            mScene.getLeafTintAmount(),
            mScene.getLeafTintValue(),
            // [夜,晨,昏,昼]：水面着色器按这四条权重混天空色带
            sky[0],
            sky[1],
            sky[2],
            sky[3]
        };
        FallVKNative.nRenderFrame(
                mRendererHandle,
                lighting,
                buildAlgaeParams(data),
                data.getProjectionMatrix(),
                data.getViewMatrix(),
                leafData,
                leafCount,
                data.getXOffset(),
                meshRebuild ? data.getWaterMeshVertices() : null,
                meshRebuild ? data.getWaterMeshTexCoords() : null,
                meshRebuild ? data.getWaterMeshIndices() : null,
                meshRebuild ? data.getWaterMeshVertexCount() : 0,
                meshRebuild ? data.getWaterMeshIndexCount() : 0,
                data.getDropData(),
                data.getAlgaePower(),
                data.getAlgaeWorld(),
                data.getActiveDropCount(),
                data.getGlHeight(),
                data.getBgScale(),
                data.getMeshScaleX(),
                data.getMeshScaleY(),
                data.getDxMul(),
                data.getRotate());
    }

    /**
     * 蓝藻的每帧参数，顺序与 fallvk_jni.cpp 的 unpackAlgae* 一一对应：
     * {@code [0]} 水面可见度，{@code [1]} 叶子受光可见度，
     * {@code [2..6]} 阈值 / 波带 / 增益 / 噪声尺度 / 亮弧尺度，
     * {@code [7..9]} low、{@code [10..12]} high、{@code [13..15]} 叶子受光色，
     * {@code [16..21]} 世界→网格的度量、坐标尺度与偏移。
     *
     * <p>世界→网格的后三项与 {@code FallGL.drawLeaves} 里那组 uniform 同一套算式，
     * 少一项叶子上的光就和水面那圈错位。
     */
    private float[] buildAlgaeParams(FallScene.SceneData data) {
        float[] out = mAlgaeParams;
        float meshScaleX = data.getMeshScaleX();
        float meshScaleY = data.getMeshScaleY();
        float halfH = Math.max(1e-4f, data.getGlHeight() * 0.5f);
        out[0] = mScene.getAlgaeAmount();
        out[1] = mScene.getLeafAlgaeAmount();
        out[2] = FallScene.ALGAE_THRESHOLD;
        out[3] = FallScene.ALGAE_BAND;
        out[4] = FallScene.ALGAE_GAIN;
        out[5] = FallScene.ALGAE_NOISE_TILE;
        out[6] = FallScene.ALGAE_ARC_SCALE;
        out[7] = FallScene.ALGAE_LOW[0];
        out[8] = FallScene.ALGAE_LOW[1];
        out[9] = FallScene.ALGAE_LOW[2];
        out[10] = FallScene.ALGAE_HIGH[0];
        out[11] = FallScene.ALGAE_HIGH[1];
        out[12] = FallScene.ALGAE_HIGH[2];
        out[13] = FallScene.LEAF_LIGHT_COLOR[0];
        out[14] = FallScene.LEAF_LIGHT_COLOR[1];
        out[15] = FallScene.LEAF_LIGHT_COLOR[2];
        out[16] = meshScaleX * data.getDxMul();
        out[17] = meshScaleY / halfH;
        out[18] = meshScaleX;
        out[19] = meshScaleY / halfH;
        out[20] = (data.getXOffset() * 2.0f + 1.0f) * meshScaleX;
        out[21] = meshScaleY;
        return out;
    }

    /** 与 GLES 同源：直接读插件自己的 prefs。 */
    private boolean greenLeavesEnabled() {
        return mPrefs != null && mPrefs.getBoolean(WallpaperSettings.KEY_FALL_GREEN_LEAVES, false);
    }

    @Override
    public void onPreviewChanged(boolean preview) {
        mPreview = preview;
        if (mScene != null) mScene.setPreview(preview);
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
    public void onSceneTouch(float x, float y) {
        if (mScene != null) mScene.addDrop((int) x, (int) y);
    }

    /**
     * fall 特有的滑动手势：按住拖动能连续落叶子，但每移动 42px 才落一片。
     *
     * <p>覆盖了默认实现（默认只把按下转成 onSceneTouch）—— 两者不能同时生效，否则
     * 一次按下会落两片。
     */
    @Override
    public void onTouchEvent(MotionEvent event) {
        if (event == null || mScene == null) return;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int index = event.getActionIndex();
                float x = event.getX(index);
                float y = event.getY(index);
                mTouchTracker.down(event.getPointerId(index), x, y, event.getEventTime());
                mScene.addDrop((int) x, (int) y);
                break;
            }
            case MotionEvent.ACTION_MOVE:
                if (!mScene.isSwipeRippleEnabled()) break;
                for (int i = 0; i < event.getPointerCount(); i++) {
                    float power = mTouchTracker.move(
                            event.getPointerId(i),
                            event.getX(i),
                            event.getY(i),
                            event.getEventTime());
                    if (power >= 0f) {
                        mScene.addDrop((int) event.getX(i), (int) event.getY(i));
                    }
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                mTouchTracker.up(event.getPointerId(event.getActionIndex()));
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mTouchTracker.reset();
                break;
            default:
                break;
        }
    }

    /** 系统指令里的 tap 也落一片。 */
    @Override
    public void onCommand(String action, int x, int y, int z, Bundle extras) {
        if (action != null && action.toLowerCase().contains("tap") && mScene != null) {
            mScene.addDrop(x, y);
        }
    }
}
