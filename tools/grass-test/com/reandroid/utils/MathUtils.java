package com.reandroid.utils;

/**
 * JVM 测试替身:让 GrassWindField 能在纯 JVM 下编译运行。
 *
 * <p>真身 {@code app/src/main/java/com/reandroid/utils/MathUtils.java} 里
 * {@code rgbToHsv} 用到了 {@code android.graphics.Color},在 JVM 上编不过;
 * 这里只补测试需要的那一个函数,位于 tools/ 下,不参与 APK 构建。
 */
public final class MathUtils {

    private MathUtils() {
    }

    /** 与真身一致:a + (b - a) * t。 */
    public static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
