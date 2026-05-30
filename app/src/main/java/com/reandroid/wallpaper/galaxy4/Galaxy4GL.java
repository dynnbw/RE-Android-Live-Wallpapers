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

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.util.Log;

import com.reandroid.gles.AssetLoader;
import com.reandroid.gles.GLESScene;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Galaxy4动态壁纸的OpenGL ES 2.0渲染核心类
 * 完全从RenderScript移植到OpenGL ES 2.0，100%还原原版Galaxy4动态壁纸的视觉效果
 * 负责壁纸的OpenGL初始化、着色器编译、纹理加载、粒子系统（星星/星云）的绘制与动画
 */
public class Galaxy4GL extends GLESScene {
    private static final String TAG = "Galaxy4GL";
    private boolean mGLInitialized = false;
    private int mBgProgram;
    private int mCloudProgram;
    private int mBgStarProgram;
    private int mStaticStarProgram;
    private int mTexBg;
    private int mTexCloud;
    private int mTexStaticStar;
    private int mTexStaticStar2;
    private FloatBuffer mSpaceCloudBuffer;
    private FloatBuffer mBgStarBuffer;
    private FloatBuffer mStaticStarBuffer;
    private final Context mContext;
    private final Galaxy4Scene mScene;
    
    /**
     * 构造方法：初始化Galaxy4GL渲染器
     * @param width 屏幕宽度
     * @param height 屏幕高度
     * @param context 上下文对象，用于获取资源和SharedPreferences
     */
    public Galaxy4GL(int width, int height, Context context) {
        super(width, height);
        mContext = context;
        mScene = new Galaxy4Scene(width, height, context);
    }
    
    /**
     * 生命周期方法：创建时回调（GL线程）
     * 标记OpenGL初始化待执行
     */
    @Override
    protected void onCreate() {
        Log.d(TAG, "onCreate - 将在GL线程初始化");
    }

    @Override
    public void release() {
        // 释放纹理资源
        int[] tex = new int[] { mTexBg, mTexCloud, mTexStaticStar, mTexStaticStar2 };
        GLES20.glDeleteTextures(tex.length, tex, 0);
        mTexBg = 0;
        mTexCloud = 0;
        mTexStaticStar = 0;
        mTexStaticStar2 = 0;

        // 释放着色器程序
        if (mBgProgram != 0) {
            GLES20.glDeleteProgram(mBgProgram);
            mBgProgram = 0;
        }
        if (mCloudProgram != 0) {
            GLES20.glDeleteProgram(mCloudProgram);
            mCloudProgram = 0;
        }
        if (mBgStarProgram != 0) {
            GLES20.glDeleteProgram(mBgStarProgram);
            mBgStarProgram = 0;
        }
        if (mStaticStarProgram != 0) {
            GLES20.glDeleteProgram(mStaticStarProgram);
            mStaticStarProgram = 0;
        }

        mSpaceCloudBuffer = null;
        mBgStarBuffer = null;
        mStaticStarBuffer = null;
        mGLInitialized = false;
    }
    
    public void setBgStarCount(int count) {
        mScene.setBgStarCount(count);
    }

    public void setSpaceCloudCount(int count) {
        mScene.setSpaceCloudCount(count);
    }
    
