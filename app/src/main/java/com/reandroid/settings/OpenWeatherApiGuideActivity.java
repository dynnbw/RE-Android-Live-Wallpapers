package com.reandroid.settings;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.cardview.widget.CardView;

import com.reandroid.wallpaper.R;

public class OpenWeatherApiGuideActivity extends AppCompatActivity {
    private static final String OPENWEATHER_URL = "https://openweathermap.org/";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_openweather_api_guide);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.pref_apply_api_title);
        }

        TextView link = findViewById(R.id.api_guide_link_text);
        link.setOnClickListener(v -> openBrowser());

        CardView openWebButton = findViewById(R.id.api_guide_open_web_button);
        openWebButton.setOnClickListener(v -> openBrowser());
    }

    private void openBrowser() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(OPENWEATHER_URL)));
        } catch (Exception e) {
            Toast.makeText(this, R.string.config_open_browser_failed, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
