package android.os;

/**
 * JVM 测试替身:让 FireworksScene 能在纯 JVM 下编译运行。
 * 位于 tools/ 下,不参与 APK 构建。
 */
public final class SystemClock {

    private static long sNow = 1000000L;

    public static long uptimeMillis() {
        return sNow;
    }

    public static void set(long ms) {
        sNow = ms;
    }
}
