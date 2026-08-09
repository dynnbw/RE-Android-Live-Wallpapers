package com.reandroid.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.widget.FrameLayout;

import androidx.appcompat.app.AppCompatActivity;

import com.reandroid.gles.GLESPreviewView;
import com.reandroid.gles.GLESScene;
import com.reandroid.plugin.PluginResources;
import com.reandroid.plugin.PluginSettingsFragment;
import com.reandroid.plugin.ProxyWallpaperService;
import com.reandroid.wallpaper.R;

import org.json.JSONObject;

/**
 * 全屏壁纸预览 + 底部抽屉设置。
 * 入口：应用内磁贴点按（extra EXTRA_PLUGIN_ID）或系统壁纸预览设置齿轮（读取当前激活插件）。
 */
public class PluginSettingsActivity extends AppCompatActivity
        implements PluginSettingsFragment.PreviewHost {

    private static final String TAG = "PluginSettingsActivity";

    public static final String EXTRA_PLUGIN_ID = "plugin_id";

    private String mPluginId;
    private FrameLayout mPreviewContainer;
    private GLESPreviewView mPreviewView;
    private String mPreviewClass;
    private boolean mPreviewStopped;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wallpaper_preview);

        // 与主设置页一致：挂接支持 ActionBar，返回箭头 + 标题（setTitle 经此渲染）
        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        mPluginId = getIntent().getStringExtra(EXTRA_PLUGIN_ID);
        if (mPluginId == null) {
            mPluginId = ProxyWallpaperService.getActivePlugin(this);
        }
        if (mPluginId == null) {
            finish();
            return;
        }

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        JSONObject info = PluginResources.loadInfo(this, mPluginId);
        mPreviewClass = info != null ? info.optString("previewClass", null) : null;
        setTitle(PluginResources.resolveLabel(this, mPluginId, info));

        mPreviewContainer = findViewById(R.id.preview_container);
        createPreviewView();

        PluginSettingsFragment fragment = (PluginSettingsFragment) getSupportFragmentManager()
                .findFragmentById(R.id.settings_container);
        if (fragment == null) {
            fragment = PluginSettingsFragment.newInstance(mPluginId);
            getSupportFragmentManager()
                    .beginTransaction()
                    .add(R.id.settings_container, fragment)
                    .commit();
        }
        fragment.setPreviewHost(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mPreviewStopped) {
            createPreviewView(); // 重新挂载视图，surfaceCreated 触发渲染线程重启
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mPreviewView != null) {
            mPreviewView.stopRenderer();
            mPreviewStopped = true;
        }
    }

    @Override
    protected void onDestroy() {
        if (mPreviewView != null) {
            mPreviewView.stopRenderer();
            mPreviewView = null;
        }
        super.onDestroy();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    public void onBackPressed() {
        BottomSheetPanel sheet = findViewById(R.id.bottom_sheet);
        if (sheet != null && sheet.collapseToPeek()) {
            return; // 抽屉展开时返回键先收起
        }
        super.onBackPressed();
    }

    // ==================== PreviewHost ====================

    @Override
    public GLESScene createScene(int width, int height) {
        return createPreviewScene(mPreviewClass, width, height);
    }

    @Override
    public GLESScene getScene() {
        return mPreviewView != null ? mPreviewView.getScene() : null;
    }

    @Override
    public void refreshPreview() {
        runOnUiThread(this::createPreviewView);
    }

    private void createPreviewView() {
        if (mPreviewContainer == null) return;
        if (mPreviewView != null) {
            mPreviewContainer.removeView(mPreviewView);
            mPreviewView.stopRenderer();
            mPreviewView = null;
        }
        if (mPreviewClass == null) return;
        mPreviewView = new GLESPreviewView(this, this::createScene);
        mPreviewView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        mPreviewContainer.addView(mPreviewView);
        mPreviewStopped = false;
    }

    private GLESScene createPreviewScene(String className, int w, int h) {
        if (className == null) return null;
        try {
            Class<?> clz = Class.forName(className);
            GLESScene scene = null;
            try {
                scene = (GLESScene) clz.getConstructor(int.class, int.class, Context.class)
                        .newInstance(w, h, this);
            } catch (NoSuchMethodException e1) {
                try {
                    scene = (GLESScene) clz.getConstructor(Context.class, int.class, int.class)
                            .newInstance(this, w, h);
                } catch (NoSuchMethodException e2) {
                    scene = (GLESScene) clz.getConstructor(int.class, int.class)
                            .newInstance(w, h);
                }
            }
            injectPluginPrefs(scene);
            return scene;
        } catch (Exception e) {
            Log.w(TAG, "Failed to create preview scene", e);
            return null;
        }
    }

    private void injectPluginPrefs(GLESScene scene) {
        try {
            java.lang.reflect.Method m = scene.getClass()
                    .getMethod("setPluginPrefs", SharedPreferences.class);
            m.invoke(scene, getSharedPreferences("plugin_" + mPluginId, Context.MODE_PRIVATE));
        } catch (Exception e) {
            Log.w(TAG, "Failed to inject prefs into preview scene", e);
        }
    }
}
