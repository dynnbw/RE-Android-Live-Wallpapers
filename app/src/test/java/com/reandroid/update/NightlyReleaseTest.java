package com.reandroid.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Instant;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * 夜间通道里两条纯判断：版本名怎么拼、"算不算有新版"。
 *
 * <p>为什么值得钉：这两条错了屏幕上不会有任何提示 —— 名字拼歪了只是标题难看，
 * 比错了就是"永远提示有新版"或"永远说已是最新"，两种都不会报错。
 */
public class NightlyReleaseTest {

    /**
     * 与 {@code UpdateDownloader.VERSION_NAME_PATTERN} 是同一条规则（那边是 private 的）。
     * 这个名字会被拼进下载文件名，不符就被直接拒掉 —— 所以两处必须保持一致，
     * 改动任何一边时这条测试会提醒你。
     */
    private static final Pattern DOWNLOADER_ACCEPTS = Pattern.compile("^[0-9A-Za-z.\\-]+$");

    private static final TimeZone SHANGHAI = TimeZone.getTimeZone("Asia/Shanghai");
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private static long at(String iso) {
        return Instant.parse(iso).toEpochMilli();
    }

    /** 名字会被拼进下载文件名，那里有严格白名单 —— 只要含空格或斜杠就整条下不动。 */
    @Test
    public void theNameOnlyUsesCharactersTheDownloaderAccepts() {
        String name = NightlyRelease.versionNameFor(at("2026-10-10T14:41:00Z"), SHANGHAI);
        assertTrue("下载器会拒掉这个名字：" + name, DOWNLOADER_ACCEPTS.matcher(name).matches());
    }

    /** 显示的是**该时区**的发布时间 —— 同一条 release 在上海和 UTC 下差 8 小时。 */
    @Test
    public void theNameCarriesThePublicationTimeInTheGivenZone() {
        long published = at("2026-10-10T14:41:00Z");
        assertEquals("Nightly2026.10.10.22.41", NightlyRelease.versionNameFor(published, SHANGHAI));
        assertEquals("Nightly2026.10.10.14.41", NightlyRelease.versionNameFor(published, UTC));
    }

    // ---- 是不是"给我用的"新版 ----

    /** 本包自己是什么时候构建的。 */
    private static final long BUILT = at("2026-10-11T00:30:00Z");

    /**
     * 同一天（哪怕发布时间更晚）不算新版 —— **这条是报障的根**：
     * CI 先构建、几分钟后才发 release，比到秒的话每个夜间包都会把自己认成有新版，
     * 于是天天提示"更新到自己"。比到天，同一天就相等了。
     */
    @Test
    public void theSameDayIsNeverAnUpdate() {
        assertFalse("同一天被当成了新版", NightlyRelease.isUpdateFor(at("2026-10-11T01:10:20Z"), BUILT));
    }

    /** 次日的夜间包才是新版。 */
    @Test
    public void theNextDayIsAnUpdate() {
        assertTrue(NightlyRelease.isUpdateFor(at("2026-10-12T00:55:00Z"), BUILT));
    }

    /** 更早的那些不算更新，否则会把旧包推给用户。 */
    @Test
    public void anEarlierDayIsNotAnUpdate() {
        assertFalse(NightlyRelease.isUpdateFor(at("2026-10-10T00:55:00Z"), BUILT));
    }

    /**
     * 按 **UTC** 划天，不看本机时区 —— 否则同一条 release 落在不同时区的机器上
     * 可能算成不同的两天（UTC 00:55 那条 = 北京 08:55，跨没跨天两边说法不同）。
     */
    @Test
    public void daysAreCountedInUtc() {
        // 同一天的两端：UTC 00:00 与 UTC 23:59:59 —— 相等，不是新版
        assertFalse(
                NightlyRelease.isUpdateFor(at("2026-10-11T23:59:59Z"), at("2026-10-11T00:00:00Z")));
        // 跨过 UTC 午夜才算次日的包
        assertTrue(
                NightlyRelease.isUpdateFor(at("2026-10-12T00:00:01Z"), at("2026-10-11T23:59:59Z")));
    }
}
