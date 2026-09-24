package com.reandroid.wallpaper.fall;

import com.reandroid.utils.SkyField;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 资源文件与着色器的对表。
 *
 * <p>为什么要测一份**数据文件**：{@code pond_sky_fields.txt} 由手工写的 64 个十六进制
 * 色值组成，揉进一行六百多字符。少写一个色值、多写一个逗号，
 * {@link SkyField#parseSection} 都不会报错 —— 要么整段返回 null（水面退回占位色），
 * 要么整条渐变悄悄错位。这些都是要到真机上肉眼才看得出来的事，所以在单测里钉住。
 *
 * <p>还要守**跨文件约定**：Java 侧按名字查 uniform，而 GL 对不存在的 uniform
 * 只是静默忽略（{@code glGetUniformLocation} 返回 -1，设值不报错）。
 * 于是着色器里拼错一个名字，症状是"那个功能就是没效果"，没有任何错误信息。
 * 这里把两边对一遍。
 */
public class FallAssetsTest {

    private static final File SKY_FIELDS =
            new File("src/main/assets/fall/data/pond_sky_fields.txt");
    private static final File WATER_FS =
            new File("src/main/assets/fall/shaders/GLES/fall_water_fs.glsl");
    private static final File LEAF_FS =
            new File("src/main/assets/fall/shaders/GLES/fall_fs.glsl");
    /** 只为了核对 {@code STAR_WRAP_MS} —— 它与着色器的 STAR_WRAP_S 是一对。 */
    private static final File FALL_GL =
            new File("src/main/java/com/reandroid/wallpaper/fall/FallGL.java");

    /**
     * 四段色场。顺序与 {@code FallGL.SKY_SECTIONS} 一致 ——
     * 但这里的断言不依赖顺序，只要求四段都在。
     */
    private static final String[] SECTIONS = {
            "SKY_FIELD_MORNING", "SKY_FIELD_DAY", "SKY_FIELD_DUSK", "SKY_FIELD_NIGHT"
    };

    /** {@link SkyField} 把色场重采样成固定 64 行，于是每条色带必须正好 64 个色值。 */
    private static final int ROWS = 64;

    private static String read(File file) throws IOException {
        assertTrue(file + " 不存在：" + file.getAbsolutePath(), file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /**
     * 着色器里有没有 {@code uniform [精度] <类型> <名字>;} 这条声明。
     *
     * <p>按声明找而不是按 {@code "uniform " + name} 找：类型与名字之间的空白数
     * 是随对齐变的（{@code uniform vec2  uEmitterPos;} 就是两个空格），
     * 按字符串拼会漏掉，而漏掉的症状恰好是"测试报红但代码没错"。
     *
     * <p>精度限定词可有可无（{@code uniform highp float uStarTime;}）——
     * 漏掉这一支会把它当成"没声明"。数组下标同理（{@code uniform float uAlgaePower[$DROP_SIZE];}）。
     */
    private static boolean declaresUniform(String glsl, String name) {
        return Pattern.compile(
                        "uniform\\s+(?:(?:lowp|mediump|highp)\\s+)?\\w+\\s+"
                                + Pattern.quote(name) + "(?:\\[[\\w$]+\\])?\\s*;")
                .matcher(glsl).find();
    }

    @Test
    public void everySectionParses() throws IOException {
        String text = read(SKY_FIELDS);
        for (String section : SECTIONS) {
            int[][] field = SkyField.parseSection(text, section);
            assertNotNull("色场段解析不出来：" + section, field);
            assertEquals(section + " 应当只有一行（纯竖直渐变）", 1, field.length);
            assertEquals(section + " 的色值个数", ROWS, field[0].length);
        }
    }

    @Test
    public void everyColorIsFullyOpaque() throws IOException {
        String text = read(SKY_FIELDS);
        for (String section : SECTIONS) {
            int[] row = SkyField.parseSection(text, section)[0];
            for (int i = 0; i < row.length; i++) {
                int alpha = (row[i] >>> 24) & 0xFF;
                assertEquals(section + " 第 " + i + " 个色值的 alpha", 0xFF, alpha);
            }
        }
    }

    /** 四条色带必须彼此不同 —— 复制粘贴时最容易留下一段没改的。 */
    @Test
    public void theFourRampsAreAllDifferent() throws IOException {
        String text = read(SKY_FIELDS);
        for (int i = 0; i < SECTIONS.length; i++) {
            for (int j = i + 1; j < SECTIONS.length; j++) {
                int[] a = SkyField.parseSection(text, SECTIONS[i])[0];
                int[] b = SkyField.parseSection(text, SECTIONS[j])[0];
                assertTrue(SECTIONS[i] + " 与 " + SECTIONS[j] + " 完全相同，八成是漏改了",
                        !java.util.Arrays.equals(a, b));
            }
        }
    }

    /** 每条渐变的两个端点要不一样：整条同色说明色值写重复了。 */
    @Test
    public void everyRampActuallyRamps() throws IOException {
        String text = read(SKY_FIELDS);
        for (String section : SECTIONS) {
            int[] row = SkyField.parseSection(text, section)[0];
            assertTrue(section + " 的首尾同色，不像一条渐变", row[0] != row[row.length - 1]);
        }
    }

    /**
     * 色场段名与着色器 uniform 的对表。
     *
     * <p>{@code SKY_FIELD_MORNING} ↔ {@code uSkyMorning} / {@code uWeightMorning}。
     */
    @Test
    public void shaderDeclaresOneUniformPairPerSection() throws IOException {
        String fs = read(WATER_FS);
        for (String section : SECTIONS) {
            String suffix = section.substring("SKY_FIELD_".length());
            String camel = suffix.charAt(0) + suffix.substring(1).toLowerCase(java.util.Locale.ROOT);
            assertTrue("着色器缺少 uniform uSky" + camel,
                    declaresUniform(fs, "uSky" + camel));
            assertTrue("着色器缺少 uniform uWeight" + camel,
                    declaresUniform(fs, "uWeight" + camel));
        }
    }

    /**
     * 天空发光体的 uniform 必须都在水面着色器里 ——
     * 少一个就是"发光体不出现"，而且不会有任何报错。
     */
    @Test
    public void waterShaderDeclaresTheEmitterUniforms() throws IOException {
        String fs = read(WATER_FS);
        for (String name : new String[]{
                "uEmitterPos", "uEmitterRadius", "uEmitterColor", "uEmitterGain",
                "uStarAmount", "uStarTime", "uStarAspect",
                "uAlgaeAmount", "uAlgaeGain",
                "uAlgaeThreshold", "uAlgaeBand", "uAlgaePower",
                "uAlgaeNoise", "uAlgaeNoiseTile", "uAlgaeNoiseGain",
                "uAlgaeArcScale",
                "uAlgaeLow", "uAlgaeHigh"}) {
            assertTrue("水面着色器缺少 uniform " + name, declaresUniform(fs, name));
        }
    }

    /**
     * 亮弧场的 uniform 在**两份**着色器里都要有。
     *
     * <p>漏了不会有任何报错：{@code glGetUniformLocation} 返回 -1，设值静默失败 ——
     * 水面那边就是"还是一整圈"，叶子那边就是"光被整片吃掉"
     * （门限恒为未初始化的 0）。
     */
    @Test
    public void algaeArcUniformExistsInBothShaders() throws IOException {
        for (File file : new File[]{WATER_FS, LEAF_FS}) {
            String fs = read(file);
            for (String name : new String[]{"uAlgaeArcScale", "uAlgaeLow", "uAlgaeHigh"}) {
                assertTrue(file.getName() + " 缺少 uniform " + name,
                        declaresUniform(fs, name));
            }
        }
    }

    /**
     * 两份着色器里的 {@code ALGAE_*} 常量必须**逐条相等**。
     *
     * <p>GLSL 没有 include，这段逻辑在水面与叶子各留了一份。常量一旦只改一边，
     * 症状是"叶子上的光和水里那圈对不上" —— 没有报错、也不会崩，只能靠肉眼看出来。
     */
    @Test
    public void algaeConstantsMatchBetweenTheTwoShaders() throws IOException {
        java.util.regex.Matcher m = Pattern
                .compile("const\\s+float\\s+(ALGAE_\\w+)\\s*=\\s*([0-9.]+)")
                .matcher(read(WATER_FS));
        java.util.Map<String, String> water = new java.util.TreeMap<>();
        while (m.find()) {
            water.put(m.group(1), m.group(2));
        }
        assertTrue("水面着色器里一个 ALGAE_ 常量都没找到", water.size() >= 4);

        java.util.regex.Matcher n = Pattern
                .compile("const\\s+float\\s+(ALGAE_\\w+)\\s*=\\s*([0-9.]+)")
                .matcher(read(LEAF_FS));
        java.util.Map<String, String> leaf = new java.util.TreeMap<>();
        while (n.find()) {
            leaf.put(n.group(1), n.group(2));
        }
        assertEquals("两份着色器的 ALGAE_ 常量对不上：叶子上的光会和水里那圈错位",
                water.toString(), leaf.toString());
    }

    /**
     * {@code uStarTime} 必须是 highp。
     *
     * <p>片元着色器的 float 默认 mediump（约 10 位尾数），而这个要参与正弦的自变量，
     * 精度不够就会把闪烁量化成台阶 —— 上机实测过：最亮值连续 48 帧不动、然后一步跳 80%。
     */
    @Test
    public void starTimeIsDeclaredHighPrecision() throws IOException {
        String fs = read(WATER_FS);
        assertTrue("uStarTime 必须以 highp 声明，否则闪烁会变成阶梯",
                Pattern.compile("uniform\\s+highp\\s+float\\s+uStarTime\\s*;")
                        .matcher(fs).find());
    }

    /**
     * 闪烁时间的回绕周期，Java 与着色器两边必须一致。
     *
     * <p>这是个**跨文件、且失败无声**的约定：周期对不上，回绕那一刻相位不会走完整数圈，
     * 全天星星会一起跳一下 —— 三十秒才发生一次，很容易被当成"偶发的卡顿"而不去查。
     */
    @Test
    public void starWrapPeriodMatchesBetweenJavaAndShader() throws IOException {
        java.util.regex.Matcher shader = Pattern
                .compile("STAR_WRAP_S\\s*=\\s*([0-9.]+)")
                .matcher(read(WATER_FS));
        assertTrue("着色器里找不到 STAR_WRAP_S", shader.find());
        float shaderSeconds = Float.parseFloat(shader.group(1));

        java.util.regex.Matcher java = Pattern
                .compile("STAR_WRAP_MS\\s*=\\s*(\\d+)L")
                .matcher(read(FALL_GL));
        assertTrue("FallGL 里找不到 STAR_WRAP_MS", java.find());
        long javaMs = Long.parseLong(java.group(1));

        assertEquals("着色器的 STAR_WRAP_S 与 FallGL 的 STAR_WRAP_MS 必须对得上，"
                        + "否则回绕处星星会一起跳",
                (long) (shaderSeconds * 1000), javaMs);
    }

    /** 落叶的染色 uniform 同理：少了就是"染色没效果"。 */
    @Test
    public void leafShaderDeclaresTheTintUniforms() throws IOException {
        String fs = read(LEAF_FS);
        for (String name : new String[]{"uTint", "uTintAmount", "uValue"}) {
            assertTrue("落叶着色器缺少 uniform " + name, declaresUniform(fs, name));
        }
    }
}
