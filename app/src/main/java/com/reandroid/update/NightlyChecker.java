package com.reandroid.update;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 夜间通道的更新检查：读 GitHub 上那个 {@code nightly} prerelease，**造一个
 * {@link VersionInfo}**，剩下的对话框、下载、安装全部交给现成的 {@link UpdateHelper} /
 * {@link UpdateDownloader} —— 这条通道不需要另写一套下载或安装。
 *
 * <p>与正式通道（version.json）的差别都是有意为之，不是省事：
 *
 * <ul>
 *   <li>比的是**构建时间**（release 的 {@code published_at}）而不是 versionCode ——
 *       夜间包与正式包同号，比不出先后。
 *   <li>只有 GitHub 直连，不给网盘 —— 网盘是给连不上 GitHub 的大陆正式用户的，
 *       而这条入口的前提恰恰是**已经连上了 api.github.com**。
 *   <li>更新说明直接用它 release 的正文（由 CI 从提交标题生成，单语言）。
 * </ul>
 */
public final class NightlyChecker {

    private static final String TAG = "NightlyChecker";

    /** 固定标签：CI 每次删除重发，所以查这一个就等于"最新夜间包"。 */
    private static final String ENDPOINT =
            "https://api.github.com/repos/dynnbw/RE-Android-Live-Wallpapers/releases/tags/nightly";

    private static final int TIMEOUT_MS = 8000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 与 {@code UpdateChecker.Callback} 同一个形状。 */
    public interface Callback {
        void onUpdateAvailable(VersionInfo info);

        void onUpToDate();

        void onError(String message);
    }

    private NightlyChecker() {}

    /** 回调在主线程。任何失败都走 {@code onError}，不抛给调用方。 */
    public static void check(final Callback callback) {
        new Thread(
                        () -> {
                            VersionInfo info;
                            try {
                                info = fetch();
                            } catch (Exception e) {
                                Log.w(TAG, "nightly check failed", e);
                                String message = e.getMessage();
                                MAIN.post(() ->
                                        callback.onError(message != null ? message : "unknown"));
                                return;
                            }
                            if (info == null) {
                                MAIN.post(callback::onUpToDate);
                            } else {
                                MAIN.post(() -> callback.onUpdateAvailable(info));
                            }
                        },
                        "NightlyCheck")
                .start();
    }

    /** @return 有新版就返回能直接交给对话框的 {@link VersionInfo}；否则 null */
    private static VersionInfo fetch() throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
            // GitHub API 会 403 掉没有 User-Agent 的请求 —— 这条不是可选的
            conn.setRequestProperty("User-Agent", "RE-Android-Live-Wallpapers");
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);

            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                // 还没发过夜间包 —— 不是错误，就是没有
                return null;
            }
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("HTTP " + code);
            }

            StringBuilder raw = new StringBuilder();
            try (InputStream in = conn.getInputStream();
                    BufferedReader reader =
                            new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    raw.append(line);
                }
            }
            return parse(raw.toString(), System.currentTimeMillis());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 包成包内可见，好在单测里对着固定 JSON 钉住"从 release 到 VersionInfo"这一步。 */
    static VersionInfo parse(String raw, long buildTimeMs) throws Exception {
        JSONObject root = new JSONObject(raw);

        /*
         * 只认预览版，而且**失败要看得见**。
         *
         * 标签本身已经保证了取到的是 `nightly` 那一条（按标签查的，不会落到 v 开头的正式版），
         * 这一步是二次确认：万一这条 release 不是预览版，说明 CI 那边漏了 `--prerelease` ——
         * 那是配置错了，不是"没有新版"。早先这里是静默 return null，症状会变成"永远说已是最新"，
         * 屏幕上一句提示都没有；这个仓库最讨厌这种静默失效，所以改成报错。
         */
        if (!root.optBoolean("prerelease", false)) {
            throw new IllegalStateException("nightly release is not marked as a prerelease — "
                    + "the publish step must pass --prerelease");
        }

        long publishedAt = parseIso(root.optString("published_at", ""));
        if (!NightlyRelease.isNewerThan(publishedAt, buildTimeMs)) {
            return null;
        }

        String apkUrl = findApkAsset(root.optJSONArray("assets"));
        if (apkUrl == null) {
            throw new IllegalStateException("nightly release has no .apk asset");
        }

        VersionInfo info = new VersionInfo();
        info.versionName = NightlyRelease.versionNameFor(publishedAt, TimeZone.getDefault());
        // 更新说明由 CI 从提交标题生成，单语言 —— 两个字段填同一份，哪个语言下都有得看
        String notes = root.optString("body", "");
        info.changelogEn = notes;
        info.changelogZh = notes;
        /*
         * 标 FOREIGN 不只是"顺手"：能取到这个接口就说明这个网络通境外，那正是 Source.FOREIGN 的语义。
         * 而一旦标成 DOMESTIC，downloadOptionsFor 会把 direct 入口滤掉 —— 选项变空之后对话框会
         * 回落到"老行为"，拼出 …/releases/download/vNightly…/app-release.apk 这种 404 地址。
         */
        info.source = VersionInfo.Source.FOREIGN;
        info.downloads.add(
                new VersionInfo.Download(VersionInfo.Download.KIND_DIRECT, apkUrl, null));
        return info;
    }

    private static String findApkAsset(JSONArray assets) {
        if (assets == null) {
            return null;
        }
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) {
                continue;
            }
            if (asset.optString("name", "").endsWith(".apk")) {
                return asset.optString("browser_download_url", null);
            }
        }
        return null;
    }

    /** GitHub 给的是 {@code 2026-09-19T05:15:36Z}。非严格解析：格式不对宁可报错，也别算出一个错的时间。 */
    private static long parseIso(String iso) throws Exception {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        format.setLenient(false);
        Date parsed = format.parse(iso);
        if (parsed == null) {
            throw new IllegalStateException("unreadable published_at: " + iso);
        }
        return parsed.getTime();
    }
}
