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

import android.app.Activity;
import android.os.Bundle;

/**
 * 落叶壁纸主Activity
 * 作为FallView的容器，管理视图的生命周期
 */
public class Fall extends Activity {
    // 自定义的落叶视图实例
    private FallView mView;

    /**
     * Activity创建时调用
     * 初始化FallView并设置为ContentView
     * @param icicle 保存的实例状态
     */
    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        // 创建FallView实例
        mView = new FallView(this);
        // 设置当前Activity的布局为FallView
        setContentView(mView);
    }

    /**
     * Activity恢复时调用
     * 通知FallView恢复动画/渲染
     */
    @Override
    protected void onResume() {
        super.onResume();
        mView.resume();
    }

    /**
     * Activity暂停时调用
     * 通知FallView暂停动画/渲染，并退出进程
     */
    @Override
    protected void onPause() {
        super.onPause();
        mView.pause();

        // 退出当前进程
        Runtime.getRuntime().exit(0);
    }
}