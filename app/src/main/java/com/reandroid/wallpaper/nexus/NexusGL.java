package com.reandroid.wallpaper.nexus;

import android.app.WallpaperManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.SystemClock;
import android.util.Log;

import com.reandroid.wallpaper.R;
import com.reandroid.gles.GLESScene;
import com.reandroid.gles.RawResourceLoader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Nexus动态壁纸 - 基于RenderScript（nexus.rs）移植的OpenGL ES 2.0实现
 * 100%还原原版视觉效果，包含纹理脉冲、光晕和背景的渲染逻辑
 */
public class NexusGL extends GLESScene {
    // 日志标签
    private static final String TAG = "NexusGL";

    // ---- 场景逻辑层（非 GL）----
    private final NexusScene mScene;

    // OpenGL初始化完成标记
    private boolean mGLInitialized;

    // OpenGL着色器程序句柄
    private int mProgram;
    // 顶点位置属性句柄
    private int mPositionHandle;
    // 纹理坐标属性句柄
    private int mTexCoordHandle;
    // MVP矩阵统一变量句柄
    private int mMatrixHandle;
    // 颜色统一变量句柄
    private int mColorHandle;
    // 纹理采样器句柄
    private int mTextureHandle;

    // 背景纹理ID
    private int mTexBackground;
    // 脉冲纹理ID
    private int mTexPulse;
    // 光晕纹理ID
    private int mTexGlow;

    // 顶点数据缓冲区
    private FloatBuffer mVertexBuffer;
    // 纹理坐标缓冲区
    private FloatBuffer mTexCoordBuffer;

    // 模型矩阵（模型变换）
    private final float[] mModelMatrix = new float[16];
    // MVP矩阵（投影*视图*模型）
    private final float[] mMVPMatrix = new float[16];

    // 当前渲染颜色（RGBA）
    private final float[] mColor = new float[4];

    // 背景管理器（含GL纹理操作）
    private final NexusBackgroundManager mBackgroundManager = new NexusBackgroundManager();

    /**
     * 构造方法
     * @param width 初始宽度
     * @param height 初始高度
     */
    public NexusGL(int width, int height) {
        super(width, height);
        mScene = new NexusScene(width, height);
    }

    /**
     * 场景创建回调（GLESScene生命周期）
     * 延迟初始化OpenGL相关资源
     */
    @Override
    protected void onCreate() {
        Log.d(TAG, "onCreate - 延迟GL初始化");
    }

    @Override
    public void release() {
        // 释放纹理资源
        int[] tex = new int[] { mTexBackground, mTexPulse, mTexGlow };
        GLES20.glDeleteTextures(tex.length, tex, 0);
        mTexBackground = 0;
        mTexPulse = 0;
        mTexGlow = 0;

        // 释放着色器程序
        if (mProgram != 0) {
            GLES20.glDeleteProgram(mProgram);
            mProgram = 0;
        }

        mGLInitialized = false;
    }

