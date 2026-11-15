/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.reandroid.wallpaper.fall;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;

/**
 * 落叶壁纸的自定义View
 * 处理触摸事件，并转发给FallGL渲染器实现交互
 */
class FallView extends View {
    // 渲染器引用（运行时实际为FallGL实例）
    private Object mRender;
    /** 多指分别计距、各自触发；见 {@link FallTouchTracker}。 */
    private final FallTouchTracker mTouchTracker = new FallTouchTracker();

    /**
     * 构造方法
     * @param context 上下文
     */
    public FallView(Context context) {
        super(context);
        // 设置可获取焦点
        setFocusable(true);
        // 设置触摸模式下可获取焦点
        setFocusableInTouchMode(true);
    }

    /**
     * 初始化渲染器引用
     * @param rs 预留参数（RenderScript相关，未使用）
     * @param scene 渲染场景实例（实际为FallGL）
     */
    void init(Object rs, Object scene) {
        // 保存通用类型的渲染器引用，运行时为FallGL实例
        mRender = scene;
    }

    /**
     * 恢复渲染/动画（生命周期方法）
     * 占位方法，由Activity的onResume调用
     */
    public void resume() {
        // 占位：由引擎生命周期调用的恢复方法
    }

    /**
     * 暂停渲染/动画（生命周期方法）
     * 占位方法，由Activity的onPause调用
     */
    public void pause() {
        // 占位：由引擎生命周期调用的暂停方法
    }

    /**
     * 处理触摸事件
     * 当触摸按下时，转发坐标给FallGL的addDrop方法生成水滴/涟漪
     * @param event 触摸事件
     * @return boolean 消费触摸事件返回true
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int index = event.getActionIndex();
                mTouchTracker.down(
                        event.getPointerId(index),
                        event.getX(index),
                        event.getY(index),
                        event.getEventTime());
                triggerRipple(event.getX(index), event.getY(index));
                break;
            }
            case MotionEvent.ACTION_MOVE:
                if (!isSwipeRippleEnabled()) {
                    break;
                }
                for (int i = 0; i < event.getPointerCount(); i++) {
                    float power = mTouchTracker.move(
                            event.getPointerId(i),
                            event.getX(i),
                            event.getY(i),
                            event.getEventTime());
                    if (power >= 0f) {
                        triggerRipple(event.getX(i), event.getY(i));
                    }
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                mTouchTracker.up(event.getPointerId(event.getActionIndex()));
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mTouchTracker.reset();
                break;
            default:
                break;
        }
        // 返回true表示消费了该触摸事件
        return true;
    }

    /**
     * 滑动水波纹开关（滑动每 42px 触发一次水波纹）
     * 委托给 FallGL 查询，FallGL 不可用时保持默认开启
     */
    private boolean isSwipeRippleEnabled() {
        if (mRender instanceof FallGL) {
            return ((FallGL) mRender).isSwipeRippleEnabled();
        }
        return true;
    }

    private void triggerRipple(float x, float y) {
        if (mRender instanceof FallGL) {
            ((FallGL) mRender).addDrop((int) x, (int) y);
        }
    }
}
