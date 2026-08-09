package com.reandroid.settings;

import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import androidx.customview.widget.ViewDragHelper;

import com.reandroid.wallpaper.R;

/**
 * 全屏预览下的底部设置抽屉。
 * 两态：默认态（peek，把手 + 第一行设置项）与展开态（75% 屏高完整列表）。
 * 触摸规则：
 *  - 默认态：抽屉区域内手势超过 slop 即拖动抽屉（列表不滚动）；
 *  - 展开态：列表可内部滚动，滚到顶部后下拉转交抽屉；
 *  - 预览区域（抽屉上方）点按：展开时收起回默认态，事件透传给下层预览。
 */
public class BottomSheetPanel extends FrameLayout {

    private static final int PEEK_HEIGHT_DP = 100;
    private static final float EXPANDED_HEIGHT_RATIO = 0.75f;
    private static final float FLING_VELOCITY = 800f;
    private static final long ANIM_MS = 200L;

    private final ViewDragHelper mDragHelper;
    private final int mTouchSlop;
    private View mSheet;
    private int mExpandedTop;    // 展开态时抽屉顶部 y
    private int mCollapsedTop;   // 默认态时抽屉顶部 y
    private boolean mSheetMeasured;
    private boolean mExpanded;
    private boolean mTouchInSheet;
    private boolean mDragging;
    private float mDownY;

    public BottomSheetPanel(Context context, AttributeSet attrs) {
        super(context, attrs);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mDragHelper = ViewDragHelper.create(this, 1f, new DragCallback());
    }

    public boolean isExpanded() {
        return mExpanded;
    }

    /**
     * 展开时收起到默认态；已默认态返回 false（供 Back 键判断是否消费）。
     */
    public boolean collapseToPeek() {
        if (!mExpanded) return false;
        animateTo(mCollapsedTop);
        setExpanded(false);
        return true;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mSheet = findViewById(R.id.sheet_content);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (mSheetMeasured) return;
        mSheetMeasured = true;
        int panelH = getHeight();
        if (panelH <= 0) return;
        int peekH = Math.round(PEEK_HEIGHT_DP * getResources().getDisplayMetrics().density);
        mExpandedTop = Math.round(panelH * (1f - EXPANDED_HEIGHT_RATIO));
        mCollapsedTop = Math.max(mExpandedTop, panelH - peekH);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mSheet.getLayoutParams();
        lp.height = panelH - mExpandedTop;
        lp.topMargin = mCollapsedTop;
        mSheet.setLayoutParams(lp);
    }

    private void setExpanded(boolean expanded) {
        if (mExpanded == expanded) return;
        mExpanded = expanded;
    }

    private void animateTo(int targetTop) {
        final FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mSheet.getLayoutParams();
        ValueAnimator animator = ValueAnimator.ofInt(lp.topMargin, targetTop);
        animator.setDuration(ANIM_MS);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) mSheet.getLayoutParams();
            p.topMargin = (Integer) a.getAnimatedValue();
            mSheet.setLayoutParams(p);
        });
        animator.start();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            mDownY = ev.getY();
            mTouchInSheet = ev.getY() >= mSheet.getTop();
            mDragging = false;
            mDragHelper.shouldInterceptTouchEvent(ev); // 仅让 ViewDragHelper 跟踪指针
            if (!mTouchInSheet && mExpanded) {
                // 预览区域点按：收起回默认态，事件透传给下层预览
                animateTo(mCollapsedTop);
                setExpanded(false);
            }
            return false; // 先让子视图（按钮/列表）看到按下
        }
        if (action == MotionEvent.ACTION_MOVE && mTouchInSheet) {
            if (mDragging) {
                return true; // 已接管的手势继续归面板处理
            }
            float dy = ev.getY() - mDownY;
            if (Math.abs(dy) <= mTouchSlop) {
                return false;
            }
            boolean dragSheet = !mExpanded || !canScrollInDirection(ev.getX(), ev.getY(), dy);
            if (dragSheet) {
                // 手动接管拖拽：先喂给 ViewDragHelper 更新运动追踪，再显式捕获抽屉
                mDragging = true;
                mDragHelper.shouldInterceptTouchEvent(ev);
                mDragHelper.captureChildView(mSheet, ev.getPointerId(0));
                return true;
            }
            return false; // 列表自身可沿该方向滚动，交给列表
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            mDragging = false;
        }
        return false;
    }

    /**
     * 从触摸点视图向上查找是否有可沿 dy 方向滚动的视图。
     * dy > 0（手指下滑）→ 朝列表起始方向滚动；dy < 0（手指上滑）→ 朝列表末尾方向。
     */
    private boolean canScrollInDirection(float x, float y, float dy) {
        int direction = dy > 0 ? 1 : -1;
        View v = findViewUnder(x, y);
        while (v != null && v != this) {
            if (v.canScrollVertically(direction)) {
                return true;
            }
            v = (View) v.getParent();
        }
        return false;
    }

    /** 深度优先命中测试，返回触摸点最深的子视图。 */
    private View findViewUnder(float x, float y) {
        return findDeepestChild(this, x, y);
    }

    private View findDeepestChild(ViewGroup parent, float x, float y) {
        for (int i = parent.getChildCount() - 1; i >= 0; i--) {
            View child = parent.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            if (x >= child.getLeft() && x < child.getRight()
                    && y >= child.getTop() && y < child.getBottom()) {
                if (child instanceof ViewGroup
                        && ((ViewGroup) child).getChildCount() > 0) {
                    View deep = findDeepestChild((ViewGroup) child,
                            x - child.getLeft(), y - child.getTop());
                    if (deep != null) return deep;
                }
                return child;
            }
        }
        return null;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN && ev.getY() < mSheet.getTop()) {
            return false; // 预览区域不消费
        }
        mDragHelper.processTouchEvent(ev);
        return true;
    }

    @Override
    public void computeScroll() {
        if (mDragHelper.continueSettling(true)) {
            postInvalidateOnAnimation();
        }
    }

    private class DragCallback extends ViewDragHelper.Callback {

        @Override
        public boolean tryCaptureView(View child, int pointerId) {
            return child == mSheet;
        }

        @Override
        public int clampViewPositionVertical(View child, int top, int dy) {
            return Math.max(mExpandedTop, Math.min(mCollapsedTop, top));
        }

        @Override
        public int getViewVerticalDragRange(View child) {
            return mCollapsedTop - mExpandedTop;
        }

        @Override
        public void onViewPositionChanged(View child, int left, int top, int dx, int dy) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) child.getLayoutParams();
            lp.topMargin = top;
            child.setLayoutParams(lp);
        }

        @Override
        public void onViewReleased(View releasedChild, float xvel, float yvel) {
            int settleTop;
            if (yvel > FLING_VELOCITY) {
                settleTop = mCollapsedTop;
            } else if (yvel < -FLING_VELOCITY) {
                settleTop = mExpandedTop;
            } else {
                int current = releasedChild.getTop();
                settleTop = Math.abs(current - mExpandedTop) < Math.abs(current - mCollapsedTop)
                        ? mExpandedTop : mCollapsedTop;
            }
            setExpanded(settleTop == mExpandedTop);
            if (mDragHelper.settleCapturedViewAt(releasedChild.getLeft(), settleTop)) {
                postInvalidateOnAnimation();
            }
        }
    }
}
