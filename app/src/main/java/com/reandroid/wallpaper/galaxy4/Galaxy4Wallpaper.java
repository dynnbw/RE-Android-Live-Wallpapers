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

package com.reandroid.wallpaper.galaxy4;

import com.reandroid.wallpaper.gles.GLESScene;
import com.reandroid.wallpaper.gles.GLESWallpaper;

/**
 * Galaxy4动态壁纸的主类
 * 继承自GLESWallpaper（OpenGL ES壁纸基类），负责创建Galaxy4壁纸的OpenGL场景
 */
public class Galaxy4Wallpaper extends GLESWallpaper {
    /**
     * 创建壁纸的OpenGL渲染场景
     * @param width 屏幕宽度
     * @param height 屏幕高度
     * @return 初始化后的Galaxy4GL场景实例，用于渲染Galaxy4壁纸
     */
    @Override
    protected GLESScene createScene(int width, int height) {
        return new Galaxy4GL(width, height, this);
    }
}