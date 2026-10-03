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
    /** 滑动时每隔这么远落一片叶子，免得手指一动就刷一片。 */
    private static final float TOUCH_TRIGGER_DISTANCE_THRESHOLD_PX = 42.0f;

    private final Context mContext;
    private final SharedPreferences mPrefs;

    private FallScene mScene;
    private long mRendererHandle;
    private int mLeafTextureCount = 1;
    private boolean mGreenLeavesEnabled;
    private long mLastAtlasSyncCheckMs;
    private float mLastTouchTriggerX = -1.0f;
    private float mLastTouchTriggerY = -1.0f;

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
        return mScene != null;
    }

    @Override
    public void ensureScene(int width, int height) {
        if (width <= 0 || height <= 0) return;
        if (mScene == null) {
            mScene = new FallScene(width, height);
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
            mLeafTextureCount = Math.max(1, FallVKNative.uploadTextures(mContext, mRendererHandle));
            mGreenLeavesEnabled = WallpaperSettings.isGreenLeavesEnabled(false);
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
        FallVKNative.nOnSurfaceCreated(mRendererHandle, surface, width, height);
    }

    @Override
    public void onSurfaceChanged(Surface surface, int width, int height) {
        FallVKNative.nOnSurfaceChanged(mRendererHandle, surface, width, height);
    }

    @Override
    public void onSurfaceDestroyed() {
        FallVKNative.nOnSurfaceDestroyed(mRendererHandle);
    }

    /** 绿叶图集切换后要重传（同样每秒查一次设置）。 */
    @Override
    public void syncTexturesIfNeeded() {
        if (mRendererHandle == 0L) return;
        long now = SystemClock.uptimeMillis();
        if (now - mLastAtlasSyncCheckMs < SETTINGS_SYNC_INTERVAL_MS) return;
        mLastAtlasSyncCheckMs = now;
        boolean enabled = WallpaperSettings.isGreenLeavesEnabled(false);
        if (enabled == mGreenLeavesEnabled) return;
        mLeafTextureCount = Math.max(1, FallVKNative.uploadTextures(mContext, mRendererHandle));
        mGreenLeavesEnabled = enabled;
        if (mScene != null) mScene.setLeafTextureCount(mLeafTextureCount);
    }

    @Override
    public void renderFrame() {
        long now = SystemClock.uptimeMillis();
        mScene.update(now);
        FallScene.SceneData data = mScene.getSceneData();
        float[] leafData = mScene.buildLeafDataForVK();
        int leafCount = mScene.getVKLeafCount();
        boolean meshRebuild = mScene.consumeMeshBufferRebuildRequested();
        FallVKNative.nRenderFrame(
                mRendererHandle,
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
                data.getActiveDropCount(),
                data.getGlHeight(),
                data.getBgScale(),
                data.getMeshScaleX(),
                data.getMeshScaleY(),
                data.getDxMul(),
                data.getRotate());
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
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mScene.addDrop((int) x, (int) y);
                mLastTouchTriggerX = x;
                mLastTouchTriggerY = y;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!mScene.isSwipeRippleEnabled()) break;
                if (mLastTouchTriggerX < 0.0f || mLastTouchTriggerY < 0.0f) {
                    mScene.addDrop((int) x, (int) y);
                    mLastTouchTriggerX = x;
                    mLastTouchTriggerY = y;
                    break;
                }
                float dx = x - mLastTouchTriggerX;
                float dy = y - mLastTouchTriggerY;
                if ((float) Math.sqrt(dx * dx + dy * dy) >= TOUCH_TRIGGER_DISTANCE_THRESHOLD_PX) {
                    mScene.addDrop((int) x, (int) y);
                    mLastTouchTriggerX = x;
                    mLastTouchTriggerY = y;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mLastTouchTriggerX = -1.0f;
                mLastTouchTriggerY = -1.0f;
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
