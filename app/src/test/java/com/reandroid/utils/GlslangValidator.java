package com.reandroid.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 离线着色器闸门的公共部分：找 {@code glslangValidator}，以及调它编译 / 链接。
 *
 * <p>抽出来是因为**每个壁纸一套闸门**（grass、musicvis……），而"去哪里找这个工具"这件事
 * 很容易各写各的、然后其中一份悄悄失效。
 *
 * <p><b>刻意不写死版本号目录</b>：曾经写的是 {@code F:/VulkanSDK/1.4.341.1/Bin/...}，
 * SDK 一升级就找不到，{@code Assume} 让整个闸门**静默变成"跳过"并报绿** —— 给出恰好相反
 * 的虚假信心：以为着色器都在被检查，其实一个都没检查。这里扫所有版本目录、取名字最大的那个。
 */
public final class GlslangValidator {

    private GlslangValidator() {
    }

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

    /** @return 可执行文件；找不到返回 null（调用方 {@code Assume} 跳过） */
    public static File find() {
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

    /** 文件名 → glslang 认的阶段名。 */
    public static String stageOf(String name) {
        if (name.endsWith("_vs.glsl")) return "vert";
        if (name.endsWith("_fs.glsl")) return "frag";
        throw new IllegalArgumentException("认不出阶段：" + name);
    }

    /**
     * 编译一条着色器。
     *
     * @return 通过返回 {@code null}，否则返回编译器的输出（连同文件名），给断言直接看
     */
    public static String compileError(File validator, File shader) throws IOException {
        return run(validator, new String[]{
                "-S", stageOf(shader.getName()), shader.getAbsolutePath()},
                shader.getName());
    }

    /**
     * 链接一对顶点/片元着色器。
     *
     * <p>单条编译通过、链接失败照样是黑屏：重写太阳着色器时就栽过 —— 新 vs 顶部是
     * {@code precision highp float;} 而当时的 fs 还是 {@code mediump}，两者共用的 uniform
     * 精度不一致，链接直接失败，而**单条编译完全查不出这一条**。
     *
     * <p>glslangValidator 靠扩展名认阶段，而我们的文件叫 {@code *_vs.glsl}，所以要先把两条
     * 拷成 {@code .vert} / {@code .frag} 临时文件再 {@code -l}。
     */
    public static String linkError(File validator, File vs, File fs) throws IOException {
        File tmpVs = File.createTempFile("shadergate", ".vert");
        File tmpFs = File.createTempFile("shadergate", ".frag");
        try {
            copy(vs, tmpVs);
            copy(fs, tmpFs);
            return run(validator, new String[]{"-l", tmpVs.getAbsolutePath(), tmpFs.getAbsolutePath()},
                    vs.getName() + " + " + fs.getName());
        } finally {
            tmpVs.delete();
            tmpFs.delete();
        }
    }

    private static String run(File validator, String[] args, String label) throws IOException {
        String[] cmd = new String[args.length + 1];
        cmd[0] = validator.getAbsolutePath();
        System.arraycopy(args, 0, cmd, 1, args.length);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (!proc.waitFor(60, TimeUnit.SECONDS)) {
                return "=== " + label + System.lineSeparator() + "编译超时";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "=== " + label + System.lineSeparator() + "被中断";
        }
        if (proc.exitValue() == 0) {
            return null;
        }
        return "=== " + label + System.lineSeparator() + out;
    }

    private static void copy(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }
}
