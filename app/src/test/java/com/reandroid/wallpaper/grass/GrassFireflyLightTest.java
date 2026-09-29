package com.reandroid.wallpaper.grass;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 萤火虫光源提取的守卫。
 *
 * <p>要守的两件事都不是"某版写了什么"，而是**错了会静默画歪**的那种：
 * <ul>
 *   <li>光斑中心取的是 quad 的**对角中点**。直接读顶点 0（左上角）也会算出数、
 *       也不会报错，只是整片光偏出去半格。</li>
 *   <li>槽位每帧先清零。不清的话上一帧的光斑会留在原地不动 —— 萤火虫飞走了，
 *       地上那块亮斑还在。</li>
 * </ul>
 */
public class GrassFireflyLightTest {

    private static final int MAX = GrassConstants.FIREFLY_LIGHT_MAX;
    private static final int STRIDE = 5;
    private static final int QUAD = STRIDE * 6;

    /** 与 {@code GrassRenderDataBuilder.appendSpriteQuadVertices} 同一套算式的两三角 quad。 */
    private static int appendQuad(float[] out, int cursor,
            float cx, float cy, float size, float alpha, float rotationDeg) {
        float half = size * 0.5f;
        float rad = (float) Math.toRadians(rotationDeg);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);

        float x0 = (-half * cos) - (-half * sin) + cx;
        float y0 = (-half * sin) + (-half * cos) + cy;
        float x1 = (-half * cos) - (half * sin) + cx;
        float y1 = (-half * sin) + (half * cos) + cy;
        float x2 = (half * cos) - (half * sin) + cx;
        float y2 = (half * sin) + (half * cos) + cy;
        float x3 = (half * cos) - (-half * sin) + cx;
        float y3 = (half * sin) + (-half * cos) + cy;

