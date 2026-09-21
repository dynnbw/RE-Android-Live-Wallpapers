package com.reandroid.wallpaper.grass;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
 */
public class GrassShaderCompileTest {

    /** 非 Windows 上直接按文件名找（走 PATH）。 */
    private static final String[] UNIX_NAMES = {
            "/usr/local/bin/glslangValidator",
            "/usr/bin/glslangValidator",
    };

    /** Windows 上扫描的 SDK 根目录。版本号目录名会变，所以**不能写死版本**。 */
    private static final String[] SDK_ROOTS = {
            "F:/VulkanSDK",
            "C:/VulkanSDK",
    };

    /**
     * 找 glslangValidator。
     *
     * <p><b>刻意不写死版本号目录</b>：原来这里写的是 {@code F:/VulkanSDK/1.4.341.1/Bin/...}。
     * 那样 SDK 一升级（比如 1.4.342.0），{@link #findValidator} 就找不到，
     * {@code Assume} 让整个闸门<b>静默变成"跳过"并报绿</b> —— 这会给出恰好相反的虚假信心：
     * 以为着色器都在被检查，其实一个都没检查。这里改成扫所有版本目录、取名字最大的那个。
     */
    private static File findValidator() {
        for (String n : UNIX_NAMES) {
            File f = new File(n);
            if (f.isFile()) return f;
        }
        String env = System.getenv("VULKAN_SDK");
        if (env != null && !env.isEmpty()) {
            File f = new File(new File(env, "Bin"), "glslangValidator.exe");
            if (f.isFile()) return f;
        }
        for (String root : SDK_ROOTS) {
            File[] versions = new File(root).listFiles();
            if (versions == null) continue;
            java.util.Arrays.sort(versions, (a, b) -> b.getName().compareTo(a.getName()));
            for (File v : versions) {
                File f = new File(new File(v, "Bin"), "glslangValidator.exe");
                if (f.isFile()) return f;
            }
        }
        return null;
    }

    private static final File SHADER_DIR = new File("src/main/assets/grass/shaders/GLES");

    private static String stageOf(String name) {
        if (name.endsWith("_vs.glsl")) return "vert";
        if (name.endsWith("_fs.glsl")) return "frag";
        throw new IllegalArgumentException("认不出阶段：" + name);
    }

    @Test
    public void everyGrassShaderCompiles() throws Exception {
        File validator = findValidator();
        Assume.assumeTrue("未找到 glslangValidator，跳过（注意：跳过 = 本闸门什么都没验）",
                validator != null);
        System.out.println("glslangValidator = " + validator.getAbsolutePath());

        File[] files = SHADER_DIR.listFiles((d, n) -> n.endsWith(".glsl"));
        assertTrue("着色器目录为空：" + SHADER_DIR.getAbsolutePath(),
                files != null && files.length > 0);

        List<String> failures = new ArrayList<>();
        for (File f : files) {
            ProcessBuilder pb = new ProcessBuilder(
                    validator.getAbsolutePath(), "-S", stageOf(f.getName()), f.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(f.getName() + " 编译超时", proc.waitFor(60, TimeUnit.SECONDS));
            if (proc.exitValue() != 0) {
                failures.add("=== " + f.getName() + "\n" + out);
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
