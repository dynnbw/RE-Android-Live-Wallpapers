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
}