    /**
     * 初始化OpenGL环境
     * 包括：清屏颜色、混合模式、着色器程序创建、纹理加载、粒子位置初始化
     * 仅在未初始化且资源有效时执行
     */
    private void initGL() {
        if (mGLInitialized || mResources == null) return;
        
        Log.d(TAG, "initGL 开始执行");
        mGLInitialized = true;
        
        // 设置清屏颜色（黑色，半透明）
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.5f);
        // 禁用深度测试（2D壁纸不需要）
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        // 启用混合模式（实现透明效果）
        GLES20.glEnable(GLES20.GL_BLEND);
        // 设置混合因子：源Alpha * 源颜色 + 1 * 目标颜色（加法混合，适合粒子发光效果）
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE);
        
        // 创建所有着色器程序
        createPrograms();
        loadTextures();
        
        Log.d(TAG, "initGL 执行完成");
    }
    
    /**
     * 创建所有OpenGL着色器程序
     * 包括：背景、星云、背景星星、静态星星的顶点/片段着色器
     */
    private void createPrograms() {
        // 1. 背景着色器程序：绘制全屏纹理四边形
        mBgProgram = createShaderProgram(
            AssetLoader.readText(mContext, "galaxy4/shaders/GLES/galaxy4_bg_vs.glsl"),
            AssetLoader.readText(mContext, "galaxy4/shaders/GLES/galaxy4_bg_fs.glsl")
        );
        
        // 2. 星云着色器程序：绘制带旋转的点精灵粒子
        mCloudProgram = createShaderProgram(
            AssetLoader.readText(mContext, "galaxy4/shaders/GLES/galaxy4_cloud_vs.glsl"),
            AssetLoader.readText(mContext, "galaxy4/shaders/GLES/galaxy4_cloud_fs.glsl")
        );
        
        // 3. 背景星星着色器程序：绘制固定大小的白色点精灵
        mBgStarProgram = createShaderProgram(
            AssetLoader.readText(mContext, "galaxy4/shaders/GLES/galaxy4_bg_star_vs.glsl"),
            AssetLoader.readText(mContext, "galaxy4/shaders/GLES/galaxy4_bg_star_fs.glsl")
        );
        
        // 4. 静态星星着色器程序：双纹理混合实现脉冲效果
        mStaticStarProgram = createShaderProgram(
            AssetLoader.readText(mContext, "galaxy4/shaders/GLES/galaxy4_static_star_vs.glsl"),
            AssetLoader.readText(mContext, "galaxy4/shaders/GLES/galaxy4_static_star_fs.glsl")
        );
        
        Log.d(TAG, "着色器程序创建完成");
    }
    
    /**
     * 创建并链接OpenGL着色器程序
     * @param vertexSource 顶点着色器源码
     * @param fragmentSource 片段着色器源码
     * @return 链接成功的着色器程序句柄（失败返回0）
     */
    private int createShaderProgram(String vertexSource, String fragmentSource) {
        // 编译顶点着色器
        int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        // 编译片段着色器
        int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        
        // 创建程序对象并附加着色器
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        // 链接程序
        GLES20.glLinkProgram(program);
        
        // 检查链接状态
        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] == 0) {
            Log.e(TAG, "程序链接错误: " + GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            return 0;
        }
        
        // 链接成功后删除着色器（已附加到程序，无需保留）
        GLES20.glDeleteShader(vertexShader);
        GLES20.glDeleteShader(fragmentShader);
        
        return program;
    }
    
    /**
     * 编译单个OpenGL着色器
     * @param type 着色器类型（GL_VERTEX_SHADER/GL_FRAGMENT_SHADER）
     * @param source 着色器源码
     * @return 编译成功的着色器句柄（失败返回0）
     */
    private int compileShader(int type, String source) {
        // 创建着色器对象
        int shader = GLES20.glCreateShader(type);
        // 设置着色器源码
        GLES20.glShaderSource(shader, source);
        // 编译着色器
        GLES20.glCompileShader(shader);
        
        // 检查编译状态
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e(TAG, "着色器编译错误: " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }
        
        return shader;
    }
    
    /**
     * 加载壁纸所需的所有纹理资源
     * 包括：背景、星云、静态星星1、静态星星2
     */
    private void loadTextures() {
        // 加载各纹理
        mTexBg = loadTexture("galaxy4/drawable/galaxy4_bg.png");
        mTexCloud = loadTexture("galaxy4/drawable/galaxy4_cloud.png");
        mTexStaticStar = loadTexture("galaxy4/drawable/galaxy4_staticstar.png");
        mTexStaticStar2 = loadTexture("galaxy4/drawable/galaxy4_staticstar2.png");

        Log.d(TAG, "纹理加载完成");
    }
    
    /**
     * 从assets路径加载OpenGL纹理
     * @param assetPath 纹理在assets中的路径（如"galaxy4/drawable/galaxy4_bg.png"）
     * @return 加载成功的纹理句柄
     */
    private int loadTexture(String assetPath) {
        // 从assets解码位图
        Bitmap bitmap = AssetLoader.decodeBitmap(mContext, assetPath);
        
        // 生成纹理句柄
        int[] textureHandle = new int[1];
        GLES20.glGenTextures(1, textureHandle, 0);
        
        // 绑定纹理并设置参数
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle[0]);
        // 缩小过滤：线性过滤
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        // 放大过滤：线性过滤
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        // S轴纹理环绕：夹紧到边缘（避免纹理重复）
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        // T轴纹理环绕：夹紧到边缘
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        
        // 将位图数据上传到OpenGL纹理
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        
        // 释放位图内存（已上传到GPU，无需保留）
        bitmap.recycle();
        
        return textureHandle[0];
    }
    
    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        mScene.resize(width, height);
    }
    
    /**
     * 绘制每一帧（GL线程回调）
     * 执行顺序：清屏 → 绘制背景 → 更新并绘制星云 → 更新并绘制背景星星 → 绘制静态星星
     * @param timeMs 时间戳（毫秒），用于动画控制
     */
    @Override
    public void drawFrame(long timeMs) {
        mScene.update(timeMs);
        Galaxy4Scene.SceneData sceneData = mScene.getSceneData();

        if (!mGLInitialized) {
            initGL();
        }

        syncParticleBuffers(sceneData);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        drawBackground();
        drawSpaceClouds(sceneData);
        drawBgStars(sceneData);
        drawStaticStars(sceneData);
    }

    private void syncParticleBuffers(Galaxy4Scene.SceneData sceneData) {
        boolean rebuildBuffers = mScene.consumeParticleBufferRebuildRequested()
                || mSpaceCloudBuffer == null
                || mBgStarBuffer == null
                || mStaticStarBuffer == null
                || mSpaceCloudBuffer.capacity() != sceneData.getSpaceClouds().length
                || mBgStarBuffer.capacity() != sceneData.getBgStars().length
                || mStaticStarBuffer.capacity() != sceneData.getStaticStars().length;

        if (rebuildBuffers) {
            mSpaceCloudBuffer = createFloatBuffer(sceneData.getSpaceClouds());
            mBgStarBuffer = createFloatBuffer(sceneData.getBgStars());
            mStaticStarBuffer = createFloatBuffer(sceneData.getStaticStars());
            return;
        }

        if (mScene.consumeDynamicParticlesDirty()) {
            mSpaceCloudBuffer.position(0);
            mSpaceCloudBuffer.put(sceneData.getSpaceClouds());
            mSpaceCloudBuffer.position(0);

            mBgStarBuffer.position(0);
            mBgStarBuffer.put(sceneData.getBgStars());
            mBgStarBuffer.position(0);
        }
    }
    
    /**
     * 绘制全屏背景纹理
     */
    private void drawBackground() {
        // 使用背景着色器程序
        GLES20.glUseProgram(mBgProgram);
        
        // 全屏四边形顶点数据：(x,y) 位置 + (u,v) 纹理坐标
        float[] vertices = {
            -1, -1, 0, 1,  // 左下
             1, -1, 1, 1,  // 右下
            -1,  1, 0, 0,  // 左上
             1,  1, 1, 0   // 右上
        };
        
        // 创建顶点缓冲区
        FloatBuffer vertexBuffer = createFloatBuffer(vertices);
        
        // 获取着色器属性/统一变量句柄
        int posHandle = GLES20.glGetAttribLocation(mBgProgram, "aPosition");    // 位置属性
        int texHandle = GLES20.glGetAttribLocation(mBgProgram, "aTexCoord");    // 纹理坐标属性
        int samplerHandle = GLES20.glGetUniformLocation(mBgProgram, "uTexture");// 纹理采样器
        
        // 启用属性数组
        GLES20.glEnableVertexAttribArray(posHandle);
        GLES20.glEnableVertexAttribArray(texHandle);
        
        // 设置位置属性指针（每4个浮点数为一组，步长16字节）
        vertexBuffer.position(0);
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer);
        // 设置纹理坐标属性指针
        vertexBuffer.position(2);
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer);
        
        // 绑定背景纹理到纹理单元0
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTexBg);
        GLES20.glUniform1i(samplerHandle, 0);
        
        // 绘制四边形（三角带方式，4个顶点）
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        
        // 禁用属性数组（绘制完成后释放）
        GLES20.glDisableVertexAttribArray(posHandle);
        GLES20.glDisableVertexAttribArray(texHandle);
    }
    
    /**
     * 绘制星云粒子（点精灵方式）
     */
    private void drawSpaceClouds(Galaxy4Scene.SceneData sceneData) {
        // 使用星云着色器程序
        GLES20.glUseProgram(mCloudProgram);
        
        // 获取着色器句柄
        int posHandle = GLES20.glGetAttribLocation(mCloudProgram, "aPosition");  // 粒子位置属性
        int mvpHandle = GLES20.glGetUniformLocation(mCloudProgram, "uMVPMatrix");// MVP矩阵统一变量
        int samplerHandle = GLES20.glGetUniformLocation(mCloudProgram, "uTexture");// 纹理采样器
        
        // 设置MVP矩阵
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, sceneData.getMvpMatrix(), 0);
        
        // 启用位置属性数组
        GLES20.glEnableVertexAttribArray(posHandle);
        
        // 设置粒子位置属性指针
        mSpaceCloudBuffer.position(0);
        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, mSpaceCloudBuffer);
        
        // 绑定星云纹理到纹理单元0
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTexCloud);
        GLES20.glUniform1i(samplerHandle, 0);
        
        // 绘制所有星云粒子（点精灵方式）
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, sceneData.getSpaceCloudCount());
        
        // 禁用属性数组
        GLES20.glDisableVertexAttribArray(posHandle);
    }
    
    /**
     * 绘制背景星星粒子（点精灵方式）
     */
    private void drawBgStars(Galaxy4Scene.SceneData sceneData) {
        // 使用背景星星着色器程序
        GLES20.glUseProgram(mBgStarProgram);
        
        // 获取着色器句柄
        int posHandle = GLES20.glGetAttribLocation(mBgStarProgram, "aPosition");// 粒子位置属性
        int mvpHandle = GLES20.glGetUniformLocation(mBgStarProgram, "uMVPMatrix");// MVP矩阵统一变量
        
        // 设置MVP矩阵
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, sceneData.getMvpMatrix(), 0);
        
        // 启用位置属性数组
        GLES20.glEnableVertexAttribArray(posHandle);
        
        // 设置粒子位置属性指针
        mBgStarBuffer.position(0);
        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, mBgStarBuffer);
        
        // 绘制所有背景星星粒子（点精灵方式）
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, sceneData.getBgStarCount());
        
        // 禁用属性数组
        GLES20.glDisableVertexAttribArray(posHandle);
    }
    
    /**
     * 绘制静态星星（带双纹理混合的脉冲动画）
     * @param time 时间（秒），用于控制混合动画
     */
    private void drawStaticStars(Galaxy4Scene.SceneData sceneData) {
        // 使用静态星星着色器程序
        GLES20.glUseProgram(mStaticStarProgram);
        
        // 获取着色器句柄
        int posHandle = GLES20.glGetAttribLocation(mStaticStarProgram, "aPosition");  // 位置属性
        int sizeHandle = GLES20.glGetAttribLocation(mStaticStarProgram, "aPointSize");// 点大小属性
        int mvpHandle = GLES20.glGetUniformLocation(mStaticStarProgram, "uMVPMatrix");// MVP矩阵
        int tex1Handle = GLES20.glGetUniformLocation(mStaticStarProgram, "uTexture1");// 纹理1采样器
        int tex2Handle = GLES20.glGetUniformLocation(mStaticStarProgram, "uTexture2");// 纹理2采样器
        int timeHandle = GLES20.glGetUniformLocation(mStaticStarProgram, "uTime");    // 时间统一变量
        
        // 静态星星使用单位矩阵（屏幕空间绘制）
        float[] identity = new float[16];
        android.opengl.Matrix.setIdentityM(identity, 0);
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, identity, 0);
        GLES20.glUniform1f(timeHandle, sceneData.getTimeSeconds());
        
        // 启用属性数组
        GLES20.glEnableVertexAttribArray(posHandle);
        GLES20.glEnableVertexAttribArray(sizeHandle);
        
        // 设置位置属性指针（每3个浮点数为一组，步长12字节）
        mStaticStarBuffer.position(0);
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 12, mStaticStarBuffer);
        // 设置点大小属性指针
        mStaticStarBuffer.position(2);
        GLES20.glVertexAttribPointer(sizeHandle, 1, GLES20.GL_FLOAT, false, 12, mStaticStarBuffer);
        
        // 绑定纹理1到纹理单元0
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTexStaticStar);
        GLES20.glUniform1i(tex1Handle, 0);
        
        // 绑定纹理2到纹理单元1
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTexStaticStar2);
        GLES20.glUniform1i(tex2Handle, 1);
        
        // 绘制所有静态星星粒子
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, sceneData.getStaticStarCount());
        
        // 禁用属性数组
        GLES20.glDisableVertexAttribArray(posHandle);
        GLES20.glDisableVertexAttribArray(sizeHandle);
    }
    
    /**
     * 创建FloatBuffer（原生内存）
     * 用于传递顶点数据到OpenGL（避免JVM内存拷贝）
     * @param data 浮点数组数据
     * @return 初始化后的FloatBuffer
     */
    private FloatBuffer createFloatBuffer(float[] data) {
        // 分配原生内存（每个浮点数4字节）
        ByteBuffer bb = ByteBuffer.allocateDirect(data.length * 4);
        bb.order(ByteOrder.nativeOrder()); // 匹配CPU字节序
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(data);                      // 写入数据
        fb.position(0);                    // 重置位置到起始
        return fb;
    }
}