package com.reandroid.wallpaper.aurora2;

/** 程序化极光的纯逻辑：色相档与强度包络。不依赖 Android。 */
final class Aurora2Procedural {

    static final String[] PALETTE_KEYS = {"physical", "teal", "violet", "warm"};

    /** 亚暴式增亮的周期：每 3 分钟一次，持续约 40 秒。 */
    private static final long BURST_PERIOD_MS = 180_000L;

    private static final long BURST_LEN_MS = 40_000L;

    private Aurora2Procedural() {}

    static int paletteIndex(String key) {
        if (key != null) {
            for (int i = 0; i < PALETTE_KEYS.length; i++) {
                if (PALETTE_KEYS[i].equals(key)) {
                    return i;
                }
            }
        }
        return 0;
    }

    /** 0 = 平静，1 = 最亮。余弦包络，两端平滑。 */
    static float envelope(long nowMs) {
        long phase = Math.floorMod(nowMs, BURST_PERIOD_MS);
        if (phase >= BURST_LEN_MS) {
            return 0.0f;
        }
        float x = phase / (float) BURST_LEN_MS;
        return (float) (0.5 - 0.5 * Math.cos(x * Math.PI * 2.0));
    }
}
