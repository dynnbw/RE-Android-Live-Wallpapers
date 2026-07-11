package com.reandroid.update;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.util.Log;
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

    private static final String TAG = "UpdateHelper";
    private static final android.os.Handler sHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

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
                Log.d(TAG, "onUpdateAvailable, showing dialog");
                // Brief delay so the dialog does not race the activity enter transition
                // (this check fires from onResume; showing immediately can clash with the
                // transition and leave the dialog dismissed or mis-stacked).
                sHandler.postDelayed(() -> {
                    if (!activity.isFinishing() && !activity.isDestroyed()) {
                        showUpdateDialog(activity, info);
                    }
                }, 500);
            }

            @Override
            public void onUpToDate() {
                if (silent || activity.isFinishing() || activity.isDestroyed()) return;
                Toast.makeText(activity,
                        activity.getString(R.string.up_to_date_message, BuildConfig.VERSION_NAME),
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String message) {
                if (silent || activity.isFinishing() || activity.isDestroyed()) return;
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
}
