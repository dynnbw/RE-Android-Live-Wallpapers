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
 * 版本更新检查器：按顺序尝试多个源，用**第一个能取到的**。
 *
 * <p>顺序是刻意的：<b>先探境外，再给国内，jsDelivr 垫底</b>。这样"清单从哪来"本身就是
 * 一次地区探测 —— 见 {@link VersionInfo.Source}，由界面决定给哪种下载入口。
 *
 * <p>jsDelivr 之所以排在 Gitee **之后**：它在国内也能通，只是慢（实测首次 13 秒）。
 * 把它放前面会让国内用户既等得久，又被误判成"能连境外"，从而看不到网盘。
 *
 * <p>对国内用户这不是浪费：{@code raw.githubusercontent.com} 在这里是**立刻**连接失败
 * （实测 0.13 秒，不是等超时），所以多试一个源几乎不花时间。
 */
public class UpdateChecker {

    private static final String TAG = "UpdateChecker";

    private static final String REPO = "dynnbw/RE-Android-Live-Wallpapers";

    /** 一个清单来源，以及"从这里取到说明用户在哪"这个判断。 */
    private static final class Endpoint {
        final String url;
        final int timeoutMs;
        final VersionInfo.Source implied;

        Endpoint(String url, int timeoutMs, VersionInfo.Source implied) {
            this.url = url;
            this.timeoutMs = timeoutMs;
            this.implied = implied;
        }
    }

    /**
     * 按顺序尝试（见类注释）。
     *
     * <p>{@code raw.githubusercontent.com} 在国内是**立刻**连接失败（实测 0.13 秒，不是等超时），
     * 所以对国内用户来说多探它一次几乎不花时间。
     */
    private static final Endpoint[] SOURCES = {
        new Endpoint(
                "https://raw.githubusercontent.com/" + REPO + "/main/version.json",
                5000,
                VersionInfo.Source.FOREIGN),
        new Endpoint(
                "https://gitee.com/" + REPO + "/raw/main/version.json",
                6000,
                VersionInfo.Source.DOMESTIC),
        // 垫底：它在国内也通、但慢，判不出用户在哪儿 —— 标 UNKNOWN，两种入口都给
        new Endpoint(
                "https://cdn.jsdelivr.net/gh/" + REPO + "@main/version.json",
                8000,
                VersionInfo.Source.UNKNOWN),
    };

    private static final ExecutorService sExecutor = Executors.newSingleThreadExecutor();
    private static final Handler sHandler = new Handler(Looper.getMainLooper());

    public interface Callback {
        void onUpdateAvailable(VersionInfo info);

        void onUpToDate();

        void onError(String message);
    }

    public static void check(Callback callback) {
        sExecutor.execute(() -> {
            String raw = null;
            VersionInfo.Source region = VersionInfo.Source.UNKNOWN;
            for (Endpoint s : SOURCES) {
                raw = fetch(s.url, s.timeoutMs);
                if (raw != null) {
                    region = s.implied;
                    Log.d(TAG, "version.json from " + region + " source");
                    break;
                }
            }
            if (raw == null) {
                post(() -> callback.onError("Network unreachable"));
                return;
            }

            try {
                if (BuildConfig.DEBUG) Log.d(TAG, "version.json response: " + raw);

                VersionInfo info = parse(raw, region);

                Log.d(
                        TAG,
                        "remote versionCode=" + info.versionCode
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

    /**
     * 解析清单。纯函数 —— 网络之外的部分全在这里，可以直接上 JVM 单测。
     *
     * @param region 清单是从哪一类源取到的
     */
    static VersionInfo parse(String raw, VersionInfo.Source region) throws Exception {
        JSONObject json = new JSONObject(raw);
        VersionInfo info = new VersionInfo();
        info.source = region;
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

        /*
         * 下载入口。清单里没有 downloads 时留空 —— 调用方退回老行为（GitHub Releases 前缀），
         * 这样老清单、以及还没发新清单的那段时间都不会崩。
         */
        org.json.JSONArray arr = json.optJSONArray("downloads");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String url = o.optString("url", "");
                if (url.isEmpty()) continue;
                String kind = o.optString("kind", VersionInfo.Download.KIND_DIRECT);
                info.downloads.add(new VersionInfo.Download(kind, url, o.optString("label", "")));
            }
        }
        return info;
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
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
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
