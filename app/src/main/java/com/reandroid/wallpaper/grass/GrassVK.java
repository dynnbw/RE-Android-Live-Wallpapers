package com.reandroid.wallpaper.grass;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

public class GrassVK extends Activity {
    private GrassVKSurfaceView mPreviewView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!supportsVulkan()) {
            Toast.makeText(this, "Vulkan not available on this device", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        mPreviewView = new GrassVKSurfaceView(this);
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
        return GrassVKNative.nIsVulkanSupported();
    }
}
