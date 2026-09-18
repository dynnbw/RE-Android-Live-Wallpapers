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

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.XmlResourceParser;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.text.format.Time;
import android.util.Log;

import com.reandroid.wallpaper.R;
import com.reandroid.utils.AssetLoader;
import com.reandroid.gles.GLESScene;
import com.reandroid.gles.GLESWallpaper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.TimeZone;

import static org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
import static org.xmlpull.v1.XmlPullParser.START_TAG;

/**
 * 极坐标时钟的OpenGL ES 2.0渲染核心类
 * 负责时钟的GL初始化、绘制逻辑、偏好设置读取、调色板加载等
 */
public class PolarClockGL extends GLESScene {

    private static final String TAG = "PolarClockGL";

    private final Context mContext;

    // 时钟环端点圆弧的分段数（越多越平滑）
    private static final int CAP_SEGMENTS = 16;

    // 存储所有加载的调色板（key：调色板ID，value：调色板实例）
    private final HashMap<String, ClockPalette> mPalettes = new HashMap<>();
    // 当前使用的调色板
    private ClockPalette mPalette;

    // ---- 场景逻辑层（非 GL）：环的布局与角度 ----
    private final PolarClockScene mScene = new PolarClockScene();

    // 共享偏好设置实例，用于读取时钟配置
    private SharedPreferences mPluginPrefs;

    // 用于获取当前时间的日历实例
    private Time mCalendar;
    // X轴偏移量（预览模式下使用）
    private float mOffsetX = 0.0f;

    // OpenGL程序和句柄
    private int mProgram;               // GL着色器程序ID
    private int mPositionHandle;        // 顶点位置句柄
    private int mMatrixHandle;          // 矩阵变换句柄
    private int mColorHandle;           // 颜色句柄
    private boolean mGlInitialized;     // GL是否初始化完成

    // 矩阵数组（投影矩阵、模型矩阵、MVP组合矩阵）
    private final float[] mProjectionMatrix = new float[16];
    private final float[] mModelMatrix = new float[16];
    private final float[] mMvpMatrix = new float[16];
    /** colorToRgba 每帧至少调一次（清屏色），颜色 scratch 复用。 */
    private final float[] mRgbaScratch = new float[4];

    /**
     * 构造函数，初始化渲染场景的宽高
     * @param width 渲染宽度
     * @param height 渲染高度
     * @param context 应用上下文
     */
    public PolarClockGL(int width, int height, Context context) {
        super(width, height);
        mContext = context.getApplicationContext();
    }

    public void setPluginPrefs(SharedPreferences p) {
        mPluginPrefs = p;
        if (p != null) {
            reloadFromPrefs(p);
        }
    }

    /**
     * 场景创建时的初始化逻辑
     * 加载调色板、读取偏好设置、初始化日历等
     */
    @Override
    protected void onCreate() {
        if (mResources == null) return;

        mGlInitialized = false;

        // 加载XML中定义的所有调色板
        loadPalettes();
        // 如果调色板加载失败，使用默认回退调色板
        if (mPalette == null) {
            mPalette = CyclingClockPalette.getFallback();
        }

        // 初始化偏好设置对应的变量。设置一律由引擎注入（原来未注入时会去读
        // "polar_clock_settings" —— 那是插件架构之前的旧文件名，现在没有写入方）
        if (mPluginPrefs != null) {
            reloadFromPrefs(mPluginPrefs);
        }

        // 初始化日历，设置为当前时区和当前时间
        mCalendar = new Time(TimeZone.getDefault().getID());
        mCalendar.setToNow();

        // 预览模式下设置X轴偏移
        if (mPreview) {
            mOffsetX = 0.5f;
        }
    }

    /**
     * 场景启动时的逻辑
     * 完整重读一次偏好设置（监听器已由引擎统一注入，无需自行注册）
     */
    @Override
    public void start() {
        if (mPluginPrefs != null) {
            reloadFromPrefs(mPluginPrefs);
        }
    }

    /**
     * 场景停止时的逻辑
     * （偏好设置监听已由引擎统一管理，无需在此注销）
     */
    @Override
    public void stop() {
    }

    @Override
    public void release() {
        // 释放着色器程序
        if (mProgram != 0) {
            GLES20.glDeleteProgram(mProgram);
            mProgram = 0;
        }
        mGlInitialized = false;
    }

