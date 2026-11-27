package com.reandroid.plugin;

import androidx.preference.Preference;

import java.util.WeakHashMap;

/**
 * 每行"静止时"的 alpha。
 *
 * <p>分组展开动画会给列表里所有行重设 alpha（新行淡入、旧行复位）。置灰行由绑定器
 * 算出的 0.4 会被这次复位覆盖成 1，而且行不重新绑定就再也回不来 —— 表现就是
 * "展开别的组之后，原本置灰的开关不灰了"。这里记住每行最后一次由绑定器算出的值，
 * 动画按它恢复。
 */
final class PreferenceAlpha {

    private static final WeakHashMap<Preference, Float> ALPHAS = new WeakHashMap<>();

    private PreferenceAlpha() {}

    static synchronized void set(Preference pref, float alpha) {
        ALPHAS.put(pref, alpha);
    }

    /** 没登记过的行按不透明处理（普通按钮、分组标题等）。 */
    static synchronized float of(Preference pref) {
        Float alpha = ALPHAS.get(pref);
        return alpha != null ? alpha : 1f;
    }
}
