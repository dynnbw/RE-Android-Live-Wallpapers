package com.reandroid.plugin;

/**
 * 设置页里那块预览的最小契约。
 *
 * <p>实现方分别是 {@code GLESPreviewView}（OpenGL ES 版）和 {@code VKSurfaceView}
 * （Vulkan 版）—— 两者本来是不同的类、不同的基类，设置页只关心这两件事：
 *
 * <ul>
 *   <li>{@link #stopRenderer()}：页面离开后台时让渲染停下来
 *   <li>{@link #getScene()}：设置变了要把新的 prefs 推给场景。这里刻意返回 {@code Object} ——
 *       调用方是按方法名反射调 {@code setPluginPrefs(SharedPreferences)} 的（GL 与 VK
 *       用的是同一个 Scene 类），不需要知道具体类型。
 * </ul>
 */
public interface WallpaperPreview {

    /** 停掉渲染线程（页面不可见时调用）。 */
    void stopRenderer();

    /** 预览正在使用的场景对象；没有就返回 null。 */
    Object getScene();
}
