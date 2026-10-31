package com.reandroid.wallpaper.bokeh;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 光斑生命周期与彩虹取色。两者都是纯 Java，能直接跑。 */
public class BokehCircleTest {

    /** 前 1/4 段亮度线性升到 128，半径按 1.5 倍匀速长。 */
    @Test
    public void tickRaisesAlphaAndRadius() {
        BokehCircle circle = new BokehCircle(100f, 100f, 40f, 0xFF0000, 40, 20f, -20f);
        for (int i = 0; i < 10; i++) {
            circle.tick();
        }
        assertEquals(128, circle.alpha);
        assertEquals(40f * 1.125f, circle.radius, 0.01f);
        assertEquals(105f, circle.x, 0.01f);
        assertEquals(95f, circle.y, 0.01f);
    }

    /** 走到终点后亮度回到 0，并标记结束。 */
    @Test
    public void fadesOutAtTheEnd() {
        BokehCircle circle = new BokehCircle(0f, 0f, 40f, 0xFF0000, 40, 0f, 0f);
        for (int i = 0; i < 40; i++) {
            circle.tick();
        }
        assertEquals(0, circle.alpha);
        assertEquals(60f, circle.radius, 0.01f);
        assertFalse(circle.isDone());
        circle.tick();
        assertTrue(circle.isDone());
    }

    /** 彩虹取色：0 是红、1/3 是绿、2/3 是蓝。 */
    @Test
    public void rainbowColoursMatchHsv() {
        assertEquals(0xFF0000, BokehScene.rainbowColor(0f));
        assertEquals(0x00FF00, BokehScene.rainbowColor(1f / 3f));
        assertEquals(0x0000FF, BokehScene.rainbowColor(2f / 3f));
    }

    /** 越界的色相绕回来，不抛异常。 */
    @Test
    public void rainbowWrapsOutOfRange() {
        assertEquals(BokehScene.rainbowColor(0.25f), BokehScene.rainbowColor(1.25f));
        assertEquals(BokehScene.rainbowColor(0.75f), BokehScene.rainbowColor(-0.25f));
    }
}
