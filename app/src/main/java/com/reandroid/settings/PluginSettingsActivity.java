package com.reandroid.settings;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.reandroid.plugin.PluginSettingsFragment;
import com.reandroid.plugin.ProxyWallpaperService;
import com.reandroid.wallpaper.R;

/**
 * Settings activity launched from the system wallpaper preview.
 * Reads the active plugin ID and loads its dynamic settings (layout.json).
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

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.settings_container,
                            PluginSettingsFragment.newInstance(pluginId))
                    .commit();
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
