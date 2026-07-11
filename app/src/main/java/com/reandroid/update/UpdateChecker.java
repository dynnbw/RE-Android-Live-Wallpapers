package com.reandroid.update;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.reandroid.wallpaper.BuildConfig;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 版本更新检查器，先从 GitHub 原始链接拉取，超时则回退到 jsDelivr CDN。
 */
public class UpdateChecker {

    private static final String TAG = "UpdateChecker";

    private static final String URL_PRIMARY =
            "https://raw.githubusercontent.com/dynnbw/RE-Android-Live-Wallpapers/main/version.json";
    private static final String URL_FALLBACK =
            "https://cdn.jsdelivr.net/gh/dynnbw/RE-Android-Live-Wallpapers@main/version.json";

    private static final int TIMEOUT_PRIMARY_MS = 5000;
    private static final int TIMEOUT_FALLBACK_MS = 10000;

    private static final ExecutorService sExecutor = Executors.newSingleThreadExecutor();
    private static final Handler sHandler = new Handler(Looper.getMainLooper());

    public interface Callback {
        void onUpdateAvailable(VersionInfo info);
        void onUpToDate();
        void onError(String message);
    }

    public static void check(Callback callback) {
        sExecutor.execute(() -> {
            // 优先用 GitHub 原始链接，确保推送后立即生效
            String raw = fetch(URL_PRIMARY, TIMEOUT_PRIMARY_MS);
            if (raw == null) {
                Log.d(TAG, "Primary URL failed, falling back to CDN");
                raw = fetch(URL_FALLBACK, TIMEOUT_FALLBACK_MS);
            }
            if (raw == null) {
                post(() -> callback.onError("Network unreachable"));
                return;
            }

            try {
                if (BuildConfig.DEBUG) Log.d(TAG, "version.json response: " + raw);

                JSONObject json = new JSONObject(raw);
                VersionInfo info = new VersionInfo();
                info.versionCode = json.getInt("versionCode");
                info.versionName = json.optString("versionName", "");

                if (json.opt("changelog") instanceof JSONObject) {
                    JSONObject cl = json.getJSONObject("changelog");
                    info.changelogEn = cl.optString("en", "");
                    info.changelogZh = cl.optString("zh", info.changelogEn);
                } else {
                    info.changelogEn = json.optString("changelog", "");
                    info.changelogZh = info.changelogEn;
                }

                Log.d(TAG, "remote versionCode=" + info.versionCode
                        + " local=" + BuildConfig.VERSION_CODE
                        + " remote versionName=" + info.versionName
                        + " local=" + BuildConfig.VERSION_NAME);

                if (info.versionCode > BuildConfig.VERSION_CODE) {
                    post(() -> callback.onUpdateAvailable(info));
                } else {
                    post(() -> callback.onUpToDate());
                }
            } catch (Exception e) {
                Log.e(TAG, "Check failed", e);
                post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    @androidx.annotation.Nullable
    private static String fetch(String urlStr, int timeoutMs) {
        HttpURLConnection conn = null;
        BufferedReader reader = null;
        try {
            URL url = new URL(urlStr + "?t=" + System.currentTimeMillis());
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod("GET");
            conn.setUseCaches(false);
            conn.setRequestProperty("Cache-Control", "no-cache, no-store");

            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                return null;
            }

            reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } catch (Exception e) {
            Log.d(TAG, "Fetch failed for " + urlStr + ": " + e.getMessage());
            return null;
        } finally {
            // Release the connection and reader on every path (including exception),
            // otherwise the socket leaks when getResponseCode/getInputStream/readLine throw.
            if (reader != null) {
                try { reader.close(); } catch (IOException ignored) {}
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static void post(Runnable r) {
        sHandler.post(r);
    }
}
