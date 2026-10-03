package com.reandroid.settings;

/**
 * 帧率决策：把存储的偏好、屏幕刷新率、省电状态算成「实际跑多少帧」和「这一帧还要不要补睡」。
 *
 * <p><b>纯 Java，不碰 android.*</b> —— 单测直接在 JVM 上跑 {@link #decide}，由
 * {@link WallpaperSettings#resolveFrameRateDecision(int)} 负责去问系统和读偏好。
 * 与 {@code PluginResources.labelFrom} 是同一条路子。
 *
 * <p><b>为什么要算 {@code vsyncPaced}。</b> 渲染循环里 {@code eglSwapBuffers} 本来就在等
 * 垂直同步，实测的"帧耗时"里已经包含了这段等待，循环却还要再睡
 * {@code max(1, 帧时 − 实测耗时)}。那个 1 毫秒的下限是要命的：120Hz 屏上 vsync 周期
 * 8.33ms、目标 120 的帧时是 8.0ms，于是 {@code max(1, −0.33) = 1ms} —— 一轮变成 9.33ms，
 * 越过 8.33ms 那条线，交换只好等到 16.67ms，<b>实际只剩 60 帧</b>。同理"180 FPS"在
 * 120Hz 屏上还不如选 120。
 *
 * <p>所以目标不低于面板能给的这一档，sleep 的下限取 <b>0</b> 而不是 1ms，见
 * {@link #pacingSleepMs}。
 *
 * <p>但下限是 0 <b>不等于不睡</b>。surface 没被合成的时候 {@code eglSwapBuffers} 根本不阻塞，
 * 实测耗时接近 0 —— 这时若干脆不睡，循环就没有任何上限了（实测 120 帧跑不到 1ms，还伴生
 * 每秒数次的 GC）。"vsync 已经在配速"只在画面真的被呈现时才成立。
 */
public final class FrameRatePolicy {

    /** 偏好里代表"跟随屏幕刷新率"的取值，与旧的十进制帧率字符串共存。 */
    public static final String FOLLOW_SCREEN = "screen";

    /** 屏幕刷新率读不到时的兜底值（与参考实现一致）。 */
    public static final int REFRESH_FALLBACK_FPS = 60;

    /** 省电联动生效时的上限帧率。注意是<b>上限</b>：用户自己选了 24 就还是 24。 */
    public static final int POWER_SAVE_FPS = 24;

    /** 固定档位的兜底与不可解析时的取值。 */
    public static final int DEFAULT_FPS = 60;

    private FrameRatePolicy() {}

    /** 一次决策的结果。 */
    public static final class Decision {
        /** 目标帧率。 */
        public final int fps;

        /**
         * true 表示目标不低于面板能给的，呈现路径会把节奏压在面板帧率上，循环该把 sleep 的
         * 下限从 1ms 放宽到 0（见 {@link #pacingSleepMs}）—— <b>不是</b>不睡。
         *
         * <p>false 表示目标低于面板能给的，靠 sleep 拉到那个帧率。
         */
        public final boolean vsyncPaced;

        Decision(int fps, boolean vsyncPaced) {
            this.fps = fps;
            this.vsyncPaced = vsyncPaced;
        }
    }

    /**
     * 这一帧该睡多久。所有渲染循环都走这里，公式只留一份。
     *
     * <p>目标帧时还没到就补到目标；已经过了就不补。{@code vsyncPaced} <b>只改下限</b>
     * （0 而不是 1ms，多睡的那 1ms 会顶过 vsync 线、把 120 砍成 60），并不免除睡眠 ——
     * surface 没被合成时 {@code eglSwapBuffers} 不阻塞，那时不睡就没有上限了。
     *
     * @param targetFrameMs 目标帧时（毫秒）
     * @param vsyncPaced    呈现路径是否已经在配速
     * @param frameCostMs   这一帧「绘制 + 呈现」的实测耗时
     * @return 需要 sleep 的毫秒数，可能为 0
     */
    public static long pacingSleepMs(long targetFrameMs, boolean vsyncPaced, long frameCostMs) {
        long remaining = targetFrameMs - frameCostMs;
        return vsyncPaced ? Math.max(0L, remaining) : Math.max(1L, remaining);
    }

    /**
     * @param prefValue        {@code global_frame_rate} 的原始字符串；null 或无法解析时落到
     *                         {@link #DEFAULT_FPS}
     * @param displayRefreshHz 面板刷新率；&lt;= 0 或 NaN 表示读不到
     * @param powerSaveMode    手机当前是否处于省电模式
     * @param powerSaveLinked  省电联动开关是否打开
     */
    public static Decision decide(
            String prefValue,
            float displayRefreshHz,
            boolean powerSaveMode,
            boolean powerSaveLinked) {
        boolean follow = FOLLOW_SCREEN.equals(prefValue);
        boolean cap = powerSaveMode && powerSaveLinked;
        boolean refreshKnown = isRefreshKnown(displayRefreshHz);
        int refresh = normalizeRefresh(displayRefreshHz);

        int fps;
        if (follow) {
            fps = cap ? Math.min(refresh, POWER_SAVE_FPS) : refresh;
        } else {
            fps = parseFps(prefValue);
            if (cap) {
                fps = Math.min(fps, POWER_SAVE_FPS);
            }
        }

        // 刷新率未知时**不能**假设成 60：面板可能是 144Hz，那时把用户选的 60 交给 vsync
        // 配速会跑成 144。只有"跟随屏幕"在未知时仍然交给 vsync（它不可能超过面板，安全）。
        boolean vsyncPaced = refreshKnown ? (fps >= refresh) : follow;
        return new Decision(fps, vsyncPaced);
    }

    /** 解析固定帧率；非法值落到默认，且不允许 &lt;1（与原来 {@code max(1, …)} 的口径一致）。 */
    static int parseFps(String value) {
        try {
            int fps = Integer.parseInt(value);
            return fps >= 1 ? fps : DEFAULT_FPS;
        } catch (Exception e) {
            return DEFAULT_FPS;
        }
    }

    /** 刷新率是否可用。 */
    static boolean isRefreshKnown(float hz) {
        return !Float.isNaN(hz) && hz > 0f;
    }

    /** 刷新率就近取整；读不到或非法时用兜底值。 */
    static int normalizeRefresh(float hz) {
        if (!isRefreshKnown(hz)) {
            return REFRESH_FALLBACK_FPS;
        }
        int refresh = Math.round(hz);
        return refresh >= 1 ? refresh : REFRESH_FALLBACK_FPS;
    }
}
