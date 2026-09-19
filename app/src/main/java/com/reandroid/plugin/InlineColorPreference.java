package com.reandroid.plugin;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.reandroid.wallpaper.R;

/**
 * 内嵌在设置页里的取色组件(不是弹窗):标题 + 十六进制色值 + 预览色块 +
 * 色调 / 饱和度 / 亮度 滑块(HSV,比 RGB 直观)。
 *
 * 滑块个数由 layout.json 的 labels 决定:三项为 色调+饱和度+亮度,
 * 两项则只有 色调+饱和度(例如 phasebeam 的亮度是独立增益,不并入颜色)。
 *
 * 拖动滑块即时写入设置项(int ARGB),引擎的 pref 变更监听会立刻把设置重新注入场景,
 * 因此壁纸与设置页预览都是实时跟随的。
 *
 * 依赖父项关闭时按项目惯例软禁用:滑块保持 enabled(禁用 SeekBar 会让部分设备
 * ANGLE 崩溃),改为置灰 + 吞掉触摸。
 */
public class InlineColorPreference extends Preference {

    private static final float DISABLED_ALPHA = 0.4f;
    private static final int MAX_HUE = 360;
    private static final int MAX_PERCENT = 100;

    private static final int[] ROW_IDS = {R.id.color_row_1, R.id.color_row_2, R.id.color_row_3};
    private static final int[] LABEL_IDS = {R.id.color_label_1, R.id.color_label_2, R.id.color_label_3};
    private static final int[] BAR_IDS = {R.id.color_s1, R.id.color_s2, R.id.color_s3};
    private static final int[] VALUE_IDS = {R.id.color_v1, R.id.color_v2, R.id.color_v3};

    private final SharedPreferences mPrefs;
    private final int mDefaultColor;
    /** 各滑块标签;长度 2 或 3 决定显示哪些滑块(色调、饱和度、亮度)。 */
    private final String[] mLabels;

    /** 绑定期间抑制回调,避免 setProgress 触发写盘。 */
    private boolean mBinding;
    /** 依赖父项是否满足(由工厂写入)。 */
    private boolean mActive = true;

    /** 拖动期间保留的 HSV 分量:避免饱和度/亮度归零时色相被系统换算重置。 */
    private float mHue;
    private float mSaturation = 1.0f;
    private float mBrightness = 1.0f;

    public InlineColorPreference(Context context, SharedPreferences prefs, int defaultColor,
            String[] labels) {
        super(context);
        mPrefs = prefs;
        mDefaultColor = defaultColor;
        mLabels = labels != null && labels.length > 0 ? labels : new String[] {"H", "S", "V"};
        setLayoutResource(R.layout.preference_inline_color);
        setPersistent(false); // 自行写入 prefs,不走 Preference 的持久化
    }

    /**
     * 从 prefs 重读颜色并刷新显示。
     *
     * <p>本控件 setPersistent(false)、自己读写 prefs，颜色是在 onBindViewHolder 里读的，
     * 所以重绑一次就会显示最新值。设置页在外部改动该 key 后调用它
     * （notifyChanged 是 protected，只能由本类暴露）。
     */
    public void refreshFromPrefs() {
        notifyChanged();
    }

    /** 由设置工厂依据依赖条件调用。 */
    public void setControlsActive(boolean active) {
        if (mActive == active) {
            return;
        }
        mActive = active;
        notifyChanged();
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);

        View swatch = holder.findViewById(R.id.color_swatch);
        TextView hex = (TextView) holder.findViewById(R.id.color_hex);
        if (swatch == null || hex == null) {
            return; // 布局不匹配时静默跳过,避免整页崩掉
        }

        View[] rows = new View[3];
        TextView[] labels = new TextView[3];
        TextView[] values = new TextView[3];
        final SeekBar[] bars = new SeekBar[3];
        for (int i = 0; i < 3; i++) {
            rows[i] = holder.findViewById(ROW_IDS[i]);
            labels[i] = (TextView) holder.findViewById(LABEL_IDS[i]);
            values[i] = (TextView) holder.findViewById(VALUE_IDS[i]);
            bars[i] = (SeekBar) holder.findViewById(BAR_IDS[i]);
        }

        int color = ColorPrefs.getColor(mPrefs, getKey(), mDefaultColor);
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        mHue = hsv[0];
        mSaturation = hsv[1];
        mBrightness = hsv[2];

        int sliderCount = Math.min(3, mLabels.length);
        mBinding = true;
        for (int i = 0; i < 3; i++) {
            if (rows[i] == null || labels[i] == null || values[i] == null || bars[i] == null) {
                continue;
            }
            boolean used = i < sliderCount;
            rows[i].setVisibility(used ? View.VISIBLE : View.GONE);
            if (!used) {
                continue;
            }
            final int channel = i;
            labels[i].setText(mLabels[i]);
            bars[i].setMax(i == 0 ? MAX_HUE : MAX_PERCENT);
            bars[i].setProgress(progressOf(i));
            values[i].setText(valueText(i));
            // 软禁用:不禁用控件,依赖未满足时置灰并吞掉触摸
            bars[i].setAlpha(mActive ? 1.0f : DISABLED_ALPHA);
            bars[i].setOnTouchListener((view, event) -> !mActive);
            bars[i].setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                    applyChannel(channel, value);
                    values[channel].setText(valueText(channel));
                    int updated = currentColor();
                    swatch.setBackgroundColor(updated);
                    hex.setText(ColorPrefs.format(updated));
                    if (fromUser && !mBinding && mActive) {
                        // 实时写盘:引擎的 pref 监听会立即把设置注入场景
                        mPrefs.edit().putInt(getKey(), updated).apply();
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });
        }
        mBinding = false;

        swatch.setBackgroundColor(color);
        hex.setText(ColorPrefs.format(color));
    }

    /** 0 = 色调,1 = 饱和度,2 = 亮度。 */
    private void applyChannel(int channel, int value) {
        if (channel == 0) {
            mHue = value;
        } else if (channel == 1) {
            mSaturation = value / (float) MAX_PERCENT;
        } else {
            mBrightness = value / (float) MAX_PERCENT;
        }
    }

    private int currentColor() {
        return Color.HSVToColor(new float[] {mHue, mSaturation, mBrightness});
    }

    private int progressOf(int channel) {
        if (channel == 0) {
            return Math.round(mHue);
        }
        if (channel == 1) {
            return Math.round(mSaturation * MAX_PERCENT);
        }
        return Math.round(mBrightness * MAX_PERCENT);
    }

    private String valueText(int channel) {
        int value = progressOf(channel);
        return channel == 0 ? value + "°" : value + "%";
    }
}
