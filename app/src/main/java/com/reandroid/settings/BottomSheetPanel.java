package com.reandroid.settings;

import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import androidx.customview.widget.ViewDragHelper;

import com.reandroid.wallpaper.R;

/**
 * 全屏预览下的底部设置抽屉。
 * 两态：默认态（peek，把手 + 第一行设置项）与展开态（75% 屏高完整列表）。
 * 触摸规则：
 *  - 抽屉的展开/收回严格约束在把手（handle_area）区域拖动；
 *  - 列表区域手势完全交给列表自身滚动，面板永不截胡；
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
    private View mHandle;
    private int mExpandedTop;    // 展开态时抽屉顶部 y
    private int mCollapsedTop;   // 默认态时抽屉顶部 y
    private boolean mSheetMeasured;
    private boolean mExpanded;
    private boolean mTouchOnHandle;
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
        mHandle = findViewById(R.id.handle_area);
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
            mTouchOnHandle = isTouchOnHandle(ev.getX(), ev.getY());
            mDragging = false;
            mDragHelper.shouldInterceptTouchEvent(ev); // 仅让 ViewDragHelper 跟踪指针
            if (ev.getY() < mSheet.getTop() && mExpanded) {
                // 预览区域点按：收起回默认态，事件透传给下层预览
                animateTo(mCollapsedTop);
                setExpanded(false);
            }
            return false; // 先让子视图（按钮/列表）看到按下
        }
        if (action == MotionEvent.ACTION_MOVE && mTouchOnHandle) {
            if (mDragging) {
                return true; // 已接管的手势继续归面板处理
            }
            if (Math.abs(ev.getY() - mDownY) > mTouchSlop) {
                // 把手手势：先喂给 ViewDragHelper 更新运动追踪，再显式捕获抽屉
                mDragging = true;
                mDragHelper.shouldInterceptTouchEvent(ev);
                mDragHelper.captureChildView(mSheet, ev.getPointerId(0));
                return true;
            }
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            mDragging = false;
        }
        return false; // 其余区域（列表等）一律不拦截，交给自身滚动
    }

    /** 触摸点是否落在把手区域内（面板坐标系）。 */
    private boolean isTouchOnHandle(float x, float y) {
        if (mHandle == null) return false;
        int top = 0;
        View v = mHandle;
        while (v != null && v != this) {
            top += v.getTop();
            v = (View) v.getParent();
        }
        return y >= top && y < top + mHandle.getHeight();
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
