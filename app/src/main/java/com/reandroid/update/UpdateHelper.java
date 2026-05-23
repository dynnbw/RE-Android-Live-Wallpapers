package com.reandroid.update;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.reandroid.wallpaper.BuildConfig;
import com.reandroid.wallpaper.R;

import java.util.Locale;

/**
 * 更新对话框和下载辅助方法，供 Activity 和 Fragment 共用。
 */
public final class UpdateHelper {

    private UpdateHelper() {}

    private static String getChangelog(VersionInfo info) {
        String lang = Locale.getDefault().getLanguage();
        if ("zh".equals(lang)) {
            return info.changelogZh != null && !info.changelogZh.isEmpty()
                    ? info.changelogZh : info.changelogEn;
        }
        return info.changelogEn != null ? info.changelogEn : "";
    }

    public static void showUpdateDialog(Activity activity, VersionInfo info) {
        String changelog = getChangelog(info);
        String message = !changelog.isEmpty()
                ? changelog + "\n\n" + activity.getString(R.string.app_version_summary, info.versionName)
                : activity.getString(R.string.app_version_summary, info.versionName);

        new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.update_available_title, info.versionName))
                .setMessage(message)
                .setPositiveButton(R.string.update_button, (dialog, which) -> startDownload(activity, info))
                .setNegativeButton(R.string.update_later_button, null)
                .show();
    }

    public static void showUpdateDialog(Fragment fragment, VersionInfo info) {
        showUpdateDialog(fragment.requireActivity(), info);
    }

    public static void checkAndShow(Fragment fragment) {
        UpdateChecker.check(new UpdateChecker.Callback() {
            @Override
            public void onUpdateAvailable(VersionInfo info) {
                if (fragment.isAdded()) showUpdateDialog(fragment, info);
            }

            @Override
            public void onUpToDate() {
                if (!fragment.isAdded()) return;
                Toast.makeText(fragment.getContext(),
                        fragment.getString(R.string.up_to_date_message, BuildConfig.VERSION_NAME),
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String message) {
                if (!fragment.isAdded()) return;
                Toast.makeText(fragment.getContext(),
                        fragment.getString(R.string.update_check_failed, message),
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    public static void checkAndShow(Activity activity, boolean silent) {
        UpdateChecker.check(new UpdateChecker.Callback() {
            @Override
            public void onUpdateAvailable(VersionInfo info) {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                android.util.Log.d("UpdateHelper", "onUpdateAvailable, showing dialog");
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    if (!activity.isFinishing() && !activity.isDestroyed()) {
                        showUpdateDialog(activity, info);
                    }
                }, 500);
            }

            @Override
            public void onUpToDate() {
                if (silent || activity.isFinishing()) return;
                Toast.makeText(activity,
                        activity.getString(R.string.up_to_date_message, BuildConfig.VERSION_NAME),
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String message) {
                if (silent || activity.isFinishing()) return;
                Toast.makeText(activity,
                        activity.getString(R.string.update_check_failed, message),
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static void startDownload(Context context, VersionInfo info) {
        UpdateDownloader downloader = new UpdateDownloader(context);
        downloader.download(info, new UpdateDownloader.Callback() {
            @Override
            public void onDownloadStarted() {
                Toast.makeText(context, R.string.update_downloading_title, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onComplete(Uri apkUri) {}

            @Override
            public void onError(String message) {
                Toast.makeText(context,
                        context.getString(R.string.update_check_failed, message),
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ---- 24h 缓存 ----

    private static final String PREFS_NAME = "update_prefs";
    private static final String KEY_LAST_CHECK = "last_check_time";

    public static boolean shouldCheck(Context context) {
        long last = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_CHECK, 0);
        return System.currentTimeMillis() - last > 24 * 3600 * 1000L;
    }

    public static void recordCheck(Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                .apply();
    }
}
