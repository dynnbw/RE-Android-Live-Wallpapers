# Keep classes that expose native methods; JNI name-based lookup can break with obfuscation.
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep wallpaper services and activities declared by Android components.
-keep public class * extends android.service.wallpaper.WallpaperService
-keep public class * extends android.app.Activity

# Keep all WallpaperPlugin implementations — plugins are loaded reflectively via
# Class.forName() in ProxyWallpaperService.loadPlugin(), using class names read
# from assets/*/info.json. R8 must preserve both the class identity AND the
# original name, otherwise Class.forName("com.reandroid.wallpaper.*.FooPlugin")
# fails with ClassNotFoundException in release builds.
-keep public class * extends com.reandroid.plugin.WallpaperPlugin { *; }

# Keep preference fragments used through fragment class names in preference XML.
-keep public class * extends androidx.preference.PreferenceFragmentCompat

# Keep GLESScene subclasses — preview scenes are loaded reflectively via
# Class.forName() in PluginSettingsFragment.createPreviewScene(), using class
# names from assets/*/info.json. R8 must preserve the original class name
# (not just members), otherwise the preview renders nothing in release builds.
-keep public class * extends com.reandroid.gles.GLESScene { *; }
