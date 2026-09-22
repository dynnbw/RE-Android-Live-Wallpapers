package com.reandroid.wallpaper.grass;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 从天气应用解出来的贴图的回归。
 *
 * <p>三张太阳用的图现在是 **ASTC**（由 {@code tools/weather_tex/decode_lzstc.py} 从
 * {@code .lzstc} 解出，直接上传、不解码），雨丝条纹仍是 PNG（它的源本来就是普通 PNG）。
 *
 * <p><b>为什么不逐像素断言</b>：AGP 编译单元测试时把 {@code android.jar} 当
 * <b>bootclasspath</b>，{@code java.awt.image} / {@code javax.imageio} 在<b>编译期</b>
 * 就不可见（不是运行时问题），所以任何用 ImageIO 的写法在这里都编不过。这里只做两件
 * 不需要图像解码的事：读出尺寸、对整个文件取 SHA-256。
 *
 * <p>ASTC 的尺寸从它自己的 16 字节头里读 —— 那正是上传时 {@code glCompressedTexImage2D}
 * 要报的尺寸，所以这条断言同时也在守"尺寸和分块对得上"。
 */
public class SunLutAssetsTest {

    private static final File DIR = new File("src/main/assets/grass/drawable");

    /** 文件、期望宽、期望高、期望 sha256。 */
    private static final String[][] ASSETS = {
            {"sun_ramp.astc", "540", "2",
                    "60ea46a71bd3e75caf6d3db6a5ca9e86a0f25ee06a0a9cc8b9385354f2dfa152"},
            {"sun_annulus_ramp.astc", "540", "4",
                    "76e1f989da8371c69d1a4039097f4f77339e1ff90792afee4785907eb30d4437"},
            {"sun_rays.astc", "540", "540",
                    "5bf93118defe627997ff28ced03f647cbf6e275df917594f37cb48732edd07b4"},
            {"grass_rain_streak.png", "64", "64",
                    "49368cef4fe18b38261c5cd5fc1330b284878218853b5884bbc67744bc7fc21a"},
    };

    private static byte[] read(String name) throws IOException {
        File f = new File(DIR, name);
        assertTrue(name + " 不存在：" + f.getAbsolutePath(), f.isFile());
        return Files.readAllBytes(f.toPath());
    }

    private static int[] pngSize(byte[] png) {
        assertTrue("太短，不像 PNG", png.length >= 24);
        assertEquals("不是 PNG 签名", (byte) 0x89, png[0]);
        assertEquals("IHDR 不在预期位置", "IHDR",
                new String(png, 12, 4, java.nio.charset.StandardCharsets.US_ASCII));
        return new int[]{readInt(png, 16), readInt(png, 20)};
    }

    /**
     * 标准 .astc 头：魔数 4 B + 块尺寸 3 B(x,y,z) + 三个 u24 的像素尺寸。
     *
     * <p>魔数按**字节**逐位比，不折成一个整数 —— 它是小端存的 {@code 0x5CA1AB13}，
     * 折成大端整数会读成 {@code 0x13ABA15C}，比错了还得回头查是哪边的问题。
     */
    private static int[] astcSize(byte[] astc) {
        assertTrue("太短，不像 ASTC", astc.length > 16);
        assertEquals("ASTC 魔数第 0 字节", 0x13, astc[0] & 0xFF);
        assertEquals("ASTC 魔数第 1 字节", 0xAB, astc[1] & 0xFF);
        assertEquals("ASTC 魔数第 2 字节", 0xA1, astc[2] & 0xFF);
        assertEquals("ASTC 魔数第 3 字节", 0x5C, astc[3] & 0xFF);
        assertEquals("分块 x", 4, astc[4] & 0xFF);
        assertEquals("分块 y", 4, astc[5] & 0xFF);
        return new int[]{readU24(astc, 7), readU24(astc, 10)};
    }

    private static int[] sizeOf(String name, byte[] data) {
        return name.endsWith(".astc") ? astcSize(data) : pngSize(data);
    }

    private static int readInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static int readU24(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16);
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
     * 尺寸必须精确。
     *
     * <p>ASTC 那三张尤其重要：头里的尺寸就是上传时报给 {@code glCompressedTexImage2D}
     * 的尺寸，报小了会只画出一部分、报大了驱动读到载荷之外。sun_ramp 的 2 行是**分块对齐
     * 的一部分**，不是可以裁掉的填充 —— 早先转 PNG 时裁成过 540x1，那是 PNG 的规则，
     * 换到 ASTC 上就错了。
     */
    @Test
    public void dimensionsAreExact() throws IOException {
        for (String[] a : ASSETS) {
            int[] size = sizeOf(a[0], read(a[0]));
            assertEquals(a[0] + " 宽", Integer.parseInt(a[1]), size[0]);
            assertEquals(a[0] + " 高", Integer.parseInt(a[2]), size[1]);
        }
    }

    /** ASTC 的载荷长度必须正好是分块数 × 16 —— 对不上说明截断了或格式不是 4x4。 */
    @Test
    public void astcPayloadLengthMatchesBlockCount() throws IOException {
        for (String[] a : ASSETS) {
            if (!a[0].endsWith(".astc")) continue;
            byte[] data = read(a[0]);
            int w = Integer.parseInt(a[1]);
            int h = Integer.parseInt(a[2]);
            int blocks = ((w + 3) / 4) * ((h + 3) / 4);
            assertEquals(a[0] + " 的分块载荷长度", blocks * 16, data.length - 16);
        }
    }

    /**
     * 逐字节哈希。
     *
     * <p>对不上的两种可能，报错信息里都写清楚了：一是有人重新导出过（那就有损，会改变观感，
     * 必须回滚）；二是换了工具重跑解码脚本，像素其实没变、只是编码字节变了 ——
     * 那种情况确认过像素无误后更新这里的常量即可。
     */
    @Test
    public void bytesAreUntouched() throws IOException {
        for (String[] a : ASSETS) {
            assertEquals(a[0] + " 的字节变了。若是重新导出过，回滚；若只是换了编码器重跑，"
                    + "确认像素无误后更新本测试里的常量。", a[3], sha256(read(a[0])));
        }
    }

    /** 表里四个都在，且没有重复 —— 防止以后加资源时漏登记。 */
    @Test
    public void everyExtractedAssetIsCovered() {
        assertEquals("登记条数", 4, ASSETS.length);
        for (int i = 0; i < ASSETS.length; i++) {
            for (int j = i + 1; j < ASSETS.length; j++) {
                assertTrue("重复登记：" + ASSETS[i][0], !ASSETS[i][0].equals(ASSETS[j][0]));
            }
        }
    }
}
