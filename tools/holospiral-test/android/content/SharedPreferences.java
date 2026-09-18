package android.content;

/**
 * 纯 JVM 测试用的替身：只为让被测的 Scene 能编过。
 *
 * 真身是 Android 类，不在 JVM classpath 上；测试不调用 setPluginPrefs
 * （那会走 SharedPreferences），所以只需要声明 Scene 用到的那几个方法。
 */
public interface SharedPreferences {
    String getString(String key, String defValue);
    int getInt(String key, int defValue);
    boolean getBoolean(String key, boolean defValue);
}
