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

    /** 遮挡曲线的软度：阻挡者达到这个数时衰减到 e^{-1} 的位置。 */
    static final float OCCLUSION_K = 12.0f;

    /**
     * 遮挡最狠能压掉多少。
     *
     * <p>**这个上限是实测逼出来的。** 原先 {@code occ = exp(-blockers/K)} 没有上限，
     * 实测 782 片密集的草里平均 **8.6** 个阻挡者 → {@code occ ≈ 0.058}，
     * 把整片草一起压平（{@code mean|beam|} 掉到 0.015，画面上就是"极少数的草上有极细微的效果"）。
     *
     * <p>而那个阻挡者数本身是**包围盒重叠**的产物，不是真的几何遮挡 —— 盒子的范围远大于叶片。
     * 所以这里封顶：宁可让它只是"压暗一档"，也不要让它把效果整体抹掉。
     * 真实草地里逆光确实一半是剪影，但剪影不该让**受光的那部分也消失**。
     */
    static final float OCCLUSION_MAX = 0.5f;

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

    /** 由阻挡者数量得到的遮挡系数 ∈ [1 - OCCLUSION_MAX, 1]。 */
    static float occlusion(int blockers) {
        if (blockers <= 0) {
            return 1.0f;
        }
        return 1.0f - OCCLUSION_MAX * (1.0f - (float) Math.exp(-blockers / OCCLUSION_K));
    }

    /**
     * 朝向调制的下限：叶片横截面轴与光向接近垂直时仍保留这么多。
     *
     * <p>**这一条是实测逼出来的。** 只用 {@code |facing|} 当强弱时，实测 782 片叶
     * （7 万个顶点）的 {@code mean|beam|} 只有 **0.05**、超过 0.3 的只占 **5%** ——
     * 因为黄金时刻太阳在屏幕**正下方**，而竖直叶片的横截面轴是**水平**的，
     * 两者点乘自然接近 0，于是九成叶片被抹平。
     */
    static final float FACING_FLOOR = 0.35f;

    /**
     * 可见度：离光源的屏幕距离越远越小，**半径处恰好为 0**。
     *
     * <p>这一项曾经被删掉过，理由是"文献里透光模型没有距离项" —— 那条对
     * **无穷远的平行光**成立，而我们的太阳是屏幕上的一个点。2D 投影里，
     * "离光源多近"正是"光是不是在这片叶背后"的唯一代理（相机与光源的连线投影成一个点）。
     * 删掉它之后强弱就只剩朝向，于是塌成了上面那个 0.05。
     *
     * <p>用 smoothstep 而不是 {@code 1/(1+d²/r²)}：后者在半径外还留尾巴，
     * 会把局域特效摊成全局滤镜。
     */
    static float visibility(float bladeX, float bladeY,
                            float lightX, float lightY, float radius) {
        float dx = lightX - bladeX;
        float dy = lightY - bladeY;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        float r = Math.max(radius, 1.0f);
        float t = MathUtils.clamp(dist / r, 0.0f, 1.0f);
        return 1.0f - t * t * (3.0f - 2.0f * t);
    }

    /**
     * 朝向带来的调制 ∈ [FACING_FLOOR, 1]：**用绝对值并抬高下限**，不塌到 0。
     *
     * <p>朝向仍然有用 —— 它逐帧跟着叶片摆动变，是"活的"那一半；只是不能再由它独占强弱。
     */
    static float facingGain(float facing) {
        return FACING_FLOOR + (1.0f - FACING_FLOOR) * Math.abs(facing);
    }

    /**
     * 每片叶的受光，带符号 ∈ [-1, 1]。
     *
     * <p>分解成四件事，各有各的职责：
     * <ul>
     *   <li>{@code visibility} —— 位置：光是不是在这片叶背后</li>
     *   <li>{@code facingGain} —— 因叶而异，且逐帧跟着摆动</li>
     *   <li>符号 —— 光在叶片的**哪一侧**，决定亮边落在哪条边</li>
     *   <li>{@code occlusion} —— 有没有被先画的叶子挡住</li>
     * </ul>
     *
     * <p>{@code lightStrength} 是总强度（开关 × 高度角曲线 × 天气，来自 {@link GrassBacklight}）。
     * 它为 0 时 {@code beam} **恰好**是 0 —— 这是"关掉就等于今天"的可测形式：
     * 着色器在 {@code uLight <= 0} 时提前返回，整条路径与加特效之前逐像素一致。
     */
    static float beam(float facing, float visibility, float occlusion, float lightStrength) {
        float side = facing < 0.0f ? -1.0f : 1.0f;
        return visibility * facingGain(facing) * side * occlusion * lightStrength;
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
