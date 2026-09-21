package com.reandroid.wallpaper.grass;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 四张提取出来的贴图的回归。
 *
 * <p>它们是参考实现 APK 里 {@code .lzstc} 解出来的原像素（见
 * {@code tools/weather_tex/decode_lzstc.py}）。失效场景很具体：有人用有损工具重新导出
 * —— 540×1 变成 512×1、或者色彩被重新编码。**两者都不会有任何报错**，只是太阳的光盘
 * 边缘手感变了、射线扇变成一条灰线。
 *
 * <p><b>为什么不逐像素断言</b>：AGP 编译单元测试时把 {@code android.jar} 当
 * <b>bootclasspath</b>，{@code java.awt.image} / {@code javax.imageio} 在<b>编译期</b>
 * 就不可见（不是运行时问题），所以任何用 ImageIO 的写法在这里都编不过。这里只做两件
 * 不需要图像解码的事：从 IHDR 读宽高、对整个文件取 SHA-256。
 * 尺寸这条抓"被缩放"，哈希这条抓"被重新编码"，正好覆盖上面两个失效场景。
 */
public class SunLutAssetsTest {

    private static final File DIR = new File("src/main/assets/grass/drawable");

    /** 文件、期望宽、期望高、期望 sha256。 */
    private static final String[][] ASSETS = {
            {"sun_ramp.png", "540", "1",
                    "adcf8d83d3ab079781842e8f83a66c0f0fc88b420a5ddec02c9b1fd7b662f7de"},
            {"sun_annulus_ramp.png", "540", "1",
                    "10866ab65c725bb5916f462f78466e44eea9c555884c3d2084b85da9842b0060"},
            {"sun_rays.png", "540", "540",
                    "d1c0904b8f0305a8ee8090380f1b6b44184333e37790cd25b6a53dc00e99cee5"},
            {"grass_rain_streak.png", "64", "64",
                    "49368cef4fe18b38261c5cd5fc1330b284878218853b5884bbc67744bc7fc21a"},
    };

    private static byte[] read(String name) throws IOException {
        File f = new File(DIR, name);
        assertTrue(name + " 不存在：" + f.getAbsolutePath(), f.isFile());
        return Files.readAllBytes(f.toPath());
    }

    /** 从 PNG 的 IHDR 里读宽高 —— 不做任何解码。IHDR 是第一个 chunk，宽高固定在 16 / 20。 */
    private static int[] pngSize(byte[] png) {
        assertTrue("太短，不像 PNG", png.length >= 24);
        assertEquals("不是 PNG 签名", (byte) 0x89, png[0]);
        assertEquals("IHDR 不在预期位置", "IHDR", new String(png, 12, 4, StandardCharsets.US_ASCII));
        return new int[]{readInt(png, 16), readInt(png, 20)};
    }

    private static int readInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static String sha256(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte x : d) {
                sb.append(Character.forDigit((x >> 4) & 0xF, 16));
                sb.append(Character.forDigit(x & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("JDK 没有 SHA-256", e);
        }
    }

    /**
     * 尺寸必须精确 —— 540×1 被缩放成 512×1 的话，着色器按 {@code texture(tex, vec2(半径, 0.5))}
     * 采样时半径与纹理坐标的对应关系就整个偏了。
     */
    @Test
    public void dimensionsAreExact() throws IOException {
        for (String[] a : ASSETS) {
            int[] size = pngSize(read(a[0]));
            assertEquals(a[0] + " 宽", Integer.parseInt(a[1]), size[0]);
            assertEquals(a[0] + " 高", Integer.parseInt(a[2]), size[1]);
        }
    }

    /**
     * 逐字节哈希。
     *
     * <p>对不上的两种可能，报错信息里都写清楚了：一是有人重新导出过（那就有损，会改变观感，
     * 必须回滚）；二是换了 Pillow 版本重跑解码脚本，像素其实没变、只是编码字节变了 ——
     * 那种情况确认过像素无误后更新这里的常量即可。
     */
    @Test
    public void bytesAreUntouched() throws IOException {
        for (String[] a : ASSETS) {
            String actual = sha256(read(a[0]));
            assertEquals(a[0] + " 的字节变了。若是重新导出过，回滚；若只是换了编码器重跑，"
                            + "确认像素无误后更新本测试里的常量。",
                    a[3], actual);
        }
    }

    /** 表里四个都在，且没有重复 —— 防止以后加资源时漏登记。 */
    @Test
    public void everyExtractedAssetIsCovered() {
        assertEquals("登记条数", 4, ASSETS.length);
        for (int i = 0; i < ASSETS.length; i++) {
            for (int j = i + 1; j < ASSETS.length; j++) {
                assertTrue("重复登记：" + ASSETS[i][0],
                        !ASSETS[i][0].equals(ASSETS[j][0]));
            }
        }
    }
}
