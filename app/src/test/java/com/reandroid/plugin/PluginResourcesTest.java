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
}
