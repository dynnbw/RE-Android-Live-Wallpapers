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

    /**
     * 每帧参数，顺序与 aurora2_procedural_fs.glsl 的 uAurora 各字段一一对应。VK 端接同一份数组即可。
     */
    static float[] packParams(
            float timeSec,
            float width,
            float height,
            float envelope,
            int palette,
            float resolutionScale) {
        float[] p = new float[8];
        p[0] = timeSec;
        p[1] = width;
        p[2] = height;
        p[3] = envelope;
        p[4] = palette;
        p[5] = resolutionScale;
        p[6] = height > 0f ? width / height : 1.0f;
        p[7] = 0f;
        return p;
    }
}
