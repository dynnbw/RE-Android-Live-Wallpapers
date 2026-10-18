package com.reandroid.plugin;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroupAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.reandroid.wallpaper.R;

/**
 * 把一整个分组画成一张卡：连续圆角背景 + 柔和阴影 + 组内分隔线。
 *
 * <p>PreferenceGroup 的子项在 RecyclerView 里是各自独立的一行，没法用一个 CardView 包住，
 * 所以整卡背景只能在 ItemDecoration 里画。阴影不用 {@link Paint#setShadowLayer}（它在
 * 硬件加速画布上的形状绘制不可靠），而是叠多层很淡的圆角矩形。**每层必须很淡**：
 * 边缘那一圈如果太深，两张分组卡之间就会看成一条分割线。
 */
public class GroupCardDecoration extends RecyclerView.ItemDecoration {

    /** 阴影层数：层多、每层淡，边缘才不会有硬边。 */
    private static final int SHADOW_LAYERS = 24;

    private final int mSideInset;
    private final int mGroupGap;
    private final float mRadius;
    private final float mDividerInset;
    private final float mDividerHeight;
    private final float mShadowStep;

    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDivider = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();

    public GroupCardDecoration(Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        // 与独立卡片对齐：FrameLayout 4dp + CardView compat padding ≈ 12dp
        mSideInset = Math.round(12 * density);
        // 独立卡片之间实测约 24dp（两边各 12dp）
        mGroupGap = Math.round(12 * density);
        mRadius = 14 * density;
        mDividerInset = 16 * density;
        mDividerHeight = Math.max(1f, density);
        // 24 层铺满约 7.5dp，边缘约 16% 暗，和 CardView 的 4dp elevation 接近
        mShadowStep = 0.31f * density;

        mFill.setColor(context.getColor(R.color.md_theme_surface));
        int onSurface = context.getColor(R.color.md_theme_onSurface);
        mDivider.setColor((onSurface & 0x00FFFFFF) | 0x1F000000);
        mShadow.setColor(0x02000000);
    }

    @Override
    public void getItemOffsets(
            @NonNull Rect outRect,
            @NonNull View view,
            @NonNull RecyclerView parent,
            @NonNull RecyclerView.State state) {
        RecyclerView.Adapter<?> adapter = parent.getAdapter();
        if (!(adapter instanceof PreferenceGroupAdapter)) return;
        PreferenceGroupAdapter pga = (PreferenceGroupAdapter) adapter;
        int position = parent.getChildAdapterPosition(view);
        if (position == RecyclerView.NO_POSITION) return;
        Preference p = pga.getItem(position);
        CardGroupPreference group = groupOf(p);
        if (group == null) return;

        outRect.left = mSideInset;
        outRect.right = mSideInset;
        // 分组标题上方留出与上一张卡的间距
        if (p instanceof CardGroupPreference) outRect.top = mGroupGap;
        // 组内最后一个可见行（收起时就是标题行）下方留出间距
        boolean lastInGroup =
                position >= pga.getItemCount() - 1 || groupOf(pga.getItem(position + 1)) != group;
        if (lastInGroup) outRect.bottom = mGroupGap;
    }

    @Override
    public void onDraw(
            @NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        RecyclerView.Adapter<?> adapter = parent.getAdapter();
        if (!(adapter instanceof PreferenceGroupAdapter)) return;
        PreferenceGroupAdapter pga = (PreferenceGroupAdapter) adapter;

        int count = pga.getItemCount();
        int i = 0;
        while (i < count) {
            CardGroupPreference group = groupOf(pga.getItem(i));
            if (group == null) {
                i++;
                continue;
            }
            int last = i;
            while (last + 1 < count && groupOf(pga.getItem(last + 1)) == group) {
                last++;
            }
            drawGroup(c, parent, i, last);
            i = last + 1;
        }
    }

    private void drawGroup(Canvas c, RecyclerView parent, int first, int last) {
        View firstView = null;
        View lastView = null;
        for (int k = 0; k < parent.getChildCount(); k++) {
            View child = parent.getChildAt(k);
            int pos = parent.getChildAdapterPosition(child);
            if (pos < first || pos > last) continue;
            if (pos == first) firstView = child;
            if (pos == last) lastView = child;
        }
        if (firstView == null && lastView == null) return;

        View anchor = firstView != null ? firstView : lastView;
        float left = anchor.getLeft();
        float right = anchor.getRight();
        float top = firstView != null ? firstView.getTop() : 0f;
        float bottom = lastView != null ? lastView.getBottom() : parent.getHeight();
        if (bottom <= top) return;

        for (int k = SHADOW_LAYERS; k >= 1; k--) {
            float e = k * mShadowStep;
            mRect.set(left - e, top - e, right + e, bottom + e);
            c.drawRoundRect(mRect, mRadius + e, mRadius + e, mShadow);
        }
        mRect.set(left, top, right, bottom);
        c.drawRoundRect(mRect, mRadius, mRadius, mFill);

        // 分隔线画在卡片填充之上：标题下、条目之间各一条
        for (int k = 0; k < parent.getChildCount(); k++) {
            View child = parent.getChildAt(k);
            int pos = parent.getChildAdapterPosition(child);
            if (pos <= first || pos > last) continue;
            float lineTop = child.getTop();
            c.drawRect(
                    child.getLeft() + mDividerInset,
                    lineTop,
                    child.getRight() - mDividerInset,
                    lineTop + mDividerHeight,
                    mDivider);
        }
    }

    private static CardGroupPreference groupOf(Preference p) {
        Preference current = p;
        while (current != null) {
            if (current instanceof CardGroupPreference) {
                return (CardGroupPreference) current;
            }
            current = current.getParent();
        }
        return null;
    }
}
