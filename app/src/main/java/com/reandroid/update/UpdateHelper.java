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
                    ? info.changelogZh
                    : info.changelogEn;
        }
        return info.changelogEn != null ? info.changelogEn : "";
    }

    public static void showUpdateDialog(Activity activity, VersionInfo info) {
        String changelog = getChangelog(info);
        String message = !changelog.isEmpty()
                ? changelog + "\n\n"
                        + activity.getString(R.string.app_version_summary, info.versionName)
                : activity.getString(R.string.app_version_summary, info.versionName);

        /*
         * 给哪些下载入口由"清单是从哪取到的"决定：能连境外的人不需要网盘（国内网盘对他
         * 多半也不可用），只有国内源能取到的人直连又大概率下不动，判不出来的两种都给。
         * 见 VersionInfo.Source。
         */
        final java.util.List<VersionInfo.Download> options = info.downloadOptionsFor(info.source);

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.update_available_title, info.versionName))
                .setMessage(message)
                .setNegativeButton(R.string.update_later_button, null);

        if (options.isEmpty()) {
            // 没有可用入口就别把人堵死：退回老行为，直接下 GitHub Releases
            builder.setPositiveButton(
                    R.string.update_button, (dialog, which) -> startDownload(activity, info, null));
        } else if (options.size() == 1) {
            VersionInfo.Download only = options.get(0);
            builder.setPositiveButton(
                    labelFor(activity, only), (dialog, which) -> startOption(activity, info, only));
        } else {
            String[] labels = new String[options.size()];
            for (int i = 0; i < options.size(); i++) labels[i] = labelFor(activity, options.get(i));
            builder.setItems(
                    labels, (dialog, which) -> startOption(activity, info, options.get(which)));
        }
        builder.show();
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
                Toast.makeText(
                                fragment.getContext(),
                                fragment.getString(
                                        R.string.up_to_date_message, BuildConfig.VERSION_NAME),
                                Toast.LENGTH_SHORT)
                        .show();
            }

            @Override
            public void onError(String message) {
                if (!fragment.isAdded()) return;
                Toast.makeText(
                                fragment.getContext(),
                                fragment.getString(R.string.update_check_failed, message),
                                Toast.LENGTH_SHORT)
                        .show();
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
                sHandler.postDelayed(
                        () -> {
                            if (!activity.isFinishing() && !activity.isDestroyed()) {
                                showUpdateDialog(activity, info);
                            }
                        },
                        500);
            }

            @Override
            public void onUpToDate() {
                if (silent || activity.isFinishing() || activity.isDestroyed()) return;
                Toast.makeText(
                                activity,
                                activity.getString(
                                        R.string.up_to_date_message, BuildConfig.VERSION_NAME),
                                Toast.LENGTH_SHORT)
                        .show();
            }

            @Override
            public void onError(String message) {
                if (silent || activity.isFinishing() || activity.isDestroyed()) return;
                Toast.makeText(
                                activity,
                                activity.getString(R.string.update_check_failed, message),
                                Toast.LENGTH_SHORT)
                        .show();
            }
        });
    }

    /** 直接下载用固定文案；外部入口用清单给的名字（"蓝奏云"），所以不必翻译。 */
    private static String labelFor(Context context, VersionInfo.Download option) {
        if (option.isDirect()) return context.getString(R.string.update_download_direct);
        return option.label != null && !option.label.isEmpty()
                ? option.label
                : context.getString(R.string.update_button);
    }

    private static void startOption(
            Context context, VersionInfo info, VersionInfo.Download option) {
        if (option == null || option.isDirect()) {
            startDownload(context, info, option);
        } else {
            openExternally(context, option.url);
        }
    }

    /** 网盘/商店这类入口：交给浏览器，用户自己下。 */
    private static void openExternally(Context context, String url) {
        try {
            android.content.Intent intent =
                    new android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "No activity for " + url, e);
            Toast.makeText(context, R.string.update_open_failed, Toast.LENGTH_SHORT)
                    .show();
        }
    }

    private static void startDownload(
            Context context, VersionInfo info, VersionInfo.Download option) {
        UpdateDownloader downloader = new UpdateDownloader(context);
        downloader.download(info, option, new UpdateDownloader.Callback() {
            @Override
            public void onDownloadStarted() {
                Toast.makeText(context, R.string.update_downloading_title, Toast.LENGTH_SHORT)
                        .show();
            }

            @Override
            public void onComplete(Uri apkUri) {}

            @Override
            public void onError(String message) {
                Toast.makeText(
                                context,
                                context.getString(R.string.update_check_failed, message),
                                Toast.LENGTH_SHORT)
                        .show();
            }
        });
    }
}
