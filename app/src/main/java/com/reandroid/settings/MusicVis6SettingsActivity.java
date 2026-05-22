package com.reandroid.settings;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import com.reandroid.wallpaper.R;

public class MusicVis6SettingsActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.settings_container, new MusicVis6SettingsFragment()).commit();
            setTitle(R.string.wallpaper_vis6);
        }
    }
    @Override public boolean onSupportNavigateUp() { if (getSupportFragmentManager().popBackStackImmediate()) return true; return super.onSupportNavigateUp(); }
}
