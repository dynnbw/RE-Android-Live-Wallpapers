package com.reandroid.wallpaper.musicvis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「跟随系统音量」这个开关的三段必须对得上：映射函数 → 六份 layout.json → 全部语言包。
 *
 * <p>为什么会有这个开关：幅度不跟随系统音量**不是**我们的 bug，而是 {@code Visualizer}
 * 默认缩放模式（{@code SCALING_MODE_NORMALIZED}）的设计行为 —— 它按捕获到的内容自身做放大，
 * 因此与音量无关；AOSP 文档写明这是默认模式，且 "suitable for music visualization"。
 *
 * <p>而换成平台的 {@code SCALING_MODE_AS_PLAYED} 也不行，实测是**突然变小**（50% 档以下
 * 几乎看不见、跨过中段立马变大）：它乘的是物理响度，媒体音量曲线在低档跨度极大，
 * 渲染端又要把幅度平方或取均值，两者一乘低档就落到万分之一。所以改成**自己按档位比例乘**。
 *
 * <p>开关名是**按字符串查找**的：Java 侧的 {@code PREF_FOLLOW_VOLUME} 与
 * {@code layout.json} 里的 {@code key} 只要拼错一个字母，症状就是"开关拨了没反应"，
 * 没有任何错误信息。所以这里把两边对一遍（同 {@code FallAssetsTest} 的立意）。
 */
public class MusicVisFollowVolumeTest {

    private static final String[] VIS = {"vis1", "vis2", "vis3", "vis4", "vis5", "vis6"};

    private static final String TITLE = AudioCapture.PREF_FOLLOW_VOLUME;
    private static final String SUMMARY = AudioCapture.PREF_FOLLOW_VOLUME + "_summary";

    // ---- 档位 → 增益 ----

    /** 按**档位比例**：50% 档 → 0.5。线性，不是 dB —— dB 就是"突然变小"的那个形状。 */
    @Test
    public void theGainFollowsTheVolumeStepRatio() {
        assertEquals(1.0f, AudioCapture.gainForVolume(100, 100), 1e-6f);
        assertEquals(0.5f, AudioCapture.gainForVolume(50, 100), 1e-6f);
        assertEquals(0.25f, AudioCapture.gainForVolume(25, 100), 1e-6f);
        assertEquals(0.0f, AudioCapture.gainForVolume(0, 100), 1e-6f);
    }

    @Test
    public void theGainIsClampedAndSurvivesABadMaxIndex() {
        assertEquals(0f, AudioCapture.gainForVolume(-5, 100), 1e-6f);
        assertEquals(1f, AudioCapture.gainForVolume(150, 100), 1e-6f);
        assertEquals("拿不到档位上限时不该缩放", 1f, AudioCapture.gainForVolume(3, 0), 1e-6f);
    }

    // ---- 每张 vis 都声明了它，且默认关 ----

    @Test
    public void everyVisDeclaresTheSwitchAndDefaultsToOff() throws IOException {
        for (String vis : VIS) {
            String layout = read(new File(assets(vis), "layout.json"));
            assertTrue(vis + " 的 layout.json 里没有 " + TITLE, layout.contains("\"" + TITLE + "\""));
            assertTrue(
                    vis + " 的 layout.json 里没有 " + SUMMARY, layout.contains("\"" + SUMMARY + "\""));
            // 默认值必须是 false —— 关 = 加此设置之前的行为
            assertTrue(
                    vis + " 的开关默认值不是 false",
                    Pattern.compile("\"key\"\\s*:\\s*\""
                                    + Pattern.quote(TITLE)
                                    + "\"[\\s\\S]{0,400}?\"default\"\\s*:\\s*\"false\"")
                            .matcher(layout)
                            .find());
        }
    }

    // ---- 13 份语言包都要有 title 与 summary ----

    @Test
    public void everyLanguageHasBothLabels() {
        for (String vis : VIS) {
            File[] files = new File(assets(vis), "language")
                    .listFiles((dir, name) -> name.endsWith(".json"));
            assertTrue(vis + " 没有语言包", files != null && files.length > 0);
            for (File file : files) {
                Set<String> keys;
                try {
                    keys = jsonKeys(read(file));
                } catch (IOException e) {
                    throw new AssertionError("读不出 " + file, e);
                }
                assertTrue(vis + "/" + file.getName() + " 缺 " + TITLE, keys.contains(TITLE));
                assertTrue(vis + "/" + file.getName() + " 缺 " + SUMMARY, keys.contains(SUMMARY));
            }
        }
    }

    // ---- 不许绕过统一工厂 ----

    /**
     * 谁都不许在工厂之外 {@code new AudioCapture} —— 绕开 {@code createAudioCapture} 的采集
     * 接不上增益来源，症状正是"开关拨了没反应"。
     *
     * <p>这条是照着真实踩过的坑写的：{@code MusicVisWaveGL} 换 FFT 尺寸那条路直接 new，
     * 而 vis2/vis3 启动时因为 {@code musicvis_fft_size} 与默认不同一定走那条路 ——
     * 于是那两张的设置整片失效，且没有任何报错。
     */
    @Test
    public void nobodyConstructsACaptureOutsideTheFactory() throws IOException {
        File dir = new File("src/main/java/com/reandroid/wallpaper/musicvis");
        // 工厂自己；ManyScene 是 vis5 那份独立实现，它自己接来源（见 volumeGain()）
        Set<String> allowed = new LinkedHashSet<>();
        allowed.add("AudioCapture.java");
        allowed.add("AudioVisBase.java");
        allowed.add("ManyScene.java");

        List<String> offenders = new ArrayList<>();
        for (File file : javaFilesUnder(dir)) {
            if (allowed.contains(file.getName())) {
                continue;
            }
            if (read(file).contains("new AudioCapture(")) {
                offenders.add(file.getName());
            }
        }
        assertTrue("这些文件绕过了 createAudioCapture：" + offenders, offenders.isEmpty());
    }

    // ---- helpers ----

    private static List<File> javaFilesUnder(File dir) {
        List<File> out = new ArrayList<>();
        File[] children = dir.listFiles();
        if (children == null) {
            return out;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                out.addAll(javaFilesUnder(child));
            } else if (child.getName().endsWith(".java")) {
                out.add(child);
            }
        }
        return out;
    }

    private static File assets(String vis) {
        return new File("src/main/assets/" + vis);
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /** 取出 JSON 里作为键出现过的名字。只为核对键名存在，够用即可。 */
    private static Set<String> jsonKeys(String json) {
        Set<String> keys = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("\"([^\"]+)\"\\s*:").matcher(json);
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        return keys;
    }
}