    /**
     * 设置壁纸偏移量（多屏滑动时调用）
     * @param xOffset 横向偏移比例
     * @param yOffset 纵向偏移比例（未使用）
     * @param xPixels 横向偏移像素值（未使用）
     * @param yPixels 纵向偏移像素值（未使用）
     */
    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        mScene.setOffset(xOffset);
    }

    /**
     * 屏幕尺寸变化回调
     * @param width 新宽度
     * @param height 新高度
     */
    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        mScene.resize(width, height);
    }

    /**
     * 壁纸指令处理（点击/触摸等）
     * @param action 指令类型（如点击、二次点击）
     * @param x 点击X坐标
     * @param y 点击Y坐标
     * @param z 额外参数（未使用）
     */
    @Override
    public void onCommand(String action, int x, int y, int z) {
        if (WallpaperManager.COMMAND_TAP.equals(action)
                || WallpaperManager.COMMAND_SECONDARY_TAP.equals(action)
                || WallpaperManager.COMMAND_DROP.equals(action)) {
            if (mScene.mPulseController.getExtras() == null || mScene.MAX_EXTRAS <= 0 || mScene.PULSE_SIZE <= 0) {
                return;
            }
            // 竖屏时修正X坐标（适配偏移）
            if (mWidth < mHeight) {
                x = (int) (x + (mScene.mXOffset * mWidth / mScene.mWorldScaleX));
            }
            // 添加点击触发的脉冲
            mScene.addTap(x, y, SystemClock.uptimeMillis());
        }
    }

    /**
     * 绘制每一帧
     * @param timeMs 当前时间（毫秒）
     */
    @Override
    public void drawFrame(long timeMs) {
        // 初始化OpenGL资源
        initGL();
        if (!mGLInitialized) {
            return;
        }

        // 背景设置变化时重新加载纹理
        mTexBackground = mBackgroundManager.reloadIfChanged(mResources, mTexBackground);

        // 判断是否横屏（宽>高则旋转渲染）
        mScene.mRotate = mWidth > mHeight;
        // 设置视口大小
        GLES20.glViewport(0, 0, mWidth, mHeight);
        // 清空颜色缓冲区（黑色背景）
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        // 绘制背景
        drawBackground();

        // 获取当前时间，用于计算脉冲动画进度
        int now = (int) SystemClock.uptimeMillis();
        // 绘制常规脉冲
        drawPulses(mScene.mPulseController.getPulses(), mScene.MAX_PULSES, now);
        // 绘制额外脉冲（点击触发）
        drawPulses(mScene.mPulseController.getExtras(), mScene.MAX_EXTRAS, now);
    }

    /**
     * 初始化OpenGL相关资源
     * 包括着色器程序、纹理、脉冲数据等
     */
    private void initGL() {
        if (mGLInitialized || mResources == null) {
            return;
        }

        // 禁用深度测试（2D渲染不需要）
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        // 启用混合（实现透明/光晕效果）
        GLES20.glEnable(GLES20.GL_BLEND);

        mScene.applySettings(NexusSettings.load(mResources));

        NexusShaderProgram.Handles handles = NexusShaderProgram.create(mResources);
        if (handles == null) {
            Log.e(TAG, "createProgram failed, skip GL init");
            return;
        }
        mProgram = handles.program;
        mPositionHandle = handles.position;
        mTexCoordHandle = handles.texCoord;
        mMatrixHandle = handles.matrix;
        mColorHandle = handles.color;
        mTextureHandle = handles.texture;

        // 加载纹理资源
        loadTextures();
        if (mTexBackground == 0 || mTexPulse == 0 || mTexGlow == 0) {
            Log.e(TAG, "loadTextures failed, skip GL init");
            return;
        }
        // 加载UV坐标
        mScene.mUv0 = RawResourceLoader.readRawFloatArray(mResources, R.raw.nexus_uv_0);
        mScene.mUv90 = RawResourceLoader.readRawFloatArray(mResources, R.raw.nexus_uv_90);
        mScene.mUv180 = RawResourceLoader.readRawFloatArray(mResources, R.raw.nexus_uv_180);
        mScene.mUv270 = RawResourceLoader.readRawFloatArray(mResources, R.raw.nexus_uv_270);
        // 初始化脉冲数据
        mScene.initPulses(mWidth, mHeight, SystemClock.uptimeMillis());
        // 更新投影矩阵
        mScene.updateProjection(mWidth, mHeight);

        // 初始化顶点缓冲区（4个顶点，每个顶点4个浮点值）
        mVertexBuffer = ByteBuffer.allocateDirect(4 * 4 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        // 初始化纹理坐标缓冲区（4个顶点，每个顶点2个浮点值）
        mTexCoordBuffer = ByteBuffer.allocateDirect(4 * 2 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();

        mGLInitialized = true;
    }

    /**
     * 加载纹理资源
     * 包括背景、脉冲、光晕纹理
     */
    private void loadTextures() {
        BitmapFactory.Options options = new BitmapFactory.Options();
        // 禁用自动缩放（保持原始尺寸）
        options.inScaled = false;

        // 加载背景纹理
        mTexBackground = mBackgroundManager.loadInitialTexture(mResources);
        // 加载脉冲纹理
        mTexPulse = loadTexture(R.drawable.pulse, options);
        // 加载光晕纹理
        mTexGlow = loadTexture(R.drawable.glow, options);
    }

    /**
     * 加载单个纹理资源
     * @param resourceId 资源ID
     * @param options 位图加载选项
     * @return 纹理ID
     */
    private int loadTexture(int resourceId, BitmapFactory.Options options) {
        // 从资源解码位图
        Bitmap bitmap = BitmapFactory.decodeResource(mResources, resourceId, options);
        if (bitmap == null) {
            Log.e(TAG, "纹理解码失败: resId=" + resourceId);
            return 0;
        }
        int[] textureHandle = new int[1];
        // 生成纹理ID
        GLES20.glGenTextures(1, textureHandle, 0);
        // 绑定纹理
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle[0]);
        // 设置纹理过滤模式（线性过滤）
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        // 设置纹理环绕模式（边缘夹紧）
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        // 将位图数据上传到纹理
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        // 回收位图（释放内存）
        bitmap.recycle();
        return textureHandle[0];
    }

    /**
     * 绘制背景纹理
     * 适配屏幕缩放和横向偏移
     */
    private void drawBackground() {
        // 背景不需要混合（不透明）
        GLES20.glDisable(GLES20.GL_BLEND);
        // 使用着色器程序
        GLES20.glUseProgram(mProgram);

        // 初始化模型矩阵
        Matrix.setIdentityM(mModelMatrix, 0);
        // 应用缩放（适配屏幕尺寸）
        Matrix.scaleM(mModelMatrix, 0, mScene.mWorldScaleX, mScene.mWorldScaleY, 1.0f);
        // 竖屏时应用横向偏移（多屏滑动）
        if (!mScene.mRotate) {
            Matrix.translateM(mModelMatrix, 0, -(mScene.mXOffset * mWidth), 0f, 0f);
        }
        // 计算MVP矩阵（投影*模型）
        Matrix.multiplyMM(mMVPMatrix, 0, mScene.mProjectionMatrix, 0, mModelMatrix, 0);

        // 设置背景颜色（白色不透明）
        setColor(1f, 1f, 1f, 1f);
        // 计算背景绘制范围（横屏/竖屏适配）
        float right = mScene.mRotate ? mHeight * 2f : mWidth * 2f;
        float bottom = mScene.mRotate ? mWidth : mHeight;
        float[] bgUv = buildBackgroundUv(right, bottom);
        // 绘制背景矩形
        drawTexturedRect(mTexBackground, 0f, 0f, right, bottom, bgUv, mMVPMatrix);
    }

    private float[] buildBackgroundUv(float drawWidth, float drawHeight) {
        float viewAspect = drawHeight > 0f ? drawWidth / drawHeight : 1f;
        float bgAspect = mBackgroundManager.getBackgroundAspect() > 0f
            ? mBackgroundManager.getBackgroundAspect()
            : 1f;

        float u0 = 0f;
        float v0 = 0f;
        float u1 = 1f;
        float v1 = 1f;

        if (viewAspect > bgAspect) {
            float scale = bgAspect / viewAspect;
            float pad = (1f - scale) * 0.5f;
            v0 = pad;
            v1 = 1f - pad;
        } else if (viewAspect < bgAspect) {
            float scale = viewAspect / bgAspect;
            float pad = (1f - scale) * 0.5f;
            u0 = pad;
            u1 = 1f - pad;
        }

        return new float[] {
                u0, v0,
                u1, v0,
                u0, v1,
                u1, v1
        };
    }

    /**
     * 绘制脉冲数组
     * @param pulseSet 脉冲数组
     * @param setSize 数组长度
     * @param now 当前时间（毫秒）
     */
    private void drawPulses(NexusPulseController.Pulse[] pulseSet, int setSize, int now) {
        if (pulseSet == null || setSize <= 0) {
            return;
        }
        // 启用混合（脉冲/光晕需要透明效果）
        GLES20.glEnable(GLES20.GL_BLEND);
        // 设置混合模式（加法混合，增强光晕效果）
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE);
        // 使用着色器程序
        GLES20.glUseProgram(mProgram);

        // 遍历所有脉冲
        for (int i = 0; i < setSize; i++) {
            NexusPulseController.Pulse p = pulseSet[i];
            if (p == null) {
                continue;
            }
            // 计算脉冲已运行时间
            int delta = now - p.startTime;
            // 仅绘制激活且已到开始时间的脉冲
            if (p.active != 0 && delta >= 0) {
                // 初始化模型矩阵
                Matrix.setIdentityM(mModelMatrix, 0);
                // 竖屏时应用横向偏移
                if (!mScene.mRotate) {
                    Matrix.translateM(mModelMatrix, 0, -(mScene.mXOffset * mWidth), 0f, 0f);
                }
                // 应用脉冲缩放和屏幕适配缩放
                Matrix.scaleM(mModelMatrix, 0, p.scale * mScene.mWorldScaleX, p.scale * mScene.mWorldScaleY, 1.0f);
                // 计算MVP矩阵
                Matrix.multiplyMM(mMVPMatrix, 0, mScene.mProjectionMatrix, 0, mModelMatrix, 0);

                // 计算脉冲当前位置
                float x = p.originX + (p.dx * mScene.SPEED * delta);
                float y = p.originY + (p.dy * mScene.SPEED * delta);

                // 设置脉冲颜色
                setColorForPulse(p.color);

                // 根据移动方向绘制脉冲和光晕
                if (p.dx < 0) {
                    // 向左移动
                    float xx = x + (mScene.TRAIL_SIZE * mScene.PULSE_SIZE);
                    if (xx <= 0) {
                        // 超出屏幕，重新初始化脉冲
                        mScene.mPulseController.resetPulse(
                                p,
                                p.pulseType,
                                mWidth,
                                mHeight,
                                mScene.PULSE_SIZE,
                                mScene.SPEED_DELTA_MIN,
                                mScene.SPEED_DELTA_MAX,
                                mScene.MAX_DELAY,
                                SystemClock.uptimeMillis()
                        );
                    } else {
                        // 绘制脉冲纹理
                        drawTexturedRect(mTexPulse, x, y, xx, y + mScene.PULSE_SIZE, mScene.mUv0, mMVPMatrix);
                        // 绘制光晕纹理（居中对齐）
                        drawTexturedRect(mTexGlow,
                                x + mScene.HALF_PULSE_SIZE - mScene.HALF_GLOW_SIZE,
                                y + mScene.HALF_PULSE_SIZE - mScene.HALF_GLOW_SIZE,
                                x + mScene.HALF_PULSE_SIZE + mScene.HALF_GLOW_SIZE,
                                y + mScene.HALF_PULSE_SIZE + mScene.HALF_GLOW_SIZE,
                                mScene.mUv0, mMVPMatrix);
                    }
                } else if (p.dx > 0) {
                    // 向右移动
                    x += mScene.PULSE_SIZE;
                    float xx = x - (mScene.TRAIL_SIZE * mScene.PULSE_SIZE);
                    if (xx >= mWidth * 2f) {
                        // 超出屏幕，重新初始化脉冲
                        mScene.mPulseController.resetPulse(
                                p,
                                p.pulseType,
                                mWidth,
                                mHeight,
                                mScene.PULSE_SIZE,
                                mScene.SPEED_DELTA_MIN,
                                mScene.SPEED_DELTA_MAX,
                                mScene.MAX_DELAY,
                                SystemClock.uptimeMillis()
                        );
                    } else {
                        // 绘制脉冲纹理（180度旋转）
                        drawTexturedRect(mTexPulse, xx, y, x, y + mScene.PULSE_SIZE, mScene.mUv180, mMVPMatrix);
                        // 绘制光晕纹理（居中对齐，180度旋转）
                        drawTexturedRect(mTexGlow,
                                x - mScene.HALF_PULSE_SIZE - mScene.HALF_GLOW_SIZE,
                                y + mScene.HALF_PULSE_SIZE - mScene.HALF_GLOW_SIZE,
                                x - mScene.HALF_PULSE_SIZE + mScene.HALF_GLOW_SIZE,
                                y + mScene.HALF_PULSE_SIZE + mScene.HALF_GLOW_SIZE,
                                mScene.mUv180, mMVPMatrix);
                    }
                } else if (p.dy < 0) {
                    // 向上移动
                    float yy = y + (mScene.TRAIL_SIZE * mScene.PULSE_SIZE);
                    if (yy <= 0) {
                        // 超出屏幕，重新初始化脉冲
                        mScene.mPulseController.resetPulse(
                                p,
                                p.pulseType,
                                mWidth,
                                mHeight,
                                mScene.PULSE_SIZE,
                                mScene.SPEED_DELTA_MIN,
                                mScene.SPEED_DELTA_MAX,
                                mScene.MAX_DELAY,
                                SystemClock.uptimeMillis()
                        );
                    } else {
                        // 绘制脉冲纹理（270度旋转）
                        drawTexturedRect(mTexPulse, x, y, x + mScene.PULSE_SIZE, yy, mScene.mUv270, mMVPMatrix);
                        // 绘制光晕纹理（居中对齐，270度旋转）
                        drawTexturedRect(mTexGlow,
                                x + mScene.HALF_PULSE_SIZE - mScene.HALF_GLOW_SIZE,
                                y + mScene.HALF_PULSE_SIZE - mScene.HALF_GLOW_SIZE,
                                x + mScene.HALF_PULSE_SIZE + mScene.HALF_GLOW_SIZE,
                                y + mScene.HALF_PULSE_SIZE + mScene.HALF_GLOW_SIZE,
                                mScene.mUv270, mMVPMatrix);
                    }
                } else if (p.dy > 0) {
                    // 向下移动
                    y += mScene.PULSE_SIZE;
                    float yy = y - (mScene.TRAIL_SIZE * mScene.PULSE_SIZE);
                    if (yy >= mHeight) {
                        // 超出屏幕，重新初始化脉冲
                        mScene.mPulseController.resetPulse(
                                p,
                                p.pulseType,
                                mWidth,
                                mHeight,
                                mScene.PULSE_SIZE,
                                mScene.SPEED_DELTA_MIN,
                                mScene.SPEED_DELTA_MAX,
                                mScene.MAX_DELAY,
                                SystemClock.uptimeMillis()
                        );
                    } else {
                        // 绘制脉冲纹理（90度旋转）
                        drawTexturedRect(mTexPulse, x, yy, x + mScene.PULSE_SIZE, y, mScene.mUv90, mMVPMatrix);
                        // 绘制光晕纹理（居中对齐，90度旋转）
                        drawTexturedRect(mTexGlow,
                                x + mScene.HALF_PULSE_SIZE - mScene.HALF_GLOW_SIZE,
                                y - mScene.HALF_PULSE_SIZE - mScene.HALF_GLOW_SIZE,
                                x + mScene.HALF_PULSE_SIZE + mScene.HALF_GLOW_SIZE,
                                y - mScene.HALF_PULSE_SIZE + mScene.HALF_GLOW_SIZE,
                                mScene.mUv90, mMVPMatrix);
                    }
                }
            }
        }
    }

    /**
     * 设置脉冲颜色
     * @param c 颜色索引（0-3）
     */
    private void setColorForPulse(int c) {
        if (mScene.mMode == 1) {
            setColor(0.9f, 0.1f, 0.1f, 0.8f);
            return;
        }
        if (mScene.mMode == 2) {
            setColor(0.1f, 0.9f, 0.1f, 0.8f);
            return;
        }
         if (mScene.mMode == 3) {
            setColor(0.1f, 0.1f, 0.9f, 0.8f);
            return;
        }
         if (mScene.mMode == 4) {
            setColor(0.9f, 0.9f, 0.9f, 0.8f);
            return;
        }
         if (mScene.mMode == 5) {
            setColor(0.3f, 0.9f, 0.9f, 0.8f);
            return;
        }
         if (mScene.mMode == 6) {
            setColor(0.9f, 0.3f, 0.9f, 0.8f);
            return;
        }
         if (mScene.mMode == 7) {
            setColor(0.95f, 0.7f, 0.75f, 0.8f);
            return;
        }
        // 根据颜色索引设置RGBA
        if (c == 0) {
            setColor(1.0f, 0.0f, 0.0f, 0.8f);
        } else if (c == 1) {
            setColor(0.0f, 0.8f, 0.0f, 0.8f);
        } else if (c == 2) {
            setColor(0.0f, 0.4f, 0.9f, 0.8f);
        } else if (c == 3) {
            setColor(1.0f, 0.8f, 0.0f, 0.8f);
        }else if (c == 4) {
            setColor(0.9f, 0.3f, 0.9f, 0.8f);
        }else if (c == 5) {
            setColor(0.9f, 0.9f, 0.9f, 0.8f);
        }else if (c == 6) {
            setColor(0.3f, 0.9f, 0.9f, 0.8f);
        }else {
            setColor(0.95f, 0.7f, 0.75f, 0.8f);
        }
    }

    /**
     * 设置当前渲染颜色
     * @param r 红色分量（0-1）
     * @param g 绿色分量（0-1）
     * @param b 蓝色分量（0-1）
     * @param a 透明度分量（0-1）
     */
    private void setColor(float r, float g, float b, float a) {
        mColor[0] = r;
        mColor[1] = g;
        mColor[2] = b;
        mColor[3] = a;
    }

    /**
     * 绘制带纹理的矩形
     * @param textureId 纹理ID
     * @param left 左边界坐标
     * @param top 上边界坐标
     * @param right 右边界坐标
     * @param bottom 下边界坐标
     * @param uv 纹理UV坐标数组
     * @param mvp MVP矩阵
     */
    private void drawTexturedRect(int textureId, float left, float top, float right, float bottom,
                                  float[] uv, float[] mvp) {
        // 构建顶点数据（矩形，三角带方式绘制）
        float[] vertices = {
                left, top, 0f, 1f,
                right, top, 0f, 1f,
                left, bottom, 0f, 1f,
                right, bottom, 0f, 1f
        };

        // 填充顶点缓冲区
        mVertexBuffer.clear();
        mVertexBuffer.put(vertices).position(0);
        // 填充纹理坐标缓冲区
        mTexCoordBuffer.clear();
        mTexCoordBuffer.put(uv).position(0);

        // 设置MVP矩阵统一变量
        GLES20.glUniformMatrix4fv(mMatrixHandle, 1, false, mvp, 0);
        // 设置颜色统一变量
        GLES20.glUniform4f(mColorHandle, mColor[0], mColor[1], mColor[2], mColor[3]);

        // 激活并绑定纹理
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glUniform1i(mTextureHandle, 0);

        // 启用顶点位置属性
        GLES20.glEnableVertexAttribArray(mPositionHandle);
        GLES20.glVertexAttribPointer(mPositionHandle, 4, GLES20.GL_FLOAT, false, 16, mVertexBuffer);

        // 启用纹理坐标属性
        GLES20.glEnableVertexAttribArray(mTexCoordHandle);
        GLES20.glVertexAttribPointer(mTexCoordHandle, 2, GLES20.GL_FLOAT, false, 8, mTexCoordBuffer);

        // 绘制三角带（4个顶点）
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        // 禁用属性（优化性能）
        GLES20.glDisableVertexAttribArray(mPositionHandle);
        GLES20.glDisableVertexAttribArray(mTexCoordHandle);
    }
}
