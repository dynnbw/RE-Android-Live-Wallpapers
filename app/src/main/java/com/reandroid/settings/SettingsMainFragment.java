package com.reandroid.settings;

import android.app.WallpaperManager;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceFragmentCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.reandroid.wallpaper.R;

public class SettingsMainFragment extends PreferenceFragmentCompat {
    private static final int WALLPAPER_GRID_SPAN_COUNT = 2;
    private static final String KEY_OPEN_WALLPAPER_CHOOSER = "pref_open_wallpaper_chooser";
    private static final int FULL_WIDTH_SPACING_DP = -8;
    private static final int GRID_EDGE_SPACING_DP = 8;
    private static final int GRID_MIDDLE_SPACING_DP = -10;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.prefs_settings, rootKey);
        applyHomeLayouts();

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

    private boolean isFullWidthPreference(@Nullable Preference preference) {
        if (preference == null) {
            return true;
        }
        String key = preference.getKey();
        return KEY_OPEN_WALLPAPER_CHOOSER.equals(key);
    }

    private int dpToPx(int dp) {
        float density = requireContext().getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    private void openLiveWallpaperChooser() {
        Intent intent = new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER);
        startActivity(intent);
    }
}
