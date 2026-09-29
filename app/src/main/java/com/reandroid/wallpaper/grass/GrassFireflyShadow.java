package com.reandroid.wallpaper.grass;

/**
 * 逐叶算"萤火虫的光能不能照到这片草"。
 *
 * <p>光从**前方**来（萤火虫画在草之后，是离镜头最近的那一层），所以挡光的是**近处的草** ——
 * 见 {@link GrassBladeLighting#occlusionFromFrontOf}，与太阳那条只是方向相反。
 *
 * <p><b>为什么每片叶只按一只萤火虫算。</b> 着色器里是把半径内所有萤火虫的贡献**相加**的，
 * 而这片叶只有一个标量能带下去，没法逐只区分。取一只作代表是个近似，但光是局域的：
 * 一只就占了绝大多数。
 *
 * <p><b>取的是最近的那只，不是最亮的那只。</b> 亮度是**振荡**的（眨眼），按它挑的话，
 * 两只挨得差不多的萤火虫会轮流被选中，整片叶的阴影每帧跟着跳 —— 画面上是一片片
 * 硬闪的暗斑。距离是稳的：谁离得近，几秒才变一次；而且点光源下本来就是最近的主导阴影。
 *
 * <p>结果 1 = 不受影响，越小越暗，下限见 {@link GrassBladeLighting#OCCLUSION_MAX}。
 *
 * <p>纯数学，无 GL / Android 依赖。
 */
final class GrassFireflyShadow {

    private GrassFireflyShadow() {
    }

    /**
     * 算每片叶的萤火虫遮挡，写进 {@code out}（与 {@code blades} 一一对应）。
     *
     * <p>没有一只萤火虫照得到这片叶时写 1 —— 那样它本来就不受光，乘多少都一样。
     *
     * @param blades     按绘制顺序排列，与 {@code SceneData.blades} 同一份
     * @param lights     光源，每只 3 个 float：x, y, 亮度（见 {@link GrassFireflyLight}）
     * @param lightCount 有效光源数
     * @param radius     光斑半径（像素），与着色器里那个是同一个
     */
    static void compute(Blade[] blades, float[] lights, int lightCount,
                        float radius, float[] out) {
        if (blades == null || out == null || out.length < blades.length) {
            return;
        }
        if (lights == null || lightCount <= 0) {
            for (int i = 0; i < blades.length; i++) {
                out[i] = 1.0f;
            }
            return;
        }

        float r2 = radius * radius;
        for (int i = 0; i < blades.length; i++) {
            Blade blade = blades[i];
            if (blade == null) {
                out[i] = 1.0f;
                continue;
            }

            float bestDist2 = Float.MAX_VALUE;
            float bestX = 0.0f;
            float bestY = 0.0f;
            for (int k = 0; k < lightCount && k * 3 + 2 < lights.length; k++) {
                float glow = lights[k * 3 + 2];
                if (glow <= 0.0f) {
                    continue;
                }
                float dx = lights[k * 3] - blade.xPos;
                float dy = lights[k * 3 + 1] - blade.yPos;
                float d2 = dx * dx + dy * dy;
                // 照不到这片叶的（半径之外）不参与；**最近**的那只作代表，见类注释
                if (d2 >= r2 || d2 >= bestDist2) {
                    continue;
                }
                bestDist2 = d2;
                bestX = lights[k * 3];
                bestY = lights[k * 3 + 1];
            }

            out[i] = bestDist2 < Float.MAX_VALUE
                    ? GrassBladeLighting.occlusionFromFrontOf(blades, i, bestX, bestY)
                    : 1.0f;
        }
    }
}
