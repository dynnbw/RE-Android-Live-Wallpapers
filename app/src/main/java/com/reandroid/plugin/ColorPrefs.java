package com.reandroid.plugin;

import android.content.SharedPreferences;

/**
 * 颜色设置项的读写工具:取色器写入 int(ARGB),同时兼容早期以字符串
 * (#RRGGBB / #AARRGGBB)保存的旧值,避免升级后读取出错。
 */
public final class ColorPrefs {

    private ColorPrefs() {
    }

    /** 读取颜色:优先 int,其次解析旧的十六进制字符串,失败用 fallback。 */
    public static int getColor(SharedPreferences prefs, String key, int fallback) {
        if (prefs == null || key == null) {
            return fallback;
        }
        try {
            return prefs.getInt(key, fallback);
        } catch (ClassCastException e) {
            Object raw;
            try {
                raw = prefs.getAll().get(key);
            } catch (Throwable t) {
                return fallback;
            }
            if (raw instanceof String) {
                return parseHex((String) raw, fallback);
            }
            return fallback;
        }
    }

    /** 解析 "#RRGGBB" / "#AARRGGBB" / "RRGGBB"。 */
    public static int parseHex(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        String hex = value.trim();
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }
        try {
            if (hex.length() == 6) {
                return 0xFF000000 | (int) Long.parseLong(hex, 16);
            }
            if (hex.length() == 8) {
                return (int) Long.parseLong(hex, 16);
            }
        } catch (NumberFormatException ignored) {
        }
        return fallback;
    }

    /** 显示用文本,如 "#30587C"。 */
    public static String format(int color) {
        return String.format("#%06X", color & 0xFFFFFF);
    }
}
