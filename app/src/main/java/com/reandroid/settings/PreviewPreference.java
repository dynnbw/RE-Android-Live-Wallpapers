package com.reandroid.settings;

import android.content.Context;
import android.util.AttributeSet;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.reandroid.wallpaper.R;
import com.reandroid.gles.GLESPreviewView;
import com.reandroid.gles.GLESScene;
import com.reandroid.gles.GLESWallpaper;

public class PreviewPreference extends Preference {
    private static final String PREF_PREVIEW_RATIO = "pref_preview_ratio";
    private static final String DEFAULT_PREVIEW_RATIO = "9:16";
    private GLESPreviewView.SceneFactory mSceneFactory;
    private GLESPreviewView mPreviewView;

    public PreviewPreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.pref_live_preview);
        setSelectable(true);
    }

    public void setSceneFactory(GLESPreviewView.SceneFactory factory) {
        mSceneFactory = factory;
        notifyChanged();
    }

    /**
     * 获取当前的Scene对象（用于实时更新）
     */
    public GLESScene getScene() {
        return mPreviewView != null ? mPreviewView.getScene() : null;
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        FrameLayout container = (FrameLayout) holder.findViewById(R.id.preview_container);
        if (container == null || mSceneFactory == null) return;
        GLESWallpaper.initializeAppContext(getContext());
        // 移除所有子View，保证每次都刷新比例
        container.removeAllViews();
        applyPreviewRatio(container);
        mPreviewView = new GLESPreviewView(getContext(), mSceneFactory);
        mPreviewView.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));
        container.addView(mPreviewView);
    }

    private void applyPreviewRatio(FrameLayout container) {
        String ratio = DEFAULT_PREVIEW_RATIO;
        // 始终从全局 SharedPreferences 读取，避免 Fragment 层级 name 不一致
        Context context = getContext();
        if (context != null) {
            android.content.SharedPreferences globalPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
            ratio = globalPrefs.getString(PREF_PREVIEW_RATIO, DEFAULT_PREVIEW_RATIO);
        }
        if (!isValidRatio(ratio)) {
            ratio = DEFAULT_PREVIEW_RATIO;
        }

        ViewGroup.LayoutParams layoutParams = container.getLayoutParams();
        if (layoutParams instanceof ConstraintLayout.LayoutParams) {
            ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) layoutParams;
            params.dimensionRatio = ratio;
            container.setLayoutParams(params);
        }
    }

    private boolean isValidRatio(String value) {
        if (value == null) {
            return false;
        }
        String raw = value.trim();
        if (raw.isEmpty()) {
            return false;
        }
        String[] parts = raw.split(":");
        if (parts.length != 2) {
            return false;
        }
        try {
            int w = Integer.parseInt(parts[0].trim());
            int h = Integer.parseInt(parts[1].trim());
            return w > 0 && h > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
