package com.reandroid.plugin;

import android.content.SharedPreferences;

/**
 * 由宿主注入，让 Scene / GL 能拿到**其他插件**的设置。
 *
 * <p>只有合成类壁纸需要它：vis5 把 vis2 与 vis3 的画面合在一起显示，得按它们的设置
 * 才能模仿其观感；fireworks 的草地夜景背景要画得像 grass，同理。
 * 而 {@link WallpaperPluginHost#getSharedPreferences()} 只给得到**本插件自己**那一份，
 * 所以过去这些壁纸是自己去 `getSharedPreferences("plugin_xxx")` 直读的 ——
 * 那正是准则「设置由引擎注入，Scene 不要自己读 prefs」要避免的。
 *
 * <p>实现方保证 {@code forPlugin(id)} 与注入给该插件的是**同一个实例**
 * （SharedPreferences 按名字缓存），因此对方设置变更时的可见性与直读一致。
 */
public interface PluginPrefsProvider {

    /** 指定插件自己的设置。 */
    SharedPreferences forPlugin(String pluginId);
}
