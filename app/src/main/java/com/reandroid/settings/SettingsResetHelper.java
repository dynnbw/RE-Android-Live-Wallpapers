package com.reandroid.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.XmlResourceParser;
import android.util.AttributeSet;
import android.util.Xml;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import com.reandroid.wallpaper.R;
import com.reandroid.wallpaper.deepsea.DeepSeaGL;
import com.reandroid.wallpaper.phasebeam.PhaseBeamGL;
import com.reandroid.wallpaper.polarclock.PolarClockWallpaper;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class SettingsResetHelper {
    public static final String TARGET_CONFIG = "config";
    public static final String TARGET_FALL = "fall";
    public static final String TARGET_GRASS = "grass";
    public static final String TARGET_WILDWORLD = "wildworld";
    public static final String TARGET_WINDMILL = "windmill";
    public static final String TARGET_OCEAN = "ocean";
    public static final String TARGET_FIREWORKS = "fireworks";
    public static final String TARGET_GALAXY4 = "galaxy4";
    public static final String TARGET_GALAXY = "galaxy";
    public static final String TARGET_WALKAROUND = "walkaround";
    public static final String TARGET_PHASEBEAM = "phasebeam";
    public static final String TARGET_NEBULA = "nebula";
    public static final String TARGET_NOISEFIELD = "noisefield";
    public static final String TARGET_MUSICVIS = "musicvis";
    public static final String TARGET_NEXUS = "nexus";
    public static final String TARGET_POLARCLOCK = "polarclock";
    public static final String TARGET_MAGICSMOKE = "magicsmoke";
    public static final String TARGET_HOLOSPIRAL = "holospiral";
    public static final String TARGET_BLUESEA = "bluesea";
    public static final String TARGET_DEEPSEA = "deepsea";
    public static final String TARGET_FLSORESCENCE = "flsorescence";
    public static final String TARGET_MICROBES = "microbes";
    public static final String TARGET_NIGHTSKY = "nightsky";
    public static final String TARGET_AURORA1 = "aurora1";
    public static final String TARGET_AURORA2 = "aurora2";

    private static final String RESET_PREF_KEY_PREFIX = "pref_reset_defaults_";
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    private static final String DEFAULT_SHARED_PREFS_SUFFIX = "_preferences";
    private static final String KEY_GLOBAL_FRAME_RATE = "global_frame_rate";
    private static final String KEY_PREVIEW_RATIO = "pref_preview_ratio";
    private static final String KEY_WEATHER_UPDATE_MINUTES = "weather_update_minutes";
    private static final String KEY_WEATHER_API_KEY = "openweather_api_key";

    private interface ExternalResetAction {
        void run(Context context);
    }

    private static final class ResetSpec {
        final int xmlResId;
        @Nullable final String sharedPrefsName;
        @Nullable final ExternalResetAction externalResetAction;

        ResetSpec(int xmlResId, @Nullable String sharedPrefsName, @Nullable ExternalResetAction externalResetAction) {
            this.xmlResId = xmlResId;
            this.sharedPrefsName = sharedPrefsName;
            this.externalResetAction = externalResetAction;
        }
    }

    private SettingsResetHelper() {
    }

    public static void attachResetPreference(PreferenceFragmentCompat fragment, String targetId) {
        if (fragment.getPreferenceScreen() == null) {
            return;
        }
        String preferenceKey = RESET_PREF_KEY_PREFIX + targetId;
        if (fragment.findPreference(preferenceKey) != null) {
            return;
        }

        Preference preference = new Preference(fragment.requireContext());
        preference.setKey(preferenceKey);
        preference.setTitle(R.string.reset_current_settings_title);
        preference.setSummary(R.string.reset_current_settings_summary);
        preference.setLayoutResource(R.layout.preference_modern_item);
        preference.setOnPreferenceClickListener(pref -> {
            showResetTargetDialog(fragment, targetId);
            return true;
        });
        fragment.getPreferenceScreen().addPreference(preference);
    }

    public static void showResetAllDialog(FragmentActivity activity) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.reset_all_settings_title)
                .setMessage(R.string.reset_all_settings_message)
                .setPositiveButton(R.string.reset_action_confirm, (dialog, which) -> {
                    resetAll(activity);
                    Toast.makeText(activity, R.string.reset_all_settings_done, Toast.LENGTH_SHORT).show();
                    activity.recreate();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void showResetTargetDialog(PreferenceFragmentCompat fragment, String targetId) {
        new AlertDialog.Builder(fragment.requireContext())
                .setTitle(R.string.reset_current_settings_title)
                .setMessage(R.string.reset_current_settings_message)
                .setPositiveButton(R.string.reset_action_confirm, (dialog, which) -> {
                    resetTarget(fragment.requireContext(), targetId);
                    Toast.makeText(fragment.requireContext(), R.string.reset_current_settings_done, Toast.LENGTH_SHORT).show();
                    FragmentActivity activity = fragment.getActivity();
                    if (activity != null) {
                        activity.recreate();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    public static void resetAll(Context context) {
        for (String targetId : getAllTargets()) {
            resetTarget(context, targetId);
        }
    }

    public static void resetTarget(Context context, String targetId) {
        ResetSpec spec = getSpec(targetId);
        if (spec == null) {
            return;
        }
        if (spec.xmlResId != 0) {
            resetPreferenceValues(context, spec);
        }
        if (spec.externalResetAction != null) {
            spec.externalResetAction.run(context.getApplicationContext());
        }
    }

    @Nullable
    private static ResetSpec getSpec(String targetId) {
        switch (targetId) {
            case TARGET_CONFIG:
            return new ResetSpec(0, null,
                        context -> {
                            SharedPreferences prefs = context.getSharedPreferences(
                                    context.getPackageName() + DEFAULT_SHARED_PREFS_SUFFIX,
                                    Context.MODE_PRIVATE
                            );
                            prefs.edit()
                                    .remove(KEY_GLOBAL_FRAME_RATE)
                                    .remove(KEY_PREVIEW_RATIO)
                        .remove(KEY_WEATHER_UPDATE_MINUTES)
                        .remove(KEY_WEATHER_API_KEY)
                                    .apply();
                        });
            case TARGET_FALL:
                return new ResetSpec(R.xml.prefs_fall, null, null);
            case TARGET_GRASS:
                return new ResetSpec(R.xml.prefs_grass, null, null);
            case TARGET_WILDWORLD:
                return new ResetSpec(R.xml.prefs_wildworld, null, null);
            case TARGET_WINDMILL:
                return new ResetSpec(R.xml.prefs_windmill, null, null);
            case TARGET_OCEAN:
                return new ResetSpec(R.xml.prefs_ocean, null, null);
            case TARGET_FIREWORKS:
                return new ResetSpec(R.xml.prefs_fireworks, null,
                        context -> context.getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
                                .edit()
                                .remove("fireworks_custom_background_uri")
                                .apply());
            case TARGET_GALAXY4:
                return new ResetSpec(R.xml.prefs_galaxy4, null, null);
            case TARGET_GALAXY:
                return new ResetSpec(R.xml.prefs_galaxy, null, null);
            case TARGET_WALKAROUND:
                return new ResetSpec(R.xml.prefs_walkaround, null, null);
            case TARGET_PHASEBEAM:
                return new ResetSpec(R.xml.prefs_phasebeam, null,
                        context -> context.getSharedPreferences(PhaseBeamGL.PREFS_NAME, Context.MODE_PRIVATE)
                                .edit()
                                .clear()
                                .apply());
            case TARGET_NEBULA:
                return new ResetSpec(R.xml.prefs_nebula, null, null);
            case TARGET_NOISEFIELD:
                return new ResetSpec(R.xml.prefs_noisefield, null, null);
            case TARGET_MUSICVIS:
                return new ResetSpec(R.xml.prefs_musicvis, null, null);
            case TARGET_NEXUS:
                return new ResetSpec(R.xml.prefs_nexus, null,
                        context -> context.getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
                                .edit()
                                .remove("nexus_custom_background_uri")
                                .apply());
            case TARGET_POLARCLOCK:
                return new ResetSpec(R.xml.polar_clock_prefs, PolarClockWallpaper.SHARED_PREFS_NAME, null);
            case TARGET_MAGICSMOKE:
                return new ResetSpec(R.xml.prefs_magicsmoke, "magicsmoke", null);
            case TARGET_HOLOSPIRAL:
                return new ResetSpec(R.xml.prefs_holospiral, null, null);
            case TARGET_BLUESEA:
                return new ResetSpec(R.xml.prefs_bluesea, null, null);
            case TARGET_DEEPSEA:
                return new ResetSpec(R.xml.prefs_deepsea, DeepSeaGL.PREFS_NAME,
                        context -> {
                            File cacheDir = context.getExternalCacheDir() != null
                                    ? context.getExternalCacheDir()
                                    : context.getCacheDir();
                            File outFile = new File(cacheDir, "deepseabackground.jpg");
                            if (outFile.exists()) {
                                outFile.delete();
                            }
                        });
            case TARGET_FLSORESCENCE:
                return new ResetSpec(R.xml.prefs_flsorescence, null, null);
            case TARGET_MICROBES:
                return new ResetSpec(R.xml.prefs_microbes, null, null);
            case TARGET_NIGHTSKY:
                return new ResetSpec(R.xml.prefs_nightsky, null, null);
            case TARGET_AURORA1:
                return new ResetSpec(R.xml.prefs_aurora1, null, null);
            case TARGET_AURORA2:
                return new ResetSpec(R.xml.prefs_aurora2, null, null);
            default:
                return null;
        }
    }

    private static List<String> getAllTargets() {
        List<String> targets = new ArrayList<>();
        targets.add(TARGET_CONFIG);
        targets.add(TARGET_FALL);
        targets.add(TARGET_GRASS);
        targets.add(TARGET_WILDWORLD);
        targets.add(TARGET_WINDMILL);
        targets.add(TARGET_OCEAN);
        targets.add(TARGET_FIREWORKS);
        targets.add(TARGET_GALAXY4);
        targets.add(TARGET_GALAXY);
        targets.add(TARGET_WALKAROUND);
        targets.add(TARGET_PHASEBEAM);
        targets.add(TARGET_NEBULA);
        targets.add(TARGET_NOISEFIELD);
        targets.add(TARGET_MUSICVIS);
        targets.add(TARGET_NEXUS);
        targets.add(TARGET_POLARCLOCK);
        targets.add(TARGET_MAGICSMOKE);
        targets.add(TARGET_HOLOSPIRAL);
        targets.add(TARGET_BLUESEA);
        targets.add(TARGET_DEEPSEA);
        targets.add(TARGET_FLSORESCENCE);
        targets.add(TARGET_MICROBES);
        targets.add(TARGET_NIGHTSKY);
        targets.add(TARGET_AURORA1);
        targets.add(TARGET_AURORA2);
        return targets;
    }

    private static void resetPreferenceValues(Context context, ResetSpec spec) {
        String prefsName = spec.sharedPrefsName != null
                ? spec.sharedPrefsName
            : context.getPackageName() + "_preferences";
        SharedPreferences sharedPreferences = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        for (String key : collectPreferenceKeys(context, spec.xmlResId)) {
            editor.remove(key);
        }
        editor.apply();
        PreferenceManager.setDefaultValues(context, prefsName, Context.MODE_PRIVATE, spec.xmlResId, true);
    }

    private static Set<String> collectPreferenceKeys(Context context, int xmlResId) {
        Set<String> keys = new LinkedHashSet<>();
        XmlResourceParser parser = context.getResources().getXml(xmlResId);
        try {
            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    AttributeSet attrs = Xml.asAttributeSet(parser);
                    String key = attrs.getAttributeValue(ANDROID_NS, "key");
                    if (key != null && !key.isEmpty()) {
                        keys.add(key);
                    }
                }
                eventType = parser.next();
            }
        } catch (Exception ignored) {
        } finally {
            parser.close();
        }
        return keys;
    }
}