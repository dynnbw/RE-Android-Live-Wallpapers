package com.reandroid.wallpaper.grass;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * grass 两条渲染路径之间靠**约定**连起来的那些东西。
 *
 * <p>GLSL 没有 include，草叶光照在 GLES 与 VK 各有一份；JNI 描述符是手写字符串。
 * 这些地方错了都不会报错：着色器照常编译，只是观感悄悄变了；漏一个参数则是打开壁纸
 * 直接崩在 {@code System.loadLibrary} 里。都在这里钉住。
 */
public class GrassVkParityTest {

    private static final File GRASS_FS_GLES =
            new File("src/main/assets/grass/shaders/GLES/grass_grass_fs.glsl");
    private static final File GRASS_FS_VK = new File("src/main/shaders/grassvk_grass.frag");
    private static final File GRASS_JNI = new File("src/main/jni/grassvk_jni.cpp");
    private static final File GLOW_BLUR_GLES =
            new File("src/main/assets/grass/shaders/GLES/glow_blur_fs.glsl");
    private static final File GLOW_BLUR_VK = new File("src/main/shaders/grassvk_glow_blur.frag");
    private static final File GLOW_PARAMS =
            new File("src/main/java/com/reandroid/gles/GlowParams.java");
    private static final File GRASS_VNATIVE =
            new File("src/main/java/com/reandroid/wallpaper/grass/GrassVKNative.java");

    private static String read(File file) throws IOException {
        assertTrue(file + " 不存在", file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /**
     * 逆光里几处系数两份着色器必须一致。
     *
     * <p>它们不是常量（写死在式子里），抄错一个数不会有任何报错 ——
     * 只是 VK 那边的草看起来比 GLES 那边"薄"或"厚"一点，很难定位。
     */
    @Test
    public void backlightWeightsMatchBetweenTheEngines() throws IOException {
        String gles = read(GRASS_FS_GLES);
        String vk = read(GRASS_FS_VK);
        for (String snippet : new String[] {
            "mix(0.85, 1.10", // 透射：偏背光侧的加成
            "mix(0.90, 1.15", // 透射色：薄处透得更多
            "4.0 * a * (1.0 - a)", // 轮廓：必须用 alpha 本身
        }) {
            assertTrue(GRASS_FS_GLES.getName() + " 缺少 " + snippet, gles.contains(snippet));
            assertTrue(GRASS_FS_VK.getName() + " 缺少 " + snippet, vk.contains(snippet));
        }
    }

    /** 光源数组的长度：Java 侧常量、VK UBO 与着色器循环三者必须一致。 */
    @Test
    public void fireflyLightCountFitsTheShaderLoop() throws IOException {
        assertEquals(16, GrassConstants.FIREFLY_LIGHT_MAX);
        assertTrue("VK 着色器的循环上限与 FIREFLY_LIGHT_MAX 不一致", read(GRASS_FS_VK).contains("i < 16"));
        assertTrue(
                "GLES 着色器的 FLY_MAX 与 FIREFLY_LIGHT_MAX 不一致",
                read(GRASS_FS_GLES).contains("#define FLY_MAX 16"));
    }

    /**
     * 精灵槽位表：Java 上传的槽位与 native 的 {@code kSpriteTextureCount} 必须对得上。
     *
     * <p>漏一个槽位的症状是"那张天气贴图永远不出现"，不报错；多一个则会有未写的描述符。
     */
    @Test
    public void weatherSpriteSlotsMatchTheNativeTable() throws IOException {
        String java = read(GRASS_VNATIVE);
        java.util.regex.Matcher m = Pattern.compile(
                        "upload(?:Sprite|Solid)Texture\\(handle, (\\d+),")
                .matcher(java);
        int maxSlot = -1;
        while (m.find()) {
            maxSlot = Math.max(maxSlot, Integer.parseInt(m.group(1)));
        }
        assertTrue("GrassVKNative 里一个精灵槽位都没找到", maxSlot >= 0);
        assertTrue("槽位 " + maxSlot + " 超出了 native 的 kSpriteTextureCount = 24", maxSlot < 24);
        // VK 那边加槽位时这边也要动，所以把两边的"表"钉在一起
        String jni = read(GRASS_JNI);
        assertTrue("native 的槽位数与 Java 的 24 槽约定对不上", jni.contains("kSpriteTextureCount = 24"));
    }

    /**
     * 辉光模糊的核两份着色器必须一致（grass 用的是现算权重的 9 抽头，不是 fall 那套固定权重）。
     *
     * <p>抄错一个数只是光晕观感不同，不会报错；σ 也得两边同一个来源。
     */
    @Test
    public void glowBlurKernelMatchesBetweenTheEngines() throws IOException {
        String gles = read(GLOW_BLUR_GLES);
        String vk = read(GLOW_BLUR_VK);
        for (String snippet : new String[] {"const int TAPS = 4;", "exp(-0.5 * f * f * inv)"}) {
            assertTrue(GLOW_BLUR_GLES.getName() + " 缺少 " + snippet, gles.contains(snippet));
            assertTrue(GLOW_BLUR_VK.getName() + " 缺少 " + snippet, vk.contains(snippet));
        }
        // σ 在 GLES 侧是 GlowParams.BLUR_SIGMA_STEPS，VK 侧是 native 里的常量
        assertTrue("GlowParams 的 σ 变了", read(GLOW_PARAMS).contains("BLUR_SIGMA_STEPS = 2.0f"));
        assertTrue(
                "grassvk 的 σ 与 GlowParams 对不上", read(GRASS_JNI).contains("glowBlurSigma_ = 2.0f"));
    }

    /** native 登记的描述符必须与 Java 声明一致（多一个少一个参数都会崩在 loadLibrary）。 */
    @Test
    public void renderFrameSignatureMatchesTheJavaDeclaration() throws Exception {
        assertEquals(
                expectedSignature("com.reandroid.wallpaper.grass.GrassVKNative", "nRenderFrame"),
                registeredSignature(GRASS_JNI, "nRenderFrame"));
    }

    private static String expectedSignature(String className, String methodName)
            throws ClassNotFoundException {
        // initialize = false：静态块会 System.loadLibrary，JVM 上没有这个 .so
        Class<?> cls = Class.forName(className, false, GrassVkParityTest.class.getClassLoader());
        Method found = null;
        for (Method method : cls.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                found = method;
            }
        }
        assertNotNull("Java 侧没有 native 方法 " + methodName, found);
        StringBuilder out = new StringBuilder("(");
        for (Class<?> type : found.getParameterTypes()) {
            out.append(descriptor(type));
        }
        return out.append(')').append(descriptor(found.getReturnType())).toString();
    }

    private static String descriptor(Class<?> type) {
        if (type == int.class) return "I";
        if (type == long.class) return "J";
        if (type == float.class) return "F";
        if (type == double.class) return "D";
        if (type == short.class) return "S";
        if (type == byte.class) return "B";
        if (type == boolean.class) return "Z";
        if (type == char.class) return "C";
        if (type == void.class) return "V";
        if (type.isArray()) return "[" + descriptor(type.getComponentType());
        return "L" + type.getName().replace('.', '/') + ";";
    }

    private static String registeredSignature(File source, String name) throws IOException {
        String text = read(source);
        Matcher m = Pattern.compile("\"" + name + "\",\\s*\"([^\"]+)\"").matcher(text);
        assertTrue("native 那里没有登记 " + name, m.find());
        return m.group(1);
    }
}
