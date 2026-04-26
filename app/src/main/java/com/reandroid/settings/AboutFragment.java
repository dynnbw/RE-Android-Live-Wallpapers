package com.reandroid.settings;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.reandroid.wallpaper.BuildConfig;
import com.reandroid.wallpaper.R;

import android.content.Context;

public class AboutFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.prefs_about, rootKey);
        // 版本号
        Preference versionPref = findPreference("about_version");
        if (versionPref != null) {
            versionPref.setSummary(BuildConfig.VERSION_NAME);
        }
        // 官网
        Preference website = findPreference("about_official_website");
        if (website != null) {
            website.setOnPreferenceClickListener(pref -> {
                openUrl(getString(R.string.about_official_website_url));
                return true;
            });
        }
        // 邮箱
        Preference email = findPreference("about_email");
        if (email != null) {
            email.setOnPreferenceClickListener(pref -> {
                sendEmail(getString(R.string.about_email_address));
                return true;
            });
        }
        Preference email2 = findPreference("about_email2");
        if (email2 != null) {
            email2.setOnPreferenceClickListener(pref -> {
                sendEmail(getString(R.string.about_email_address_alt));
                return true;
            });
        }
        // Bilibili主页
        Preference bilibili = findPreference("about_bilibili");
        if (bilibili != null) {
            bilibili.setOnPreferenceClickListener(pref -> {
                openUrl(getString(R.string.about_bilibili_url));
                return true;
            });
        }
        // QQ
        Preference qq = findPreference("about_qq");
        if (qq != null) {
            qq.setOnPreferenceClickListener(pref -> {
                copyToClipboard(getString(R.string.about_qq_number));
                Toast.makeText(getContext(), R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();
                return true;
            });
        }
        // 反馈
        Preference feedback = findPreference("about_feedback");
        if (feedback != null) {
            feedback.setOnPreferenceClickListener(pref -> {
                Toast.makeText(getContext(), R.string.about_feedback_contact, Toast.LENGTH_SHORT).show();
                return true;
            });
        }
    }

    // 保持Material2风格，无需包裹overlay

    private void openUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(getContext(), R.string.no_browser_found, Toast.LENGTH_SHORT).show();
        }
    }

    private void sendEmail(String email) {
        Intent sendToIntent = new Intent(Intent.ACTION_SENDTO);
        sendToIntent.setData(Uri.parse("mailto:" + email));
        try {
            startActivity(sendToIntent);
            return;
        } catch (ActivityNotFoundException ignored) {
            // Fallback for devices/apps that do not expose SENDTO handlers reliably.
        }

        Intent sendIntent = new Intent(Intent.ACTION_SEND);
        sendIntent.setType("message/rfc822");
        sendIntent.putExtra(Intent.EXTRA_EMAIL, new String[]{email});
        try {
            startActivity(Intent.createChooser(sendIntent, getString(R.string.about_email)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(getContext(), R.string.no_email_found, Toast.LENGTH_SHORT).show();
        }
    }

    private void copyToClipboard(String text) {
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        android.content.ClipData clip = android.content.ClipData.newPlainText("label", text);
        clipboard.setPrimaryClip(clip);
    }
}
