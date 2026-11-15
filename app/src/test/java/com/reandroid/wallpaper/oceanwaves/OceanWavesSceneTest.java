package com.reandroid.wallpaper.oceanwaves;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 时间回绕与桌面滚动换算。
 *
 * <p>回绕点必须是波浪相位的整周期 —— 否则每两分钟画面会跳一下。
 */
public class OceanWavesSceneTest {

    @Test
    public void timeAdvancesWithSpeed() {
        OceanWavesScene scene = new OceanWavesScene();
        scene.update(0L);
        scene.update(1000L);
        assertEquals(1f, scene.time(), 0.001f);

        scene.setSpeed(2f);
        scene.update(2000L);
        assertEquals(3f, scene.time(), 0.001f);
    }

    /** 时间回绕到 40π 之后归零，且回绕处波浪相位是整 2π，看不出接缝。 */
    @Test
    public void wrapKeepsTheWavePhaseContinuous() {
        double phaseJump = 5.0 * OceanWavesScene.TIME_WRAP_SECONDS;
        double remainder = phaseJump % (2.0 * Math.PI);
        assertTrue("回绕处的相位差应是 2π 的整数倍", Math.min(remainder, 2.0 * Math.PI - remainder) < 1e-6);

        OceanWavesScene scene = new OceanWavesScene();
        scene.setSpeed(3f);
        scene.update(0L);
        long ms = 0L;
        for (int i = 0; i < 200; i++) {
            ms += 1000L;
            scene.update(ms);
            assertTrue(
                    "时间必须保持在回绕区间内",
                    scene.time() >= 0f && scene.time() < OceanWavesScene.TIME_WRAP_SECONDS);
        }
    }

    /** 桌面滚动的像素换算成设计单位：一屏宽刚好等于 480。 */
    @Test
    public void offsetIsConvertedToDesignUnits() {
        OceanWavesScene scene = new OceanWavesScene();
        scene.resize(1080, 2400);
        scene.setOffset(-540);
        assertEquals(-240f, scene.panX(), 0.01f);
        assertEquals(2.25f, scene.scale(), 0.0001f);
        assertEquals(1066.67f, scene.viewHeightDesign(), 0.01f);
    }

    /** 缩放设置越界也要夹住，别让贴图周期变成 0。 */
    @Test
    public void zoomIsClamped() {
        OceanWavesScene scene = new OceanWavesScene();
        scene.setZoom(0f);
        assertEquals(0.5f, scene.zoom(), 0.001f);
        scene.setZoom(10f);
        assertEquals(2.5f, scene.zoom(), 0.001f);
        scene.setZoom(1.5f);
        assertEquals(1.5f, scene.zoom(), 0.001f);
    }

    /** 速度设置越界要夹住，别把时间推成 0 或负数。 */
    @Test
    public void speedIsClamped() {
        OceanWavesScene scene = new OceanWavesScene();
        scene.setSpeed(0f);
        scene.update(0L);
        scene.update(1000L);
        assertTrue(scene.time() > 0f);

        scene.setSpeed(100f); // 夹到 3 倍
        scene.update(2000L);
        assertEquals(3.1f, scene.time(), 0.001f);
    }
}
