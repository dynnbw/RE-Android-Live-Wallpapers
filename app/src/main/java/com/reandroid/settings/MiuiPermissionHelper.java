package com.reandroid.settings;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import com.reandroid.wallpaper.R;

public final class MiuiPermissionHelper {
    private static final String PREFS_NAME = "wallpaper_prefs";
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

    private static boolean hasShownDialog(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_DIALOG_SHOWN, false);
    }

    private static void markDialogShown(Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_DIALOG_SHOWN, true).apply();
    }

    private static void showPermissionDialog(Fragment fragment, Class<?> wallpaperClass) {
        Context ctx = fragment.requireContext();
        new AlertDialog.Builder(ctx)
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
                .setNeutralButton(R.string.miui_permission_continue, (d, w) -> {
                    markDialogShown(ctx);
                    launchLivePreview(fragment, wallpaperClass);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
