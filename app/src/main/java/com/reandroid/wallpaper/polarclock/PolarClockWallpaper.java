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

import android.content.res.XmlResourceParser;
import android.graphics.Color;

import com.reandroid.gles.GLESScene;
import com.reandroid.gles.GLESWallpaper;

/**
 * 极坐标时钟壁纸的主类
 * 继承自GLESWallpaper（OpenGL ES壁纸基类），负责创建渲染场景、定义调色板体系
 */
public class PolarClockWallpaper extends GLESWallpaper {
    // 共享偏好设置的文件名（存储时钟配置）
    public static final String SHARED_PREFS_NAME = "polar_clock_settings";

    // 偏好设置的Key常量
    static final String PREF_SHOW_SECONDS = "show_seconds";          // 是否显示秒环
    static final String PREF_VARIABLE_LINE_WIDTH = "variable_line_width"; // 是否启用可变线宽
    static final String PREF_PALETTE = "palette";                    // 当前使用的调色板ID

    /**
     * 创建OpenGL渲染场景
     * @param width 渲染宽度
     * @param height 渲染高度
     * @return 极坐标时钟的GL渲染场景实例
     */
    @Override
    protected GLESScene createScene(int width, int height) {
        return new PolarClockGL(width, height, this);
    }

    /**
     * 时钟调色板的抽象基类
     * 定义时钟各部分（秒、分、时、日、月、背景）的颜色获取接口
     */
    static abstract class ClockPalette {
        /**
         * 解析XML中的palette标签，创建对应的调色板实例
         * @param xrp XML资源解析器
         * @return 解析后的ClockPalette实例（Cycling/Fixed类型）
         */
        public static ClockPalette parseXmlPaletteTag(XmlResourceParser xrp) {
            // 根据palette标签的"kind"属性区分类型：cycling（渐变）/fixed（固定色）
            String kind = xrp.getAttributeValue(null, "kind");
            if ("cycling".equals(kind)) {
                return CyclingClockPalette.parseXmlPaletteTag(xrp);
            } else {
                return FixedClockPalette.parseXmlPaletteTag(xrp);
            }
        }

        /**
         * 获取时钟背景色
         * @return ARGB格式的颜色值
         */
        public abstract int getBackgroundColor();

        /**
         * 获取秒环颜色（角度范围0.0~1.0，1.0需兼容）
         * @param forAngle 秒的进度（0=0秒，1=60秒）
         * @return ARGB格式的颜色值
         */
        public abstract int getSecondColor(float forAngle);

        /**
         * 获取分环颜色
         * @param forAngle 分的进度（0=0分，1=60分）
         * @return ARGB格式的颜色值
         */
        public abstract int getMinuteColor(float forAngle);

        /**
         * 获取小时环颜色
         * @param forAngle 小时的进度（0=0时，1=24时）
         * @return ARGB格式的颜色值
         */
        public abstract int getHourColor(float forAngle);

        /**
         * 获取日期环颜色
         * @param forAngle 日期的进度（0=1号，1=月末）
         * @return ARGB格式的颜色值
         */
        public abstract int getDayColor(float forAngle);

        /**
         * 获取月份环颜色
         * @param forAngle 月份的进度（0=1月，1=12月）
         * @return ARGB格式的颜色值
         */
        public abstract int getMonthColor(float forAngle);

        /**
         * 获取调色板唯一ID
         * @return 调色板ID
         */
        public abstract String getId();
    }

    /**
     * 固定色调色板（各部分颜色固定，不随角度变化）
     */
    static class FixedClockPalette extends ClockPalette {
        protected String mId;               // 调色板ID
        protected int mBackgroundColor;     // 背景色
        protected int mSecondColor;         // 秒环颜色
        protected int mMinuteColor;         // 分环颜色
        protected int mHourColor;           // 小时环颜色
        protected int mDayColor;            // 日期环颜色
        protected int mMonthColor;          // 月份环颜色

