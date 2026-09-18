package com.reandroid.settings;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.reandroid.wallpaper.R;

public final class MiuiPermissionHelper {
    private static final String TAG = "MiuiPermissionHelper";

    /** 旧位置：独立的 "wallpaper_prefs"，只在迁移时读一次，随后删除。 */
    private static final String LEGACY_PREFS_NAME = "wallpaper_prefs";
    private static final String KEY_DIALOG_SHOWN = "miui_permission_dialog_shown";

    private MiuiPermissionHelper() {}

    public static void launchLivePreview(Fragment fragment, Class<?> wallpaperClass) {
        if (isMIUI() && !hasShownDialog(fragment.requireContext())) {
            showPermissionDialog(fragment, wallpaperClass);
            return;
        }
        try {
            Intent intent = new Intent("android.service.wallpaper.CHANGE_LIVE_WALLPAPER");
            intent.putExtra("android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT",
                    new ComponentName(fragment.requireContext(), wallpaperClass));
            fragment.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(fragment.requireContext(), R.string.pref_open_wallpaper_picker_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private static boolean isMIUI() {
        String v = getSystemProperty("ro.miui.ui.version.name", "");
        if (!v.isEmpty()) return true;
        v = getSystemProperty("ro.miui.ui.version.code", "");
        return !v.isEmpty();
    }

    private static String getSystemProperty(String key, String def) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            return (String) sp.getMethod("get", String.class, String.class).invoke(null, key, def);
        } catch (Exception e) { return def; }
    }

    /**
     * 存在应用默认 prefs（与天气 API、调试开关同一份），不再单开一个文件。
     * 见 {@link #migrateLegacyFlag} 对旧位置的迁移。
     */
    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    private static boolean hasShownDialog(Context context) {
        SharedPreferences prefs = prefs(context);
        if (prefs.contains(KEY_DIALOG_SHOWN)) {
            return prefs.getBoolean(KEY_DIALOG_SHOWN, false);
        }
        return migrateLegacyFlag(context, prefs);
    }

    /**
     * 把旧 "wallpaper_prefs" 里的标记搬到默认 prefs，然后删掉旧文件，
     * 避免两个来源并存（这个文件里本来也只有这一个键）。
     */
    private static boolean migrateLegacyFlag(Context context, SharedPreferences prefs) {
        boolean shown = false;
        try {
            SharedPreferences legacy =
                    context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE);
            shown = legacy.getBoolean(KEY_DIALOG_SHOWN, false);
            if (shown) {
                prefs.edit().putBoolean(KEY_DIALOG_SHOWN, true).apply();
            }
            if (legacy.contains(KEY_DIALOG_SHOWN)) {
                context.deleteSharedPreferences(LEGACY_PREFS_NAME);
            }
        } catch (Exception e) {
            Log.w(TAG, "迁移旧 wallpaper_prefs 失败，按未弹过处理", e);
        }
        return shown;
    }

    private static void markDialogShown(Context context) {
        prefs(context).edit().putBoolean(KEY_DIALOG_SHOWN, true).apply();
    }

    private static void showPermissionDialog(Fragment fragment, Class<?> wallpaperClass) {
        Context ctx = fragment.requireContext();
        new AlertDialog.Builder(ctx, R.style.ThemeOverlay_WallpaperSettings_AppCompatDialog)
                .setTitle(R.string.miui_permission_title)
                .setMessage(R.string.miui_permission_message)
                .setPositiveButton(R.string.miui_permission_go_settings, (d, w) -> {
                    markDialogShown(ctx);
                    try {
                        fragment.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(Uri.parse("package:" + ctx.getPackageName())));
                    } catch (Exception e) {
                        Toast.makeText(ctx, R.string.miui_permission_open_failed, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
