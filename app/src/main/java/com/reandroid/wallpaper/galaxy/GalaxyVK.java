package com.reandroid.wallpaper.galaxy;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Galaxy Vulkan 预览 Activity。
 *
 * 说明：
 * 1) 与原 OpenGL 版本并行存在，不修改任何已有 OpenGL 类。
 * 2) 预览和壁纸服务都走同一条 Vulkan 原生渲染路径。
 */
public class GalaxyVK extends Activity {
     private GalaxyVKSurfaceView mPreviewView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!supportsVulkan()) {
            Toast.makeText(this, "Vulkan not available on this device", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        mPreviewView = new GalaxyVKSurfaceView(this);
        setContentView(mPreviewView);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mPreviewView != null) {
            mPreviewView.resumeRenderer();
        }
    }

    @Override
    protected void onPause() {
        if (mPreviewView != null) {
            mPreviewView.pauseRenderer();
        }
        super.onPause();
        finish();
    }

    @Override
    protected void onDestroy() {
        if (mPreviewView != null) {
            mPreviewView.releaseRenderer();
        }
        super.onDestroy();
    }

    private boolean supportsVulkan() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && GalaxyVKNative.nIsVulkanSupported();
    }
}
