package com.reandroid.wallpaper.grass;

import com.reandroid.utils.GlslangValidator;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * grass 全部 GLSL 的离线编译闸门。
 *
 * <p>着色器没法在 JVM 里渲染验证，但可以过一遍 {@code glslangValidator}。这一步的价值在
 * 于：改着色器时语法错误会立刻红，而不是等到装机之后从 logcat 里翻
 * {@code glGetShaderInfoLog}。本项目的 ES3 迁移也是用同一个工具当硬闸门的。
 *
 * <p>没装 glslangValidator 的机器上跳过（{@link Assume}），不判失败。
 *
 * <p>工具的查找与调用在 {@link GlslangValidator} —— musicvis 那套闸门用的是同一个，
 * 免得"去哪里找这个工具"各写各的、其中一份悄悄失效。
 */
public class GrassShaderCompileTest {

    private static final File SHADER_DIR = new File("src/main/assets/grass/shaders/GLES");

    @Test
    public void everyGrassShaderCompiles() throws Exception {
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

    /**
     * 这 10 条必须都在。
     *
     * <p>没有这一条的话，{@link #everyGrassShaderCompiles} 只 glob 现有文件 ——
     * **删掉一条着色器照样通过**（"剩下的都编过了"）。而删掉一条的真实后果是运行期
     * {@code AssetLoader.readText} 返回 null、程序编译失败、那个壁纸功能静默失效。
     */
    private static final String[] REQUIRED = {
            "grass_bg_fs.glsl", "grass_bg_vs.glsl",
            "grass_grass_fs.glsl", "grass_grass_vs.glsl",
            "grass_moon_fs.glsl", "grass_moon_vs.glsl",
            "grass_sky_fs.glsl", "grass_sky_vs.glsl",
            "grass_sun_fs.glsl", "grass_sun_vs.glsl",
            "grass_rain_screen_fs.glsl", "grass_rain_screen_vs.glsl",
            "glow_quad_vs.glsl", "glow_bright_fs.glsl",
            "glow_blur_fs.glsl", "glow_composite_fs.glsl",
    };

    /**
     * vs → 它要配的 fs。
     *
     * <p>**不能按后缀推。** 原来这里是 "由 vs 去掉后缀拼出 fs 的名字"，
     * 那假设了一对一；而 {@code glow_quad_vs} 一个顶点着色器要配三条片元
     * （亮部提取 / 模糊 / 合成），按后缀推会去找不存在的 {@code glow_quad_fs}
     * 而直接红。写成显式表之后，一对多就是一目了然的几行。
     */
    private static final String[][] LINK_PAIRS = {
            { "grass_bg_vs.glsl", "grass_bg_fs.glsl" },
            { "grass_grass_vs.glsl", "grass_grass_fs.glsl" },
            { "grass_moon_vs.glsl", "grass_moon_fs.glsl" },
            { "grass_sky_vs.glsl", "grass_sky_fs.glsl" },
            { "grass_sun_vs.glsl", "grass_sun_fs.glsl" },
            { "grass_rain_screen_vs.glsl", "grass_rain_screen_fs.glsl" },
            { "glow_quad_vs.glsl", "glow_bright_fs.glsl" },
            { "glow_quad_vs.glsl", "glow_blur_fs.glsl" },
            { "glow_quad_vs.glsl", "glow_composite_fs.glsl" },
    };

    @Test
    public void everyRequiredShaderIsPresent() {
        File[] files = SHADER_DIR.listFiles((d, n) -> n.endsWith(".glsl"));
        assertTrue("着色器目录为空：" + SHADER_DIR.getAbsolutePath(),
                files != null && files.length > 0);

        java.util.Set<String> present = new java.util.HashSet<>();
        for (File f : files) present.add(f.getName());

        java.util.List<String> missing = new java.util.ArrayList<>();
        for (String r : REQUIRED) {
            if (!present.contains(r)) missing.add(r);
        }
        assertEquals("这些着色器不见了（删掉或改名了？）：" + missing, 0, missing.size());
    }

    /**
     * 成对的 vs/fs 必须能**链接** —— 单条编译通过、链接失败照样是黑屏。
     *
     * <p>这不是假想的风险。重写太阳着色器时，新 vs 顶部是 {@code precision highp float;}
     * 而当时的 fs 还是 {@code mediump}，两者共用的 {@code uTime} / {@code uLineAlpha}
     * 精度不一致，链接直接失败 —— 而**单条编译完全查不出这一条**（每条单独都合法）。
     */
    @Test
    public void everyShaderPairLinks() throws Exception {
        File validator = GlslangValidator.find();
        Assume.assumeTrue("未找到 glslangValidator，跳过（跳过 = 本闸门什么都没验）",
                validator != null);

        List<String> failures = new ArrayList<>();
        for (String[] pair : LINK_PAIRS) {
            String vs = pair[0];
            String fs = pair[1];
            File fsFile = new File(SHADER_DIR, fs);
            assertTrue(fs + " 不存在（" + vs + " 没有配对的片段着色器）", fsFile.isFile());

            String error = GlslangValidator.linkError(validator,
                    new File(SHADER_DIR, vs), fsFile);
            if (error != null) {
                failures.add(error);
            }
        }
        assertEquals("有着色器配对链接失败：" + failures, 0, failures.size());
    }

    /** 每条着色器都要显式声明 `#version 300 es` —— 缺了就退回 ESSL 1.00。 */
    @Test
    public void everyShaderDeclaresEssl300() throws Exception {
        File[] files = SHADER_DIR.listFiles((d, n) -> n.endsWith(".glsl"));
        assertTrue(files != null && files.length > 0);
        for (File f : files) {
            String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            assertTrue(f.getName() + " 缺少 #version 300 es",
                    src.startsWith("#version 300 es"));
        }
    }
}
