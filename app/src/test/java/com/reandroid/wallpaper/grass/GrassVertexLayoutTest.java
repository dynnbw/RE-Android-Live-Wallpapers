package com.reandroid.wallpaper.grass;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 草叶顶点步长的守卫：**它只能有一个来源**。
 *
 * <p>步长从 8 改成 9 那次，顶点数组按新步长写了，而"顶点数 = float 数 ÷ 8"这一句没跟着改。
 * 于是 {@code drawBlades} 算出的 float 数比数组本身还大，撞上"越界就别画"的保护直接返回 ——
 * **整片草地消失**，而且不报错、日志里什么都没有。
 *
 * <p>这类漏改没法用行为测试守（顶点构建会碰 {@code android.graphics.Color}，JVM 单测里会抛
 * not mocked），所以守源码：凡是与顶点数、步长有关的地方，都必须引那个共用的常量。
 */
public class GrassVertexLayoutTest {

    private static final String BUILDER =
            "src/main/java/com/reandroid/wallpaper/grass/GrassRenderDataBuilder.java";
    private static final String GL = "src/main/java/com/reandroid/wallpaper/grass/GrassGL.java";

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }

    /** 顶点数必须从共用步长除出来，不能再写死一个数。 */
    @Test
    public void theVertexCountDividesByTheSharedStride() throws Exception {
        String builder = read(BUILDER);
        assertTrue("顶点数没有按 FLOATS_PER_GRASS_VERTEX 换算",
                builder.contains("mVKGrassFloatCount / FLOATS_PER_GRASS_VERTEX"));
        assertFalse("顶点数还在按写死的 8 换算 —— 草会整片消失",
                builder.contains("mVKGrassFloatCount / 8"));
    }

    /** 顶点数组的容量也要按共用步长算，否则写到一半就撞边界。 */
    @Test
    public void theVertexArrayIsSizedByTheSharedStride() throws Exception {
        String builder = read(BUILDER);
        assertFalse("顶点数组的容量还在乘写死的 8",
                builder.contains("vertexCount * 2) * 8"));
    }

    /** GLES 侧的属性指针一律用字节步长常量，不留字面量。 */
    @Test
    public void theGlesAttributePointersUseTheSharedStride() throws Exception {
        String gl = read(GL);
        assertTrue("GLES 没有用 GRASS_VERTEX_STRIDE_BYTES",
                gl.contains("GRASS_VERTEX_STRIDE_BYTES"));
        assertFalse("GLES 的属性指针还在写死 32 字节",
                gl.contains(", 32, mGrassVertexBuffer)"));
    }

    /** 顶点缓冲的分配也要用同一个常量。 */
    @Test
    public void theVertexBufferAllocationUsesTheSharedStride() throws Exception {
        String gl = read(GL);
        assertTrue("草叶顶点缓冲没有按 FLOATS_PER_GRASS_VERTEX 分配",
                gl.contains("vertexTotal * stride * 4"));
    }
}
