package com.reandroid.settings;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.text.InputType;
import android.text.TextUtils;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.preference.PreferenceManager;

import com.reandroid.utils.DebugExporter;
import com.reandroid.wallpaper.R;
import com.reandroid.weather.WeatherCondition;
import com.reandroid.weather.WeatherManager;
import com.reandroid.weather.WeatherState;

import java.util.Locale;

/**
 * 设置页工具栏统一控制器：天气按钮（弹窗/更新间隔/API Key/调试）、
 * 溢出菜单（全局帧率/重置所有/关于）与溢出键长按调试入口。
 * 主设置页与壁纸全屏预览页共用，保证两页工具栏行为完全一致。
 */
public class SettingsToolbarHelper {

    private static final String KEY_GLOBAL_FRAME_RATE = "global_frame_rate";

    private final AppCompatActivity mActivity;
    private final Toolbar mToolbar;
    private ImageButton weatherButton;
    private WeatherManager weatherManager;
    private WeatherState lastWeatherState;
    private boolean mOverflowHooked;

    public SettingsToolbarHelper(AppCompatActivity activity, Toolbar toolbar) {
        mActivity = activity;
        mToolbar = toolbar;
    }

    /** 挂接天气按钮与天气管理器；在 onCreate 中调用一次。 */
    public void setup() {
        weatherButton = mActivity.findViewById(R.id.toolbar_weather_button);
        if (weatherButton != null) {
            weatherButton.setOnClickListener(v -> showWeatherPopupMenu());
            weatherButton.setOnLongClickListener(v -> {
                showWeatherDebugDialog();
                return true;
            });
        }

        // Restore debug location override
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mActivity);
        float savedLat = prefs.getFloat("debug_lat", Float.NaN);
        if (!Float.isNaN(savedLat)) {
            float savedLng = prefs.getFloat("debug_lng", 0);
            com.reandroid.utils.LocationProvider.setDebugLocation(savedLat, savedLng);
        }

