/*
 * 校验 GrassScene 里对风相位取模的周期常量 —— 纯 JVM:
 *
 *   javac -d /tmp/windtest \
 *         app/src/main/java/com/reandroid/wallpaper/grass/GrassWindField.java \
 *         tools/grass-test/com/reandroid/utils/MathUtils.java \
 *         tools/grass-test/WindPhasePeriodTest.java
 *   java -cp /tmp/windtest com.reandroid.wallpaper.grass.WindPhasePeriodTest
 *
 * (MathUtils 用 tools/ 下的替身:真身带 android.graphics.Color,纯 JVM 编不过。
 *  只列这两个源文件,别把真身也放进来。)
 *
 * 看点不是"误差是否恰好为 0"(float 做不到),而是两件事:
 *   1. 256 处的误差要远小于噪声自身的值域,也要小于**单帧的正常变化量** ——
 *      满足才算"取模无缝",否则回绕处会出现一次可见的跳变。
 *   2. 128 处不能也几乎相等,否则真实周期更小,常量取大了。
 *   3. 最后一条是真正的回归用例:复现"天气切换时旧式相位瞬移",并证明积分式不会。
 */
package com.reandroid.wallpaper.grass;

import java.util.Random;

public final class WindPhasePeriodTest {

    /** GrassScene.WIND_PHASE_PERIOD */
    private static final float PERIOD = 256.0f;
    /** GrassScene.WIND_PHASE_PER_SEC */
    private static final float PER_SEC = 0.04f;

