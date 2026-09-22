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

    /** 遮挡射线的长度（像素）。取得太长会把整片草都算成互相遮挡。 */
    static final float OCCLUSION_RAY_LEN = 260.0f;

    /** 遮挡曲线的软度：阻挡者达到这个数时遮挡降到 1/e。 */
    static final float OCCLUSION_K = 3.0f;

    /**
     * 遮挡重算间隔（毫秒）。
     *
     * <p>叶片摆动只影响**朝向**，遮挡变化慢得多（要等草长得够多或光源明显移动），
     * 所以朝向每帧算、遮挡限频算。这个比值是"看得出变化"与"不浪费"之间的取舍。
     */
    static final long OCCLUSION_INTERVAL_MS = 250L;

    /** 叶片的包围盒：{minX, minY, maxX, maxY}，写进 {@code out}。 */
    private static void boundsOf(Blade blade, float[] out) {
        float scale = blade.scale;
        float halfSpread = blade.size * scale;
        float reach = (blade.lengthX + blade.lengthY) * scale;
        out[0] = blade.xPos - halfSpread;
        out[1] = blade.yPos - reach;
        out[2] = blade.xPos + halfSpread;
        out[3] = blade.yPos + halfSpread;
    }

    /** 线段 (x0,y0)-(x1,y1) 是否与包围盒相交（slab 法）。 */
    private static boolean segmentHitsBox(float x0, float y0, float x1, float y1, float[] box) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float tMin = 0.0f;
        float tMax = 1.0f;

        // X 轴 slab
        if (Math.abs(dx) < EPSILON) {
            if (x0 < box[0] || x0 > box[2]) return false;
        } else {
            float t1 = (box[0] - x0) / dx;
            float t2 = (box[2] - x0) / dx;
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
            if (tMin > tMax) return false;
        }
        // Y 轴 slab
        if (Math.abs(dy) < EPSILON) {
            if (y0 < box[1] || y0 > box[3]) return false;
        } else {
            float t1 = (box[1] - y0) / dy;
            float t2 = (box[3] - y0) / dy;
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
            if (tMin > tMax) return false;
        }
        return true;
    }

    /**
     * 第 {@code selfIndex} 片叶的遮挡 ∈ [0,1]，1 是完全受光。
     *
     * <p>{@code blades} 必须**按绘制顺序**排列（{@code SceneData.blades} 就是），
     * 因为绘制顺序就是深度：先画的离相机远、离太阳近，光先打到它们。
     *
     * <p>只数**先于自己绘制**的叶片。
     *
     * <p>复杂度 O(n²) 的包围盒测试：200 片时是 4 万次简单运算，可忽略；而且这是**限频**的
     * （见 {@link #OCCLUSION_INTERVAL_MS}）。
     */
    static float occlusionOf(Blade[] blades, int selfIndex, float lightX, float lightY) {
        if (blades == null || selfIndex < 0 || selfIndex >= blades.length) {
            return 1.0f;
        }
        Blade self = blades[selfIndex];
        if (self == null) {
            return 1.0f;
        }

        float dx = lightX - self.xPos;
        float dy = lightY - self.yPos;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < EPSILON) {
            return 1.0f;
        }
        float reach = Math.min(OCCLUSION_RAY_LEN, len);
        float ex = self.xPos + dx / len * reach;
        float ey = self.yPos + dy / len * reach;

        float[] box = new float[4];
        int blockers = 0;
        for (int i = 0; i < selfIndex; i++) {
            Blade other = blades[i];
            if (other == null) {
                continue;
            }
            boundsOf(other, box);
            if (segmentHitsBox(self.xPos, self.yPos, ex, ey, box)) {
                blockers++;
            }
        }
        return occlusion(blockers);
    }

    /** 由阻挡者数量得到的遮挡系数 ∈ (0,1]。 */
    static float occlusion(int blockers) {
        if (blockers <= 0) {
            return 1.0f;
        }
        return (float) Math.exp(-blockers / OCCLUSION_K);
    }

    /**
     * 每片叶的受光，带符号 ∈ [-1, 1]。
     *
     * <p>{@code lightStrength} 是总强度（开关 × 高度角曲线 × 天气，来自 {@link GrassBacklight}）。
     * 它为 0 时 {@code beam} **恰好**是 0 —— 这是"关掉就等于今天"的可测形式：
     * 着色器在 {@code uLight <= 0} 时提前返回，整条路径与加特效之前逐像素一致。
     */
    static float beam(float facing, float occlusion, float lightStrength) {
        return facing * occlusion * lightStrength;
    }

    /**
     * 叶尖位置 ∈ [0,1]：叶根 0、叶尖 1。
     *
     * <p>它同时是**厚度代理**：{@link GrassBladeGeometry#trace} 里的半宽在叶根是
     * {@code size * scale}、到叶尖线性收到 0，所以这个比值与"此处多厚"成正比。
     * 薄的地方透光多 —— 这就是"叶片内部要有从根到尖的渐变"那条。
     */
    static float tipFraction(int k, int size) {
        if (size <= 0) {
            return 0.0f;
        }
        return MathUtils.clamp(k / (float) size, 0.0f, 1.0f);
    }
}
