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

import com.reandroid.wallpaper.gles.GLESWallpaper;
import com.reandroid.wallpaper.gles.GLESScene;

/**
 * 星系壁纸入口类
 * 继承GLESWallpaper，创建GalaxyGL场景用于渲染
 */
public class GalaxyWallpaper extends GLESWallpaper {
    /**
     * 创建GL渲染场景
     * @param width  屏幕宽度
     * @param height 屏幕高度
     * @return GalaxyGL渲染场景
     */
    @Override
    protected GLESScene createScene(int width, int height) {
        return new GalaxyGL(width, height, this);
    }
}