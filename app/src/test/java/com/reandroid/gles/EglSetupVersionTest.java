package com.reandroid.gles;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * {@code GL_VERSION} 字符串的解析。
 *
 * <p>这条逻辑不重，但它错了的后果很隐蔽：版本判低了会误报"设备不够格"，
 * 判高了则在只有 3.0 的机器上不报警 —— 然后 compute 着色器编不出来、
 * 雨静默消失，看着像"这个功能没做"。
 *
 * <p>驱动的字符串各家不一样，所以这里把已知的几种形态都钉住。
 */
public class EglSetupVersionTest {

    @Test
    public void parsesAdrenoStyleVersion() {
        assertArrayEquals(new int[]{3, 2},
                EglSetup.parseGlVersion("OpenGL ES 3.2 V@0530.0"));
    }

    /** ANGLE 后面跟着很长一串，只取紧邻的 X.Y。 */
    @Test
    public void parsesAngleStyleVersion() {
        assertArrayEquals(new int[]{3, 2},
                EglSetup.parseGlVersion(
                        "OpenGL ES 3.2 (ANGLE 2.1.0 git hash: abcdef 1234567890)"));
    }

    @Test
    public void parsesOlderVersions() {
        assertArrayEquals(new int[]{3, 0}, EglSetup.parseGlVersion("OpenGL ES 3.0 V@0123.0"));
        assertArrayEquals(new int[]{3, 1}, EglSetup.parseGlVersion("OpenGL ES 3.1"));
        assertArrayEquals(new int[]{2, 0}, EglSetup.parseGlVersion("OpenGL ES 2.0"));
    }

    /** 只有主版本时小版本按 0 算 —— 不能因为缺小数点就把整条判失败。 */
    @Test
    public void majorOnlyMeansMinorZero() {
        assertArrayEquals(new int[]{3, 0}, EglSetup.parseGlVersion("OpenGL ES 3"));
    }

    /** 版本号不合规时返回 null，而不是猜一个。 */
    @Test
    public void returnsNullForUnparseableStrings() {
        assertNull(EglSetup.parseGlVersion(null));
        assertNull(EglSetup.parseGlVersion(""));
        assertNull(EglSetup.parseGlVersion("V@0530.0"));
        assertNull(EglSetup.parseGlVersion("OpenGL ES "));
        assertNull(EglSetup.parseGlVersion("OpenGL ES x.y"));
    }

    /** 要求的是 3.2；顺带把边界钉住，免得以后调 REQUIRED 时忘了这里。 */
    @Test
    public void requiredVersionIsThreeTwo() {
        assertEquals(3, EglSetup.REQUIRED_MAJOR);
        assertEquals(2, EglSetup.REQUIRED_MINOR);
    }
}