        // 静态回退调色板（加载失败时使用）
        private static FixedClockPalette sFallbackPalette = null;

        /**
         * 获取默认回退调色板（白底黑字）
         * @return 静态的回退调色板实例
         */
        public static FixedClockPalette getFallback() {
            if (sFallbackPalette == null) {
                sFallbackPalette = new FixedClockPalette();
                sFallbackPalette.mId = "default";
                sFallbackPalette.mBackgroundColor = Color.WHITE;
                // 所有时钟环都使用黑色
                sFallbackPalette.mSecondColor =
                    sFallbackPalette.mMinuteColor =
                    sFallbackPalette.mHourColor =
                    sFallbackPalette.mDayColor =
                    sFallbackPalette.mMonthColor =
                    Color.BLACK;
            }
            return sFallbackPalette;
        }

        /**
         * 私有构造函数（仅内部创建）
         */
        private FixedClockPalette() { }

        /**
         * 解析XML中的fixed类型调色板
         * @param xrp XML资源解析器
         * @return 解析后的FixedClockPalette实例（ID为空则返回null）
         */
        public static ClockPalette parseXmlPaletteTag(XmlResourceParser xrp) {
            final FixedClockPalette pal = new FixedClockPalette();
            // 读取调色板ID
            pal.mId = xrp.getAttributeValue(null, "id");
            String val;
            // 读取背景色（支持#RRGGBB/#AARRGGBB格式）
            if ((val = xrp.getAttributeValue(null, "background")) != null)
                pal.mBackgroundColor = Color.parseColor(val);
            // 读取秒环颜色
            if ((val = xrp.getAttributeValue(null, "second")) != null)
                pal.mSecondColor = Color.parseColor(val);
            // 读取分环颜色
            if ((val = xrp.getAttributeValue(null, "minute")) != null)
                pal.mMinuteColor = Color.parseColor(val);
            // 读取小时环颜色
            if ((val = xrp.getAttributeValue(null, "hour")) != null)
                pal.mHourColor = Color.parseColor(val);
            // 读取日期环颜色
            if ((val = xrp.getAttributeValue(null, "day")) != null)
                pal.mDayColor = Color.parseColor(val);
            // 读取月份环颜色
            if ((val = xrp.getAttributeValue(null, "month")) != null)
                pal.mMonthColor = Color.parseColor(val);
            // ID为空则返回null（无效调色板）
            return (pal.mId == null) ? null : pal;
        }

        @Override
        public int getBackgroundColor() {
            return mBackgroundColor;
        }

        @Override
        public int getSecondColor(float forAngle) {
            return mSecondColor;
        }

        @Override
        public int getMinuteColor(float forAngle) {
            return mMinuteColor;
        }

        @Override
        public int getHourColor(float forAngle) {
            return mHourColor;
        }

        @Override
        public int getDayColor(float forAngle) {
            return mDayColor;
        }

        @Override
        public int getMonthColor(float forAngle) {
            return mMonthColor;
        }

        @Override
        public String getId() {
            return mId;
        }
    }

    /**
     * 渐变调色板（颜色随角度渐变，基于HSV色彩空间）
     */
    static class CyclingClockPalette extends ClockPalette {
        protected String mId;               // 调色板ID
        protected int mBackgroundColor;     // 背景色
        protected float mSaturation;        // 饱和度（0.0~1.0）
        protected float mBrightness;        // 亮度（0.0~1.0）

        // 颜色缓存数量（预计算720种颜色，避免实时计算）
        private static final int COLORS_CACHE_COUNT = 720;
        // 预计算的渐变颜色缓存
        private final int[] mColors = new int[COLORS_CACHE_COUNT];

        // 静态回退渐变调色板
        private static CyclingClockPalette sFallbackPalette = null;

