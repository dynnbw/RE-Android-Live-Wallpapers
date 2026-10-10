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

    @Test
    public void aLaterBuildIsAnUpdate() {
        assertTrue(NightlyRelease.isNewerThan(2000L, 1000L));
    }

    /**
     * 取等号**不算**有新版 —— 这正是"同一条发布"的情形（本地就是拿它构建的）。
     * 若判成有新版，关于页会永远提示可以更新到它自己。
     */
    @Test
    public void theSameBuildIsNotAnUpdate() {
        assertFalse(NightlyRelease.isNewerThan(1000L, 1000L));
    }

    @Test
    public void anOlderBuildIsNotAnUpdate() {
        assertFalse(NightlyRelease.isNewerThan(1000L, 2000L));
    }
}
