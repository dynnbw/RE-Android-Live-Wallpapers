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

package com.reandroid.wallpaper.polarclock;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.reandroid.wallpaper.R;
import com.reandroid.settings.PolarClockSettingsFragment;

/**
 * 极坐标时钟设置页面Activity
 * 基于现代PreferenceFragment的设置界面，适配Android 5.0及以上系统
 */
public class PolarClockSettings extends AppCompatActivity {
    /**
     * 初始化Activity，设置布局并加载设置Fragment
     * @param savedInstanceState 保存的实例状态，用于恢复页面状态
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 设置当前Activity的布局文件
        setContentView(R.layout.activity_settings);

        // 首次创建时，将设置Fragment替换到布局容器中
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.settings_container, new PolarClockSettingsFragment())
                    .commit();
        }

        // 配置ActionBar：显示返回按钮并设置标题
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.clock_settings);
        }
    }

    /**
     * 处理ActionBar返回按钮的导航逻辑
     * @return 导航是否成功
     */
    @Override
    public boolean onSupportNavigateUp() {
        // 触发返回键逻辑
        onBackPressed();
        return true;
    }
}