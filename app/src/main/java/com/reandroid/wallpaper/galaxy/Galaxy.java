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

import android.app.Activity;
import android.os.Bundle;

/**
 * 星系壁纸预览Activity
 * 用于展示壁纸效果，实际壁纸服务由GalaxyWallpaper提供
 */
public class Galaxy extends Activity {
    private GalaxyView mView; // 壁纸预览视图

    /**
     * 生命周期回调 - 创建时触发
     * @param icicle 保存的实例状态
     */
    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        // 创建预览视图并设置为ContentView
        mView = new GalaxyView(this);
        setContentView(mView);
    }

    /**
     * 生命周期回调 - 恢复时触发
     * 通知视图恢复渲染
     */
    @Override
    protected void onResume() {
        super.onResume();
        mView.resume();
    }

    /**
     * 生命周期回调 - 暂停时触发
     * 通知视图暂停渲染并退出进程
     */
    @Override
    protected void onPause() {
        super.onPause();
        mView.pause();

        // 退出进程（预览结束后清理资源）
        Runtime.getRuntime().exit(0);
    }
}