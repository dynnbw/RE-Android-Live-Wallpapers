package com.reandroid.wallpaper.phasebeam;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 桌面不支持随屏滚动时的偏移回退。
 *
 * <p>气泡生成在 {@code x ∈ [0, 3]}，而屏幕只有 {@code [-1, 1]}，全靠偏移量把它们推进
 * 画面。国产 ROM 的默认桌面基本都不支持随屏滚动，却仍会回调 {@code onOffsetsChanged}
 * 并固定上报 {@code xOffset = 0} —— 照单全收就只剩右半边有气泡。
 *
 * <p>但 {@code xOffset = 0} 单独看是有歧义的（两页桌面的第 1 页也是 0），所以判据只能
 * 是"桌面自己说它不滚动"（步长 {@code <= 0}）。这几条钉子分别守住两侧：该回退的回退，
 * 不该动的绝不能动。
 */
public class PhaseBeamScrollFallbackTest {

    private static PhaseBeamScene scene() {
        return new PhaseBeamScene();
    }

    /** 不滚动的桌面 + 上报 0 → 回退到居中（预览用的那一档），气泡才进得了画面。 */
    @Test
    public void noScrollLauncherFallsBackToCentered() {
        PhaseBeamScene s = scene();
        s.setScrollStep(0.0f);
        s.setOffset(0.0f);
        assertEquals("不滚动的桌面应回退到居中", 0.5f, s.mXOffset, 0.0f);
    }

    /** 能滚动的桌面报 0 是"第 1 页"，必须原样保留 —— 否则壁纸在第 1 页上不动。 */
    @Test
    public void scrollingLauncherKeepsPageOne() {
        PhaseBeamScene s = scene();
        s.setScrollStep(1.0f);
        s.setOffset(0.0f);
        assertEquals("第 1 页就该是最左边那一屏", 0.0f, s.mXOffset, 0.0f);
    }

    /** 能滚动的桌面照常跟随偏移。 */
    @Test
    public void scrollingLauncherFollowsTheOffset() {
        PhaseBeamScene s = scene();
        s.setScrollStep(0.5f);
        s.setOffset(0.25f);
        assertEquals(0.25f, s.mXOffset, 0.0f);
        s.setOffset(0.75f);
        assertEquals(0.75f, s.mXOffset, 0.0f);
    }

    /**
     * 即使桌面说自己不滚动，报上来的**非 0** 值也照旧尊重 ——
     * 有自定义桌面会乱报步长，宁可少回退也不能覆盖真实数据。
     */
    @Test
    public void nonzeroOffsetIsHonoredEvenWithoutAStep() {
        PhaseBeamScene s = scene();
        s.setScrollStep(0.0f);
        s.setOffset(0.7f);
        assertEquals("非 0 的偏移一律照收", 0.7f, s.mXOffset, 0.0f);
    }

    /** 插件自己的滚动开关关掉时，什么偏移都不理会（原有行为）。 */
    @Test
    public void pluginSwitchStillWins() {
        PhaseBeamScene s = scene();
        s.mCanScroll = false;
        s.setScrollStep(1.0f);
        s.setOffset(0.25f);
        assertEquals("开关关掉时不该被偏移改动", 0.5f, s.mXOffset, 0.0f);
    }

    /** 没收到过步长时按"能滚动"处理 —— 不改变任何现有调用路径的行为。 */
    @Test
    public void missingStepKeepsTheOldBehavior() {
        PhaseBeamScene s = scene();
        s.setOffset(0.0f);
        assertEquals(0.0f, s.mXOffset, 0.0f);
    }
}
