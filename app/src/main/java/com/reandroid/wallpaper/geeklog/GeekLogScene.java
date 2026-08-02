package com.reandroid.wallpaper.geeklog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * GeekLog 场景逻辑层（纯 Java，无 GL 调用）。
 * 负责日志环形缓冲、ASCII 转义、行截断、事件去抖、级别与颜色状态。
 * log/snapshot 跨线程（主线程事件 + 渲染线程读取），用 synchronized 保护。
 */
final class GeekLogScene {

    static final int LEVEL_INFO = 0;
    static final int LEVEL_WARN = 1;
    static final int LEVEL_ERROR = 2;

    /** 每行前缀固定长度：[HH:mm:ss.SSS] 0001>  = 15 + 6 = 21 */
    static final int PREFIX_LEN = 21;
    static final int MAX_LINE_CHARS = 120;

    static final class Entry {
        final int level;
        final String text;
        Entry(int level, String text) {
            this.level = level;
            this.text = text;
        }
    }

    private final ArrayList<Entry> mEntries = new ArrayList<>();
    private int mNextId = 1;

    /** 渲染状态（GL 线程读取，volatile 保证可见性） */
    volatile int mColorIndex;          // 0=green 1=amber 2=cyan 3=white
    volatile boolean mHighlightErrors; // WARN 琥珀 / ERROR 红

    private volatile int mMaxLines = 80;

    // 去抖时间戳
    private long mLastOffsetLogMs;
    private long mLastPrefsLogMs;
    private long mLastFpsWarnMs;
    private long mLastOverflowMs;

    void setColorIndex(int index) { mColorIndex = index; }
    void setHighlightErrors(boolean on) { mHighlightErrors = on; }
    void setMaxLines(int maxLines) { mMaxLines = Math.max(10, maxLines); }

    /** 追加一条日志（线程安全）。 */
    synchronized void log(int level, String msg) {
        if (msg == null) return;
        int id = mNextId++;
        String line = format(id, level, msg);
        mEntries.add(new Entry(level, line));
        if (mEntries.size() > mMaxLines) {
            overflowLog();
        }
    }

    private void overflowLog() {
        long now = System.currentTimeMillis();
        if (now - mLastOverflowMs < 30000) return;
        mLastOverflowMs = now;
        String line = format(mNextId++, LEVEL_WARN, "log buffer: oldest line dropped");
        mEntries.add(new Entry(LEVEL_WARN, line));
        while (mEntries.size() > mMaxLines) mEntries.remove(0);
    }

    private String format(int id, int level, String msg) {
        return "[" + timestamp(System.currentTimeMillis()) + "] "
                + String.format(Locale.US, "%04d", id) + "> " + sanitize(msg);
    }

    /** 时间戳 HH:mm:ss.SSS（手动格式化，避免 SimpleDateFormat 线程问题） */
    private static String timestamp(long nowMs) {
        long ms = nowMs % 1000;
        long totalSec = nowMs / 1000;
        long sec = totalSec % 60;
        long totalMin = totalSec / 60;
        long min = totalMin % 60;
        long hour = totalMin / 60 % 24;
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", hour, min, sec, ms);
    }

    /** 仅保留 ASCII 可打印字符（0x20-0x7E），其余转义为 '?'；超长截断。 */
    private static String sanitize(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < MAX_LINE_CHARS; i++) {
            char c = s.charAt(i);
            sb.append((c >= 0x20 && c <= 0x7E) ? c : '?');
        }
        return sb.toString();
    }

    /** 渲染线程读取快照（线程安全）。 */
    synchronized List<Entry> snapshot() {
        return new ArrayList<>(mEntries);
    }

    // ---- 事件 API（由 Engine / GL 层调用）----

    void onTap(int x, int y) {
        log(LEVEL_INFO, "user input: tap (" + x + ", " + y + ")");
    }

    void onVisibility(boolean visible) {
        log(LEVEL_INFO, "wallpaper: " + (visible ? "visible" : "hidden"));
    }

    /** 桌面滑动高频触发，去抖 500ms。 */
    void onOffsetsChanged() {
        long now = System.currentTimeMillis();
        if (now - mLastOffsetLogMs < 500) return;
        mLastOffsetLogMs = now;
        log(LEVEL_INFO, "wallpaper: scroll offset changed");
    }

    /** 设置变更（拖动 seekbar 会连续触发），去抖 500ms。 */
    void onPrefsChanged(String key) {
        long now = System.currentTimeMillis();
        if (now - mLastPrefsLogMs < 500) return;
        mLastPrefsLogMs = now;
        log(LEVEL_INFO, "settings: " + (key == null ? "unknown" : key) + " changed");
    }

    /** 帧率监测（GL 层 10s 统计回调），去抖 10s。 */
    void onFpsDrop(int fps) {
        long now = System.currentTimeMillis();
        if (now - mLastFpsWarnMs < 10000) return;
        mLastFpsWarnMs = now;
        log(LEVEL_WARN, "performance: fps dropped to " + fps);
    }
}
