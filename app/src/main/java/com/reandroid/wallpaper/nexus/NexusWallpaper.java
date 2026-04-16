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

package com.reandroid.wallpaper.nexus;

import com.reandroid.gles.GLESWallpaper;
import com.reandroid.gles.GLESScene;

/**
 * Nexus动态壁纸主类
 * 基于GLESWallpaper实现，创建NexusGL场景完成OpenGL ES 2.0渲染
 * 100%还原RenderScript版本（nexus.rs）的视觉效果
 */
public class NexusWallpaper extends GLESWallpaper {
    /**
     * 创建壁纸渲染场景
     * @param width 屏幕宽度
     * @param height 屏幕高度
     * @return NexusGL场景实例（负责具体的OpenGL渲染逻辑）
     */
    @Override
    protected GLESScene createScene(int width, int height) {
        return new NexusGL(width, height);
    }
}