package com.reandroid.wallpaper.fireworks;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.SystemClock;

import com.reandroid.gles.AssetLoader;
import com.reandroid.gles.GLESScene;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import static com.reandroid.wallpaper.fireworks.FireworksScene.*;

/**
 * 烟花壁纸的OpenGL渲染核心类
 * 负责烟花粒子系统的创建、更新、绘制，触摸交互处理，背景纹理加载等
 */
public class FireworksGL extends GLESScene {

    // ---- 场景逻辑层（非 GL） ----
    private final FireworksScene mScene;
    private final Context mContext;

    // OpenGL相关初始化标记
    private boolean mGLInitialized = false;

    // OpenGL着色器程序句柄
    private int mProgram;
    // 顶点位置属性句柄
    private int mPositionHandle;
    // 纹理坐标属性句柄
    private int mTexHandle;
    // 投影矩阵统一变量句柄
    private int mMatrixHandle;
    // 纹理采样器句柄
    private int mSamplerHandle;
    // 透明度统一变量句柄
    private int mAlphaHandle;
    // 颜色统一变量句柄
    private int mColorHandle;

    // 背景纹理ID
    private int mTexBackground;
    // 星星（粒子）纹理ID
    private int mTexStar;
    // 背景纹理宽高比
    private float mBackgroundAspect = 1.0f;
    // 缓存上次加载的背景URI（避免重复加载）
    private String mLastBackgroundUri = null;

    // 四边形顶点缓冲（用于绘制纹理矩形）
    private FloatBuffer mQuadBuffer;
    // 投影矩阵（正交投影）
    private final float[] mProjectionMatrix = new float[16];

    // X轴偏移量（适配壁纸滚动）
    private float mXOffset = 0.0f;

    /**
     * 构造方法
     * @param width 渲染宽度
     * @param height 渲染高度
     */
    public FireworksGL(int width, int height, Context context) {
        super(width, height);
        mContext = context;
        mScene = new FireworksScene(width, height);
    }

    /**
     * 生命周期方法：创建时初始化
     * 仅执行一次基础初始化
     */
    @Override
    protected void onCreate() {
        if (mScene.mInitialized) return;
        mScene.mInitialized = true;
        mScene.initialize();
    }

    @Override
    public void release() {
        // 释放纹理资源
        int[] tex = new int[] { mTexBackground, mTexStar };
        GLES20.glDeleteTextures(tex.length, tex, 0);
        mTexBackground = 0;
        mTexStar = 0;

        // 释放着色器程序
        if (mProgram != 0) {
            GLES20.glDeleteProgram(mProgram);
            mProgram = 0;
        }

        mGLInitialized = false;
    }

