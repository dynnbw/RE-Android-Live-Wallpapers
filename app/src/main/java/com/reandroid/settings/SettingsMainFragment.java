package com.reandroid.settings;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.preference.PreferenceViewHolder;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.reandroid.gles.GlCapabilities;
import com.reandroid.utils.IoUtils;
import com.reandroid.wallpaper.R;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;

public class SettingsMainFragment extends PreferenceFragmentCompat {
    private static final String TAG = "SettingsMainFragment";
    private static final int WALLPAPER_GRID_SPAN_COUNT = 2;
    private static final String KEY_OPEN_WALLPAPER_CHOOSER = "pref_open_wallpaper_chooser";
    private static final String KEY_BANNER_CAROUSEL = "pref_banner_carousel";
    private static final String KEY_GRID_FEEDBACK = "pref_grid_feedback";
    private static final int FULL_WIDTH_SPACING_DP = 4;
    private static final int GRID_EDGE_SPACING_DP = 8;
    private static final int GRID_MIDDLE_SPACING_DP = 8;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.prefs_settings, rootKey);
        addDynamicEntries(getPreferenceScreen());
        applyHomeLayouts();
        // 顺序不能颠倒：applyHomeLayouts 会把所有网格项的布局重设成壁纸磁贴
        addFeedbackTileIfOdd(getPreferenceScreen());

        Preference openChooser = findPreference("pref_open_wallpaper_chooser");
        if (openChooser != null) {
            openChooser.setOnPreferenceClickListener(pref -> {
                openLiveWallpaperChooser();
                return true;
            });
        }
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        RecyclerView listView = getListView();
        GridLayoutManager layoutManager = new GridLayoutManager(requireContext(), WALLPAPER_GRID_SPAN_COUNT);
        layoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                PreferenceGroup screen = getPreferenceScreen();
                if (screen == null || position < 0 || position >= screen.getPreferenceCount()) {
                    return WALLPAPER_GRID_SPAN_COUNT;
                }

                Preference preference = screen.getPreference(position);
                return isFullWidthPreference(preference) ? WALLPAPER_GRID_SPAN_COUNT : 1;
            }
        });
        listView.setLayoutManager(layoutManager);
        listView.addItemDecoration(new RecyclerView.ItemDecoration() {
            private final int fullWidthSpacingPx = dpToPx(FULL_WIDTH_SPACING_DP);
            private final int gridEdgeSpacingPx = dpToPx(GRID_EDGE_SPACING_DP);
            private final int gridMiddleSpacingPx = dpToPx(GRID_MIDDLE_SPACING_DP);

            @Override
            public void getItemOffsets(Rect outRect, View view, RecyclerView parent, RecyclerView.State state) {
                int position = parent.getChildAdapterPosition(view);
                if (position == RecyclerView.NO_POSITION) {
                    outRect.set(0, 0, 0, 0);
                    return;
                }

                PreferenceGroup screen = getPreferenceScreen();
                if (screen == null || position >= screen.getPreferenceCount()) {
                    outRect.set(0, 0, 0, 0);
                    return;
                }

                Preference preference = screen.getPreference(position);
                if (isFullWidthPreference(preference)) {
                    outRect.set(fullWidthSpacingPx, fullWidthSpacingPx, fullWidthSpacingPx, fullWidthSpacingPx);
                    return;
                }

                boolean isLeftColumn = (position % WALLPAPER_GRID_SPAN_COUNT) == 1;
                int left = isLeftColumn ? gridEdgeSpacingPx : gridMiddleSpacingPx / 2;
                int right = isLeftColumn ? gridMiddleSpacingPx / 2 : gridEdgeSpacingPx;
                outRect.set(left, fullWidthSpacingPx, right, fullWidthSpacingPx);
            }
        });
    }

    private void addDynamicEntries(PreferenceScreen screen) {
        AssetManager am = requireContext().getAssets();
        try {
            String[] dirs = am.list("");
            if (dirs == null) return;
            for (String dir : dirs) {
                String jsonPath = dir + "/info.json";
                String fragmentClass = null;
                String label = null;
                String pluginClass = null;
                boolean useLegacySettings = false;
                boolean hidden = false;
                int minGlVersion = 2;
                try (InputStream is = am.open(jsonPath)) {
                    JSONObject json = new JSONObject(new String(IoUtils.readAllBytes(is), "UTF-8"));
                    fragmentClass = json.optString("fragment", null);
                    label = json.optString("label", null);
                    pluginClass = json.optString("plugin", null);
                    useLegacySettings = json.optBoolean("useLegacySettings", false);
                    hidden = json.optBoolean("hidden", false);
                    minGlVersion = json.optInt("minGlVersion", 2);
                } catch (Exception e) { Log.w(TAG, "Failed to parse info.json", e); continue; }
                // 隐藏入口：info.json 中 "hidden": true 时不在列表显示
                if (label == null || hidden) continue;
                // GL 门槛：声明了 "minGlVersion": 3 的壁纸(用了 #version 300 es)
                // 在只支持 ES2 的设备上不出现——它是个可选壁纸，藏起来比让用户
                // 选中后黑屏好。只在真有壁纸声明门槛时才去探测设备能力。
                if (minGlVersion > 2) {
                    GlCapabilities.probe();
                    if (GlCapabilities.getMajorVersion() < minGlVersion) {
                        Log.i(TAG, "跳过 " + dir + ": 需要 ES" + minGlVersion
                                + "，设备为 ES" + GlCapabilities.getMajorVersion());
                        continue;
                    }
                }

                // Resolve @string/ references
                String title = label;
                if (label.startsWith("@string/")) {
                    int id = getResources().getIdentifier(
                            label.substring(8), "string", requireContext().getPackageName());
                    if (id != 0) title = getString(id);
                }

                Preference entry = new WallpaperGridPreference(requireContext());
                entry.setTitle(title);
                entry.setLayoutResource(R.layout.preference_wallpaper_grid_item);
                entry.setIcon(loadWallpaperIcon(am, dir));
                if (pluginClass != null && !useLegacySettings) {
                    String finalDir = dir;
                    entry.setOnPreferenceClickListener(pref -> {
                        Intent intent = new Intent(requireContext(), PluginSettingsActivity.class);
                        intent.putExtra(PluginSettingsActivity.EXTRA_PLUGIN_ID, finalDir);
                        startActivity(intent);
                        return true;
                    });
                } else {
                    entry.setFragment(fragmentClass);
                }
                screen.addPreference(entry);
            }
        } catch (Exception e) { Log.w(TAG, "Failed to build wallpaper list", e); }
    }

    private void applyHomeLayouts() {
        PreferenceGroup screen = getPreferenceScreen();
        if (screen == null) {
            return;
        }

        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            Preference preference = screen.getPreference(i);
            if (!isFullWidthPreference(preference)) {
                preference.setLayoutResource(R.layout.preference_wallpaper_grid_item);
            }
        }
    }

    /**
     * 壁纸数量为奇数时最后一格会落单、右侧留半行空白。补一个同尺寸的占位磁贴填满：
     * 做成反馈入口（提 issue / 壁纸移植请求），点击打开仓库 issues。
     * 数量为偶数时该格不存在，网格本来就对称。
     */
    private void addFeedbackTileIfOdd(PreferenceScreen screen) {
        if (screen == null) {
            return;
        }

        int gridCount = 0;
        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            if (!isFullWidthPreference(screen.getPreference(i))) {
                gridCount++;
            }
        }
        if (gridCount % WALLPAPER_GRID_SPAN_COUNT == 0) {
            return;
        }

        // 用磁贴 Preference：卡片内容层自持水波纹，点击转发给根布局才触发这里的动作
        Preference feedback = new WallpaperGridPreference(requireContext());
        feedback.setKey(KEY_GRID_FEEDBACK);
        feedback.setPersistent(false);
        feedback.setTitle(getString(R.string.grid_feedback_title));
        feedback.setLayoutResource(R.layout.preference_wallpaper_grid_cta);
        feedback.setOnPreferenceClickListener(pref -> {
            openUrl(getString(R.string.grid_feedback_url));
            return true;
        });
        screen.addPreference(feedback);
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(requireContext(), R.string.no_browser_found, Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isFullWidthPreference(@Nullable Preference preference) {
        if (preference == null) {
            return true;
        }
        String key = preference.getKey();
        return KEY_OPEN_WALLPAPER_CHOOSER.equals(key) || KEY_BANNER_CAROUSEL.equals(key);
    }

    private int dpToPx(int dp) {
        float density = requireContext().getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    /** 网格磁贴：卡片内容层自持水波纹（clickable），点击转发给根布局触发 Preference 动作 */
    private static class WallpaperGridPreference extends Preference {
        WallpaperGridPreference(Context context) {
            super(context);
        }

        @Override
        public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
            super.onBindViewHolder(holder);
            View content = holder.itemView.findViewById(R.id.tile_content);
            if (content != null) {
                content.setOnClickListener(v -> holder.itemView.performClick());
            }
        }
    }

    /** 从 assets/{dir}/ 加载壁纸缩略图（icon.png 或 icon.jpg），失败回退占位图 */
    private Drawable loadWallpaperIcon(AssetManager am, String dir) {
        Bitmap bmp = decodeIcon(am, dir, "icon.png");
        if (bmp == null) bmp = decodeIcon(am, dir, "icon.jpg");
        if (bmp == null) {
            return ContextCompat.getDrawable(requireContext(), R.drawable.ic_wallpaper_placeholder);
        }
        return new BitmapDrawable(getResources(), bmp);
    }

    private Bitmap decodeIcon(AssetManager am, String dir, String name) {
        try (InputStream is = am.open(dir + "/" + name)) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(is, null, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            int sample = 1;
            int maxDim = Math.max(bounds.outWidth, bounds.outHeight);
            while (maxDim / sample > 640) sample *= 2;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            try (InputStream is2 = am.open(dir + "/" + name)) {
                return BitmapFactory.decodeStream(is2, null, opts);
            }
        } catch (IOException e) {
            return null;
        }
    }

    private void openLiveWallpaperChooser() {
        Intent intent = new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER);
        startActivity(intent);
    }
}
