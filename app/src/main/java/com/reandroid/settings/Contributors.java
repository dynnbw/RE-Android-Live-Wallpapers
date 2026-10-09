package com.reandroid.settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 贡献者名单里与 Android 无关的那部分：缓存有效期、以及"哪些该显示、按什么次序"。
 *
 * <p>**零 {@code android.*} 导入**，照 {@code FrameRatePolicy} / {@code PluginResources.labelFrom}
 * 的先例 —— 取值逻辑与取数逻辑分开，前者才能在 JVM 单测里钉住。
 * {@link ContributorStore}（要碰 Context / Handler / org.json）只负责取与存。
 */
public final class Contributors {

    /** 缓存有效期：名单变得慢，而匿名调 GitHub API 只有 60 次/小时/IP。 */
    public static final long CACHE_TTL_MS = 24L * 60 * 60 * 1000;

    private Contributors() {}

    /** 缓存是否还新鲜。没有存过（{@code fetchedAtMs == 0}）一律算不新鲜。 */
    public static boolean isFresh(long fetchedAtMs, long nowMs) {
        return fetchedAtMs > 0L && nowMs - fetchedAtMs < CACHE_TTL_MS;
    }

    /**
     * 该显示哪些、按什么次序：丢掉机器人（GitHub 约定 {@code [bot]} 结尾）与没有名字的，
     * 其余按贡献数降序。
     *
     * <p>API 本身已经排好序，这里重排是为了不依赖它的输出顺序。
     */
    public static List<Contributor> visible(List<Contributor> all) {
        List<Contributor> out = new ArrayList<>();
        for (Contributor contributor : all) {
            if (contributor.login == null || contributor.login.isEmpty()) {
                continue;
            }
            if (contributor.login.endsWith("[bot]")) {
                continue;
            }
            out.add(contributor);
        }
        Collections.sort(
                out, Comparator.comparingInt((Contributor c) -> c.contributions).reversed());
        return out;
    }
}
