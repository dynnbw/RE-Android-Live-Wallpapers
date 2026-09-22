package com.reandroid.gles;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 辉光的两个纯计算。
 *
 * <p>辉光几乎全是 GPU，JVM 里跑不了 —— 这一小块是唯一能测的。
 * 挑的这两条都是**错了之后症状很难定位**的：
 * 半分辨率算成 0 会让 FBO 建失败（表现为辉光整个不出现），
 * 步长除零会算出 Infinity，模糊采样到 NaN（表现为**整片辉光变黑**，且不报任何错）。
 */
public class GlowParamsTest {

    /** 半分辨率边长，**下限 1**。 */
    @Test
    public void halfSizeNeverReachesZero() {
        assertEquals(540, GlowParams.halfSize(1080));
        assertEquals(1, GlowParams.halfSize(1));
        assertEquals(1, GlowParams.halfSize(0));
        assertEquals(1, GlowParams.halfSize(2));
        for (int w = 0; w <= 4096; w++) {
            assertTrue("w=" + w + " 算出 " + GlowParams.halfSize(w),
                    GlowParams.halfSize(w) >= 1);
        }
    }

    /** 模糊步长 = 半径 / 边长；尺寸为 0 时也必须是有限值。 */
    @Test
    public void blurStepIsFiniteEvenForZeroSize() {
        assertEquals("半径 8、边长 540", 8.0f / 540.0f,
                GlowParams.blurStep(540, 8.0f), 1.0E-6f);
        // 半径为 0 → 步长为 0，那一遍退化成"原样拷贝"，是良定义的
        assertEquals(0.0f, GlowParams.blurStep(540, 0.0f), 0.0f);
        // 尺寸为 0 → 不能是 Infinity / NaN
        float degenerate = GlowParams.blurStep(0, 8.0f);
        assertTrue("尺寸为 0 时算出了 " + degenerate,
                !Float.isInfinite(degenerate) && !Float.isNaN(degenerate));
        assertEquals(8.0f, degenerate, 1.0E-6f);
    }
}
