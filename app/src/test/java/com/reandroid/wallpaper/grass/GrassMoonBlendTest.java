package com.reandroid.wallpaper.grass;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Pattern;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 月亮的昼夜过渡必须是**连续**的。
 *
 * <p>原先的毛病：{@code GrassScene} 里一句 {@code boolean isDaytime = sunAlt > 0.0}，
 * 渲染器再拿它二选一 {@code setBlendFunc} —— 白天滤色（
 * {@code GL_ONE, GL_ONE_MINUS_SRC_COLOR}，月面暗部 {@code src≈0} 时结果≈背景色，
 * 于是暗部看不见），夜里普通混合（暗部实心画出来）。太阳高度角一过 0，
 * 混合函数**整帧翻转**，月面暗部就"啪"地变实。
 *
 * <p>同一类毛病在天气色调那层已经修过一次 —— 见 {@code GrassScene} 里
 * "不能硬切"那段注释，当初也是 {@code if (sd.isNight) return;}。月亮是漏网的。
 *
 * <p>这一条测试守的是**结构**，不是像素：着色器里没法在 JVM 里求值，但"昼夜权重只能用
 * 连续量的形式参与"这件事可以查。一旦有人把布尔判断写回去，这条立刻红。
 *
 * <p>为什么按结构守而不是按数值守：数值那版只能把着色器的公式在 Java 里再抄一遍，
 * 抄件和正本会各自漂移，守不住任何东西。真正保证连续性的是"没有分支"这个形状。
 */
public class GrassMoonBlendTest {

    private static final File SHADER_DIR = new File("src/main/assets/grass/shaders/GLES");
    private static final File JAVA_DIR = new File("src/main/java/com/reandroid/wallpaper/grass");

    /** {@code if (...uDayWeight...)} —— 拿昼夜权重做真假判断，正是要禁掉的写法。 */
    private static final Pattern IF_ON_DAY_WEIGHT =
            Pattern.compile("if\\s*\\([^)]*uDayWeight[^)]*\\)", Pattern.DOTALL);

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    /**
     * 昼夜权重在着色器里必须只以连续量的形式参与。
     *
     * <p>反过来写（{@code if (uDayWeight > 0.5)} 之类）就等于把已修好的硬切又装回去，
     * 而且这次连编译都不会报错 —— 只会让月亮在某个瞬间跳一下。
     */
    @Test
    public void theMoonShaderNeverBranchesOnTheDayWeight() throws Exception {
        String src = read(new File(SHADER_DIR, "grass_moon_fs.glsl"));

        assertTrue("月亮着色器里找不到 uDayWeight —— 昼夜权重没有传进来，过渡不可能连续",
                src.contains("uDayWeight"));
        assertTrue("昼夜权重应当以 mix() 的插值参数出现，而不是被判断",
                src.contains("mix("));
        assertFalse("月亮着色器又拿昼夜权重做真假判断了，那会重新变成硬切："
                        + IF_ON_DAY_WEIGHT.matcher(src).pattern(),
                IF_ON_DAY_WEIGHT.matcher(src).find());
    }

    /**
     * Java 侧不许再有"是不是白天"这个布尔。
     *
     * <p>它不是少了会编译不过的那种东西 —— {@code moonIsDaytime} 可以安安静静地留着，
     * 只是没人用；而下一个人看到它，很容易就当成分昼夜的依据再拿起来用。
     */
    @Test
    public void noDaytimeBooleanSurvivesInTheJavaSources() throws Exception {
        File[] files = JAVA_DIR.listFiles((d, n) -> n.endsWith(".java"));
        assertTrue("没找到 grass 的 Java 源文件：" + JAVA_DIR.getAbsolutePath(),
                files != null && files.length > 0);

        StringBuilder hits = new StringBuilder();
        for (File f : files) {
            if (read(f).contains("moonIsDaytime")) {
                hits.append(f.getName()).append(' ');
            }
        }
        assertTrue("这些文件里还留着 moonIsDaytime 这个布尔：" + hits, hits.length() == 0);
    }

    /**
     * 月亮必须走**预乘 alpha** 那条路。
     *
     * <p>这是整个修法的支点：滤色和普通混合本来没法用混合函数插值（
     * {@code glBlendFunc} 是二选一的），但换成 {@code GL_ONE, ONE_MINUS_SRC_ALPHA}
     * 之后，两种观感**只是输出 alpha 不同**，于是可以连续插值。混合函数若被改回
     * 逐个判断的写法，这条修法就整个失效了。
     */
    @Test
    public void theMoonUsesPremultipliedAlpha() throws Exception {
        String src = read(new File(JAVA_DIR, "GrassGL.java"));

        assertTrue("月亮没有用预乘 alpha 的混合函数（GL_ONE, ONE_MINUS_SRC_ALPHA）",
                src.contains("GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA"));
        assertFalse("月亮渲染器又重新按昼夜切换混合函数了",
                src.contains("GL_ONE_MINUS_SRC_COLOR"));
    }
}