    /**
     * 尺寸调整回调
     * @param width 新宽度
     * @param height 新高度
     */
    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        if (mGLInitialized) {
            // 更新OpenGL视口
            GLES20.glViewport(0, 0, mWidth, mHeight);
            // 更新正交投影矩阵
            Matrix.orthoM(mProjectionMatrix, 0, 0, mWidth, mHeight, 0, -1.0f, 1.0f);
        }
    }

    /**
     * 设置偏移量（适配壁纸滚动）
     * @param xOffset X轴偏移比例
     * @param yOffset Y轴偏移比例（未使用）
     * @param xPixels X轴偏移像素数（未使用）
     * @param yPixels Y轴偏移像素数（未使用）
     */
    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        mXOffset = xOffset;
    }

    /**
     * 命令处理回调（处理触摸事件）
     * @param action 命令动作
     * @param x 坐标X
     * @param y 坐标Y
     * @param z 额外参数（未使用）
     */
    @Override
    public void onCommand(String action, int x, int y, int z) {
        // 处理壁纸触摸事件
        if ("android.wallpaper.tap".equals(action)) {
            mScene.mTapX = x;
            mScene.mTapY = y;
            mScene.mTapPending = true;
        }
    }

    /**
     * 帧绘制方法（核心渲染逻辑）
     * @param timeMs 帧时间戳（毫秒）
     */
    @Override
    public void drawFrame(long timeMs) {
        if (!mScene.mInitialized) return;
        // 延迟初始化OpenGL资源（避免提前初始化导致异常）
        if (!mGLInitialized) {
            if (mContext == null) return;
            initGL();
        }

        // 检查并重新加载背景（如果URI变化）
        checkAndReloadBackground();

        // 更新当前时间
        mScene.mNow = (int) SystemClock.uptimeMillis();

        // 处理待执行的触摸事件
        if (mScene.mTapPending) {
            mScene.mTapPending = false;
            // 预览模式下无偏移，实际壁纸需叠加滚动偏移
            float tapOffset = isPreview() ? 0.0f : mXOffset;
            int x = (int) (mScene.mTapX + tapOffset * mWidth);
            mScene.addTap(x, mScene.mTapY);
        }

        // 计算最终的X轴偏移（适配壁纸滚动）
        float offset = isPreview() ? 0.0f : mXOffset;
        float offsetX = offset * mWidth;

        // 清空颜色缓冲区（准备绘制新帧）
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        // 使用着色器程序
        GLES20.glUseProgram(mProgram);
        // 设置投影矩阵
        GLES20.glUniformMatrix4fv(mMatrixHandle, 1, false, mProjectionMatrix, 0);

        // 设置混合模式（背景：常规Alpha混合）
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        // 绘制背景
        drawBackground(mWidth, mHeight, offsetX);

        // 更新所有粒子状态（物理计算）
        mScene.update();

        // 设置混合模式（粒子：加法混合，实现发光效果）
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE);
        // 绘制所有粒子和拖尾
        draw(offsetX);
    }

    /**
     * 初始化OpenGL相关资源
     */
    private void initGL() {
        mGLInitialized = true;

        // 禁用深度测试（2D渲染不需要）
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        // 启用混合（实现透明效果）
        GLES20.glEnable(GLES20.GL_BLEND);
        // 设置视口大小
        GLES20.glViewport(0, 0, mWidth, mHeight);
        // 创建正交投影矩阵（适配屏幕坐标）
        Matrix.orthoM(mProjectionMatrix, 0, 0, mWidth, mHeight, 0, -1.0f, 1.0f);

        // 从assets加载着色器
        String vs = AssetLoader.readText(mContext, "fireworks/shaders/GLES/fireworks_vs.glsl");
        String fs = AssetLoader.readText(mContext, "fireworks/shaders/GLES/fireworks_fs.glsl");

        // 创建并链接着色器程序
        mProgram = createProgram(vs, fs);
        // 获取着色器属性/统一变量句柄
        mPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
        mTexHandle = GLES20.glGetAttribLocation(mProgram, "aTexCoord");
        mMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");
        mSamplerHandle = GLES20.glGetUniformLocation(mProgram, "uSampler");
        mAlphaHandle = GLES20.glGetUniformLocation(mProgram, "uAlpha");
        mColorHandle = GLES20.glGetUniformLocation(mProgram, "uColor");

        // 创建四边形顶点缓冲（用于绘制纹理矩形）
        mQuadBuffer = ByteBuffer.allocateDirect(4 * 4 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();

        // 尝试加载自定义背景纹理
        if (mContext != null) {
            mTexBackground = loadCustomBackgroundTexture(mContext);
        }

        // 自定义背景加载失败时使用默认背景
        if (mTexBackground == 0) {
            Bitmap bmp = AssetLoader.decodeBitmap(mContext, "fireworks/drawable/background.jpg");
            if (bmp != null) {
                mBackgroundAspect = bmp.getWidth() / (float) bmp.getHeight();
                mTexBackground = loadTexture(bmp, false);
            }
        }

        // 加载星星（粒子）纹理
        Bitmap starBmp = AssetLoader.decodeBitmap(mContext, "fireworks/drawable/star.png");
        if (starBmp != null) {
            mTexStar = loadTexture(starBmp, true);
        }
    }

    /**
     * 检查并重新加载背景纹理（当URI变化时）
     */
    private void checkAndReloadBackground() {
        try {
            if (mContext == null) return;

            // 获取当前保存的自定义背景URI
            String currentUri = mContext.getSharedPreferences("wallpaper_prefs", 0)
                .getString("fireworks_custom_background_uri", null);

            // 检查URI是否发生变化
            if ((mLastBackgroundUri == null && currentUri != null) ||
                (mLastBackgroundUri != null && !mLastBackgroundUri.equals(currentUri))) {

                android.util.Log.d("FireworksGL", "背景URI变化: " + mLastBackgroundUri + " -> " + currentUri);

                // 删除旧纹理（释放资源）
                if (mTexBackground != 0) {
                    int[] tex = new int[]{mTexBackground};
                    GLES20.glDeleteTextures(1, tex, 0);
                    mTexBackground = 0;
                }

                // 重新加载背景纹理
                mTexBackground = loadCustomBackgroundTexture(mContext);
                if (mTexBackground == 0) {
                    android.util.Log.d("FireworksGL", "自定义背景加载失败，使用默认背景");
                    // 加载默认背景
                    Bitmap bmp = AssetLoader.decodeBitmap(mContext, "fireworks/drawable/background.jpg");
                    if (bmp != null) {
                        mBackgroundAspect = bmp.getWidth() / (float) bmp.getHeight();
                        mTexBackground = loadTexture(bmp, false);
                    }
                } else {
                    android.util.Log.d("FireworksGL", "自定义背景加载成功");
                }

                // 更新缓存的URI
                mLastBackgroundUri = currentUri;
            }
        } catch (Exception e) {
            android.util.Log.e("FireworksGL", "背景检查/重新加载异常", e);
        }
    }

    /**
     * 加载自定义背景纹理
     * @param ctx 上下文
     * @return 纹理ID（0表示加载失败）
     */
    private int loadCustomBackgroundTexture(Context ctx) {
        try {
            if (ctx == null) {
                android.util.Log.e("FireworksGL", "上下文为空");
                return 0;
            }

            // 获取自定义背景URI
            String uriString = ctx.getSharedPreferences("wallpaper_prefs", 0)
                .getString("fireworks_custom_background_uri", null);
            mLastBackgroundUri = uriString; // 更新缓存

            if (uriString == null) {
                android.util.Log.d("FireworksGL", "未找到自定义背景URI");
                return 0;
            }

            android.util.Log.d("FireworksGL", "加载自定义背景: " + uriString);

            // 解析URI并打开输入流
            android.net.Uri uri = android.net.Uri.parse(uriString);
            java.io.InputStream stream = ctx.getContentResolver().openInputStream(uri);
            if (stream == null) {
                android.util.Log.e("FireworksGL", "无法打开URI输入流: " + uriString);
                return 0;
            }

            // 获取图片尺寸（不加载像素数据）
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            bounds.inScaled = false;
            BitmapFactory.decodeStream(stream, null, bounds);
            stream.close();

            // 计算宽高比
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                mBackgroundAspect = bounds.outWidth / (float) bounds.outHeight;
            }

            // 加载图片像素数据
            stream = ctx.getContentResolver().openInputStream(uri);
            Bitmap bmp = BitmapFactory.decodeStream(stream);
            stream.close();

            if (bmp == null) return 0;

            // 创建OpenGL纹理
            int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
            // 设置纹理过滤模式
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            // 设置纹理环绕模式（边缘夹紧）
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            // 将位图数据上传到纹理
            android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
            // 释放位图资源
            bmp.recycle();

            return tex[0];
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 设置粒子颜色（兼容旧方法）
     * @param fireworks 粒子实例
     * @param life 粒子生命值
     */
    private void setColor(FireworkParticle fireworks, float life) {
        float r = fireworks.r / 255.0f;
        float g = fireworks.g / 255.0f;
        float b = fireworks.b / 255.0f;
        float a = (float) Math.sqrt(Math.abs(life));
        GLES20.glUniform1f(mAlphaHandle, a);
        GLES20.glUniform3f(mColorHandle, r, g, b);
    }

    /**
     * 设置粒子绘制颜色和透明度
     * @param fireworks 粒子实例（null则使用白色）
     * @param life 粒子生命值
     */
    private void setParticleColor(FireworkParticle fireworks, float life) {
        float r = 1.0f;
        float g = 1.0f;
        float b = 1.0f;
        // 使用粒子自身颜色（如果不为空）
        if (fireworks != null) {
            r = fireworks.r / 255.0f;
            g = fireworks.g / 255.0f;
            b = fireworks.b / 255.0f;
        }
        // 透明度关联生命值（平方根映射，让衰减更自然）
        float a = (float) Math.sqrt(Math.abs(life));
        // 设置着色器统一变量
        GLES20.glUniform1f(mAlphaHandle, a);
        GLES20.glUniform3f(mColorHandle, r, g, b);
    }

    /**
     * 绘制背景
     * 绘制双背景以实现滚动无缝衔接
     * @param width 屏幕宽度
     * @param height 屏幕高度
     * @param offsetX X轴偏移量
     */
    private void drawBackground(int width, int height, float offsetX) {
        // 计算背景宽度（保持宽高比）
        float bgWidth = height * mBackgroundAspect;
        float startX = -offsetX;
        // 绘制第一个背景
        drawRect(mTexBackground, startX, 0.0f, startX + bgWidth, height);
        // 绘制第二个背景（衔接第一个）
        drawRect(mTexBackground, startX + bgWidth, 0.0f, startX + bgWidth * 2.0f, height);
    }

    /**
     * 绘制烟花粒子组
     * @param arr 粒子数组
     * @param index 组起始索引
     * @param offsetX X轴偏移量
     */
    private void drawFireworks(FireworkParticle[] arr, int index, float offsetX) {
        for (int i = 0; i < STRIDE; i++) {
            FireworkParticle p = arr[index + i];
            int delta = mScene.mNow - p.time;
            // 仅绘制激活且时间差为正的粒子
            if (p.active && delta >= 0) {
                setParticleColor(p, p.life);
                float size = mScene.getSize(p.life);
                float x = p.posX - offsetX;
                float y = p.posY;
                // 绘制粒子（中心对齐）
                drawRect(mTexStar, x - size * 0.5f, y - size * 0.5f, x + size * 0.5f, y + size * 0.5f);
            }
        }
    }

    /**
     * 绘制拖尾粒子
     * @param tail 拖尾粒子实例
     * @param offsetX X轴偏移量
     */
    private void drawTails(TailParticle tail, float offsetX) {
        if (tail == null) return;
        FireworkParticle root = tail.root;
        if (tail.life < 0.0f) return;

        if (tail.type == 0) {
            // 绘制普通拖尾
            float size = mScene.getSize(tail.life);
            float x = tail.posX - offsetX;
            float y = tail.posY;
            setParticleColor(root, tail.life);
            drawRect(mTexStar, x - size * 0.5f, y - size * 0.5f, x + size * 0.5f, y + size * 0.5f);
        } else if (tail.type == 1) {
            // 绘制闪光效果
            float size = 350.0f;
            float x = tail.posX - offsetX;
            float y = tail.posY;
            setParticleColor(null, 1.0f);
            drawRect(mTexStar, x - size * 0.5f, y - size * 0.5f, x + size * 0.5f, y + size * 0.5f);
            // 闪光绘制后重置拖尾
            mScene.initTails(tail);
        }
    }

    /**
     * 绘制所有可见元素
     * @param offsetX X轴偏移量
     */
    private void draw(float offsetX) {
        // 绘制常规烟花
        for (int i = 0; i < MAX_NORMAL; i++) {
            drawFireworks(mScene.mNormal, i * STRIDE, offsetX);
        }
        // 绘制额外烟花
        for (int i = 0; i < MAX_EXTRAS; i++) {
            drawFireworks(mScene.mExtras, i * STRIDE, offsetX);
        }
        // 绘制拖尾粒子
        for (int i = 0; i < MAX_TAILS; i++) {
            drawTails(mScene.mTails[i], offsetX);
        }
    }

    /**
     * 绘制纹理矩形
     * @param texture 纹理ID
     * @param x0 左X坐标
     * @param y0 下Y坐标
     * @param x1 右X坐标
     * @param y1 上Y坐标
     */
    private void drawRect(int texture, float x0, float y0, float x1, float y1) {
        // 构建四边形顶点数据（XY坐标 + 纹理坐标）
        float[] verts = new float[] {
                x0, y0, 0.0f, 0.0f,
                x0, y1, 0.0f, 1.0f,
                x1, y1, 1.0f, 1.0f,
                x1, y0, 1.0f, 0.0f
        };

        // 填充顶点缓冲
        mQuadBuffer.clear();
        mQuadBuffer.put(verts).position(0);

        // 启用顶点位置属性
        GLES20.glEnableVertexAttribArray(mPositionHandle);
        GLES20.glVertexAttribPointer(mPositionHandle, 2, GLES20.GL_FLOAT, false, 16, mQuadBuffer);

        // 启用纹理坐标属性
        mQuadBuffer.position(2);
        GLES20.glEnableVertexAttribArray(mTexHandle);
        GLES20.glVertexAttribPointer(mTexHandle, 2, GLES20.GL_FLOAT, false, 16, mQuadBuffer);

        // 绑定纹理并设置采样器
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glUniform1i(mSamplerHandle, 0);

        // 背景纹理强制使用不透明白色
        if (texture == mTexBackground) {
            GLES20.glUniform1f(mAlphaHandle, 1.0f);
            GLES20.glUniform3f(mColorHandle, 1.0f, 1.0f, 1.0f);
        }

        // 绘制四边形（三角扇模式）
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4);

        // 禁用属性（性能优化）
        GLES20.glDisableVertexAttribArray(mPositionHandle);
        GLES20.glDisableVertexAttribArray(mTexHandle);
    }

    /**
     * 从Bitmap创建GL纹理
     * @param bmp 位图数据
     * @param repeat 是否重复纹理
     * @return 纹理ID（0=失败）
     */
    private int loadTexture(Bitmap bmp, boolean repeat) {
        if (bmp == null) return 0;

        // 创建OpenGL纹理
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);

        // 设置纹理过滤模式（最近邻过滤）
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);

        // 设置纹理环绕模式
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                repeat ? GLES20.GL_REPEAT : GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                repeat ? GLES20.GL_REPEAT : GLES20.GL_CLAMP_TO_EDGE);

        // 上传位图数据到纹理
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
        // 释放位图资源
        bmp.recycle();

        return tex[0];
    }

    /**
     * 创建着色器程序
     * @param vs 顶点着色器代码
     * @param fs 片段着色器代码
     * @return 程序句柄
     */
    private int createProgram(String vs, String fs) {
        // 加载并编译着色器
        int v = loadShader(GLES20.GL_VERTEX_SHADER, vs);
        int f = loadShader(GLES20.GL_FRAGMENT_SHADER, fs);

        // 创建程序并附加着色器
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, v);
        GLES20.glAttachShader(program, f);
        // 链接程序
        GLES20.glLinkProgram(program);

        return program;
    }

    /**
     * 加载并编译着色器
     * @param type 着色器类型（顶点/片段）
     * @param source 着色器代码
     * @return 着色器句柄
     */
    private int loadShader(int type, String source) {
        // 创建着色器
        int shader = GLES20.glCreateShader(type);
        // 设置着色器代码
        GLES20.glShaderSource(shader, source);
        // 编译着色器
        GLES20.glCompileShader(shader);

        return shader;
    }
}
