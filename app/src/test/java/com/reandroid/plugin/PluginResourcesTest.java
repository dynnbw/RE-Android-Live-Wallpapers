package com.reandroid.plugin;

import org.junit.Test;

import static org.junit.Assert.*;

public class PluginResourcesTest {

    @Test
    public void parseLabelRef_returnsResourceName() {
        assertEquals("fall_wallpaper", PluginResources.parseLabelRef("@string/fall_wallpaper"));
    }

    @Test
    public void parseLabelRef_plainLabel_returnsNull() {
        assertNull(PluginResources.parseLabelRef("Fall"));
    }

    @Test
    public void parseLabelRef_null_returnsNull() {
        assertNull(PluginResources.parseLabelRef(null));
    }

    @Test
    public void parseLabelRef_otherPrefix_returnsNull() {
        assertNull(PluginResources.parseLabelRef("@drawable/icon"));
    }

    // ---- 名字的取舍顺序（见 resolveLabel）----

    /**
     * **插件自己的语言包优先于 res。** 这正是这次改动的目的：名字写在各壁纸自己的
     * assets 里，而不是 res 的 strings.xml。
     */
    @Test
    public void thePluginsOwnBundleWinsOverTheResource() {
        assertEquals("Waveform", PluginResources.labelFrom(null, "Waveform", "声波", "vis2"));
    }

    /** 字面文字仍然优先 —— 个别插件就想在 info.json 里硬写一个名字。 */
    @Test
    public void aPlainLabelWinsOverEverything() {
        assertEquals("Nixie", PluginResources.labelFrom("Nixie", "Waveform", "声波", "vis2"));
    }

    /**
     * 两条兜底都空时落到 res —— **清单里那 8 个壁纸靠这条**
     * （AndroidManifest 的 label 只能来自 res，系统壁纸选择器读的就是它）。
     */
    @Test
    public void theResourceIsTheFallbackForTheManifestWallpapers() {
        assertEquals("Grass", PluginResources.labelFrom(null, null, "Grass", "grass"));
    }

    /** 三者都没有时给插件 id，不要显示空白。 */
    @Test
    public void thePluginIdIsTheLastResort() {
        assertEquals("vis2", PluginResources.labelFrom(null, null, null, "vis2"));
        assertEquals("vis2", PluginResources.labelFrom(null, "", "", "vis2"));
    }
}
