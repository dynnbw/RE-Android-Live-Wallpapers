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
     * 这条 release 是不是"给我用的"新版：**比到"天"为止**。
     *
     * <p>夜间包是每天一条（nightly 的含义就是交给 Actions 自动构建），所以日粒度足够，
     * 而且顺带解决了一个陷阱：发布时刻必然**晚于**构建时刻（CI 先构建、几分钟后才发 release），
     * 若比到秒，每个夜间包都会把自己认成"有新版"、天天提示更新到自己。同一天 ⇒ 相等 ⇒
     * 不是新版，这条自然就没了。
     *
     * <p>两边都按 **UTC** 取天。用本地时区的话，同一条 release 在不同时区的机器上可能落在
     * 不同的两天（UTC 00:55 那条 = 北京 08:55，跨没跨天两边说法不同）。
     */
    public static boolean isUpdateFor(long publishedAtMs, long buildTimeMs) {
        return dayOf(publishedAtMs) > dayOf(buildTimeMs);
    }

    /** 自 epoch 起的第几天（UTC）。 */
    static long dayOf(long ms) {
        return Math.floorDiv(ms, 24L * 60 * 60 * 1000);
    }
}
