package com.reandroid.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * 贡献者名单的「取哪些、什么次序、缓存还算不算数」这三件事。
 *
 * <p>为什么测这个：关于页那一栏是**运行时**从 GitHub 拉的，屏幕上没有任何东西能告诉你
 * 过滤或排序错了 —— 机器人混进来、次序颠倒，肉眼都当正常。而缓存过期判断错了更隐蔽：
 * 不刷新就是名单永远停在第一次的样子，刷新过频就会撞上匿名配额（60 次/小时/IP），
 * 之后整块消失。
 */
public class ContributorsTest {

    private static final long NOW = 1_800_000_000_000L;

    private static Contributor contributor(String login, int contributions) {
        return new Contributor(
                login, "https://example/avatar.png", "https://example/" + login, contributions);
    }

    // ---- 缓存有效期 ----

    @Test
    public void neverFetchedIsNotFresh() {
        assertFalse(Contributors.isFresh(0L, NOW));
    }

    @Test
    public void withinTheTtlIsFresh() {
        assertTrue(Contributors.isFresh(NOW - 60_000L, NOW));
        assertTrue(Contributors.isFresh(NOW - Contributors.CACHE_TTL_MS + 1, NOW));
    }

    /** 到期即失效 —— 取等号那一侧也要钉住，否则缓存会永远不过期。 */
    @Test
    public void atTheTtlItIsAlreadyStale() {
        assertFalse(Contributors.isFresh(NOW - Contributors.CACHE_TTL_MS, NOW));
    }

    /** 系统时间被往回调过（或存的是未来的时间戳）时，当成新鲜比反复重拉安全。 */
    @Test
    public void aTimestampFromTheFutureStaysFresh() {
        assertTrue(Contributors.isFresh(NOW + 60_000L, NOW));
    }

    // ---- 过滤与排序 ----

    @Test
    public void botsAndAnonymousEntriesAreDropped() {
        List<Contributor> visible = Contributors.visible(Arrays.asList(
                contributor("dependabot[bot]", 99),
                contributor("", 5),
                new Contributor(null, "u", "h", 7),
                contributor("dynnbw", 462)));

        assertEquals(1, visible.size());
        assertEquals("dynnbw", visible.get(0).login);
    }

    /** 次序不依赖 API 的输出顺序 —— 它现在恰好是排好的，将来不一定。 */
    @Test
    public void sortedByContributionCountDescending() {
        List<Contributor> visible = Contributors.visible(Arrays.asList(
                contributor("small", 3), contributor("big", 462), contributor("medium", 19)));

        assertEquals(
                Arrays.asList("big", "medium", "small"),
                Arrays.asList(visible.get(0).login, visible.get(1).login, visible.get(2).login));
    }

    @Test
    public void anEmptyListStaysEmpty() {
        assertTrue(Contributors.visible(Arrays.<Contributor>asList()).isEmpty());
    }
}
