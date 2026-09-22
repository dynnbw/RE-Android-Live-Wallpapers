package com.reandroid.wallpaper.grass;

import com.reandroid.utils.MathUtils;

/**
 * 逐叶受光。
 *
 * <p>第一版把"光落在哪里"做成了一个以光源屏幕位置为中心的径向渐变 —— 于是同一距离上
 * 每一片叶拿到的值**数学上必然相等**，整片草一起变金、没有个体差异。2D 光照的经典文章
 * 对这个失败模式的描述是原话级的：没有法线贴图时，
 * <i>light simply illuminates the overall shape of a sprite uniformly</i>。
 *
 * <p>所以这里补的不是参数，是**缺的信号**：叶片朝哪、有没有被挡住。
 *
 * <p>纯数学，无 GL / Android 依赖 —— 单测没开 {@code returnDefaultValues}，
 * 任何 {@code android.*} 调用都会抛 not mocked。
 */
final class GrassBladeLighting {

    private GrassBladeLighting() {
    }

    /** 朝向与光源方向几乎垂直/平行时的下限，防止退化输入产生 NaN。 */
    private static final float EPSILON = 1.0E-4f;

    /**
     * 叶片**横截面轴**与「到光源方向」的夹角余弦，带符号。
     *
     * <p>叶片是一片薄带，横截面上的法线从一侧扫到另一侧，所以横截面轴就是那个"左右"方向。
     * 它的**符号**指出光在叶片的哪一侧（决定亮边落在哪条边），**绝对值**是受光强弱。
     *
     * <p>参数一律是屏幕坐标（y 向下），与 grass 的投影一致。
     *
     * @return [-1, 1]；退化输入返回 0
     */
    static float facing(float baseX, float baseY, float tipX, float tipY,
                        float lightX, float lightY) {
        float tx = tipX - baseX;
        float ty = tipY - baseY;
        float tLen = (float) Math.sqrt(tx * tx + ty * ty);
        if (tLen < EPSILON) {
            return 0.0f;
        }
        // 横截面轴 = 叶片走向旋转 90°
        float cx = -ty / tLen;
        float cy = tx / tLen;

        float lx = lightX - baseX;
        float ly = lightY - baseY;
        float lLen = (float) Math.sqrt(lx * lx + ly * ly);
        if (lLen < EPSILON) {
            return 0.0f;
        }
        return MathUtils.clamp((cx * lx + cy * ly) / lLen, -1.0f, 1.0f);
    }
}
