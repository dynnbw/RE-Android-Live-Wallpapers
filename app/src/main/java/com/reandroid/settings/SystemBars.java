package com.reandroid.settings;

import android.content.res.Configuration;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

/**
 * 状态栏图标明暗跟随当前主题。
 *
 * <p>主题是 {@code Theme.AppCompat.DayNight}，但状态栏图标颜色不会自己跟着变：
 * 浅色主题下状态栏底色是浅的，白图标等于看不见。这里按当前夜间模式设置
 * "浅色状态栏"（即深色图标）。
 */
final class SystemBars {

    private SystemBars() {}

    static void applyLightStatusBar(AppCompatActivity activity) {
        int uiMode = activity.getResources().getConfiguration().uiMode;
        boolean light = (uiMode & Configuration.UI_MODE_NIGHT_MASK)
                != Configuration.UI_MODE_NIGHT_YES;
        WindowCompat.getInsetsController(activity.getWindow(),
                activity.getWindow().getDecorView()).setAppearanceLightStatusBars(light);
    }
}
