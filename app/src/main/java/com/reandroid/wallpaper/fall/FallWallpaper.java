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

import com.reandroid.gles.GLESWallpaper;
import com.reandroid.gles.GLESScene;

/**
 * 落叶壁纸的壁纸服务实现类
 * 继承自GLESWallpaper（OpenGL ES壁纸基类），负责创建OpenGL渲染场景
 */
public class FallWallpaper extends GLESWallpaper {
    /**
     * 创建OpenGL渲染场景
     * @param width 壁纸显示宽度
     * @param height 壁纸显示高度
     * @return FallGL 落叶壁纸的OpenGL ES 2.0渲染实现
     */
    @Override
    protected GLESScene createScene(int width, int height) {
        return new FallGL(this, width, height);
    }
}