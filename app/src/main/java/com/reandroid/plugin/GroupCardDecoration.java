package com.reandroid.plugin;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.os.Build;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroupAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.reandroid.wallpaper.R;

import java.util.HashMap;
import java.util.Map;

/**
 * 把一整个分组画成一张卡：连续圆角背景 + 柔和阴影 + 组内分隔线。
 *
 * <p>PreferenceGroup 的子项在 RecyclerView 里是各自独立的一行，没法用一个 CardView 包住，
 * 所以整卡背景只能在 ItemDecoration 里画。
 *
 * <p>API 29+ 的阴影用带 elevation 的 {@link RenderNode}，和独立条目的 CardView 是同一套
 * 系统阴影。两个容易踩的点：{@code drawRenderNode} 必须包在 {@link Canvas#enableZ()} /
 * {@link Canvas#disableZ()} 之间（默认 Z 绘制是关的）；{@link Outline} 的 alpha 默认是 0，
 * 要显式设成 1，系统才认为这个轮廓能投射阴影。低版本或软件画布退回叠层圆角矩形。
 */
public class GroupCardDecoration extends RecyclerView.ItemDecoration {

    /** 低版本阴影层数：层数适中、每层淡，边缘才不会有硬边。 */
    private static final int SHADOW_LAYERS = 12;

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
    private final RectF mLayerRect = new RectF();
    /** 与独立条目的 CardView 对齐的 elevation。 */
    private final float mElevation;

    private final Outline mOutline = new Outline();
    /** 每个分组一个 RenderNode；尺寸变化或被系统回收显示列表时重录。 */
    private final Map<CardGroupPreference, CardNode> mCardNodes = new HashMap<>();

    public GroupCardDecoration(Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        // 与独立卡片对齐：FrameLayout 4dp + CardView compat padding ≈ 12dp
        mSideInset = Math.round(12 * density);
        // 独立卡片之间实测约 24dp（两边各 12dp）
        mGroupGap = Math.round(12 * density);
        mRadius = 14 * density;
        // 与 preference_modern_item 里的 CardView 一致：cardElevation 4dp
        mElevation = 4 * density;
        mDividerInset = 16 * density;
        mDividerHeight = Math.max(1f, density);
        // 12 层铺满约 7.5dp，边缘约 16% 暗，和 CardView 的 4dp elevation 接近
        mShadowStep = 0.62f * density;

        mFill.setColor(context.getColor(R.color.md_theme_surface));
        int onSurface = context.getColor(R.color.md_theme_onSurface);
        mDivider.setColor((onSurface & 0x00FFFFFF) | 0x1F000000);
        mShadow.setColor(0x04000000);
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
            drawGroup(c, parent, group, i, last);
            i = last + 1;
        }
    }

    private void drawGroup(
            Canvas c, RecyclerView parent, CardGroupPreference group, int first, int last) {
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

        drawCard(c, group, left, top, right, bottom);

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

    private void drawCard(
            Canvas c, CardGroupPreference group, float left, float top, float right, float bottom) {
        int width = Math.round(right - left);
        int height = Math.round(bottom - top);
        if (width <= 0 || height <= 0) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && c.isHardwareAccelerated()) {
            // 不开 Z 绘制的话，系统只画节点内容、不生成 elevation 阴影
            c.enableZ();
            try {
                drawElevatedCard(c, group, left, top, width, height);
            } finally {
                c.disableZ();
            }
            return;
        }

        // 低版本 / 软件画布：自己叠层画阴影。只填卡片外圈的四条边带，
        // 避免每层都把整张卡的面积混合一遍；凹路径的 clipPath 在硬件 Canvas
        // 上不可靠，所以用四个互不重叠的矩形裁剪。
        mRect.set(left, top, right, bottom);
        float extent = SHADOW_LAYERS * mShadowStep;
        drawShadowBand(c, left - extent, top - extent, right + extent, top);
        drawShadowBand(c, left - extent, bottom, right + extent, bottom + extent);
        drawShadowBand(c, left - extent, top, left, bottom);
        drawShadowBand(c, right, top, right + extent, bottom);

        c.drawRoundRect(mRect, mRadius, mRadius, mFill);
    }

    /** 在一条边带里叠出阴影层（卡片外圈），四条带互不重叠。 */
    private void drawShadowBand(
            Canvas c, float clipLeft, float clipTop, float clipRight, float clipBottom) {
        int save = c.save();
        c.clipRect(clipLeft, clipTop, clipRight, clipBottom);
        for (int k = SHADOW_LAYERS; k >= 1; k--) {
            float e = k * mShadowStep;
            mLayerRect.set(mRect.left - e, mRect.top - e, mRect.right + e, mRect.bottom + e);
            c.drawRoundRect(mLayerRect, mRadius + e, mRadius + e, mShadow);
        }
        c.restoreToCount(save);
    }

    /** 每个分组一个节点：同一帧里不能一边画一边重录同一个节点。 */
    private static final class CardNode {
        RenderNode node;
        int width = -1;
        int height = -1;
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private void drawElevatedCard(
            Canvas c, CardGroupPreference group, float left, float top, int width, int height) {
        CardNode card = mCardNodes.get(group);
        if (card == null) {
            card = new CardNode();
            card.node = new RenderNode("group-card");
            card.node.setElevation(mElevation);
            mCardNodes.put(group, card);
        }
        /*
         * 显示列表必须同时按尺寸和「还在不在」判断：卡片滚出屏幕后就不再绘制，
         * 系统会回收它的显示列表（hasDisplayList() == false）。只比尺寸的话，
         * 滚回来时尺寸没变、不重录，画出来就是一张空节点 —— 整张卡消失。
         */
        if (!card.node.hasDisplayList() || width != card.width || height != card.height) {
            card.width = width;
            card.height = height;
            card.node.setPosition(0, 0, width, height);
            // 阴影沿这个轮廓生成；内容也画成同样的圆角填充
            mOutline.setRoundRect(0, 0, width, height, mRadius);
            // Outline 的 alpha 默认是 0；系统据此判断「能不能投射阴影」，必须显式置 1
            mOutline.setAlpha(1f);
            card.node.setOutline(mOutline);
            RecordingCanvas canvas = card.node.beginRecording();
            canvas.drawRoundRect(0f, 0f, width, height, mRadius, mRadius, mFill);
            card.node.endRecording();
        }
        int l = Math.round(left);
        int t = Math.round(top);
        card.node.setPosition(l, t, l + width, t + height);
        c.drawRenderNode(card.node);
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