        weatherManager = new WeatherManager(mActivity.getApplicationContext(), state -> {
            mActivity.runOnUiThread(() -> {
                lastWeatherState = state;
                updateWeatherMenuIcon();
            });
        });
    }

    public void onStart() {
        if (weatherManager != null) {
            weatherManager.start();
            lastWeatherState = weatherManager.getLastState();
        }
        updateWeatherMenuIcon();
    }

    public void onStop() {
        if (weatherManager != null) {
            weatherManager.stop();
        }
    }

    public void onDestroy() {
        if (weatherManager != null) {
            weatherManager.release();
            weatherManager = null;
        }
    }

    public void onCreateOptionsMenu(Menu menu) {
        mActivity.getMenuInflater().inflate(R.menu.menu_settings, menu);
        updateWeatherMenuVisibility();
        updateWeatherMenuIcon();
    }

    public void onPrepareOptionsMenu(Menu menu) {
        updateWeatherMenuIcon();
        if (!mOverflowHooked) {
            mOverflowHooked = true;
            mToolbar.post(() -> setOverflowLongPress(mToolbar));
        }
    }

    /** @return true 表示已消费该菜单项 */
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_global_frame_rate) {
            showGlobalFrameRateDialog();
            return true;
        }
        if (item.getItemId() == R.id.action_reset_all) {
            showResetAllDialog();
            return true;
        }
        if (item.getItemId() == R.id.action_about) {
            mActivity.startActivity(new Intent(mActivity, AboutActivity.class));
            return true;
        }
        return false;
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
        Drawable icon = ContextCompat.getDrawable(mActivity, iconRes);
        if (icon != null) {
            weatherButton.setImageDrawable(tintToolbarIcon(icon));
            return;
        }
        Drawable fallback = ContextCompat.getDrawable(mActivity, android.R.drawable.ic_menu_compass);
        if (fallback != null) {
            weatherButton.setImageDrawable(tintToolbarIcon(fallback));
        }
    }

    private Drawable tintToolbarIcon(Drawable drawable) {
        Drawable wrapped = DrawableCompat.wrap(drawable.mutate());
        int tint = ContextCompat.getColor(mActivity, R.color.md_theme_onPrimary);
        DrawableCompat.setTint(wrapped, tint);
        return wrapped;
    }

    private void showWeatherPopupMenu() {
        if (weatherButton == null) {
            return;
        }
        ContextThemeWrapper themedContext = new ContextThemeWrapper(
                mActivity,
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
            if (itemId == R.id.action_weather_source) {
                showWeatherSourceDialog();
                return true;
            }
            return false;
        });
        popupMenu.show();
    }

    private void showWeatherDebugDialog() {
        String[] debugOptions = {"正常天气逻辑", "晴朗", "多云", "阴沉", "雾", "阵雨", "雷暴", "飘雪/雪", "冰冷", "冻雨"};
        new AlertDialog.Builder(mActivity, R.style.ThemeOverlay_WallpaperSettings_AppCompatDialog)
                .setTitle(R.string.pref_weather_debug_title)
                .setItems(debugOptions, (dialog, which) -> {
                    if (which == 0) {
                        if (weatherManager != null) {
                            weatherManager.clearManualOverride();
                        }
                        lastWeatherState = weatherManager != null ? weatherManager.getLastState() : null;
                        updateWeatherMenuIcon();
                        Toast.makeText(mActivity, R.string.pref_weather_debug_restore, Toast.LENGTH_SHORT).show();
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
                        String message = mActivity.getString(R.string.pref_weather_debug_override, debugOptions[which]);
                        Toast.makeText(mActivity, message, Toast.LENGTH_SHORT).show();
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
            return mActivity.getString(R.string.pref_weather_last_refresh_unknown);
        }
        long updateMillis = lastWeatherState.updateUtc * 1000L;
        java.text.DateFormat formatter = android.text.format.DateFormat.getTimeFormat(mActivity);
        String formatted = formatter.format(updateMillis);
        return mActivity.getString(R.string.pref_weather_last_refresh, formatted);
    }

    private void showGlobalFrameRateDialog() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mActivity);
        String[] entries = {"24 FPS", "30 FPS", "45 FPS", "60 FPS", "90 FPS", "120 FPS", "180 FPS"};
        String[] values = {"24", "30", "45", "60", "90", "120", "180"};
        String currentValue = prefs.getString(KEY_GLOBAL_FRAME_RATE, "60");
        int checkedIndex = 0;
        for (int i = 0; i < values.length; i++) {
            if (TextUtils.equals(values[i], currentValue)) {
                checkedIndex = i;
                break;
            }
        }

        new AlertDialog.Builder(mActivity, R.style.ThemeOverlay_WallpaperSettings_AppCompatDialog)
                .setTitle(R.string.pref_global_frame_rate)
                .setSingleChoiceItems(entries, checkedIndex, (dialog, which) -> {
                    prefs.edit().putString(KEY_GLOBAL_FRAME_RATE, values[which]).apply();
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showWeatherUpdateIntervalDialog() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mActivity);
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

        new AlertDialog.Builder(mActivity, R.style.ThemeOverlay_WallpaperSettings_AppCompatDialog)
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
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mActivity);
        EditText input = new EditText(mActivity);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSingleLine(true);
        input.setText(prefs.getString("openweather_api_key", ""));
        input.setSelection(input.getText().length());

        new AlertDialog.Builder(mActivity, R.style.ThemeOverlay_WallpaperSettings_AppCompatDialog)
                .setTitle(R.string.pref_openweather_api_key_title)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    prefs.edit().putString("openweather_api_key", input.getText() == null ? "" : input.getText().toString().trim()).apply();
                    restartWeatherManager();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * 数据源选择。
     *
     * <p>用 {@code setAdapter} 而不是 {@code setSingleChoiceItems}：后者只有一行标题，
     * 而这里每一路都要带一句说明（"需要密钥" / "仅限中国大陆"）。
     *
     * <p>**中国气象局在大陆之外是灰的** —— 那一行的说明就写明了原因。
     * 取数那边还会再判一次，见 {@link WeatherManager#resolveSource()}。
     */
    private void showWeatherSourceDialog() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mActivity);
        String current = prefs.getString(WeatherManager.KEY_SOURCE, WeatherManager.SOURCE_OPENWEATHER);
        boolean cmaAllowed = WeatherManager.isSourceAvailable(WeatherManager.SOURCE_CMA, Locale.getDefault());

        String[] ids = { WeatherManager.SOURCE_OPENWEATHER, WeatherManager.SOURCE_CMA };
        String[] titles = {
                mActivity.getString(R.string.pref_weather_source_openweather),
                mActivity.getString(R.string.pref_weather_source_cma) };
        String[] notes = {
                mActivity.getString(R.string.pref_weather_source_openweather_note),
                mActivity.getString(R.string.pref_weather_source_cma_note) };
        boolean[] enabled = { true, cmaAllowed };

        WeatherSourceAdapter adapter = new WeatherSourceAdapter(
                mActivity, ids, titles, notes, enabled, current);
        AlertDialog dialog = new AlertDialog.Builder(mActivity, R.style.ThemeOverlay_WallpaperSettings_AppCompatDialog)
                .setTitle(R.string.pref_weather_source_title)
                .setAdapter(adapter, (d, which) -> {
                    prefs.edit().putString(WeatherManager.KEY_SOURCE, ids[which]).apply();
                    // 与改密钥同一条路：换源之后立刻重取一次，不然要等下一个周期
                    restartWeatherManager();
                    d.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        // 行内的两个动作要先把这个对话框关掉，否则会叠在它上面
        adapter.setDialog(dialog);
        dialog.show();
    }

    /**
     * 数据源对话框的列表：主标题 + 一句说明，当前选中的打勾，不可用的置灰。
     *
     * <p><b>OpenWeather 那一行还挂两个动作</b>（配置 API / 教程）—— 它们原先是这个弹窗菜单里
     * 的独立两项，但都是"配置 OpenWeather"这一件事的一部分，放在数据源旁边才讲得通。
     * 中国气象局没有密钥，也就没有可配置的东西，那一行的动作块是隐藏的。
     */
    private final class WeatherSourceAdapter extends BaseAdapter {
        private final Context mContext;
        private final String[] mIds;
        private final String[] mTitles;
        private final String[] mNotes;
        private final boolean[] mEnabled;
        private final String mCurrent;
        private AlertDialog mDialog;

        WeatherSourceAdapter(Context context, String[] ids, String[] titles, String[] notes,
                boolean[] enabled, String current) {
            mContext = context;
            mIds = ids;
            mTitles = titles;
            mNotes = notes;
            mEnabled = enabled;
            mCurrent = current;
        }

        void setDialog(AlertDialog dialog) {
            mDialog = dialog;
        }

        @Override
        public int getCount() {
            return mTitles.length;
        }

        @Override
        public Object getItem(int position) {
            return mTitles[position];
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        /** 两行都不可点，只有整行可用时才算可点 —— 否则置灰那行仍会被点中。 */
        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public boolean isEnabled(int position) {
            return mEnabled[position];
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView != null ? convertView
                    : LayoutInflater.from(mContext)
                            .inflate(R.layout.dialog_weather_source_item, parent, false);
            TextView title = row.findViewById(R.id.weather_source_title);
            TextView note = row.findViewById(R.id.weather_source_note);
            View actions = row.findViewById(R.id.weather_source_actions);

            boolean selected = mIds[position].equals(mCurrent);
            title.setText(selected ? "✓ " + mTitles[position] : mTitles[position]);
            note.setText(mNotes[position]);

            // 动作只挂在 OpenWeather 那一行；点它们不该顺带把这一行选中
            boolean withActions = WeatherManager.SOURCE_OPENWEATHER.equals(mIds[position]);
            actions.setVisibility(withActions ? View.VISIBLE : View.GONE);
            if (withActions) {
                TextView configure = row.findViewById(R.id.weather_source_action_api);
                configure.setText(R.string.pref_weather_source_action_api);
                configure.setOnClickListener(v -> {
                    dismissSelf();
                    showWeatherApiKeyDialog();
                });
                TextView guide = row.findViewById(R.id.weather_source_action_guide);
                guide.setText(R.string.pref_weather_source_action_guide);
                guide.setOnClickListener(v -> {
                    dismissSelf();
                    openWeatherApiGuide();
                });
            }

            // 置灰：整行一起淡下去，和"点不动"这件事对上
            row.setAlpha(mEnabled[position] ? 1.0f : 0.4f);
            return row;
        }

        private void dismissSelf() {
            if (mDialog != null && mDialog.isShowing()) {
                mDialog.dismiss();
            }
        }
    }

    private void refreshWeatherNow() {
        if (weatherButton != null) {
            weatherButton.setEnabled(false);
        }
        if (weatherManager == null) {
            weatherManager = new WeatherManager(mActivity.getApplicationContext(), state -> mActivity.runOnUiThread(() -> {
                lastWeatherState = state;
                updateWeatherMenuIcon();
            }));
            weatherManager.start();
        }

        final WeatherManager currentManager = weatherManager;
        weatherManager.refreshNow(state -> mActivity.runOnUiThread(() -> {
            if (mActivity.isFinishing() || mActivity.isDestroyed()) return;
            if (weatherButton != null) {
                weatherButton.setEnabled(true);
            }
            WeatherState resolved = state != null ? state : (currentManager != null ? currentManager.getLastState() : null);
            if (resolved != null) {
                lastWeatherState = resolved;
                updateWeatherMenuIcon();
                Toast.makeText(mActivity, R.string.pref_weather_refresh_now_success, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(mActivity, R.string.pref_weather_refresh_now_failed, Toast.LENGTH_SHORT).show();
            }
        }));
    }

    private void restartWeatherManager() {
        if (weatherManager == null) {
            weatherManager = new WeatherManager(mActivity.getApplicationContext(), state -> mActivity.runOnUiThread(() -> {
                lastWeatherState = state;
                updateWeatherMenuIcon();
            }));
        }
        weatherManager.stop();
        weatherManager.start();
        final WeatherManager currentManager = weatherManager;
        weatherManager.refreshNow(state -> mActivity.runOnUiThread(() -> {
            if (mActivity.isFinishing() || mActivity.isDestroyed()) return;
            WeatherState resolved = state != null ? state : (currentManager != null ? currentManager.getLastState() : null);
            if (resolved != null) {
                lastWeatherState = resolved;
                updateWeatherMenuIcon();
            }
        }));
    }

    private void openWeatherApiGuide() {
        mActivity.startActivity(new Intent(mActivity, OpenWeatherApiGuideActivity.class));
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
                    showDebugPopupMenu(v);
                    return true;
                });
                return;
            }
            if (child instanceof android.view.ViewGroup) {
                setOverflowButtonLongPress((android.view.ViewGroup) child);
            }
        }
    }

    private void showDebugPopupMenu(android.view.View anchor) {
        ContextThemeWrapper themedContext = new ContextThemeWrapper(
                mActivity,
                R.style.ThemeOverlay_WallpaperSettings_ToolbarPopup
        );
        PopupMenu popupMenu = new PopupMenu(themedContext, anchor);
        popupMenu.getMenuInflater().inflate(R.menu.menu_debug_toolbar, popupMenu.getMenu());
        popupMenu.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.action_debug_location_override) {
                showLocationDebugDialog();
                return true;
            }
            if (itemId == R.id.action_export_debug_log) {
                DebugExporter.exportAndShare(mActivity);
                return true;
            }
            if (itemId == R.id.action_clear_debug_log) {
                DebugExporter.clearLogcat(mActivity);
                return true;
            }
            return false;
        });
        popupMenu.show();
    }

    private void showLocationDebugDialog() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mActivity);
        float savedLat = prefs.getFloat("debug_lat", Float.NaN);
        float savedLng = prefs.getFloat("debug_lng", Float.NaN);
        String current = Float.isNaN(savedLat) ? "" : (savedLat + ", " + savedLng);

        EditText input = new EditText(mActivity);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSingleLine(true);
        input.setHint(mActivity.getString(R.string.debug_location_hint));
        if (!current.isEmpty()) input.setText(current);
        input.setSelection(input.getText().length());

        new AlertDialog.Builder(mActivity, R.style.ThemeOverlay_WallpaperSettings_AppCompatDialog)
                .setTitle(R.string.debug_location_title)
                .setMessage(R.string.debug_location_message)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String text = input.getText() == null ? "" : input.getText().toString().trim();
                    if (text.isEmpty()) {
                        prefs.edit().remove("debug_lat").remove("debug_lng").apply();
                        com.reandroid.utils.LocationProvider.setDebugLocation(0, 0);
                        Toast.makeText(mActivity, R.string.debug_location_cleared, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String[] parts = text.split(",");
                    if (parts.length != 2) {
                        Toast.makeText(mActivity, R.string.debug_location_format, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    try {
                        float lat = Float.parseFloat(parts[0].trim());
                        float lng = Float.parseFloat(parts[1].trim());
                        prefs.edit().putFloat("debug_lat", lat).putFloat("debug_lng", lng).apply();
                        com.reandroid.utils.LocationProvider.setDebugLocation(lat, lng);
                        Toast.makeText(mActivity, mActivity.getString(R.string.debug_location_set, lat, lng),
                                Toast.LENGTH_SHORT).show();
                    } catch (NumberFormatException e) {
                        Toast.makeText(mActivity, R.string.debug_location_invalid, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton(R.string.debug_location_clear, (d, w) -> {
                    prefs.edit().remove("debug_lat").remove("debug_lng").apply();
                    com.reandroid.utils.LocationProvider.setDebugLocation(0, 0);
                    Toast.makeText(mActivity, R.string.debug_location_cleared, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showResetAllDialog() {
        new androidx.appcompat.app.AlertDialog.Builder(mActivity)
                .setTitle(R.string.reset_all_settings_title)
                .setMessage(R.string.reset_all_settings_message)
                .setPositiveButton(R.string.reset_action_confirm, (dialog, which) -> {
                    // Save global settings that should survive reset
                    String defName = mActivity.getPackageName() + "_preferences";
                    SharedPreferences mainPrefs = mActivity.getSharedPreferences(defName, Context.MODE_PRIVATE);
                    String apiKey = mainPrefs.getString("openweather_api_key", "");
                    String frameRate = mainPrefs.getString(KEY_GLOBAL_FRAME_RATE, "");
                    String weatherInterval = mainPrefs.getString("weather_update_minutes", "");
                    // 数据源也是全局设置：漏了它，用户重置一次就被悄悄拨回 OpenWeather
                    String weatherSource = mainPrefs.getString(WeatherManager.KEY_SOURCE, "");

                    mainPrefs.edit().clear().apply();
                    java.io.File prefsDir = new java.io.File(mActivity.getApplicationInfo().dataDir, "shared_prefs");
                    if (prefsDir.isDirectory()) {
                        String[] files = prefsDir.list();
                        if (files != null) {
                            for (String f : files) {
                                if (f.startsWith("plugin_") && f.endsWith(".xml")) {
                                    String name = f.substring(0, f.length() - 4);
                                    mActivity.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().apply();
                                }
                            }
                        }
                    }
                    // Restore preserved settings
                    mainPrefs.edit()
                            .putString("openweather_api_key", apiKey)
                            .putString(KEY_GLOBAL_FRAME_RATE, frameRate)
                            .putString("weather_update_minutes", weatherInterval)
                            .putString(WeatherManager.KEY_SOURCE, weatherSource)
                            .apply();
                    android.widget.Toast.makeText(mActivity, R.string.reset_all_settings_done,
                            android.widget.Toast.LENGTH_SHORT).show();
                    mActivity.recreate();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}