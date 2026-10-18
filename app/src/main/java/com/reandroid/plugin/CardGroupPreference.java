package com.reandroid.plugin;

import android.content.Context;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceGroupAdapter;
import androidx.preference.PreferenceViewHolder;
import androidx.recyclerview.widget.RecyclerView;

import com.reandroid.wallpaper.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 可折叠的分组卡片标题行。
 *
 * <p>折叠直接作用于子项的 {@link Preference#setVisible(boolean)}：AndroidX 1.1.1 的
 * PreferenceGroupAdapter 会据此重建可见列表，不需要拆装偏好树。
 * 展开状态只存在对象里 —— 页面重建（重置设置、切换渲染器）后回到默认的收起态。
 */
public class CardGroupPreference extends PreferenceGroup {

    private static final long ANIM_MS = 180;
    private static final long FADE_MS = 120;

    private boolean mExpanded;
    /** 动画期间忽略重复点击。 */
    private boolean mBusy;
    /** 标题行的 View；用来在收起/展开后把它的屏幕位置钉住。 */
    private View mHeaderView;

    public CardGroupPreference(Context context) {
        super(context, null);
        setLayoutResource(R.layout.preference_card_group);
        setOnPreferenceClickListener(pref -> {
            toggleExpanded();
            return true;
        });
    }

    /**
     * 切换展开状态，并让标题行留在原来的屏幕位置。
     *
     * <p>子项一藏，PreferenceGroupAdapter 就 notifyDataSetChanged()，LinearLayoutManager
     * 会重新挑一个锚点重排 —— 锚点可能是刚被藏掉的子项，于是标题行会跟着往下跑，
     * 看起来像"从头部往尾部收"。这里记下切换前的 top，布局结束后把差值滚回去。
     */
    private void toggleExpanded() {
        if (mBusy) return;
        RecyclerView list = findRecyclerView();
        if (!mExpanded) {
            applyExpandedChange(true, list);
            return;
        }
        /*
         * 收起：先把组内各行淡出，再真正隐藏。
         * 直接隐藏的话文字会在同一帧消失，看起来是"瞬间闪没"。
         */
        List<View> children = attachedChildViews(list);
        if (children.isEmpty()) {
            applyExpandedChange(false, list);
            return;
        }
        mBusy = true;
        final int[] remaining = {children.size()};
        for (View child : children) {
            child.animate().cancel();
            child.animate()
                    .alpha(0f)
                    .setDuration(FADE_MS)
                    .withEndAction(() -> {
                        if (--remaining[0] == 0) {
                            mBusy = false;
                            applyExpandedChange(false, findRecyclerView());
                        }
                    })
                    .start();
        }
    }

    /** 当前挂在列表上的、属于本组的子行。 */
    private List<View> attachedChildViews(RecyclerView list) {
        List<View> out = new ArrayList<>();
        if (list == null) return out;
        RecyclerView.Adapter<?> adapter = list.getAdapter();
        if (!(adapter instanceof PreferenceGroupAdapter)) return out;
        PreferenceGroupAdapter pga = (PreferenceGroupAdapter) adapter;
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            int position = list.getChildAdapterPosition(child);
            if (position == RecyclerView.NO_POSITION) continue;
            Preference preference = pga.getItem(position);
            if (preference != null && preference.getParent() == this) out.add(child);
        }
        return out;
    }

    private void applyExpandedChange(boolean expanded, RecyclerView list) {
        if (list == null) {
            setExpanded(expanded);
            return;
        }
        RecyclerView.Adapter<?> adapter = list.getAdapter();
        if (!(adapter instanceof PreferenceGroupAdapter)) {
            setExpanded(expanded);
            return;
        }
        PreferenceGroupAdapter pga = (PreferenceGroupAdapter) adapter;

        // 旧位置按 Preference 身份记：收/展后 adapter position 会整体平移，不能拿 position 配对
        Map<Preference, Integer> oldTops = new HashMap<>();
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            int position = list.getChildAdapterPosition(child);
            if (position == RecyclerView.NO_POSITION) continue;
            Preference preference = pga.getItem(position);
            if (preference != null) oldTops.put(preference, child.getTop());
        }
        int oldHeaderTop = mHeaderView != null ? mHeaderView.getTop() : 0;

        setExpanded(expanded);

        /*
         * setVisible() 只是把适配器更新 post 出去，布局要到下一帧才变。
         * 所以监听器也必须 post 到那次更新之后（同一个主线程队列，先 post 的同步任务先跑），
         * 否则 pre-draw 里量到的还是旧布局，差值恒为 0 —— 表现出来就是瞬移。
         */
        list.post(() -> {
            ViewTreeObserver observer = list.getViewTreeObserver();
            observer.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    if (!observer.isAlive()) return true;
                    observer.removeOnPreDrawListener(this);
                    animateLayoutChange(list, pga, oldTops, oldHeaderTop);
                    return true;
                }
            });
        });
    }

    /** 新布局已生效：先把标题行滚回原位，再把其余行的位置差补成平移动画。 */
    private void animateLayoutChange(RecyclerView list, PreferenceGroupAdapter pga,
                                     Map<Preference, Integer> oldTops, int oldHeaderTop) {
        if (mHeaderView != null && mHeaderView.isAttachedToWindow()) {
            int dy = mHeaderView.getTop() - oldHeaderTop;
            if (dy != 0) list.scrollBy(0, dy);
        }
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            int position = list.getChildAdapterPosition(child);
            if (position == RecyclerView.NO_POSITION) continue;
            Preference preference = pga.getItem(position);
            Integer oldTop = preference != null ? oldTops.get(preference) : null;
            child.animate().cancel();
            if (oldTop == null) {
                // 新出现的行：淡入
                child.setAlpha(0f);
                child.animate().alpha(1f).setDuration(ANIM_MS).start();
            } else {
                child.setAlpha(1f);
                float dy = oldTop - child.getTop();
                if (dy != 0) {
                    child.setTranslationY(dy);
                    child.animate().translationY(0f).setDuration(ANIM_MS).start();
                }
            }
        }
    }

    private RecyclerView findRecyclerView() {
        View view = mHeaderView;
        while (view != null) {
            if (view instanceof RecyclerView) return (RecyclerView) view;
            ViewParent parent = view.getParent();
            view = parent instanceof View ? (View) parent : null;
        }
        return null;
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
        mHeaderView = holder.itemView;
        // 水波纹按当前形态裁剪圆角：收起时四角，展开时只有上面两角
        holder.itemView.setBackgroundResource(
                mExpanded ? R.drawable.bg_group_ripple_top : R.drawable.bg_group_ripple_only);
        View chevron = holder.findViewById(R.id.group_chevron);
        if (chevron == null) return;
        float target = mExpanded ? 180f : 0f;
        if (chevron.getRotation() != target) {
            chevron.animate().rotation(target).setDuration(150).start();
        }
    }
}
