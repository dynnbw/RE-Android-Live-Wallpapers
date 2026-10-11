package com.reandroid.update;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 夜间通道里与 Android、网络都无关的那点逻辑：版本名怎么拼、"算不算有新版"。
 *
 * <p>零 {@code android.*} 导入，照 {@code FrameRatePolicy} 的先例 —— 这两条判断错了
 * 屏幕上不会有任何提示（名字拼歪了只是个难看的标题，比错了就是"永远提示有新版"或
 * "永远说已是最新"），所以放进 JVM 单测里钉住。
 */
public final class NightlyRelease {

    private NightlyRelease() {}

    /**
     * 夜间包的展示名，从 GitHub release 的发布时间生成。
     *
     * <p>**必须只含字母、数字与点** —— 这个名字会被 {@link UpdateDownloader} 拼进下载文件名，
     * 那边有个严格格式的白名单（见 {@code VERSION_NAME_PATTERN}）；带空格或斜杠会被直接拒掉。
     * 所以格式固定成 {@code Nightlyyyyy.MM.dd.HH.mm}，连时分一起带上，同一天手动重跑两次也能区分。
     *
     * @param zone 由调用方给，测试里才好钉住
     */
    public static String versionNameFor(long publishedAtMs, TimeZone zone) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy.MM.dd.HH.mm", Locale.US);
        format.setTimeZone(zone);
        return "Nightly" + format.format(new Date(publishedAtMs));
    }

    /**
     * 比我构建得更晚，才算有新版。
     *
     * <p>不比较 versionCode：夜间包与正式包同号（都取自 {@code gradle.properties} 的下一个正式版本），
     * 比不出先后。
     */
    public static boolean isNewerThan(long publishedAtMs, long buildTimeMs) {
        return publishedAtMs > buildTimeMs;
    }

    /**
     * 这条 release 是不是"给我用的"新版。**先比提交、再看时间，顺序不能反。**
     *
     * <p>为什么必须先比提交：发布时刻必然**晚于**构建时刻（CI 先构建、再发 release，差几分钟），
     * 所以只看时间的话，每个夜间包都会把自己认成"有新版"，天天提示更新到自己。
     * 提交相同 ⇒ 就是我自己，时间不必看。
     *
     * <p>提交不同时再看时间：只有比我构建得更晚才算数，免得把更早的夜间包当成更新推给用户。
     *
     * @param releaseCommit release 的 {@code target_commitish}（CI 用 {@code --target <sha>}，
     *                      所以给的是完整 SHA）
     * @param mySha         本包的 {@code BuildConfig.GIT_SHA}；源码 zip 构建时为空
     */
    public static boolean isUpdateFor(
            long publishedAtMs, String releaseCommit, long buildTimeMs, String mySha) {
        if (sameCommit(releaseCommit, mySha)) {
            return false;
        }
        return isNewerThan(publishedAtMs, buildTimeMs);
    }

    /** 两边都该是完整 SHA；任一边被截短过也算命中（前缀比较）。 */
    static boolean sameCommit(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            // 取不到本包的 SHA（从源码 zip 构建）时不敢认"同一个"，宁可多提示一次
            return false;
        }
        return a.startsWith(b) || b.startsWith(a);
    }
}
