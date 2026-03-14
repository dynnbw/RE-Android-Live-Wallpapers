package com.reandroid.wallpaper.fall;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

public class FallVK extends Activity {
    private FallVKSurfaceView mPreviewView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!supportsVulkan()) {
            Toast.makeText(this, "Vulkan not available on this device", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        mPreviewView = new FallVKSurfaceView(this);
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
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && FallVKNative.nIsVulkanSupported();
    }
}
