# Keep classes that expose native methods; JNI name-based lookup can break with obfuscation.
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep wallpaper services and activities declared by Android components.
-keep public class * extends android.service.wallpaper.WallpaperService
-keep public class * extends android.app.Activity

# Keep preference fragments used through fragment class names in preference XML.
-keep public class * extends androidx.preference.PreferenceFragmentCompat

# Keep setPluginPrefs(SharedPreferences) — called via reflection from
# BasePluginEngine.tryInjectPrefs() and PluginSettingsFragment.createPreviewScene().
-keepclassmembers class * extends com.reandroid.gles.GLESScene {
    public void setPluginPrefs(android.content.SharedPreferences);
}
