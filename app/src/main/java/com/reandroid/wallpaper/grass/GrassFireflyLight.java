package com.reandroid.wallpaper.grass;

/**
 * 从萤火虫的精灵批里取出光源列表，喂给草叶着色器。
 *
 * <p><b>为什么不直接用 {@code SceneData.fireflies}：</b>那一份还得自己再算一遍闪烁、
 * 昼夜淡入淡出，以及传统萤火虫那套状态机 —— 算出来就可能与画面上看到的不是一个数。
 * 精灵批是**这一帧真正画出去的那一份**，从它里面取，两边不可能不一致。
 *
 * <p>顺带把两种萤火虫一起覆盖了：现代与传统（{@code pref_grass_legacy_firefly}）
 * 走的都是 {@code buildFireflySpriteVertices}，取一次两边都有。
 *
 * <p><b>光源数封顶，超出的按"谁更靠下"取，不按亮度。</b> 片元着色器里是一个定长循环，
 * 多出来的光斑没有位置放，所以必须挑。挑的判据不能是亮度：萤火虫的亮度是**振荡**的
 * （眨眼），按它排名的话，"最亮的 N 只"每帧都在换人，被挤出去那只会**瞬间熄灭** ——
 * 画面上就是一个个光斑硬生生地闪。而站位是稳的：谁在下、谁在上，几秒才变一次。
 *
 * <p>何况靠下本来就是更该留下的那只：草地长在屏幕下方，越往下探的光斑越照得到草。
 *
 * <p>纯数学，无 GL / Android 依赖。
 */
final class GrassFireflyLight {

    private GrassFireflyLight() {
    }

    /** 一个精灵 quad 由两个三角形拼成，共 6 个顶点。 */
    private static final int VERTICES_PER_QUAD = 6;

    /** 顶点里 alpha 的下标（格式 x, y, u, v, a）。 */
    private static final int ALPHA_OFFSET = 4;

    /** 清空输出，返回 0 —— 每次收集光源前必须先来一次（否则上一帧的槽位会留下幽灵光斑）。 */
    static int clear(float[] out) {
        final int slots = GrassConstants.FIREFLY_LIGHT_MAX;
        if (out == null) {
            return 0;
        }
        int limit = Math.min(out.length, slots * 3);
        for (int i = 0; i < limit; i++) {
            out[i] = 0.0f;
        }
        return 0;
    }

    /**
     * 把一批精灵里的萤火虫**追加**进光源列表，返回新的光源数。
     *
     * <p>之所以是"追加"而不是"覆盖"：传统萤火虫的**本体与闪光在两个批里**
     * （{@code buildFireflySpriteVertices} 按 {@code flareActive} 把自己劈成两半，
     * 一个批只有正在闪光的，另一个只有没闪的）。只看本体批的话，萤火虫一亮起来就从那个批里
     * 消失了 —— 地上那团光会**恰好在它最亮的时候熄灭**，与直觉正好相反。
     *
     * <p>亮度取 quad 的顶点 alpha，所以眨眼与昼夜淡入淡出都自动跟着走。
     *
     * @param spriteVerts 精灵批，格式 x, y, u, v, a
     * @param floatCount  批里实际有效的 float 数
     * @param out         输出，长度至少 {@code FIREFLY_LIGHT_MAX * 3}
     * @param count       已有的光源数，见 {@link #clear}
     * @return 新的光源数，不超过 {@code FIREFLY_LIGHT_MAX}
     */
    static int append(float[] spriteVerts, int floatCount, float[] out, int count) {
        final int slots = GrassConstants.FIREFLY_LIGHT_MAX;
        if (out == null || out.length < slots * 3 || spriteVerts == null || floatCount <= 0) {
            return count;
        }

        final int stride = GrassRenderDataBuilder.FLOATS_PER_SPRITE_VERTEX;
        final int quadFloats = stride * VERTICES_PER_QUAD;
        int limit = Math.min(floatCount, spriteVerts.length);

        for (int base = 0; base + quadFloats <= limit; base += quadFloats) {
            float glow = spriteVerts[base + ALPHA_OFFSET];
            if (!(glow > 0.0f)) {
                continue;
            }
            // quad 中心 = 顶点 0 与顶点 2 的中点。这两个是对角，所以即使精灵带旋转，
            // 中点仍然是中心 —— 直接读顶点 0 会拿到左上角，光斑会整体偏出去半格。
            float cx = (spriteVerts[base] + spriteVerts[base + stride * 2]) * 0.5f;
            float cy = (spriteVerts[base + 1] + spriteVerts[base + stride * 2 + 1]) * 0.5f;
            count = insert(out, count, slots, cx, cy, glow);
        }
        return count;
    }

    /**
     * 单个批的快捷方式：清空之后收一批。多批的场合用 {@link #clear} + {@link #append}。
     */
    static int collect(float[] spriteVerts, int floatCount, float[] out) {
        int count = clear(out);
        return append(spriteVerts, floatCount, out, count);
    }

    /**
     * 按 **y 降序**插入（越靠下越优先），写满 {@code slots} 之后只顶掉最后那个最靠上的。
     *
     * <p>判据为什么不是亮度，见类注释：亮度在振荡，按它排名会导致成员每帧变动、光斑硬切。
     *
     * <p>并列时保持先来的在前 —— 用的是稳定的插入排序，不引入新的抖动。
     *
     * @return 新的光源数，不超过 {@code slots}
     */
    private static int insert(float[] out, int count, int slots, float x, float y, float glow) {
        if (count >= slots && y <= out[(slots - 1) * 3 + 1]) {
            return count;
        }
        int pos = Math.min(count, slots - 1);
        // 把比新来的靠上的往后挪一格。写满时最后一个（最靠上的）就这样被挤出去 ——
        // 它本来就是该丢的那个：光斑离草地最远。
        while (pos > 0 && out[(pos - 1) * 3 + 1] < y) {
            int prev = (pos - 1) * 3;
            out[pos * 3] = out[prev];
            out[pos * 3 + 1] = out[prev + 1];
            out[pos * 3 + 2] = out[prev + 2];
            pos--;
        }
        out[pos * 3] = x;
        out[pos * 3 + 1] = y;
        out[pos * 3 + 2] = glow;
        return Math.min(count + 1, slots);
    }
}
