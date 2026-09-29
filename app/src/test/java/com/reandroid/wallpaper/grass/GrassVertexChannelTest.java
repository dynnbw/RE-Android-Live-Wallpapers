package com.reandroid.wallpaper.grass;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 顶点通道约定的守卫。
 *
 * <p>{@code vColor.a} 从恒为 {@code 1.0} 改成装**叶尖位置**之后，输出 alpha 不能再乘它，
 * 否则叶片会从根到尖淡出。**两个渲染器共用同一份顶点数据**（{@code GrassRenderDataBuilder}
 * 同时喂 GLES 和 VK），所以两条片段着色器必须一起改 —— 只改一条的话，另一条会静默变样，
 * 而且不会报任何错。
 *
 * <p>这才是要守的东西：不是"某一版写了什么"，而是**两条路径对同一个通道的解读必须一致**。
 */
public class GrassVertexChannelTest {

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }

    private static final String GLES_FS =
            "src/main/assets/grass/shaders/GLES/grass_grass_fs.glsl";
    private static final String VK_FS = "src/main/shaders/grassvk_grass.frag";

    /**
     * 输出 alpha 必须是采样到的轮廓 {@code a}，不能再乘 {@code vColor.a}
     * —— 那个分量现在装的是叶尖位置，乘上去就会把叶片淡掉。
     */
    @Test
    public void bothRenderersOutputTheSilhouetteAlpha() throws Exception {
        String gles = read(GLES_FS);
        String vk = read(VK_FS);

        assertFalse("GLES 草叶着色器又把 vColor.a 乘进输出 alpha 了",
                gles.contains("vColor.a * a"));
        assertFalse("VK 草叶着色器又把 vColor.a 乘进输出 alpha 了",
                vk.contains("vColor.a * a"));
    }

    /** 顶点构建器必须往 alpha 里写叶尖位置，而不是常数 1。 */
    @Test
    public void theBuilderWritesTheTipFractionIntoAlpha() throws Exception {
        String builder = read(
                "src/main/java/com/reandroid/wallpaper/grass/GrassRenderDataBuilder.java");

        assertTrue("构建器没有写 tipFraction", builder.contains("tipFraction"));
        assertFalse("构建器还在往 alpha 里写常数 1.0f",
                builder.contains("b, 1.0f, 0.0f, 0.0f)"));
    }

    /**
     * 草叶顶点的字节数与 C++ 那边的 {@code GrassVertex} 必须对上。
     *
     * <p>顶点数据是 Java 侧拼好、JNI 用 {@code memcpy(..., count * sizeof(GrassVertex))}
     * 整块搬进映射缓冲的。两边尺寸一旦不一致，搬过去的就是**错位的几何** —— 不崩溃、
     * 不报错，只是草长得乱七八糟。C++ 那个 {@code static_assert} 只保证自己那一侧自洽，
     * 跨语言的这一半只有这里能守。
     */
    @Test
    public void theVertexSizeMatchesTheCppStruct() throws Exception {
        String cpp = read("src/main/jni/grassvk_jni.cpp");
        int bytes = GrassRenderDataBuilder.FLOATS_PER_GRASS_VERTEX * 4;

        assertTrue("grassvk_jni.cpp 里的 GrassVertex 不是 " + bytes + " 字节："
                        + "FLOATS_PER_GRASS_VERTEX 与 C++ 结构体对不上了",
                cpp.contains("static_assert(sizeof(GrassVertex) == " + bytes));
    }

    /**
     * 第 9 个分量（逐叶萤火虫遮挡）在 GLES 那条路上要真的走到底：顶点着色器收进来、
     * 片元着色器乘进光里。少接一节就是"这个功能静默不生效"，而着色器编译得过、测试也不红。
     */
    @Test
    public void theFireflyShadowChannelIsWiredThrough() throws Exception {
        String vs = read("src/main/assets/grass/shaders/GLES/grass_grass_vs.glsl");
        String fs = read("src/main/assets/grass/shaders/GLES/grass_grass_fs.glsl");

        assertTrue("顶点着色器没有收 aFireflyShadow", vs.contains("in float aFireflyShadow"));
        assertTrue("顶点着色器没有把 aFireflyShadow 传下去",
                vs.contains("vFireflyShadow = aFireflyShadow"));
        assertTrue("片元着色器没有收 vFireflyShadow", fs.contains("in float vFireflyShadow"));
        assertTrue("片元着色器没有把 vFireflyShadow 乘进萤火虫的光里",
                fs.contains("* vFireflyShadow"));
    }

    /**
     * 萤火虫的光必须**乘在叶片自己的颜色上**，不能只是往底色上加光。
     *
     * <p>底色是「本色 × 夜色系数」，夜里那个系数是 0.1 甚至 0 —— 往黑上加光，画面上只剩灯
     * 自己的颜色，草的本色一点也读不出来。这一条栽过一次，所以钉住。
     */
    @Test
    public void theFireflyLightLandsOnTheBladesOwnColour() throws Exception {
        String fs = read("src/main/assets/grass/shaders/GLES/grass_grass_fs.glsl");

        int at = fs.indexOf("uFireflyTint * uFireflyGain");
        assertTrue("片元着色器里找不到萤火虫那一项", at > 0);
        // 取那一行往前一点，看它乘的是不是 vColor.rgb
        String around = fs.substring(Math.max(0, at - 160), at);
        assertTrue("萤火虫的光没有乘在 vColor.rgb（叶片本色）上：往黑底上加光，颜色会完全偏离",
                around.contains("vColor.rgb"));
        assertTrue("萤火虫的光没有乘本色还原增益", around.contains("uFireflyAlbedoBoost"));
    }

    /** 本色还原增益与顶点里那个夜色系数必须同源 —— 各写一份会对不上。 */
    @Test
    public void theAlbedoBoostSharesTheNightScaleWithTheVertexColour() throws Exception {
        String builder = read(
                "src/main/java/com/reandroid/wallpaper/grass/GrassRenderDataBuilder.java");
        String gl = read("src/main/java/com/reandroid/wallpaper/grass/GrassGL.java");

        assertTrue("顶点颜色没有用 bladeValueScale",
                builder.contains("blade.b * GrassRenderDataBuilder.bladeValueScale("));
        assertTrue("增益没有用 bladeValueScale",
                gl.contains("GrassRenderDataBuilder.bladeValueScale("));
    }
}