    /**
     * 渲染尺寸变化时的回调
     * @param width 新的宽度
     * @param height 新的高度
     */
    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        // 更新GL视口尺寸
        GLES20.glViewport(0, 0, width, height);
    }

    /**
     * 设置壁纸偏移量（滚动壁纸时调用）
     * @param xOffset X轴偏移比例
     * @param yOffset Y轴偏移比例
     * @param xPixels X轴偏移像素数
     * @param yPixels Y轴偏移像素数
     */
    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        // 非预览模式下更新X轴偏移
        if (!mPreview) {
            mOffsetX = xOffset;
        }
    }

    /**
     * 核心绘制方法，每帧调用
     * @param timeMs 当前时间戳（毫秒）
     */
    @Override
    public void drawFrame(long timeMs) {
        if (mPalette == null) return;

        // 如果GL未初始化，先初始化着色器程序
        if (!mGlInitialized) {
            setupProgram();
            mGlInitialized = true;
        }

        // 设置GL视口并清空画布（使用当前调色板的背景色）
        GLES20.glViewport(0, 0, mWidth, mHeight);
        int bg = mPalette.getBackgroundColor();
        float[] bgColor = colorToRgba(bg, mRgbaScratch);
        GLES20.glClearColor(bgColor[0], bgColor[1], bgColor[2], bgColor[3]);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        // 使用当前GL程序，配置渲染状态
        GLES20.glUseProgram(mProgram);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);   // 禁用深度测试
        GLES20.glDisable(GLES20.GL_CULL_FACE);    // 禁用面剔除
        GLES20.glEnable(GLES20.GL_BLEND);         // 启用混合（支持透明）
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA); // 混合模式

        // 初始化投影矩阵（正交投影）
        Matrix.orthoM(mProjectionMatrix, 0, 0f, mWidth, mHeight, 0f, -1f, 1f);
        Matrix.setIdentityM(mModelMatrix, 0);

        // 计算时钟中心坐标（考虑偏移量）
        float centerX = mWidth * (1.0f - (mPreview ? 0.5f : mOffsetX));
        float centerY = mHeight * 0.5f;
        // 模型矩阵变换：平移到中心 + 旋转90度（调整时钟起始方向）
        Matrix.translateM(mModelMatrix, 0, centerX, centerY, 0f);
        Matrix.rotateM(mModelMatrix, 0, -90.0f, 0f, 0f, 1f);
        // 宽高比适配：高度小于宽度时缩小0.9倍
        if (mHeight < mWidth) {
            Matrix.scaleM(mModelMatrix, 0, 0.9f, 0.9f, 1f);
        }

        // 计算MVP矩阵（投影矩阵 * 模型矩阵）
        Matrix.multiplyMM(mMvpMatrix, 0, mProjectionMatrix, 0, mModelMatrix, 0);
        GLES20.glUniformMatrix4fv(mMatrixHandle, 1, false, mMvpMatrix, 0);

        // 更新日历到当前时间
        mCalendar.set(timeMs);
        mCalendar.normalize(false);

        // 日期字段交给 Scene 去算角度与半径：它不能碰 android.text.format.Time，
        // 所以由这边取出字段填进去。
        PolarClockScene.DateFields d = mScene.dateFields();
        d.second = mCalendar.second;
        d.minute = mCalendar.minute;
        d.hour = mCalendar.hour;
        d.monthDay = mCalendar.monthDay;
        d.maxMonthDay = mCalendar.getActualMaximum(Time.MONTH_DAY);
        d.month = mCalendar.month;

        mScene.layout(mWidth, mHeight, timeMs);
        for (int i = 0; i < mScene.ringCount(); i++) {
            PolarClockScene.Ring ring = mScene.ringAt(i);
            setColor(colorForRing(ring));
            drawRingArc(ring.radius, ring.thickness, ring.angle);
        }
    }

    /**
     * 环 → 颜色。查询留在 GL 侧：调色板类用了 {@code android.graphics.Color} 与
     * XmlResourceParser，不是纯类，进不了 Scene。
     */
    private int colorForRing(PolarClockScene.Ring ring) {
        switch (ring.id) {
            case PolarClockScene.RING_MINUTES: return mPalette.getMinuteColor(ring.angle);
            case PolarClockScene.RING_HOURS:   return mPalette.getHourColor(ring.angle);
            case PolarClockScene.RING_DAYS:    return mPalette.getDayColor(ring.angle);
            case PolarClockScene.RING_MONTHS:  return mPalette.getMonthColor(ring.angle);
            default:                           return mPalette.getSecondColor(ring.angle);
        }
    }

    /**
     * 从偏好设置完整重读所有配置（插件注入或设置变更时调用）
     * @param prefs 共享偏好实例
     */
    private void reloadFromPrefs(SharedPreferences prefs) {
        if (prefs == null) return;
        // 如果调色板未加载，先加载
        if (mPalettes.isEmpty()) {
            loadPalettes();
        }
        // 显示秒环 / 可变线宽 / 调色板 id 由 Scene 读取
        mScene.setPluginPrefs(prefs);
        ClockPalette pal = mPalettes.get(mScene.paletteId);
        if (pal != null) {
            mPalette = pal;
        } else if (mPalette == null) {
            // 加载失败时使用回退调色板
            mPalette = CyclingClockPalette.getFallback();
        }
    }

    /**
     * 从XML资源加载所有时钟调色板
     * 解析res/xml/polar_clock_palettes.xml中的palette标签
     */
    private void loadPalettes() {
        if (mResources == null) return;
        XmlResourceParser xrp = mResources.getXml(R.xml.polar_clock_palettes);
        try {
            int what = xrp.getEventType();
            // 遍历XML直到文档结束
            while (what != END_DOCUMENT) {
                // 处理palette起始标签
                if (what == START_TAG && "palette".equals(xrp.getName())) {
                    ClockPalette pal = ClockPalette.parseXmlPaletteTag(xrp);
                    // 有效调色板加入缓存
                    if (pal != null && pal.getId() != null) {
                        mPalettes.put(pal.getId(), pal);
                    }
                }
                what = xrp.next();
            }
        } catch (Exception e) {
            Log.e(TAG, "加载调色板失败", e);
        } finally {
            // 关闭XML解析器，释放资源
            xrp.close();
        }
    }

    /**
     * 初始化GL着色器程序
     * 编译顶点着色器和片元着色器，链接成程序并获取句柄
     */
    private void setupProgram() {
        // 顶点着色器代码：处理顶点位置和矩阵变换
        String vertex = AssetLoader.readText(mContext, "polarclock/shaders/GLES/polarclock_vs.glsl");
        // 片元着色器代码：处理像素颜色
        String fragment = AssetLoader.readText(mContext, "polarclock/shaders/GLES/polarclock_fs.glsl");

        // 加载并编译着色器
        int vShader = loadShader(GLES20.GL_VERTEX_SHADER, vertex);
        int fShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragment);
        // 创建GL程序并附加着色器
        mProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(mProgram, vShader);
        GLES20.glAttachShader(mProgram, fShader);
        GLES20.glLinkProgram(mProgram);

        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(mProgram, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] == 0) {
            GLES20.glDeleteProgram(mProgram);
            mProgram = 0;
            return;
        }
        GLES20.glDeleteShader(vShader);
        GLES20.glDeleteShader(fShader);

        // 获取着色器中变量的句柄
        mPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
        mMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");
        mColorHandle = GLES20.glGetUniformLocation(mProgram, "uColor");
    }

    /**
     * 加载并编译GL着色器
     * @param type 着色器类型（GL_VERTEX_SHADER / GL_FRAGMENT_SHADER）
     * @param code 着色器源码
     * @return 编译后的着色器ID
     */
    private int loadShader(int type, String code) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, code);
        GLES20.glCompileShader(shader);
        return shader;
    }

    /**
     * 设置当前绘制的颜色
     * @param color ARGB格式的颜色值
     */
    private void setColor(int color) {
        float[] rgba = colorToRgba(color, mRgbaScratch);
        GLES20.glUniform4f(mColorHandle, rgba[0], rgba[1], rgba[2], rgba[3]);
    }

    /**
     * 将ARGB整数颜色转换为GL所需的RGBA浮点数组（0~1范围）
     * @param color ARGB格式的颜色值
     * @param out   输出缓冲，长度 4；由调用方提供以免每帧分配
     * @return 传入的 out，便于链式取值
     */
    private float[] colorToRgba(int color, float[] out) {
        out[0] = ((color >> 16) & 0xFF) / 255.0f;  // 红色通道
        out[1] = ((color >> 8) & 0xFF) / 255.0f;   // 绿色通道
        out[2] = (color & 0xFF) / 255.0f;          // 蓝色通道
        out[3] = ((color >> 24) & 0xFF) / 255.0f;  // Alpha通道
        return out;
    }

    /**
     * 绘制时钟环的圆弧
     * @param radius 环的中心半径
     * @param thickness 环的厚度
     * @param angle 环的填充角度（0~1，对应0~360度）
     */
    private void drawRingArc(float radius, float thickness, float angle) {
        if (angle <= 0.0f) return;

        // 限制角度最大值为1.0（避免超过360度）
        float sweep = Math.min(1.0f, angle);
        // 计算圆弧分段数（至少2段，保证能绘制）
        int segments = Math.max(2, (int) (360.0f * sweep));
        // 计算环的外半径和内半径
        float outer = radius + (thickness * 0.5f);
        float inner = radius - (thickness * 0.5f);

        // 计算顶点数量：(分段数+1) * 2（每个分段对应外/内两个顶点）
        int vertexCount = (segments + 1) * 2;
        FloatBuffer buffer = createBuffer(vertexCount * 2);

        // 计算圆弧的最大弧度（0~2π）
        float maxAngle = (float) (Math.PI * 2.0f * sweep);
        // 生成圆弧顶点数据
        for (int i = 0; i <= segments; i++) {
            float t = (float) i / (float) segments;
            float theta = maxAngle * t;
            float cos = (float) Math.cos(theta);
            float sin = (float) Math.sin(theta);

            // 外圆顶点
            buffer.put(outer * cos);
            buffer.put(outer * sin);
            // 内圆顶点
            buffer.put(inner * cos);
            buffer.put(inner * sin);
        }
        buffer.position(0);

        // 启用顶点属性数组，绑定顶点数据并绘制三角带
        GLES20.glEnableVertexAttribArray(mPositionHandle);
        GLES20.glVertexAttribPointer(mPositionHandle, 2, GLES20.GL_FLOAT, false, 0, buffer);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, vertexCount);

        // 如果圆弧未闭合（角度<360度），绘制端点的圆弧封帽
        if (sweep < 0.999f) {
            drawCap(radius, thickness, 0.0f);          // 起始端点
            drawCap(radius, thickness, maxAngle);      // 结束端点
        }
    }

    /**
     * 绘制时钟环端点的圆形封帽
     * @param radius 环的中心半径
     * @param thickness 环的厚度
     * @param angleRad 封帽的角度（弧度）
     */
    private void drawCap(float radius, float thickness, float angleRad) {
        // 封帽的半径（环厚度的一半）
        float capRadius = thickness * 0.5f;
        // 封帽的中心坐标
        float cx = (float) Math.cos(angleRad) * radius;
        float cy = (float) Math.sin(angleRad) * radius;

        // 封帽顶点数量：分段数 + 2（中心顶点 + 圆周顶点）
        int vertexCount = CAP_SEGMENTS + 2;
        FloatBuffer buffer = createBuffer(vertexCount * 2);
        // 中心顶点
        buffer.put(cx);
        buffer.put(cy);
        // 生成圆周顶点
        for (int i = 0; i <= CAP_SEGMENTS; i++) {
            float t = (float) i / (float) CAP_SEGMENTS;
            float theta = (float) (Math.PI * 2.0f * t);
            buffer.put(cx + (float) Math.cos(theta) * capRadius);
            buffer.put(cy + (float) Math.sin(theta) * capRadius);
        }
        buffer.position(0);

        // 绘制三角扇（TriFan）实现圆形封帽
        GLES20.glEnableVertexAttribArray(mPositionHandle);
        GLES20.glVertexAttribPointer(mPositionHandle, 2, GLES20.GL_FLOAT, false, 0, buffer);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, vertexCount);
    }

    /**
     * 创建GL可用的FloatBuffer（直接内存，避免JVM/NDK拷贝）
     * @param floatCount 浮点数据的数量
     * @return 初始化后的FloatBuffer
     */
    private FloatBuffer createBuffer(int floatCount) {
        // 分配直接内存：每个float占4字节
        ByteBuffer bb = ByteBuffer.allocateDirect(floatCount * 4);
        // 设置字节序为本地系统序（匹配GL要求）
        bb.order(ByteOrder.nativeOrder());
        return bb.asFloatBuffer();
    }
}