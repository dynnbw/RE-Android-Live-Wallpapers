package com.reandroid.wallpaper.grass;

import static com.reandroid.wallpaper.grass.GrassConstants.HALF_TESSELATION;

/**
 * 叶片中心线的行走。
 *
 * <p>从 {@code GrassRenderDataBuilder.appendBladeVertices()} 里抽出来，因为**挂珠也要用它**。
 * 两处各写一份的话，日后一改叶片形状（弯曲、锥度、风偏），水珠就会飘到叶外 ——
 * 而那种错不会报错，只会看着"有点怪"，很难查。
 *
 * <p><b>为什么水珠必须复用这一条</b>：行走用的是**当前帧的叶角**，所以锚定在
 * {@code (段号 k, 横向 u)} 上的水珠每帧重算位置，就会自动跟着叶子摆 ——
 * 不需要遮罩、不需要追踪、也不需要知道叶子上一帧在哪。这不是"顺带的好处"，
 * 而是把"跟着叶子摆"从"要实现的功能"变成了"结构上不可能做错"。
 *
 * <p><b>几何上的一个简化</b>：叶片**宽度是水平的**（同一点的两个顶点 y 相同），
 * 不是垂直于中心线的。所以叶面上 {@code (k, u)} 那一点就是
 * {@code (centerX[k] + u * halfWidth[k], centerY[k])}。
 *
 * <p>坐标是像素、y 向下（与 grass 的四边形批一致）。{@code u ∈ [-1, 1]}，
 * -1 是左边缘、+1 是右边缘。
 */
final class GrassBladeGeometry {

    private GrassBladeGeometry() {
    }

    /** 一条叶片走完有多少个点（含叶根那一个）。 */
    static int pointCount(Blade blade) {
        return blade.size + 1;
    }

    /**
     * 生效的宽度尺度。
     *
     * <p>单独抽出来是因为它有个容易漏的地方：{@link #trace} 要的是**已经乘过**
     * {@code grassWidthScale} 的值。两个调用方都从这里取，就不会有一处忘了乘。
     */
    static float effectiveScale(Blade blade, SceneData sd) {
        return blade.scale * sd.grassWidthScale;
    }

    /**
     * 叶根的屏幕 x（含整片草地的横向偏移 {@code sd.xDraw}）。
     *
     * <p>同样单独抽出来：{@link #trace} 用的是**已经加过** {@code xDraw} 的坐标。
     */
    static float originX(Blade blade, SceneData sd) {
        return blade.xPos + sd.xDraw;
    }

    /**
     * 走一遍叶片，把中心线与半宽填进调用方给的数组。
     *
     * <p>算术与顺序与原 {@code appendBladeVertices()} 里的循环逐字一致 ——
     * 这个函数是从那里**原样搬**出来的，不是照意思重写的。浮点运算不可交换，
     * 换个顺序就会让叶片几何产生肉眼看不见但逐位不同的偏移。
     *
     * @param blade         叶片
     * @param originX       叶根屏幕 x，见 {@link #originX}
     * @param scale         生效宽度尺度，见 {@link #effectiveScale}
     * @param hardnessScale {@code sd.grassHardnessScale}，决定每段转多少度
     * @param heightScale   {@code sd.grassHeightScale}，决定每段走多长
     * @param outXY         输出中心线，长度至少 {@code 2 * pointCount}；第 k 点是
     *                      {@code [2k], [2k+1]}
     * @param outHalfWidth  输出半宽，长度至少 {@code pointCount}
     */
    static void trace(Blade blade, float originX, float scale,
                      float hardnessScale, float heightScale,
                      float[] outXY, float[] outHalfWidth) {
        int size = blade.size;
        int total = size + 1;
        if (outXY.length < total * 2 || outHalfWidth.length < total) {
            throw new IllegalArgumentException("数组太小：需要 " + (total * 2)
                    + " / " + total + "，实得 " + outXY.length + " / " + outHalfWidth.length);
        }

        float bottomX = originX;
        float bottomY = blade.yPos;
        float d = blade.angle * blade.hardness * hardnessScale;
        float stepCos = (float) Math.cos(d);
        float stepSin = (float) Math.sin(d);
        float currentCos = 0.0f;
        float currentSin = 1.0f;

        // 叶根：注意 y 上有 HALF_TESSELATION 的抬升，这是原代码就有的
        outXY[0] = bottomX;
        outXY[1] = bottomY + HALF_TESSELATION;
        outHalfWidth[0] = size * scale;

        int k = 1;
        for (; size > 0; size--) {
            float lengthX = blade.lengthX * heightScale;
            float lengthY = blade.lengthY * heightScale;
            float topX = bottomX - currentCos * lengthX;
            float topY = bottomY - currentSin * lengthY;
            float spi = size * scale - scale;

            outXY[k * 2] = topX;
            outXY[k * 2 + 1] = topY;
            outHalfWidth[k] = spi;

            bottomX = topX;
            bottomY = topY;
            float nextCos = currentCos * stepCos - currentSin * stepSin;
            float nextSin = currentSin * stepCos + currentCos * stepSin;
            currentCos = nextCos;
            currentSin = nextSin;
            k++;
        }
    }

    /**
     * 叶面上 {@code (k, u)} 那一点的屏幕坐标，写进 {@code out[0..1]}。
     *
     * <p>{@code k} 是**小数**，会在相邻两点之间线性插值 —— 水珠沿叶面下滑时用的就是它。
     *
     * @param k          段号，{@code 0 <= k <= pointCount - 1}
     * @param u          横向，{@code -1} 左边缘、{@code +1} 右边缘
     */
    static void pointAt(float[] xy, float[] halfWidth, float k, float u, float[] out) {
        int last = halfWidth.length - 1;
        if (k < 0.0f) k = 0.0f;
        if (k > last) k = last;

        int i = (int) k;
        if (i >= last) {
            i = last - 1 < 0 ? 0 : last - 1;
        }
        float t = (last <= 0) ? 0.0f : (k - i);
        if (t < 0.0f) t = 0.0f;
        if (t > 1.0f) t = 1.0f;

        int i2 = Math.min(i + 1, last);
        float cx = xy[i * 2] + (xy[i2 * 2] - xy[i * 2]) * t;
        float cy = xy[i * 2 + 1] + (xy[i2 * 2 + 1] - xy[i * 2 + 1]) * t;
        float hw = halfWidth[i] + (halfWidth[i2] - halfWidth[i]) * t;

        out[0] = cx + u * hw;
        out[1] = cy;
    }
}
