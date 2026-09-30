package com.reandroid.wallpaper.musicvis;

import com.reandroid.utils.GlslangValidator;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * musicvis 全部 GLSL 的离线编译闸门 —— 与 {@code GrassShaderCompileTest} 同一套做法，
 * 工具来自 {@link GlslangValidator}。
 *
 * <p>这一套原来**完全没有闸门**（只有 grass 有）。着色器是这里最不能靠"装了机再说"的东西：
 * 语法错、或者一对 vs/fs 精度对不上导致链接失败，都不会让构建红，只会让那个壁纸在实机上
 * 静默黑屏。
 *
 * <p>没装 glslangValidator 的机器上跳过（{@link Assume}），不判失败。
 */
public class MusicVisShaderCompileTest {

    private static final File SHADER_DIR = new File("src/main/assets/musicvis/shaders/GLES");

    /**
     * 这 12 条必须都在。
     *
     * <p>没有这一条的话，{@link #everyShaderCompiles} 只 glob 现有文件 ——
     * **删掉一条照样通过**（"剩下的都编过了"）。而删掉一条的真实后果是运行期
     * {@code AssetLoader.readText} 返回 null、程序编译失败、那个可视化静默失效。
     */
    private static final String[] REQUIRED = {
            "musicvis_line_fs.glsl", "musicvis_line_vs.glsl",
            "musicvis_many_line_fs.glsl", "musicvis_many_line_vs.glsl",
            "musicvis_many_quad_fs.glsl", "musicvis_many_quad_vs.glsl",
            "musicvis_vu_fs.glsl", "musicvis_vu_vs.glsl",
            "musicvis_wave_color_fs.glsl", "musicvis_wave_color_vs.glsl",
            "musicvis_wave_fs.glsl", "musicvis_wave_vs.glsl",
    };

    /** vs → 它要配的 fs（按代码里 {@code AssetLoader.readText} 的取法一一对应）。 */
    private static final String[][] LINK_PAIRS = {
            { "musicvis_line_vs.glsl", "musicvis_line_fs.glsl" },
            { "musicvis_many_line_vs.glsl", "musicvis_many_line_fs.glsl" },
            { "musicvis_many_quad_vs.glsl", "musicvis_many_quad_fs.glsl" },
            { "musicvis_vu_vs.glsl", "musicvis_vu_fs.glsl" },
            { "musicvis_wave_vs.glsl", "musicvis_wave_fs.glsl" },
            { "musicvis_wave_color_vs.glsl", "musicvis_wave_color_fs.glsl" },
    };

    @Test
    public void everyShaderCompiles() throws Exception {
        File validator = GlslangValidator.find();
        Assume.assumeTrue("未找到 glslangValidator，跳过（注意：跳过 = 本闸门什么都没验）",
                validator != null);
        System.out.println("glslangValidator = " + validator.getAbsolutePath());

        File[] files = SHADER_DIR.listFiles((d, n) -> n.endsWith(".glsl"));
        assertTrue("着色器目录为空：" + SHADER_DIR.getAbsolutePath(),
                files != null && files.length > 0);

        List<String> failures = new ArrayList<>();
        for (File f : files) {
            String error = GlslangValidator.compileError(validator, f);
            if (error != null) {
                failures.add(error);
            }
        }
        assertEquals("有着色器编译失败：\n" + String.join("\n", failures), 0, failures.size());
    }

    @Test
    public void everyRequiredShaderIsPresent() {
        File[] files = SHADER_DIR.listFiles((d, n) -> n.endsWith(".glsl"));
        assertTrue("着色器目录为空：" + SHADER_DIR.getAbsolutePath(),
                files != null && files.length > 0);

        Set<String> present = new HashSet<>();
        for (File f : files) present.add(f.getName());

        List<String> missing = new ArrayList<>();
        for (String r : REQUIRED) {
            if (!present.contains(r)) missing.add(r);
        }
        assertEquals("这些着色器不见了（删掉或改名了？）：" + missing, 0, missing.size());
    }

    @Test
    public void everyShaderPairLinks() throws Exception {
        File validator = GlslangValidator.find();
        Assume.assumeTrue("未找到 glslangValidator，跳过（跳过 = 本闸门什么都没验）",
                validator != null);

        List<String> failures = new ArrayList<>();
        for (String[] pair : LINK_PAIRS) {
            File fsFile = new File(SHADER_DIR, pair[1]);
            assertTrue(pair[1] + " 不存在（" + pair[0] + " 没有配对的片段着色器）", fsFile.isFile());

            String error = GlslangValidator.linkError(validator,
                    new File(SHADER_DIR, pair[0]), fsFile);
            if (error != null) {
                failures.add(error);
            }
        }
        assertEquals("有着色器配对链接失败：" + failures, 0, failures.size());
    }

    /** 每条都要显式声明 {@code #version 300 es} —— 缺了就退回 ESSL 1.00。 */
    @Test
    public void everyShaderDeclaresEssl300() throws Exception {
        File[] files = SHADER_DIR.listFiles((d, n) -> n.endsWith(".glsl"));
        assertTrue(files != null && files.length > 0);
        for (File f : files) {
            String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            assertTrue(f.getName() + " 缺少 #version 300 es", src.startsWith("#version 300 es"));
        }
    }
}
