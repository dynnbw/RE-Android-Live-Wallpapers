package com.reandroid.wallpaper.cosmicflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 触摸后动画速度的缓降曲线必须保持原样。
 *
 * <p>原版 {@code updateSpeed()} 同时干两件事：按触摸年龄设速度因子（动画快慢）和设帧限目标
 * （12/20/30 FPS，并据此 sleep）。后半截已经删掉 —— 配速现在归 {@code FrameRateManager}，
 * 留着它会把用户选的帧数压掉。这里守的就是**前半截没被顺手删错**。
 *
 * <p>曲线本身带一个原版的怪处：缓降段除以 4000 而不是 3000，所以 9 秒那个端点是 0.573 而非
 * 0.5，随后才被 {@code else} 分支按到 0.5。这是原版的算式，不要"修正"。
 */
public class CosmicSpeedFactorTest {

    /** 触摸时刻取个非零值：{@code accelerateOnTouch} 只在 nowMs > 0 时才记 lastTouchMs。 */
    private static final long TAP_MS = 1000L;

    /** 触摸后过 msSinceTap 毫秒时的速度因子。 */
    private static float speedAfter(long msSinceTap) {
        return speedAfter(msSinceTap, 0.008f);
    }

    private static float speedAfter(long msSinceTap, float dtSeconds) {
        CosmicScene scene = new CosmicScene();
        scene.onTap(TAP_MS);
        scene.update(dtSeconds, TAP_MS + msSinceTap);
        return scene.speedFactor;
    }

    @Test
    public void fullSpeedRightAfterATap() {
        assertEquals(1.0f, speedAfter(0L), 0.0f);
    }

    /** 6 秒是这一档的上界，取等号仍算全速。 */
    @Test
    public void stillFullSpeedAtTheSixSecondBoundary() {
        assertEquals(1.0f, speedAfter(6000L), 0.0f);
    }

    /** 缓降段正中间（p=0.5）余弦项为 0，于是正好落在 0.75。 */
    @Test
    public void theRampIsExactlyThreeQuartersAtItsMidpoint() {
        assertEquals(0.75f, speedAfter(8000L), 1e-4f);
    }

    /** 除数是 4000：9 秒端点是 0.573，还没到底。 */
    @Test
    public void theRampEndsAboveTheFloor() {
        assertEquals(0.5732f, speedAfter(9000L), 1e-3f);
        assertTrue(speedAfter(9000L) > 0.5f);
    }

    @Test
    public void idleFallsToTheFloor() {
        assertEquals(0.5f, speedAfter(9001L), 0.0f);
        assertEquals(0.5f, speedAfter(60000L), 0.0f);
    }

    /** 缓降段必须单调不增 —— 否则动画会出现"回弹"。 */
    @Test
    public void theRampNeverGoesBackUp() {
        float previous = speedAfter(6000L);
        for (long t = 6100L; t <= 9000L; t += 100L) {
            float current = speedAfter(t);
            assertTrue("t=" + t + ": " + current + " > " + previous, current <= previous + 1e-6f);
            previous = current;
        }
    }

    /** 速度按墙上时钟走，不该随帧率变 —— 帧限删掉之后更该如此。 */
    @Test
    public void speedDoesNotDependOnTheFrameRate() {
        assertEquals(speedAfter(8000L, 0.083f), speedAfter(8000L, 0.008f), 0.0f);
    }
}
