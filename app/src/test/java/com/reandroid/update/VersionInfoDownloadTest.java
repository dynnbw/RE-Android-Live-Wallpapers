package com.reandroid.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * 下载入口的筛选规则必须钉住。
 *
 * <p>它消费的是"清单从哪个源取到的"这个地区探测（境外源在前，见 {@code UpdateChecker}）：
 * 判错的后果是**把外国用户引到一个他打不开的国内网盘**，或者反过来，让国内用户点了"直接下载"
 * 却永远下不动。这两种都不会报错，只会让人以为"更新坏了"。
 *
 * <p>JSON 反序列化那一步不在这里 —— 单测跑在 {@code android.jar} 上，那份 {@code org.json}
 * 的方法调用会抛 {@code ExceptionInInitializerError}（与 {@code OpenMeteoSourceTest} 同一约定）。
 * 这里手工构造 {@link VersionInfo}，只测"给了什么就选什么"。
 */
public class VersionInfoDownloadTest {

    private static VersionInfo with(VersionInfo.Download... downloads) {
        VersionInfo info = new VersionInfo();
        info.versionCode = 11;
        info.versionName = "2.0";
        for (VersionInfo.Download d : downloads) info.downloads.add(d);
        return info;
    }

    private static VersionInfo.Download external(String url, String label) {
        return new VersionInfo.Download(VersionInfo.Download.KIND_EXTERNAL, url, label);
    }

    private static VersionInfo.Download direct(String url) {
        return new VersionInfo.Download(VersionInfo.Download.KIND_DIRECT, url, "");
    }

    /** 国内源取到的清单：只给外部入口（网盘）—— 直连那个地址他大概率下不动。 */
    @Test
    public void domesticUsersGetTheExternalEntryOnly() {
        VersionInfo info = with(external("https://nnbw.lanzoub.com/b00u0zc3g", "蓝奏云"));
        info.source = VersionInfo.Source.DOMESTIC;

        List<VersionInfo.Download> options = info.downloadOptionsFor(info.source);
        assertEquals(1, options.size());
        assertEquals("蓝奏云", options.get(0).label);
        assertFalse(options.get(0).isDirect());
    }

    /**
     * 境外源取到的清单 + 清单里没有 direct 项 → 筛出来是**空的**。
     *
     * <p>这不是漏了：空列表正是"退回写死的 GitHub Releases 前缀"的信号，而那个地址对能连境外的
     * 用户可用。等以后有了国内对象存储，清单里加一条 direct 即可 —— 不必改应用。
     */
    @Test
    public void foreignUsersGetNothingWhenTheManifestHasNoDirectEntry() {
        VersionInfo info = with(external("https://nnbw.lanzoub.com/b00u0zc3g", "蓝奏云"));
        info.source = VersionInfo.Source.FOREIGN;

        assertTrue(info.downloadOptionsFor(info.source).isEmpty());
    }

    /** 清单里同时给了两种：各取所需，不交叉。 */
    @Test
    public void eachRegionGetsExactlyItsOwnEntry() {
        VersionInfo info = with(
                direct("https://cdn.example/app.apk"), external("https://pan.example/x", "网盘"));

        List<VersionInfo.Download> foreign = info.downloadOptionsFor(VersionInfo.Source.FOREIGN);
        assertEquals(1, foreign.size());
        assertTrue(foreign.get(0).isDirect());

        List<VersionInfo.Download> domestic = info.downloadOptionsFor(VersionInfo.Source.DOMESTIC);
        assertEquals(1, domestic.size());
        assertEquals("网盘", domestic.get(0).label);
    }

    /** 老清单（没有 downloads）对任何地区都是空的 —— 调用方据此退回老行为。 */
    @Test
    public void aManifestWithoutDownloadsYieldsNoOptions() {
        VersionInfo info = with();
        assertTrue(info.downloadOptionsFor(VersionInfo.Source.FOREIGN).isEmpty());
        assertTrue(info.downloadOptionsFor(VersionInfo.Source.DOMESTIC).isEmpty());
        assertTrue(info.downloadOptionsFor(VersionInfo.Source.UNKNOWN).isEmpty());
    }

    /**
     * 判不出地区时把两种入口都给。
     *
     * <p>这是实测逼出来的一态：jsDelivr 在国内也通（只是慢），所以"它应答"既不能推出境外、
     * 也不能推出国内。猜一边就会把用户引到打不开的入口 —— 宁可他多看一眼。
     */
    @Test
    public void unknownRegionOffersEverything() {
        VersionInfo info = with(
                direct("https://cdn.example/app.apk"), external("https://pan.example/x", "网盘"));

        List<VersionInfo.Download> options = info.downloadOptionsFor(VersionInfo.Source.UNKNOWN);
        assertEquals(2, options.size());
    }
}
