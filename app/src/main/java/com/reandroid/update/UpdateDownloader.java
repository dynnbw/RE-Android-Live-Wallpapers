package com.reandroid.update;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.util.Log;
import android.os.Environment;

import com.reandroid.wallpaper.R;

/**
 * APK 下载器，使用系统 DownloadManager 下载到公共下载目录。
 * 下载完成后通过广播通知，用户可点击系统通知安装。
 */
public class UpdateDownloader {
    private static final String TAG = "UpdateDownloader";

    private static final String PRIMARY_URL_PREFIX =
            "https://github.com/dynnbw/RE-Android-Live-Wallpapers/releases/download/";

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

    /**
     * 开始下载 APK，通过系统 DownloadManager 执行。
     * 直连 GitHub，国内用户可在浏览器手动添加加速镜像。
     */
    public void download(VersionInfo info, Callback callback) {
        mCallback = callback;

        String filename = "REWallpapers_v" + info.versionName + ".apk";
        String url = PRIMARY_URL_PREFIX + "v" + info.versionName + "/app-release.apk";

        DownloadManager dm = (DownloadManager) mContext.getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
        request.setTitle(mContext.getString(R.string.update_downloading_title));
        request.setDescription(filename);
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
        request.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setMimeType("application/vnd.android.package-archive");

        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        mContext.registerReceiver(mReceiver, filter);
        mRegistered = true;

        mDownloadId = dm.enqueue(request);
        if (mCallback != null) mCallback.onDownloadStarted();
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (id != mDownloadId) return;

            unregister();

            DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Query query = new DownloadManager.Query();
            query.setFilterById(mDownloadId);

            try (android.database.Cursor cursor = dm.query(query)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int status = cursor.getInt(
                            cursor.getColumnIndex(DownloadManager.COLUMN_STATUS));
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        Uri uri = dm.getUriForDownloadedFile(mDownloadId);
                        if (mCallback != null) mCallback.onComplete(uri);
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
            } catch (Exception e) { Log.w(TAG, "Failed to unregister download receiver", e); }
            mRegistered = false;
        }
    }
}
