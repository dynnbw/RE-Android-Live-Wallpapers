package com.reandroid.settings;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.reandroid.plugin.ProxyWallpaperService;
import com.reandroid.wallpaper.R;

/**
 * Settings activity launched from the system wallpaper preview.
 * Reads the active plugin ID and loads its dynamic settings fragment.
 */
public class PluginSettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        String pluginId = ProxyWallpaperService.getActivePlugin(this);
        if (pluginId == null) {
            finish();
            return;
        }

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        if (savedInstanceState == null && pluginId != null) {
            // Load the legacy Fragment from info.json
            androidx.fragment.app.Fragment fragment = loadSettingsFragment(pluginId);
            if (fragment != null) {
                getSupportFragmentManager()
                        .beginTransaction()
                        .replace(R.id.settings_container, fragment)
                        .commit();
            }
        }
    }

    private androidx.fragment.app.Fragment loadSettingsFragment(String pluginId) {
        try {
            java.io.InputStream is = getAssets().open(pluginId + "/info.json");
            byte[] buf = new byte[is.available()];
            is.read(buf);
            is.close();
            org.json.JSONObject json = new org.json.JSONObject(new String(buf, "UTF-8"));
            String fragClass = json.optString("fragment", null);
            if (fragClass != null) {
                Class<?> clazz = Class.forName(fragClass);
                return (androidx.fragment.app.Fragment) clazz.getDeclaredConstructor().newInstance();
            }
        } catch (Exception ignored) {}
        return null;
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
