package com.reandroid.wallpaper.musicvis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** vis3 冷光外溢的参数表。 */
public class RetroProfileTest {

    /** NONE 不建后处理、不读设置。 */
    @Test
    public void noneHasNoShaderAndNoKey() {
        RetroProfile none = RetroProfile.of(RetroProfile.STYLE_NONE);
        assertTrue(none.isNone());
        assertEquals("", none.settingsKey);
        assertEquals("", none.compositeShader);
        assertFalse(RetroProfile.of(RetroProfile.STYLE_COLD_GLOW).isNone());
    }

    /** 设置键与壁纸对应。 */
    @Test
    public void settingsKeyIsForVis3() {
        assertEquals("vis3_retro", RetroProfile.of(RetroProfile.STYLE_COLD_GLOW).settingsKey);
    }

    /** 外溢参数必须是能生效的值：半径与强度都大于 0。 */
    @Test
    public void coldGlowHasEffectiveParams() {
        RetroProfile cold = RetroProfile.of(RetroProfile.STYLE_COLD_GLOW);
        assertTrue("半径要大于 0，否则那遍模糊退化成拷贝", cold.radius > 0f);
        assertTrue("强度要大于 0，否则外溢看不见", cold.strength > 0f);
        assertTrue("knee 要为非负", cold.softKnee >= 0f);
    }

    /** 未知风格退回 NONE，不抛异常。 */
    @Test
    public void unknownStyleFallsBackToNone() {
        assertTrue(RetroProfile.of(99).isNone());
        assertTrue(RetroProfile.of(-1).isNone());
    }
}
