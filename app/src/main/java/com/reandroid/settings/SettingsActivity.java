
package com.reandroid.settings;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;

import android.view.ContextThemeWrapper;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ImageButton;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.reandroid.update.UpdateHelper;
import com.reandroid.wallpaper.R;
import com.reandroid.weather.WeatherCondition;
import com.reandroid.weather.WeatherManager;
import com.reandroid.weather.WeatherState;

public class SettingsActivity extends AppCompatActivity
    implements PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    private static final String KEY_GLOBAL_FRAME_RATE = "global_frame_rate";
    private static final String KEY_PREVIEW_RATIO = "pref_preview_ratio";
    private static final String DEFAULT_PREVIEW_RATIO = "9:16";
    private static final String KEY_PREVIEW = "pref_preview";

    private boolean mUpdateChecked;
    private Toolbar mToolbar;
    private ImageButton weatherButton;
    private WeatherManager weatherManager;
    private WeatherState lastWeatherState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 高版本自动应用 Monet 动态取色
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Toolbar toolbar = findViewById(R.id.toolbar);
        mToolbar = toolbar;
        setSupportActionBar(toolbar);
        weatherButton = findViewById(R.id.toolbar_weather_button);
        if (weatherButton != null) {
            weatherButton.setOnClickListener(v -> showWeatherPopupMenu());
            weatherButton.setOnLongClickListener(v -> {
                showWeatherDebugDialog();
                return true;
            });
        }


        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.settings_container, new SettingsMainFragment())
                    .commit();
            setTitle(R.string.settings_title);
        }

        // Restore debug location override
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        float savedLat = prefs.getFloat("debug_lat", Float.NaN);
        if (!Float.isNaN(savedLat)) {
            float savedLng = prefs.getFloat("debug_lng", 0);
            com.reandroid.wallpaper.grass.GrassDayNightSystem.setDebugLocation(savedLat, savedLng);
        }

        weatherManager = new WeatherManager(getApplicationContext(), state -> {
            runOnUiThread(() -> {
                lastWeatherState = state;
                updateWeatherMenuIcon();
            });
        });

        getSupportFragmentManager().addOnBackStackChangedListener(() -> {
            if (getSupportFragmentManager().getBackStackEntryCount() == 0) {
                setTitle(R.string.settings_title);
            }
            updateWeatherMenuVisibility();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!mUpdateChecked) {
            mUpdateChecked = true;
            UpdateHelper.checkAndShow(this, true);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (weatherManager != null) {
            weatherManager.start();
            lastWeatherState = weatherManager.getLastState();
        }
        updateWeatherMenuIcon();
    }

    @Override
    protected void onStop() {
        if (weatherManager != null) {
            weatherManager.stop();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (weatherManager != null) {
            weatherManager.release();
            weatherManager = null;
        }
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_settings, menu);
        updateWeatherMenuVisibility();
        updateWeatherMenuIcon();
        return true;
    }

    private boolean mOverflowHooked;

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        updateWeatherMenuIcon();
        if (!mOverflowHooked) {
            mOverflowHooked = true;
            mToolbar.post(() -> setOverflowLongPress(mToolbar));
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_global_frame_rate) {
            showGlobalFrameRateDialog();
            return true;
        }
        if (item.getItemId() == R.id.action_preview_ratio) {
            showPreviewRatioDialog();
            return true;
        }
        if (item.getItemId() == R.id.action_reset_all) {
            showResetAllDialog();
            return true;
        }
        if (item.getItemId() == R.id.action_about) {
            startActivity(new Intent(this, AboutActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onPreferenceStartFragment(PreferenceFragmentCompat caller, Preference pref) {
        String fragmentClass = pref.getFragment();
        if (fragmentClass == null) return false;
        androidx.fragment.app.Fragment fragment = getSupportFragmentManager()
                .getFragmentFactory()
                .instantiate(getClassLoader(), fragmentClass);
        fragment.setArguments(pref.getExtras());

        getSupportFragmentManager().beginTransaction()
                .replace(R.id.settings_container, fragment)
                .addToBackStack(pref.getKey())
                .commit();

        CharSequence title = pref.getTitle();
        if (title != null) {
            setTitle(title);
        }
        supportInvalidateOptionsMenu();
        return true;
    }

    @Override
    public boolean onSupportNavigateUp() {
        if (getSupportFragmentManager().popBackStackImmediate()) {
            return true;
        }
        return super.onSupportNavigateUp();
    }

    private void updateWeatherMenuVisibility() {
        if (weatherButton == null) {
            return;
        }
        weatherButton.setVisibility(ImageButton.VISIBLE);
    }

    private void updateWeatherMenuIcon() {
        if (weatherButton == null) {
            return;
        }
        int iconRes = getWeatherIconRes(lastWeatherState);
        Drawable icon = ContextCompat.getDrawable(this, iconRes);
        if (icon != null) {
            weatherButton.setImageDrawable(tintToolbarIcon(icon));
            return;
        }
        Drawable fallback = ContextCompat.getDrawable(this, android.R.drawable.ic_menu_compass);
        if (fallback != null) {
            weatherButton.setImageDrawable(tintToolbarIcon(fallback));
        }
    }

    private Drawable tintToolbarIcon(Drawable drawable) {
        Drawable wrapped = DrawableCompat.wrap(drawable.mutate());
        int tint = ContextCompat.getColor(this, R.color.md_theme_onPrimary);
        DrawableCompat.setTint(wrapped, tint);
        return wrapped;
    }

    private void showWeatherPopupMenu() {
        if (weatherButton == null) {
            return;
        }
        ContextThemeWrapper themedContext = new ContextThemeWrapper(
                this,
                R.style.ThemeOverlay_WallpaperSettings_ToolbarPopup
        );
        PopupMenu popupMenu = new PopupMenu(themedContext, weatherButton);
        popupMenu.getMenuInflater().inflate(R.menu.menu_weather_toolbar, popupMenu.getMenu());
        MenuItem lastRefreshItem = popupMenu.getMenu().findItem(R.id.action_weather_last_refresh);
        if (lastRefreshItem != null) {
            lastRefreshItem.setTitle(getWeatherLastRefreshTitle());
            lastRefreshItem.setEnabled(false);
        }
        popupMenu.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.action_weather_update_interval) {
                showWeatherUpdateIntervalDialog();
                return true;
            }
            if (itemId == R.id.action_weather_refresh_now) {
                refreshWeatherNow();
                return true;
            }
            if (itemId == R.id.action_weather_api_key) {
                showWeatherApiKeyDialog();
                return true;
            }
            if (itemId == R.id.action_weather_apply_api) {
                openWeatherApiGuide();
                return true;
            }
            return false;
        });
        popupMenu.show();
    }

    private void showWeatherDebugDialog() {
        String[] debugOptions = {"正常天气逻辑", "晴朗", "多云", "阴沉", "雾", "阵雨", "雷暴", "飘雪/雪", "冰冷", "冻雨"};
        new AlertDialog.Builder(this, R.style.ThemeOverlay_WallpaperSettings_AppCompatDialog)
                .setTitle(R.string.pref_weather_debug_title)
                .setItems(debugOptions, (dialog, which) -> {
                    if (which == 0) {
                        if (weatherManager != null) {
                            weatherManager.clearManualOverride();
                        }
                        lastWeatherState = weatherManager != null ? weatherManager.getLastState() : null;
                        updateWeatherMenuIcon();
                        Toast.makeText(this, R.string.pref_weather_debug_restore, Toast.LENGTH_SHORT).show();
                        return;
                    }

                    WeatherCondition[] conditions = WeatherCondition.values();
                    int conditionIndex = which - 1;
                    if (conditionIndex >= 0 && conditionIndex < conditions.length) {
                        WeatherCondition selected = conditions[conditionIndex];
                        boolean isNight = isNightTime();
                        long nowUtc = System.currentTimeMillis() / 1000L;
                        WeatherState overrideState = new WeatherState(selected, isNight, 0.0f, 0.0f,
                                0L, 0L, nowUtc);
                        if (weatherManager != null) {
                            weatherManager.setManualOverride(overrideState);
                        }
                        lastWeatherState = overrideState;
                        updateWeatherMenuIcon();
                        String message = getString(R.string.pref_weather_debug_override, debugOptions[which]);
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private boolean isNightTime() {
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        int hour = calendar.get(java.util.Calendar.HOUR_OF_DAY);
        return hour < 6 || hour >= 18;
    }

    private String getWeatherLastRefreshTitle() {
        if (lastWeatherState == null || lastWeatherState.updateUtc <= 0L) {
            return getString(R.string.pref_weather_last_refresh_unknown);
        }
        long updateMillis = lastWeatherState.updateUtc * 1000L;
        java.text.DateFormat formatter = android.text.format.DateFormat.getTimeFormat(this);
        String formatted = formatter.format(updateMillis);
        return getString(R.string.pref_weather_last_refresh, formatted);
    }

    private void showGlobalFrameRateDialog() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String[] entries = {"test", "24 FPS", "30 FPS", "45 FPS", "60 FPS", "90 FPS", "120 FPS", "180 FPS"};
        String[] values = {"1", "24", "30", "45", "60", "90", "120", "180"};
        String currentValue = prefs.getString(KEY_GLOBAL_FRAME_RATE, "60");
        int checkedIndex = 0;
        for (int i = 0; i < values.length; i++) {
            if (TextUtils.equals(values[i], currentValue)) {
                checkedIndex = i;
                break;
            }
        }

        new AlertDialog.Builder(this, R.style.ThemeOverlay_WallpaperSettings_AppCompatDialog)
                .setTitle(R.string.pref_global_frame_rate)
                .setSingleChoiceItems(entries, checkedIndex, (dialog, which) -> {
                    prefs.edit().putString(KEY_GLOBAL_FRAME_RATE, values[which]).apply();
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showPreviewRatioDialog() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSingleLine(true);
        input.setText(prefs.getString(KEY_PREVIEW_RATIO, DEFAULT_PREVIEW_RATIO));
        input.setSelection(input.getText().length());

        new AlertDialog.Builder(this, R.style.ThemeOverlay_WallpaperSettings_AppCompatDialog)
                .setTitle(R.string.pref_preview_ratio)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String normalized = normalizeRatio(input.getText() == null ? null : input.getText().toString());
                    if (normalized == null) {
                        Toast.makeText(this, R.string.pref_preview_ratio_invalid, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    prefs.edit().putString(KEY_PREVIEW_RATIO, normalized).apply();
                    notifyPreviewRatioChanged();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showWeatherUpdateIntervalDialog() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String[] entries = {"15 分钟", "30 分钟", "1 小时", "3 小时"};
        String[] values = {"15", "30", "60", "180"};
        String currentValue = prefs.getString("weather_update_minutes", "30");
        int checkedIndex = 0;
        for (int i = 0; i < values.length; i++) {
            if (TextUtils.equals(values[i], currentValue)) {
                checkedIndex = i;
                break;
            }
        }

        new AlertDialog.Builder(this, R.style.ThemeOverlay_WallpaperSettings_AppCompatDialog)
                .setTitle(R.string.pref_weather_update_interval)
                .setSingleChoiceItems(entries, checkedIndex, (dialog, which) -> {
                    prefs.edit().putString("weather_update_minutes", values[which]).apply();
                    restartWeatherManager();
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showWeatherApiKeyDialog() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSingleLine(true);
        input.setText(prefs.getString("openweather_api_key", ""));
        input.setSelection(input.getText().length());

        new AlertDialog.Builder(this, R.style.ThemeOverlay_WallpaperSettings_AppCompatDialog)
                .setTitle(R.string.pref_openweather_api_key_title)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    prefs.edit().putString("openweather_api_key", input.getText() == null ? "" : input.getText().toString().trim()).apply();
                    restartWeatherManager();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void refreshWeatherNow() {
        if (weatherButton != null) {
            weatherButton.setEnabled(false);
        }
        if (weatherManager == null) {
            weatherManager = new WeatherManager(getApplicationContext(), state -> runOnUiThread(() -> {
                lastWeatherState = state;
                updateWeatherMenuIcon();
            }));
            weatherManager.start();
        }

        final WeatherManager currentManager = weatherManager;
        weatherManager.refreshNow(state -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (weatherButton != null) {
                weatherButton.setEnabled(true);
            }
            WeatherState resolved = state != null ? state : (currentManager != null ? currentManager.getLastState() : null);
            if (resolved != null) {
                lastWeatherState = resolved;
                updateWeatherMenuIcon();
                Toast.makeText(this, R.string.pref_weather_refresh_now_success, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, R.string.pref_weather_refresh_now_failed, Toast.LENGTH_SHORT).show();
            }
        }));
    }

    private void restartWeatherManager() {
        if (weatherManager == null) {
            weatherManager = new WeatherManager(getApplicationContext(), state -> runOnUiThread(() -> {
                lastWeatherState = state;
                updateWeatherMenuIcon();
            }));
        }
        weatherManager.stop();
        weatherManager.start();
        final WeatherManager currentManager = weatherManager;
        weatherManager.refreshNow(state -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            WeatherState resolved = state != null ? state : (currentManager != null ? currentManager.getLastState() : null);
            if (resolved != null) {
                lastWeatherState = resolved;
                updateWeatherMenuIcon();
            }
        }));
    }

    private void openWeatherApiGuide() {
        startActivity(new Intent(this, OpenWeatherApiGuideActivity.class));
    }

    @Nullable
    private String normalizeRatio(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String raw = value.trim();
        if (TextUtils.isEmpty(raw)) {
            return null;
        }
        String[] parts = raw.split(":");
        if (parts.length != 2) {
            return null;
        }
        try {
            int width = Integer.parseInt(parts[0].trim());
            int height = Integer.parseInt(parts[1].trim());
            if (width <= 0 || height <= 0) {
                return null;
            }
            return width + ":" + height;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int getWeatherIconRes(WeatherState state) {
        if (state == null || state.condition == null) {
            return R.drawable.weather_day_sunny;
        }
        boolean isNight = state.isNight;
        WeatherCondition condition = state.condition;
        switch (condition) {
            case D1_CLEAR:
                return isNight ? R.drawable.weather_night_sunny : R.drawable.weather_day_sunny;
            case D2_CLOUDY:
                return isNight ? R.drawable.weather_night_cloudy : R.drawable.weather_day_cloudy;
            case D3_DREARY:
                return R.drawable.weather_drealy;
            case D4_FOG:
                return isNight ? R.drawable.weather_night_fog : R.drawable.weather_day_fog;
            case D5_RAIN_SHOWERS:
                return isNight ? R.drawable.weather_night_rain : R.drawable.weather_day_rain;
            case D6_THUNDERSTORMS:
                return isNight ? R.drawable.weather_night_lightning : R.drawable.weather_day_lightning;
            case D7_FLURRIES_SNOW:
                return isNight ? R.drawable.weather_night_snow : R.drawable.weather_day_snow;
            case D8_ICE_COLD:
                return R.drawable.weather_snowflake_cold;
            case D9_SLEET:
                return isNight ? R.drawable.weather_night_sleet : R.drawable.weather_day_sleet;
            default:
                return R.drawable.weather_day_sunny;
        }
    }

    private void notifyPreviewRatioChanged() {
        for (androidx.fragment.app.Fragment fragment : getSupportFragmentManager().getFragments()) {
            if (!(fragment instanceof PreferenceFragmentCompat)) {
                continue;
            }
            PreferenceFragmentCompat prefFragment = (PreferenceFragmentCompat) fragment;
            Preference previewPref = prefFragment.findPreference(KEY_PREVIEW);
            if (previewPref instanceof PreviewPreference) {
                ((PreviewPreference) previewPref).refreshPreviewRatioNow();
            }
        }
    }

    private void setOverflowLongPress(android.view.ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            android.view.View child = parent.getChildAt(i);
            if (child.getClass().getName().contains("ActionMenuView")) {
                setOverflowButtonLongPress((android.view.ViewGroup) child);
                return;
            }
            if (child instanceof android.view.ViewGroup) {
                setOverflowLongPress((android.view.ViewGroup) child);
            }
        }
    }

    private void setOverflowButtonLongPress(android.view.ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            android.view.View child = parent.getChildAt(i);
            if (child instanceof android.widget.ImageButton
                    || child instanceof android.widget.ImageView) {
                child.setOnLongClickListener(v -> {
                    showLocationDebugDialog();
                    return true;
                });
                return;
            }
            if (child instanceof android.view.ViewGroup) {
                setOverflowButtonLongPress((android.view.ViewGroup) child);
            }
        }
    }

    private void showLocationDebugDialog() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        float savedLat = prefs.getFloat("debug_lat", Float.NaN);
        float savedLng = prefs.getFloat("debug_lng", Float.NaN);
        String current = Float.isNaN(savedLat) ? "" : (savedLat + ", " + savedLng);

        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSingleLine(true);
        input.setHint("lat, lng (e.g. 51.5074, -0.1278)");
        if (!current.isEmpty()) input.setText(current);
        input.setSelection(input.getText().length());

        new AlertDialog.Builder(this, R.style.ThemeOverlay_WallpaperSettings_AppCompatDialog)
                .setTitle("Debug Location Override")
                .setMessage("Override GPS lat/lng for sun/moon calculation.\nLeave empty to use real GPS.")
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String text = input.getText() == null ? "" : input.getText().toString().trim();
                    if (text.isEmpty()) {
                        prefs.edit().remove("debug_lat").remove("debug_lng").apply();
                        com.reandroid.wallpaper.grass.GrassDayNightSystem.setDebugLocation(0, 0);
                        Toast.makeText(this, "Debug location cleared", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String[] parts = text.split(",");
                    if (parts.length != 2) {
                        Toast.makeText(this, "Format: lat, lng", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    try {
                        float lat = Float.parseFloat(parts[0].trim());
                        float lng = Float.parseFloat(parts[1].trim());
                        prefs.edit().putFloat("debug_lat", lat).putFloat("debug_lng", lng).apply();
                        com.reandroid.wallpaper.grass.GrassDayNightSystem.setDebugLocation(lat, lng);
                        Toast.makeText(this, "Debug location set: " + lat + ", " + lng,
                                Toast.LENGTH_SHORT).show();
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "Invalid numbers", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton("Clear", (d, w) -> {
                    prefs.edit().remove("debug_lat").remove("debug_lng").apply();
                    com.reandroid.wallpaper.grass.GrassDayNightSystem.setDebugLocation(0, 0);
                    Toast.makeText(this, "Debug location cleared", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showResetAllDialog() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.reset_all_settings_title)
                .setMessage(R.string.reset_all_settings_message)
                .setPositiveButton(R.string.reset_action_confirm, (dialog, which) -> {
                    String defName = getPackageName() + "_preferences";
                    getSharedPreferences(defName, MODE_PRIVATE).edit().clear().apply();
                    java.io.File prefsDir = new java.io.File(getApplicationInfo().dataDir, "shared_prefs");
                    if (prefsDir.isDirectory()) {
                        String[] files = prefsDir.list();
                        if (files != null) {
                            for (String f : files) {
                                if (f.startsWith("plugin_") && f.endsWith(".xml")) {
                                    String name = f.substring(0, f.length() - 4);
                                    getSharedPreferences(name, MODE_PRIVATE).edit().clear().apply();
                                }
                            }
                        }
                    }
                    android.widget.Toast.makeText(this, R.string.reset_all_settings_done,
                            android.widget.Toast.LENGTH_SHORT).show();
                    recreate();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