        /**
         * 获取默认回退渐变调色板（白底、高饱和高亮度）
         * @return 静态的回退渐变调色板实例
         */
        public static CyclingClockPalette getFallback() {
            if (sFallbackPalette == null) {
                sFallbackPalette = new CyclingClockPalette();
                sFallbackPalette.mId = "default_c";
                sFallbackPalette.mBackgroundColor = Color.WHITE;
                sFallbackPalette.mSaturation = 0.8f;   // 饱和度80%
                sFallbackPalette.mBrightness = 0.9f;   // 亮度90%
                // 预计算渐变颜色缓存
                sFallbackPalette.computeIntermediateColors();
            }
            return sFallbackPalette;
        }

        /**
         * 私有构造函数（仅内部创建）
         */
        private CyclingClockPalette() { }

        /**
         * 预计算渐变颜色缓存
         * 基于HSV生成720种颜色，覆盖0~360度色相
         */
        private void computeIntermediateColors() {
            final int[] colors = mColors;
            final int count = colors.length;
            float invCount = 1.0f / (float) COLORS_CACHE_COUNT;
            float[] hsb = new float[3]; // HSV数组：[色相, 饱和度, 亮度]
            for (int i = 0; i < count; i++) {
                // 色相：0~1 → 0~360度
                hsb[0] = ((float) i * invCount) * 360.0f;
                hsb[1] = mSaturation;   // 固定饱和度
                hsb[2] = mBrightness;   // 固定亮度
                // 转换HSV为ARGB颜色
                colors[i] = Color.HSVToColor(hsb);
            }
        }

        /**
         * 解析XML中的cycling类型调色板
         * @param xrp XML资源解析器
         * @return 解析后的CyclingClockPalette实例（ID为空则返回null）
         */
        public static ClockPalette parseXmlPaletteTag(XmlResourceParser xrp) {
            final CyclingClockPalette pal = new CyclingClockPalette();
            // 读取调色板ID
            pal.mId = xrp.getAttributeValue(null, "id");
            String val;
            // 读取背景色
            if ((val = xrp.getAttributeValue(null, "background")) != null)
                pal.mBackgroundColor = Color.parseColor(val);
            // 读取饱和度（浮点值）
            if ((val = xrp.getAttributeValue(null, "saturation")) != null)
                pal.mSaturation = Float.parseFloat(val);
            // 读取亮度（浮点值）
            if ((val = xrp.getAttributeValue(null, "brightness")) != null)
                pal.mBrightness = Float.parseFloat(val);
            // 预计算渐变颜色
            pal.computeIntermediateColors();
            // ID为空则返回null（无效调色板）
            return (pal.mId == null) ? null : pal;
        }

        @Override
        public int getBackgroundColor() {
            return mBackgroundColor;
        }

        @Override
        public int getSecondColor(float forAngle) {
            return getCyclingColor(forAngle);
        }

        @Override
        public int getMinuteColor(float forAngle) {
            return getCyclingColor(forAngle);
        }

        @Override
        public int getHourColor(float forAngle) {
            return getCyclingColor(forAngle);
        }

        @Override
        public int getDayColor(float forAngle) {
            return getCyclingColor(forAngle);
        }

        @Override
        public int getMonthColor(float forAngle) {
            return getCyclingColor(forAngle);
        }

        @Override
        public String getId() {
            return mId;
        }

        /**
         * 根据角度获取渐变颜色（从预缓存中读取）
         * @param angle 角度（0.0~1.0）
         * @return ARGB格式的渐变颜色值
         */
        private int getCyclingColor(float angle) {
            // 角度边界处理（确保在0~1范围内）
            if (angle >= 1.0f || angle < 0.0f) angle = 0.0f;
            // 计算颜色缓存索引
            int idx = (int) (angle * COLORS_CACHE_COUNT);
            // 索引边界保护
            if (idx < 0) idx = 0;
            if (idx >= COLORS_CACHE_COUNT) idx = COLORS_CACHE_COUNT - 1;
            // 返回预计算的颜色
            return mColors[idx];
        }
    }
}