        float[][] verts = {
                {x0, y0}, {x1, y1}, {x2, y2}, {x0, y0}, {x2, y2}, {x3, y3},
        };
        for (float[] v : verts) {
            out[cursor++] = v[0];
            out[cursor++] = v[1];
            out[cursor++] = 0.0f;
            out[cursor++] = 0.0f;
            out[cursor++] = alpha;
        }
        return cursor;
    }

    private static float[] single(float cx, float cy, float size, float alpha, float rot) {
        float[] batch = new float[QUAD];
        appendQuad(batch, 0, cx, cy, size, alpha, rot);
        return batch;
    }

    @Test
    public void theLightSitsAtTheQuadCentreEvenWhenTheSpriteIsRotated() {
        float[] out = new float[MAX * 3];
        // 顶点 0 在 (cx - half, cy - half)，不取中点就会偏出去半个身位
        assertEquals(1, GrassFireflyLight.collect(single(400.0f, 900.0f, 80.0f, 1.0f, 37.0f),
                QUAD, out));
        assertEquals(400.0f, out[0], 1.0E-3f);
        assertEquals(900.0f, out[1], 1.0E-3f);
    }

    @Test
    public void theVertexAlphaBecomesTheGlow() {
        float[] out = new float[MAX * 3];
        assertEquals(1, GrassFireflyLight.collect(single(10.0f, 20.0f, 40.0f, 0.35f, 0.0f),
                QUAD, out));
        assertEquals(0.35f, out[2], 1.0E-6f);
    }

    /** 暗到 0 的那几帧不该点亮任何东西 —— 萤火虫眨眼时地上那团光要跟着灭。 */
    @Test
    public void aDarkFireflyContributesNothing() {
        float[] out = new float[MAX * 3];
        assertEquals(0, GrassFireflyLight.collect(single(10.0f, 20.0f, 40.0f, 0.0f, 0.0f),
                QUAD, out));
        assertEquals(0.0f, out[2], 0.0f);
    }

    /** 超出槽位时留下**靠下的** —— 草地长在屏幕下方，越往下探的光斑越照得到草。 */
    @Test
    public void keepsTheLowestWhenThereAreMoreThanFit() {
        int count = MAX + 5;
        float[] batch = new float[count * QUAD];
        int cursor = 0;
        for (int i = 0; i < count; i++) {
            // y 递增（越往后越靠下）：留下的是最后 MAX 只，最靠上的前 5 只被挤掉
            cursor = appendQuad(batch, cursor, 100.0f, i * 10.0f, 30.0f, 0.5f, 0.0f);
        }

        float[] out = new float[MAX * 3];
        assertEquals(MAX, GrassFireflyLight.collect(batch, cursor, out));

        // 输出按 y 降序，第 0 位是全场最靠下的那只
        assertEquals((count - 1) * 10.0f, out[1], 1.0E-3f);
        // 留下的是最靠下的 MAX 只，所以最靠上的那只是 (count - MAX) * 10
        float lowestKept = (count - MAX) * 10.0f;
        for (int i = 0; i < MAX; i++) {
            assertTrue("最靠上的萤火虫混进了列表", out[i * 3 + 1] >= lowestKept);
        }
    }

    /**
     * **成员不能随亮度变。** 亮度是振荡的（眨眼），按它排名的话"最亮的 N 只"每帧换人，
     * 被挤出去那只的光斑会瞬间熄灭 —— 画面上就是硬切。同一批位置、只有亮度不同时，
     * 选出来的必须一模一样。
     */
    @Test
    public void membershipDoesNotDependOnTheOscillatingGlow() {
        int count = MAX + 4;
        // 同一批位置，两次只有亮度不同
        float[] dim = new float[count * QUAD];
        float[] bright = new float[count * QUAD];
        int cursor = 0;
        for (int i = 0; i < count; i++) {
            cursor = appendQuad(dim, cursor, 100.0f, i * 10.0f, 30.0f, 0.20f, 0.0f);
        }
        int brightCursor = 0;
        for (int i = 0; i < count; i++) {
            brightCursor = appendQuad(bright, brightCursor, 100.0f, i * 10.0f, 30.0f, 0.95f, 0.0f);
        }

        float[] a = new float[MAX * 3];
        float[] b = new float[MAX * 3];
        assertEquals(MAX, GrassFireflyLight.collect(dim, cursor, a));
        assertEquals(MAX, GrassFireflyLight.collect(bright, brightCursor, b));

        for (int i = 0; i < MAX; i++) {
            assertEquals("亮度一变，选中的萤火虫就换了位置 —— 光斑会硬切",
                    a[i * 3 + 1], b[i * 3 + 1], 0.0f);
        }
    }

    /** 萤火虫飞走之后，地上的光斑不能留在原地 —— 没被覆盖的槽位必须是 0。 */
    @Test
    public void unusedSlotsAreClearedEveryCall() {
        float[] full = new float[MAX * QUAD];
        int cursor = 0;
        for (int i = 0; i < MAX; i++) {
            cursor = appendQuad(full, cursor, i * 5.0f, 50.0f, 20.0f, 0.5f, 0.0f);
        }

        float[] out = new float[MAX * 3];
        assertEquals(MAX, GrassFireflyLight.collect(full, cursor, out));

        // 同一块输出，换一个只有一只萤火虫的批
        assertEquals(1, GrassFireflyLight.collect(single(7.0f, 8.0f, 20.0f, 0.5f, 0.0f),
                QUAD, out));
        for (int i = 1; i < MAX; i++) {
            assertEquals("槽位 " + i + " 还留着上一帧的光源", 0.0f, out[i * 3 + 2], 0.0f);
        }
    }

    @Test
    public void anEmptyBatchClearsEverything() {
        float[] out = new float[MAX * 3];
        for (int i = 0; i < out.length; i++) {
            out[i] = 9.0f;
        }
        assertEquals(0, GrassFireflyLight.collect(null, 0, out));
        for (float v : out) {
            assertEquals(0.0f, v, 0.0f);
        }
    }

    /** 尾部不满一个 quad 的零头要忽略掉，不能拿半个顶点当光源。 */
    @Test
    public void aTrailingPartialQuadIsIgnored() {
        float[] batch = new float[QUAD + 7];
        appendQuad(batch, 0, 33.0f, 44.0f, 20.0f, 0.4f, 0.0f);
        assertEquals(1, GrassFireflyLight.collect(batch, batch.length, new float[MAX * 3]));
    }

    /**
     * 传统萤火虫的**本体与闪光在两个互斥的批里**（按是否正在闪光劈开），光源必须从两个批
     * 一起收。只收本体的话，萤火虫最亮的那一刻恰好从那个批里消失 —— 地上的光跟着灭，
     * 与直觉正相反。
     */
    @Test
    public void lightsAreAccumulatedAcrossBatches() {
        float[] out = new float[MAX * 3];
        int count = GrassFireflyLight.clear(out);
        assertEquals(0, count);

        // 本体批：两只没在闪的
        float[] body = new float[2 * QUAD];
        int cursor = 0;
        cursor = appendQuad(body, cursor, 10.0f, 20.0f, 20.0f, 0.4f, 0.0f);
        cursor = appendQuad(body, cursor, 30.0f, 40.0f, 20.0f, 0.6f, 0.0f);
        count = GrassFireflyLight.append(body, cursor, out, count);
        assertEquals(2, count);

        // 闪光批：一只正在闪的，而且是最亮的 —— 它正是原来会漏掉的那只
        float[] flare = new float[QUAD];
        int flareCursor = appendQuad(flare, 0, 50.0f, 60.0f, 20.0f, 0.9f, 0.0f);
        count = GrassFireflyLight.append(flare, flareCursor, out, count);

        assertEquals("两个批的光源没有被合到一起", 3, count);
        // 闪光批那一只（y 最大，也最亮）应当在场 —— 它正是原来会漏掉的那只
        assertEquals(0.9f, out[2], 1.0E-6f);
        assertEquals(50.0f, out[0], 1.0E-3f);
    }

    /** 收集第二批之前不清空 —— 否则第一批会被抹掉，等于只收了一个批。 */
    @Test
    public void appendDoesNotWipeWhatIsAlreadyThere() {
        float[] out = new float[MAX * 3];
        int count = GrassFireflyLight.clear(out);
        count = GrassFireflyLight.append(single(1.0f, 1.0f, 10.0f, 0.3f, 0.0f), QUAD, out, count);
        count = GrassFireflyLight.append(single(2.0f, 2.0f, 10.0f, 0.5f, 0.0f), QUAD, out, count);

        assertEquals(2, count);
        assertEquals(2.0f, out[0], 1.0E-3f);   // 靠下的在前
        assertEquals(1.0f, out[3], 1.0E-3f);   // 靠上的还在，没被第二批抹掉
    }

    @Test
    public void anOutputArrayThatIsTooSmallIsRejected() {
        assertEquals(0, GrassFireflyLight.collect(single(1.0f, 2.0f, 10.0f, 1.0f, 0.0f),
                QUAD, new float[MAX * 3 - 1]));
        assertEquals(0, GrassFireflyLight.collect(single(1.0f, 2.0f, 10.0f, 1.0f, 0.0f),
                QUAD, null));
    }

    /**
     * 数组长度在两个地方各写了一遍：Java 的 {@link GrassConstants#FIREFLY_LIGHT_MAX}
     * 与着色器的 {@code FLY_MAX}。
     *
     * <p>对不上的后果是不对称的、而且**不会报错**：Java 侧更小时着色器里的空槽位
     * 永远没人清零（那些槽位的 f.z 恒为 0，实际还能侥幸无事），Java 侧更大时超出的
     * 光源根本传不进去、静默少几只。
     */
    @Test
    public void theShaderLoopBoundMatchesTheJavaConstant() throws Exception {
        String fs = new String(Files.readAllBytes(new File(
                "src/main/assets/grass/shaders/GLES/grass_grass_fs.glsl").toPath()),
                StandardCharsets.UTF_8);
        assertTrue("片元着色器里的 FLY_MAX 与 FIREFLY_LIGHT_MAX 对不上了",
                fs.contains("#define FLY_MAX " + MAX));
        assertTrue("片元着色器没有声明 uFirefly 数组",
                fs.contains("uFirefly[FLY_MAX]"));
    }
}
