package com.reandroid.wallpaper.grass;

import com.reandroid.weather.WeatherCondition;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * GrassWeatherSystem.fadeGate 的回归测试。
 *
 * <p>要守住的性质:切换天气时放行系数**每帧的变化量有上界**(dt/时长)，
 * 也就是"不会有一帧突然从 1 掉到 0"。这正是原来硬切丢掉的东西。
 */
public class WeatherFadeGateTest {

    private static final float FADE_SEC = 1.2f;

    /** 淡出用时时长正确。 */
    @Test
    public void fadeOutTakesTheFullDuration() {
        float gate = 1.0f;
        int frames60 = 0;
        while (gate > 0.0f && frames60 < 10000) {
            gate = GrassWeatherSystem.fadeGate(gate, false, 1.0f / 60.0f, FADE_SEC);
            frames60++;
        }
        assertEquals("淡出帧数@60fps", Math.round(FADE_SEC * 60), frames60);
        assertEquals("淡出终点", 0.0f, gate, 0.0f);
    }

    /** 帧率无关:跑到一半时应当刚好 0.5。 */
    @Test
    public void fadeIsFrameRateIndependent() {
        for (float fps : new float[]{30, 60, 90, 120}) {
            float g = 1.0f;
            float dt = 1.0f / fps;
            int steps = Math.round(FADE_SEC * 0.5f * fps);
            for (int i = 0; i < steps; i++) {
                g = GrassWeatherSystem.fadeGate(g, false, dt, FADE_SEC);
            }
            assertEquals("半程值@" + (int) fps + "fps", 0.5f, g, 0.02f);
        }
    }

    /** 每帧变化量有上界(这就是"不硬切"的形式化)。 */
    @Test
    public void perFrameChangeIsBounded() {
        float maxJump = 0.0f;
        float g = 1.0f;
        for (int i = 0; i < 200; i++) {
            float next = GrassWeatherSystem.fadeGate(g, false, 1.0f / 60.0f, FADE_SEC);
            maxJump = Math.max(maxJump, Math.abs(next - g));
            g = next;
        }
        assertTrue("单帧变化不应超过 dt/时长",
                maxJump <= 1.0f / 60.0f / FADE_SEC + 1.0E-6f);
    }

    /** 不越界、单调。 */
    @Test
    public void fadeOutStaysInRangeAndMonotonic() {
        float g = 1.0f;
        for (int i = 0; i < 500; i++) {
            float next = GrassWeatherSystem.fadeGate(g, false, 1.0f / 60.0f, FADE_SEC);
            assertTrue("越界 " + next + " @f" + i, next >= 0.0f && next <= 1.0f);
            assertTrue("淡出过程中回升 @f" + i, next <= g);
            g = next;
        }
    }

    /** 能淡回来。 */
    @Test
    public void fadeInReachesOne() {
        float g = 0.0f;
        for (int i = 0; i < Math.round(FADE_SEC * 60) + 2; i++) {
            g = GrassWeatherSystem.fadeGate(g, true, 1.0f / 60.0f, FADE_SEC);
        }
        assertEquals("淡入终点", 1.0f, g, 0.0f);
    }

    /** 中途反向:从当前值继续,不从头来。 */
    @Test
    public void reversalContinuesFromCurrentValue() {
        float g = 1.0f;
        for (int i = 0; i < 36; i++) {                 // 淡出 0.6s → 0.5
            g = GrassWeatherSystem.fadeGate(g, false, 1.0f / 60.0f, FADE_SEC);
        }
        assertEquals("反向前的值", 0.5f, g, 0.02f);
        float afterOneFrame = GrassWeatherSystem.fadeGate(g, true, 1.0f / 60.0f, FADE_SEC);
        assertEquals("反向一帧后应从 0.5 继续往上", 0.5f + 1.0f / 60.0f / FADE_SEC, afterOneFrame, 1.0E-5f);
    }

    /** 边界:时长为 0 直接到位;dt 为 0 不动。 */
    @Test
    public void zeroDurationAndZeroDtAreEdges() {
        assertEquals("时长为 0 应直接到位(false)", 0.0f,
                GrassWeatherSystem.fadeGate(0.7f, false, 0.016f, 0.0f), 0.0f);
        assertEquals("时长为 0 应直接到位(true)", 1.0f,
                GrassWeatherSystem.fadeGate(0.7f, true, 0.016f, 0.0f), 0.0f);
        assertEquals("dt 为 0 不应变化", 0.7f,
                GrassWeatherSystem.fadeGate(0.7f, false, 0.0f, FADE_SEC), 0.0f);
    }

    /** 天气表本身:阵雨起就不放行蒲公英。 */
    @Test
    public void weatherTableGatesDandelionAndFirefly() {
        assertTrue("阵雨不应放行蒲公英", !GrassWeatherSystem.allowsDandelion(WeatherCondition.D5_RAIN_SHOWERS));
        assertTrue("晴朗应放行蒲公英", GrassWeatherSystem.allowsDandelion(WeatherCondition.D1_CLEAR));
        assertTrue("晴朗应放行萤火虫", GrassWeatherSystem.allowsFirefly(WeatherCondition.D1_CLEAR));
        assertTrue("多云应放行萤火虫", GrassWeatherSystem.allowsFirefly(WeatherCondition.D2_CLOUDY));
        assertTrue("阴沉不应放行萤火虫", !GrassWeatherSystem.allowsFirefly(WeatherCondition.D3_DREARY));
    }
}
