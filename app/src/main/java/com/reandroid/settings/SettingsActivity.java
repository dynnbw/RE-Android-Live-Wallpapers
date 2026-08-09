
package com.reandroid.settings;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.reandroid.update.UpdateHelper;
import com.reandroid.wallpaper.R;

public class SettingsActivity extends AppCompatActivity
    implements PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    private boolean mUpdateChecked;
    private SettingsToolbarHelper mToolbarHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 高版本自动应用 Monet 动态取色
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        mToolbarHelper = new SettingsToolbarHelper(this, toolbar);
        mToolbarHelper.setup();

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.settings_container, new SettingsMainFragment())
                    .commit();
            setTitle(R.string.settings_title);
        }

        getSupportFragmentManager().addOnBackStackChangedListener(() -> {
            if (getSupportFragmentManager().getBackStackEntryCount() == 0) {
                setTitle(R.string.settings_title);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!mUpdateChecked) {
            mUpdateChecked = true;
            UpdateHelper.checkAndShow(this, true);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        mToolbarHelper.onStart();
    }

    @Override
    protected void onStop() {
        mToolbarHelper.onStop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        mToolbarHelper.onDestroy();
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        mToolbarHelper.onCreateOptionsMenu(menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        mToolbarHelper.onPrepareOptionsMenu(menu);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (mToolbarHelper.onOptionsItemSelected(item)) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onPreferenceStartFragment(PreferenceFragmentCompat caller, Preference pref) {
        String fragmentClass = pref.getFragment();
        if (fragmentClass == null) return false;
        androidx.fragment.app.Fragment fragment = getSupportFragmentManager()
                .getFragmentFactory()
                .instantiate(getClassLoader(), fragmentClass);
        fragment.setArguments(pref.getExtras());

        getSupportFragmentManager().beginTransaction()
                .replace(R.id.settings_container, fragment)
                .addToBackStack(pref.getKey())
                .commit();

        CharSequence title = pref.getTitle();
        if (title != null) {
            setTitle(title);
        }
        supportInvalidateOptionsMenu();
        return true;
    }

    @Override
    public boolean onSupportNavigateUp() {
        if (getSupportFragmentManager().popBackStackImmediate()) {
            return true;
        }
        return super.onSupportNavigateUp();
    }
}