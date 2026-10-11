package com.reandroid.update;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import com.reandroid.wallpaper.R;

/**
 * APK 下载器，使用系统 DownloadManager 下载到公共下载目录。
 * 下载完成后通过广播通知，用户可点击系统通知安装。
 */
public class UpdateDownloader {
    private static final String TAG = "UpdateDownloader";

    private static final String URL_PREFIX =
            "https://github.com/dynnbw/RE-Android-Live-Wallpapers/releases/download/";

    /** Strict version-name format; versionName is server-supplied and concatenated into the
     *  download URL and destination filename, so reject anything that could malform them. */
    private static final java.util.regex.Pattern VERSION_NAME_PATTERN =
            java.util.regex.Pattern.compile("^[0-9A-Za-z.\\-]+$");

    private final Context mContext;
    private long mDownloadId;
    private boolean mRegistered;
    private Callback mCallback;

    public interface Callback {
        void onDownloadStarted();

        void onComplete(Uri apkUri);

        void onError(String message);
    }

    public UpdateDownloader(Context context) {
        mContext = context.getApplicationContext();
    }

    /** 只接受 https —— 免得清单把下载引到 file:// / content:// 之类的地方。 */
    private static boolean isAcceptableUrl(String url) {
        return url != null && url.startsWith("https://");
    }

    /**
     * 开始下载 APK，通过系统 DownloadManager 执行。
     *
     * @param option 清单里指定的直连入口；为 null 时退回写死的 GitHub Releases 前缀
     *               （老清单没有 downloads 字段时走这条）
     */
    public void download(VersionInfo info, VersionInfo.Download option, Callback callback) {
        // Guard against re-entry: cancel any in-flight download before starting a new one,
        // otherwise the old receiver is orphaned (leaked on the application Context) and
        // mDownloadId is overwritten, losing the first download's completion handling.
        if (mRegistered) {
            cancel();
        }

        mCallback = callback;

        // versionName is server-supplied and concatenated into the URL and filename —
        // reject anything outside a strict version format to avoid path/URL manipulation.
        if (info.versionName == null
                || !VERSION_NAME_PATTERN.matcher(info.versionName).matches()) {
            if (mCallback != null) mCallback.onError("Invalid version name: " + info.versionName);
            return;
        }

        String filename = "REWallpapers_v" + info.versionName + ".apk";
        String url;
        if (option != null && isAcceptableUrl(option.url)) {
            // 地址由清单提供 —— 被篡改的后果只是"下不到"，装不进假包：APK 用同一把签名密钥，
            // 签名不同的更新 Android 会拒绝安装。
            url = option.url;
        } else {
            url = URL_PREFIX + "v" + info.versionName + "/app-release.apk";
        }

        DownloadManager dm = (DownloadManager) mContext.getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
        request.setTitle(mContext.getString(R.string.update_downloading_title));
        request.setDescription(filename);
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
        request.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setMimeType("application/vnd.android.package-archive");

        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        /*
         * Android 14（targetSdk 34）起，注册接收者必须显式声明导出性，否则**抛 SecurityException**，
         * 没人接就当场杀掉进程 —— 这一句曾让"直接下载"在所有 Android 14+ 上闪退
         * （2026-10-11 实测：进程死在 registerReceiver 上）。
         *
         * 我们只收系统的下载完成广播，所以 NOT_EXPORTED：系统广播照常送达，别的应用发不进来。
         * 不写 ContextCompat.registerReceiver(...)：那组常量要 androidx.core 1.9+，而这里是
         * 传递依赖来的 1.5.0。平台常量是编译期内联的，放在版本判断里对旧设备也没有风险。
         */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            mContext.registerReceiver(mReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            mContext.registerReceiver(mReceiver, filter);
        }
        mRegistered = true;

        mDownloadId = dm.enqueue(request);
        if (mCallback != null) mCallback.onDownloadStarted();
    }

    /** Cancel any in-flight download and unregister the receiver. Safe to call from the host
     *  Activity/Fragment onDestroy to release the receiver when navigating away mid-download. */
    public void cancel() {
        if (mDownloadId != 0) {
            try {
                DownloadManager dm =
                        (DownloadManager) mContext.getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm != null) dm.remove(mDownloadId);
            } catch (Exception e) {
                Log.w(TAG, "Failed to remove download", e);
            }
            mDownloadId = 0;
        }
        unregister();
        mCallback = null;
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (id != mDownloadId) return;

            unregister();

            DownloadManager dm =
                    (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Query query = new DownloadManager.Query();
            query.setFilterById(mDownloadId);

            try (android.database.Cursor cursor = dm.query(query)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int status =
                            cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS));
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        Uri uri = dm.getUriForDownloadedFile(mDownloadId);
                        if (uri == null) {
                            if (mCallback != null) mCallback.onError("Downloaded file URI is null");
                        } else if (mCallback != null) {
                            mCallback.onComplete(uri);
                        }
                    } else {
                        if (mCallback != null) mCallback.onError("Download status: " + status);
                    }
                }
            }
        }
    };

    private void unregister() {
        if (mRegistered) {
            try {
                mContext.unregisterReceiver(mReceiver);
            } catch (Exception e) {
                Log.w(TAG, "Failed to unregister download receiver", e);
            }
            mRegistered = false;
        }
    }
}
