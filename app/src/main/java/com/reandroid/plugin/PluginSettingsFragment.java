package com.reandroid.plugin;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import org.json.JSONObject;

/**
 * Generic settings fragment that builds its UI from a plugin's layout.json.
 * The plugin ID is passed via arguments ("plugin_id") or inferred from the active plugin.
 */
public class PluginSettingsFragment extends PreferenceFragmentCompat {

    private static final String ARG_PLUGIN_ID = "plugin_id";

    public static PluginSettingsFragment newInstance(String pluginId) {
        PluginSettingsFragment f = new PluginSettingsFragment();
        Bundle args = new Bundle();
        args.putString(ARG_PLUGIN_ID, pluginId);
        f.setArguments(args);
        return f;
    }

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        String pluginId = getArguments() != null
                ? getArguments().getString(ARG_PLUGIN_ID)
                : ProxyWallpaperService.getActivePlugin(requireContext());
        if (pluginId == null) return;

        Context ctx = requireContext();
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(ctx);
        setPreferenceScreen(screen);

        SharedPreferences prefs = ctx.getSharedPreferences("plugin_" + pluginId, Context.MODE_PRIVATE);

        JSONObject layout = PluginResources.loadLayout(ctx, pluginId);
        if (layout == null) return;

        DynamicPreferenceFactory.buildPreferences(ctx, prefs, layout, preference -> {
            preference.setLayoutResource(com.reandroid.wallpaper.R.layout.preference_modern_item);
            screen.addPreference(preference);
        });
    }
}
