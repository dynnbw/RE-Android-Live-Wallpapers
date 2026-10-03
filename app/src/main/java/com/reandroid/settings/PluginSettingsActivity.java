package com.reandroid.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.FrameLayout;

import androidx.appcompat.app.AppCompatActivity;

import com.reandroid.gles.GLESPreviewView;
import com.reandroid.gles.GLESScene;
import com.reandroid.plugin.PluginPrefsInjector;
import com.reandroid.plugin.PluginResources;
import com.reandroid.plugin.PluginSettingsFragment;
import com.reandroid.plugin.ProxyWallpaperService;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPreview;
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
    /** 容器里那块视图；渲染控制走 {@link #mPreview}。 */
    private android.view.View mPreviewView;

    /** 与之配套的渲染控制（GL 与 VK 两种实现）。 */
    private com.reandroid.plugin.WallpaperPreview mPreview;

    private String mPreviewClass;
    /** 这个插件的 Vulkan 插件类名；没有 VK 版时为 null。 */
    private String mPluginVkClass;

    private boolean mPreviewStopped;
    private SettingsToolbarHelper mToolbarHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wallpaper_preview);

        // 与主设置页一致：挂接支持 ActionBar，返回箭头 + 标题（setTitle 经此渲染）
        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        // 天气按钮 + 溢出菜单（全局帧率/重置所有/关于）+ 调试入口，与主设置页共用控制器
        mToolbarHelper = new SettingsToolbarHelper(this, toolbar);
        mToolbarHelper.setup();

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
        mPluginVkClass = info != null ? info.optString("pluginVk", null) : null;
        setTitle(PluginResources.resolveLabel(this, mPluginId, info));

        mPreviewContainer = findViewById(R.id.preview_container);
        createPreviewView();

        PluginSettingsFragment fragment = (PluginSettingsFragment)
                getSupportFragmentManager().findFragmentById(R.id.settings_container);
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
        if (mToolbarHelper != null) mToolbarHelper.onStart();
        if (mPreviewStopped) {
            createPreviewView(); // 重新挂载视图，surfaceCreated 触发渲染线程重启
        }
    }

    @Override
    protected void onStop() {
        if (mToolbarHelper != null) mToolbarHelper.onStop();
        if (mPreview != null) {
            mPreview.stopRenderer();
            mPreviewStopped = true;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (mToolbarHelper != null) mToolbarHelper.onDestroy();
        if (mPreview != null) {
            mPreview.stopRenderer();
            mPreviewView = null;
            mPreview = null;
        }
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (mToolbarHelper != null) {
            mToolbarHelper.onCreateOptionsMenu(menu);
            return true;
        }
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        if (mToolbarHelper != null) mToolbarHelper.onPrepareOptionsMenu(menu);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (mToolbarHelper != null && mToolbarHelper.onOptionsItemSelected(item)) {
            return true;
        }
        return super.onOptionsItemSelected(item);
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
    public Object getScene() {
        return mPreview != null ? mPreview.getScene() : null;
    }

    @Override
    public void refreshPreview() {
        runOnUiThread(this::createPreviewView);
    }

    /**
     * 建预览。渲染器跟随「使用 Vulkan」开关 —— 打开且这个插件有 VK 预览时用 VK，
     * 否则退回 OpenGL ES。
     *
     * <p>不跟随的话，用户在预览里看到的和实际应用后的渲染器就是两回事：VK 在设备上跑不起来
     * 也看不出来，只能等应用完壁纸才发现画面是空的。
     */
    /**
     * 开关打开时让插件交出它的 VK 预览；否则返回 null。
     *
     * <p>「能不能用 Vulkan」由插件自己判断 —— 预览视图与那个壁纸的 {@code nIsVulkanSupported()}
     * 同一个包，只有它够得着。这里只负责读开关、实例化插件类。
     */
    private WallpaperPreview createVulkanPreview() {
        if (mPluginVkClass == null) return null;
        // 与 ProxyWallpaperService.loadPlugin 读的是同一份偏好（插件作用域）
        SharedPreferences prefs = getSharedPreferences("plugin_" + mPluginId, Context.MODE_PRIVATE);
        if (!prefs.getBoolean("use_vulkan", false)) return null;
        try {
            WallpaperPlugin plugin = (WallpaperPlugin)
                    Class.forName(mPluginVkClass).getDeclaredConstructor().newInstance();
            android.view.View view = plugin.createVulkanPreview(this);
            if (view instanceof WallpaperPreview) return (WallpaperPreview) view;
            if (view != null) Log.w(TAG, "VK 预览视图没有实现 WallpaperPreview，忽略");
        } catch (Exception e) {
            Log.w(TAG, "VK 预览建不起来，退回 OpenGL ES", e);
        }
        return null;
    }

    private void createPreviewView() {
        if (mPreviewContainer == null) return;
        if (mPreviewView != null) {
            mPreviewContainer.removeView(mPreviewView);
            if (mPreview != null) mPreview.stopRenderer();
            mPreviewView = null;
            mPreview = null;
        }

        WallpaperPreview vk = createVulkanPreview();
        if (vk != null) {
            mPreview = vk;
            mPreviewView = (android.view.View) vk;
        } else {
            if (mPreviewClass == null) return;
            GLESPreviewView gl = new GLESPreviewView(this, this::createScene);
            mPreview = gl;
            mPreviewView = gl;
        }
        mPreviewView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
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
                    scene = (GLESScene) clz.getConstructor(int.class, int.class).newInstance(w, h);
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
        PluginPrefsInjector.inject(
                scene,
                getSharedPreferences("plugin_" + mPluginId, Context.MODE_PRIVATE),
                pluginId -> getSharedPreferences("plugin_" + pluginId, Context.MODE_PRIVATE));
    }
}
