package com.reandroid.plugin;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceViewHolder;

import com.reandroid.wallpaper.R;

/**
 * 可折叠的分组卡片标题行。
 *
 * <p>折叠直接作用于子项的 {@link Preference#setVisible(boolean)}：AndroidX 1.1.1 的
 * PreferenceGroupAdapter 会据此重建可见列表，不需要拆装偏好树。
 * 展开状态只存在对象里 —— 页面重建（重置设置、切换渲染器）后回到默认的收起态。
 */
public class CardGroupPreference extends PreferenceGroup {

    private boolean mExpanded;

    public CardGroupPreference(Context context) {
        super(context, null);
        setLayoutResource(R.layout.preference_card_group);
        setOnPreferenceClickListener(pref -> {
            setExpanded(!mExpanded);
            return true;
        });
    }

    @Override
    public boolean addPreference(Preference preference) {
        boolean added = super.addPreference(preference);
        if (added && !mExpanded) {
            preference.setVisible(false);
        }
        return added;
    }

    public boolean isExpanded() {
        return mExpanded;
    }

    public void setExpanded(boolean expanded) {
        if (mExpanded == expanded) return;
        mExpanded = expanded;
        for (int i = 0; i < getPreferenceCount(); i++) {
            getPreference(i).setVisible(expanded);
        }
        notifyChanged();
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        View chevron = holder.findViewById(R.id.group_chevron);
        if (chevron == null) return;
        float target = mExpanded ? 180f : 0f;
        if (chevron.getRotation() != target) {
            chevron.animate().rotation(target).setDuration(150).start();
        }
    }
}