    public static void main(String[] args) {
        int failures = 0;
        GrassWindField field = new GrassWindField();
        field.init(new Random(12345));

        // ---- 噪声自身的值域与"单帧变化量"，作为判定基准 ----
        float minF = Float.MAX_VALUE, maxF = -Float.MAX_VALUE;
        float maxFrameStep = 0.0f;
        float step = PER_SEC * 0.016f;             // 60fps 下一帧推进的相位
        for (int i = 0; i < 20000; i++) {
            float x = -20.0f + i * 0.37f;
            float y = (i * 0.013f) % PERIOD;
            float v = field.turbulencef2(x, y, 4.0f);
            minF = Math.min(minF, v);
            maxF = Math.max(maxF, v);
            float next = (y + step) % PERIOD;
            maxFrameStep = Math.max(maxFrameStep, Math.abs(
                    field.turbulencef2(x, next, 4.0f) - v));
        }
        float range = maxF - minF;
        System.out.println("噪声值域 = " + range + "，单帧正常变化上限 = " + maxFrameStep);

        // ---- 1. 周期处误差必须淹没在单帧变化之下 ----
        float maxErr = 0.0f;
        for (int i = 0; i < 20000; i++) {
            float x = -20.0f + i * 0.37f;
            // 只在场景真正会用的区间 [0, 256) 内取值：相位是取过模的
            float y = (i * 0.013f) % PERIOD;
            float a = field.turbulencef2(x, y, 4.0f);
            float b = field.turbulencef2(x, y + PERIOD, 4.0f);
            maxErr = Math.max(maxErr, Math.abs(a - b));
        }
        System.out.println("周期 256 处的最大误差 = " + maxErr);
        if (maxErr > maxFrameStep) {
            failures++;
            System.out.println("失败: 回绕误差(" + maxErr + ")超过单帧变化(" + maxFrameStep
                    + ")，取模会产生可见跳变");
        }

        // ---- 2. 128 不应是周期 ----
        float maxAtHalf = 0.0f;
        for (int i = 0; i < 20000; i++) {
            float x = -20.0f + i * 0.37f;
            float y = (i * 0.013f) % PERIOD;
            float a = field.turbulencef2(x, y, 4.0f);
            float b = field.turbulencef2(x, y + PERIOD / 2.0f, 4.0f);
            maxAtHalf = Math.max(maxAtHalf, Math.abs(a - b));
        }
        System.out.println("半周期处的最大差 = " + maxAtHalf);
        if (maxAtHalf < 0.01f * range) {
            failures++;
            System.out.println("失败: 128 也是周期，常量应改小");
        }

        // ---- 3. 真的跨过回绕点，比较回绕帧与普通帧的变化量 ----
        float x = 3.5f;
        float phase = 0.0f;
        float wrapStep = 0.0f, normalStep = 0.0f;
        int wraps = 0;
        float bigStep = PER_SEC * 0.016f;
        for (int i = 0; i < 900000; i++) {         // 0.00064/帧 → 576，跨 2 次回绕
            float next = (phase + bigStep) % PERIOD;
            float d = Math.abs(field.turbulencef2(x, next, 4.0f)
                    - field.turbulencef2(x, phase, 4.0f));
            if (next < phase) { wraps++; wrapStep = Math.max(wrapStep, d); }
            else normalStep = Math.max(normalStep, d);
            phase = next;
        }
        System.out.println("回绕次数 = " + wraps + "，回绕帧变化 = " + wrapStep
                + "，普通帧变化上限 = " + normalStep);
        if (wraps < 2) {
            failures++;
            System.out.println("失败: 用例没有真正跨过回绕点，结论无效");
        }
        if (wrapStep > normalStep) {
            failures++;
            System.out.println("失败: 回绕帧的变化大于普通帧 —— 回绕处有跳变");
        }

        // ---- 4. 天气切换：旧式(绝对时间×倍率) vs 积分式 ----
        // 复现原 BUG：开机一小时后，倍率从 1.0(晴朗) 跳到 1.9(雷暴)。
        //
        // 必须对**整片草**统计：单点采样会靠运气(相位跳了 130 单位，某个 x 处的噪声
        // 可能恰好接近)。真实场景里 x 横跨整个宽度，每根草采样不同的 x，所以判据取
        // "全场平均变化量" —— 相位一跳，整个图案平移，全场都会变。
        final int XS = 128;
        float[] xs = new float[XS];
        for (int i = 0; i < XS; i++) {
            xs[i] = -20.0f + i * (40.0f / (XS - 1));
        }

        float uptimeMs = 3600000.0f;               // 开机一小时
        float frameMs = 16.0f;
        float phaseInt = 0.0f;                     // 积分式
        float[] prevOld = new float[XS];
        float[] prevNew = new float[XS];
        float maxOldDelta = 0.0f, maxNewDelta = 0.0f;
        float oldSwitchDelta = 0.0f, oldNormalDelta = 0.0f;
        for (int i = 0; i < 200; i++) {
            float scale = i < 100 ? 1.0f : 1.9f;   // 第 100 帧切换天气
            // 旧式：相位 = 绝对时间 × 系数 × 倍率
            float yOld = uptimeMs * 0.00004f * scale;
            // 新式：相位按倍率对时间积分，并取模
            phaseInt = (phaseInt + (frameMs / 1000.0f) * PER_SEC * scale) % PERIOD;

            float sumOld = 0.0f, sumNew = 0.0f;
            for (int k = 0; k < XS; k++) {
                float vOld = field.turbulencef2(xs[k], yOld, 4.0f);
                float vNew = field.turbulencef2(xs[k], phaseInt, 4.0f);
                if (i > 0) {
                    sumOld += Math.abs(vOld - prevOld[k]);
                    sumNew += Math.abs(vNew - prevNew[k]);
                }
                prevOld[k] = vOld;
                prevNew[k] = vNew;
            }
            if (i > 0) {
                float dOld = sumOld / XS, dNew = sumNew / XS;
                maxOldDelta = Math.max(maxOldDelta, dOld);
                maxNewDelta = Math.max(maxNewDelta, dNew);
                if (i == 100) oldSwitchDelta = dOld; else oldNormalDelta = Math.max(oldNormalDelta, dOld);
            }
            uptimeMs += frameMs;
        }
        System.out.println("天气切换全场平均变化：切换帧(旧式) = " + oldSwitchDelta
                + "，普通帧(旧式)上限 = " + oldNormalDelta);
        System.out.println("天气切换：旧式最大帧间跳变 = " + maxOldDelta
                + "，积分式 = " + maxNewDelta
                + "（噪声值域 " + range + "，正常单帧 " + maxFrameStep + "）");
        if (oldSwitchDelta < 0.1f * range) {
            failures++;
            System.out.println("失败: 用例没能复现旧式的跳变，说明场景没构造对");
        }
        if (maxNewDelta > maxFrameStep) {
            failures++;
            System.out.println("失败: 积分式在切换处仍超过正常单帧变化");
        }

        System.out.println(failures == 0 ? "全部通过" : failures + " 个用例失败");
        if (failures != 0) System.exit(1);
    }
}
