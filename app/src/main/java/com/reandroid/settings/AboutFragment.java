package com.reandroid.settings;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;

import com.reandroid.update.UpdateHelper;
import com.reandroid.wallpaper.BuildConfig;
import com.reandroid.wallpaper.R;

public class AboutFragment extends PreferenceFragmentCompat {

    private static final String KEY_DEVELOPER_CATEGORY = "about_developer_category";

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
        // 邮箱：页面不再显示地址明文，两行就靠服务商分（否则像同一个邮箱写了两遍）
        Preference email = findPreference("about_email");
        if (email != null) {
            email.setOnPreferenceClickListener(pref -> {
                sendEmail(getString(R.string.about_email_address), R.string.about_email_qq);
                return true;
            });
        }
        Preference email2 = findPreference("about_email2");
        if (email2 != null) {
            email2.setOnPreferenceClickListener(pref -> {
                sendEmail(getString(R.string.about_email_address_alt), R.string.about_email_gmail);
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
        // QQ频道
        Preference qq = findPreference("about_qq");
        if (qq != null) {
            qq.setOnPreferenceClickListener(pref -> {
                openUrl(getString(R.string.about_qq_url));
                return true;
            });
        }
        // 反馈
        Preference feedback = findPreference("about_feedback");
        if (feedback != null) {
            feedback.setOnPreferenceClickListener(pref -> {
                Toast.makeText(getContext(), R.string.about_feedback_contact, Toast.LENGTH_SHORT)
                        .show();
                return true;
            });
        }

        // 检查更新
        Preference checkUpdate = findPreference("about_check_update");
        if (checkUpdate != null) {
            checkUpdate.setSummary(
                    getString(R.string.app_version_summary, BuildConfig.VERSION_NAME));
            checkUpdate.setOnPreferenceClickListener(pref -> {
                UpdateHelper.checkAndShow(this);
                return true;
            });
        }
        loadContributors();
    }

    /**
     * 把 GitHub 的贡献者追加进「开发者」那一栏：头像作图标、用户名作标题、点按开主页。
     *
     * <p>名单和头像都直接取自 GitHub，仓库里不存副本 —— 拿不到（无网、配额用完、首次启动）
     * 就一行都不加，页面保持原样，也不给用户看到一个空名单。
     */
    private void loadContributors() {
        Context context = getContext();
        if (context == null) {
            return;
        }
        ContributorStore.load(context, contributors -> {
            if (contributors.isEmpty() || !isAdded()) {
                return;
            }
            PreferenceCategory category = findPreference(KEY_DEVELOPER_CATEGORY);
            if (category == null) {
                return;
            }
            if (contributors.isEmpty()) {
                // 名单拿不到就整块不显示 —— 分类里已经没有别的内容，留着只是空标题
                getPreferenceScreen().removePreference(category);
                return;
            }
            // 显式给 order：PreferenceGroup.addPreference 是按 order 二分插入的，
            // 同 order 时的落点未定义（可能把先后倒过来）
            int order = 1;
            for (Contributor contributor : contributors) {
                Preference row = contributorRow(contributor);
                row.setOrder(order++);
                category.addPreference(row);
            }
        });
    }

    private Preference contributorRow(Contributor contributor) {
        Preference row = new Preference(requireContext());
        row.setTitle(contributor.login);
        if (contributor.htmlUrl != null && !contributor.htmlUrl.isEmpty()) {
            row.setOnPreferenceClickListener(pref -> {
                openUrl(contributor.htmlUrl);
                return true;
            });
        }
        AvatarLoader.load(requireContext(), contributor.avatarUrl, bitmap -> {
            // 头像回来时页面可能已经走了
            if (isAdded()) {
                row.setIcon(new BitmapDrawable(getResources(), bitmap));
            }
        });
        return row;
    }

    // 保持Material2风格，无需包裹overlay

    private void openUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(getContext(), R.string.no_browser_found, Toast.LENGTH_SHORT)
                    .show();
        }
    }

    /** @param titleRes 选择器标题用哪个邮箱的名字（QQ 邮箱 / Gmail 邮箱） */
    private void sendEmail(String email, int titleRes) {
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
        sendIntent.putExtra(Intent.EXTRA_EMAIL, new String[] {email});
        try {
            startActivity(Intent.createChooser(sendIntent, getString(titleRes)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(getContext(), R.string.no_email_found, Toast.LENGTH_SHORT)
                    .show();
        }
    }
}
