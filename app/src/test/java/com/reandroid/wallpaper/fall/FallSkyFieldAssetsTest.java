package com.reandroid.wallpaper.fall;

import com.reandroid.utils.SkyField;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 水面天空色场（{@code pond_sky_fields.txt}）与水面着色器的对表。
 *
 * <p>为什么要测一份**数据文件**：它由手工写的 64 个十六进制色值组成，揉进一行六百多字符。
 * 少写一个色值、多写一个逗号，{@link SkyField#parseSection} 都不会报错 ——
 * 要么整段返回 null（水面退回占位色），要么整条渐变悄悄错位。
 * 这些都是要到真机上肉眼才看得出来的事，所以在单测里钉住。
 *
 * <p>顺带守一条**跨文件约定**：色场段名 {@code SKY_FIELD_X} 必须与水面着色器里的
 * {@code uSkyX} / {@code uWeightX} 成对出现。改名只改一头，着色器那边会拿到空 uniform
 * （GL 对不存在的 uniform 只是静默忽略），画面于是永远停在默认权重上 —— 没有报错。
 */
public class FallSkyFieldAssetsTest {

    private static final File SKY_FIELDS =
            new File("src/main/assets/fall/data/pond_sky_fields.txt");
    private static final File WATER_FS =
            new File("src/main/assets/fall/shaders/GLES/fall_water_fs.glsl");

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
            assertTrue("着色器缺少 uniform sampler2D uSky" + camel,
                    fs.contains("uniform sampler2D uSky" + camel + ";"));
            assertTrue("着色器缺少 uniform float uWeight" + camel,
                    fs.contains("uniform float uWeight" + camel + ";"));
        }
    }
}
