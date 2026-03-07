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

package com.reandroid.wallpaper.galaxy;

import android.content.Context;
import android.view.View;

/**
 * 星系壁纸预览视图
 * 原RenderScript实现已移除，改为使用GLESWallpaper的GL渲染
 */
class GalaxyView extends View {
    /**
     * 构造函数
     * @param context 上下文对象
     */
    public GalaxyView(Context context) {
        super(context);
        setFocusable(true);         // 设置可聚焦
        setFocusableInTouchMode(true); // 设置触摸可聚焦
    }

    /**
     * 初始化方法（占位）
     * 原RenderScript相关逻辑已移除，GL渲染由GalaxyGL处理
     * @param rs RenderScript对象（已弃用）
     * @param scene 渲染场景（已弃用）
     */
    void init(Object rs, Object scene) {
        // 占位：RenderScript视图已移除 — GLES使用GLESWallpaper替代
    }

    /**
     * 恢复渲染（占位）
     * 由Activity的onResume调用，实际GL渲染恢复由GLESWallpaper管理
     */
    public void resume() {
        // 占位：resume()由引擎生命周期调用
    }

    /**
     * 暂停渲染（占位）
     * 由Activity的onPause调用，实际GL渲染暂停由GLESWallpaper管理
     */
    public void pause() {
        // 占位：pause()由引擎生命周期调用
    }
}