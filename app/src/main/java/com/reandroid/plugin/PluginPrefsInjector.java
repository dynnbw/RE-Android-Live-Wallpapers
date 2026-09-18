package com.reandroid.plugin;

import android.content.SharedPreferences;
import android.util.Log;

import com.reandroid.gles.GLESScene;

/**
 * 把「本插件的设置」和「读取其他插件设置的能力」注入进 Scene / GL。
 *
 * <p>三处宿主都要做同一件事 —— 桌面引擎、设置页预览、设置页 Activity 的预览 ——
 * 反射的契约集中在这里，免得三份各写各的、日后加参数时漏掉其一。
 *
 * <p>两个方法都是可选的：实现类没有对应方法就不注入。没有 {@code setPluginPrefs}
 * 的类会退回到「不读设置」的默认行为（少数没有可配置项的壁纸就是如此）。
 */
public final class PluginPrefsInjector {

    private static final String TAG = "PluginPrefsInjector";

    private PluginPrefsInjector() {}

    public static void inject(GLESScene scene, SharedPreferences prefs, PluginPrefsProvider provider) {
        injectPrefs(scene, prefs);
        injectProvider(scene, provider);
    }

    public static void injectPrefs(GLESScene scene, SharedPreferences prefs) {
        if (scene == null) return;
        try {
            java.lang.reflect.Method m = scene.getClass()
                    .getMethod("setPluginPrefs", SharedPreferences.class);
            m.invoke(scene, prefs);
        } catch (NoSuchMethodException e) {
            Log.i(TAG, scene.getClass().getSimpleName() + " has no setPluginPrefs — using default prefs source");
        } catch (Exception e) {
            Log.w(TAG, "Failed to inject prefs into " + scene.getClass().getSimpleName(), e);
        }
    }

    public static void injectProvider(GLESScene scene, PluginPrefsProvider provider) {
        if (scene == null || provider == null) return;
        try {
            java.lang.reflect.Method m = scene.getClass()
                    .getMethod("setPluginPrefsProvider", PluginPrefsProvider.class);
            m.invoke(scene, provider);
        } catch (NoSuchMethodException e) {
            // 绝大多数壁纸不需要读别的插件，没有这个方法很正常，不必记日志
        } catch (Exception e) {
            Log.w(TAG, "Failed to inject prefs provider into " + scene.getClass().getSimpleName(), e);
        }
    }
}
