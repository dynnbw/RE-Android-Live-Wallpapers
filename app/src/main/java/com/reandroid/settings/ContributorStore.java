package com.reandroid.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 关于页「开发者」那一栏的贡献者名单，直接取自 GitHub，不再手写一份。
 *
 * 关于页永远不能因为网络卡住，所以缓存命中就直接回调，否则后台拉。
 * 要有缓存：匿名调 GitHub API 只有 60 次/小时/IP，而且大陆不一定连得上。
 * 缓存 24 小时，过期那一次拉不到就退回旧缓存。
 * 拉不到就不显示：不做本地兜底名单 —— 拿不到就返回空表，调用方把整块省掉。
 * 解析走 {@code org.json}。这个类本身**不**放 JVM 单测里跑的纯逻辑 ——
 * 它有 {@code Handler} 之类的静态字段，一加载就会碰 Android stub；取值规则在
 * {@link Contributors}（零 {@code android.*} 导入）里，由那边钉住。
 */
public final class ContributorStore {

    private static final String TAG = "ContributorStore";

    private static final String PREFS = "contributor_cache";
    private static final String KEY_JSON = "json";
    private static final String KEY_FETCHED_AT = "fetched_at";

    /** 本项目自己的仓库；贡献者名单就是从这里来的。 */
    private static final String ENDPOINT =
            "https://api.github.com/repos/dynnbw/RE-Android-Live-Wallpapers/contributors?per_page=100";

    private static final int TIMEOUT_MS = 8000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 回调在主线程。拿不到就是空表 —— 调用方据此决定整块不显示。 */
    public interface Callback {
        void onLoaded(List<Contributor> contributors);
    }

    private ContributorStore() {}

    public static void load(Context context, Callback callback) {
        final Context app = context.getApplicationContext();
        final SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        final String cachedRaw = prefs.getString(KEY_JSON, null);
        final List<Contributor> cached = parse(cachedRaw);

        if (!cached.isEmpty() && Contributors.isFresh(prefs.getLong(KEY_FETCHED_AT, 0L), now())) {
            callback.onLoaded(cached);
            return;
        }

        new Thread(
                        () -> {
                            String raw = fetchRaw();
                            List<Contributor> fresh = parse(raw);
                            if (!fresh.isEmpty()) {
                                prefs.edit()
                                        .putString(KEY_JSON, raw)
                                        .putLong(KEY_FETCHED_AT, now())
                                        .apply();
                            }
                            // 拉失败就退回旧缓存（可能也是空的）
                            List<Contributor> result = fresh.isEmpty() ? cached : fresh;
                            MAIN.post(() -> callback.onLoaded(result));
                        },
                        "ContributorFetch")
                .start();
    }

    static List<Contributor> parse(String json) {
        if (json == null || json.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            JSONArray array = new JSONArray(json);
            List<Contributor> out = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                out.add(new Contributor(
                        item.optString("login", null),
                        item.optString("avatar_url", null),
                        item.optString("html_url", null),
                        item.optInt("contributions", 0)));
            }
            return Contributors.visible(out);
        } catch (JSONException e) {
            Log.w(TAG, "contributors JSON unreadable", e);
            return Collections.emptyList();
        }
    }

    /** @return 原始 JSON，失败为 null */
    private static String fetchRaw() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
            // GitHub API 会 403 掉没有 User-Agent 的请求 —— 这条不是可选的
            conn.setRequestProperty("User-Agent", "RE-Android-Live-Wallpapers");
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                // 403 = 配额用完，最常见；照常退回缓存
                Log.w(TAG, "contributors HTTP " + conn.getResponseCode());
                return null;
            }
            StringBuilder sb = new StringBuilder();
            try (InputStream in = conn.getInputStream();
                    BufferedReader reader =
                            new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "contributors fetch failed", e);
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
