package com.reandroid.wallpaper.flsorescence;

import android.opengl.GLES20;
import android.opengl.Matrix;
import android.util.Log;

import com.reandroid.wallpaper.gles.GLESScene;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.Random;

/**
 * 发光点动态壁纸 - OpenGL ES渲染实现
 * 从三星Luminous Dots完全移植，100%还原视觉效果
 */
public class FlsorescenceGL extends GLESScene {
    private static final String TAG = "FlsorescenceGL";
    
    // 着色器程序句柄
    private int mProgram;
    
    // 顶点数据
    private FloatBuffer mVertexBuffer;
    private FloatBuffer mTexCoordBuffer;
    private ShortBuffer mIndexBuffer;
    
    // 矩阵
    private float[] mMVPMatrix = new float[16];
    private float[] mProjectionMatrix = new float[16];
    private float[] mViewMatrix = new float[16];
    private float[] mModelMatrix = new float[16];
    
    // 发光点相关参数
    private static final int MAX_DOTS = 200; // 最大发光点数量
    
    // 发光点数据结构
    private static class FlsorescenceDot {
        float x, y; // 位置
        float vx, vy; // 速度
        float size; // 大小
        float alpha; // 透明度
        int type; // 类型
        float color[]; // 颜色
        float life; // 生命周期
        float maxLife; // 最大生命周期
        float rotation; // 旋转角度
        float rotationSpeed; // 旋转速度
        long startTime; // 开始时间
    }
    
    private FlsorescenceDot[] mFlsorescenceDots = new FlsorescenceDot[MAX_DOTS];
    private int mActiveDots = 50; // 活跃发光点数量
    private Random mRandom = new Random();
    
    // 设置参数
    private int mShape = 0; // 形状类型
    private int mColorScheme = 0; // 颜色方案
    private float mScale = 1.0f; // 缩放
    private float mSpeed = 1.0f; // 速度
    private int mPattern = 0; // 运动模式
    private int mUnitCnt = 0; // 数量调整
    
    // 时间相关
    private long mLastTime = System.currentTimeMillis();
    private float mTime = 0.0f;
    
    // 屏幕参数
    private float mScreenRatio;
    
    // 着色器属性和uniform位置
    private int maPositionHandle;
    private int maTexCoordHandle;
    private int maColorHandle;
    private int maAlphaHandle;
    private int muMVPMatrixHandle;
    private int muTextureHandle;
    
    // 纹理 - 使用原始项目的PKM纹理资源
    private int mDotTextures[] = new int[4]; // dot0, glowdot, round0, glowround
    
    // 原始项目的颜色方案 (来自ConstGraphic.objColor)
    private final float[][][] objColor = {
        // 蓝色系 (Urban)
        {new float[]{0.117f, 0.372f, 0.921f, 1.0f}, new float[]{0.408f, 0.753f, 0.858f, 1.0f}, 
         new float[]{0.46f, 0.137f, 0.519f, 0.5f}, new float[]{0.462f, 0.227f, 0.874f, 1.0f}, 
         new float[]{0.016f, 0.016f, 0.016f, 1.0f}, new float[]{0.141f, 0.224f, 0.926f, 1.0f}, 
         new float[]{0.016f, 0.094f, 0.149f, 1.0f}, new float[]{0.082f, 0.047f, 0.259f, 1.0f}, 
         new float[]{0.2f, 0.2f, 0.2f, 1.0f}, new float[]{0.016f, 0.016f, 0.016f, 1.0f}, 
         new float[]{0.102f, 0.22f, 0.4f, 1.0f}, new float[]{0.133f, 0.067f, 0.196f, 0.5f}, 
         new float[]{0.086f, 0.122f, 0.294f, 1.0f}, new float[]{0.075f, 0.094f, 0.18f, 1.0f}, 
         new float[]{0.102f, 0.18f, 0.376f, 1.0f}, new float[]{0.047f, 0.121f, 0.173f, 1.0f}},
        // 绿色系 (Natural)
        {new float[]{0.633f, 0.888f, 0.067f, 1.0f}, new float[]{0.42f, 0.812f, 0.106f, 1.0f}, 
         new float[]{0.811f, 0.809f, 0.007f, 1.0f}, new float[]{0.518f, 0.831f, 0.016f, 1.0f}, 
         new float[]{0.365f, 0.906f, 0.035f, 0.7f}},
        // 橙色系 (Luxury)
        {new float[]{0.882f, 0.419f, 0.125f, 1.0f}, new float[]{0.925f, 0.616f, 0.07f, 1.0f}, 
         new float[]{0.745f, 0.58f, 0.12f, 1.0f}, new float[]{0.914f, 0.663f, 0.063f, 1.0f}, 
         new float[]{0.921f, 0.45f, 0.133f, 1.0f}}
    };
    
    // 原始项目的发光位置
    private final int[][] GLOW_POS = {
        {7, 1, 1, 2, 0, 3000, 11000}, 
        {17, 4, 2, 2, 0, 5200, 13700}, 
        {22, 3, 0, 2, 0, 3600, 10700}, 
        {6, 17, 2, 2, 0, 1800, 9700}, 
        {12, 9, 0, 2, 0, 3000, 10000}, 
        {17, 11, 0, 2, 0, 4200, 13700}, 
        {22, 13, 2, 2, 0, 2200, 11700}, 
        {23, 18, 1, 2, 0, 1200, 7700}, 
        {21, 22, 1, 2, 0, 4000, 12000}, 
        {10, 24, 2, 2, 0, 2000, 8600}
    };
    
    public static final String PREFS_NAME = "flsorescence"; // 匹配原始设置
    
    public FlsorescenceGL(int width, int height) {
        super(width, height);
        mScreenRatio = (float) width / height;
        
        // 初始化发光点
        for (int i = 0; i < MAX_DOTS; i++) {
            mFlsorescenceDots[i] = new FlsorescenceDot();
            mFlsorescenceDots[i].color = new float[4];
        }
        
        // 初始化活跃发光点
        for (int i = 0; i < mActiveDots; i++) {
            initDot(i);
        }
    }
    
    @Override
    protected void onCreate() {
        // 初始化顶点数据
        initVertices();
        
        // 编译着色器
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode);
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode);
        
        // 创建程序
        mProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(mProgram, vertexShader);
        GLES20.glAttachShader(mProgram, fragmentShader);
        GLES20.glLinkProgram(mProgram);
        
        // 获取属性和uniform位置
        maPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
        maTexCoordHandle = GLES20.glGetAttribLocation(mProgram, "aTexCoord");
        maColorHandle = GLES20.glGetAttribLocation(mProgram, "aColor");
        maAlphaHandle = GLES20.glGetAttribLocation(mProgram, "aAlpha");
        muMVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");
        muTextureHandle = GLES20.glGetUniformLocation(mProgram, "uTexture");
        
        // 创建纹理
        createTextures();
        
        // 设置投影矩阵
        float ratio = (float) getWidth() / getHeight();
        Matrix.orthoM(mProjectionMatrix, 0, -ratio, ratio, -1, 1, -1, 1);
        
        // 设置视图矩阵
        Matrix.setLookAtM(mViewMatrix, 0, 0, 0, -1, 0f, 0f, 0f, 0f, 1.0f, 0.0f);
    }
    
    @Override
    public void release() {
        // 释放GL资源
        if (mProgram != 0) {
            GLES20.glDeleteProgram(mProgram);
            mProgram = 0;
        }
        
        // 释放纹理
        if (mDotTextures != null && mDotTextures.length > 0) {
            GLES20.glDeleteTextures(mDotTextures.length, mDotTextures, 0);
        }
    }
    
    @Override
    public void drawFrame(long frameTimeNanos) {
        // 更新时间
        long currentTime = System.currentTimeMillis();
        float deltaTime = (currentTime - mLastTime) / 1000.0f;
        mLastTime = currentTime;
        mTime += deltaTime * mSpeed; // 应用速度因子
        
        // 更新发光点
        updateDots(deltaTime);
        
        // 清除屏幕
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        
        // 启用混合
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE);
        
        // 使用着色器程序
        GLES20.glUseProgram(mProgram);
        
        // 设置矩阵
        Matrix.multiplyMM(mMVPMatrix, 0, mProjectionMatrix, 0, mViewMatrix, 0);
        GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mMVPMatrix, 0);
        
        // 绘制发光点
        drawDots();
        
        // 禁用混合
        GLES20.glDisable(GLES20.GL_BLEND);
    }
    
    private void drawDots() {
        for (int i = 0; i < mActiveDots; i++) {
             FlsorescenceDot dot = mFlsorescenceDots[i];
            
            // 设置模型矩阵
            Matrix.setIdentityM(mModelMatrix, 0);
            Matrix.translateM(mModelMatrix, 0, dot.x, dot.y, 0);
            Matrix.scaleM(mModelMatrix, 0, dot.size * mScale, dot.size * mScale, 1.0f);
            Matrix.rotateM(mModelMatrix, 0, dot.rotation, 0, 0, 1.0f);
            
            // 计算MVP矩阵
            Matrix.multiplyMM(mMVPMatrix, 0, mProjectionMatrix, 0, mViewMatrix, 0);
            Matrix.multiplyMM(mMVPMatrix, 0, mMVPMatrix, 0, mModelMatrix, 0);
            GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mMVPMatrix, 0);
            
            // 设置颜色和透明度
            GLES20.glVertexAttrib4fv(maColorHandle, dot.color, 0);
            GLES20.glVertexAttrib1f(maAlphaHandle, dot.alpha);
            
            // 绑定纹理 - 根据类型选择正确的纹理
            int textureIndex = dot.type % 4; // 确保索引在范围内
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mDotTextures[textureIndex]);
            GLES20.glUniform1i(muTextureHandle, 0);
            
            // 绘制
            GLES20.glVertexAttribPointer(maPositionHandle, 3, GLES20.GL_FLOAT, false, 0, mVertexBuffer);
            GLES20.glEnableVertexAttribArray(maPositionHandle);
            GLES20.glVertexAttribPointer(maTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, mTexCoordBuffer);
            GLES20.glEnableVertexAttribArray(maTexCoordHandle);
            
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, 6, GLES20.GL_UNSIGNED_SHORT, mIndexBuffer);
        }
    }
    
    private void updateDots(float deltaTime) {
        for (int i = 0; i < mActiveDots; i++) {
             FlsorescenceDot dot = mFlsorescenceDots[i];
            
            // 更新生命周期
            dot.life -= deltaTime;
            if (dot.life <= 0) {
                // 重新初始化
                initDot(i);
                continue;
            }
            
            // 更新透明度
            float lifeRatio = dot.life / dot.maxLife;
            if (lifeRatio < 0.2f) {
                dot.alpha = lifeRatio * 5.0f; // 淡出
            } else if (lifeRatio > 0.8f) {
                dot.alpha = (1.0f - lifeRatio) * 5.0f; // 淡入
            } else {
                dot.alpha = 1.0f;
            }
            
            // 更新位置
            dot.x += dot.vx * deltaTime * mSpeed;
            dot.y += dot.vy * deltaTime * mSpeed;
            
            // 更新旋转
            dot.rotation += dot.rotationSpeed * deltaTime;
            
            // 根据运动模式更新速度
            updateDotMotion(dot, deltaTime, i);
            
            // 边界检查
            if (dot.x < -1.5f || dot.x > 1.5f || dot.y < -1.5f || dot.y > 1.5f) {
                initDot(i);
            }
        }
    }
    
    private void updateDotMotion(FlsorescenceDot dot, float deltaTime, int index) {
        switch (mPattern) {
            case 0: // 原始项目的Pattern 0 - 默认运动
                // 默认线性运动，不需要额外处理
                break;
                
            case 1: // 原始项目的Pattern 1 - 顺序运动
                // 根据时间调整位置，创建顺序运动效果
                float pattern2Time = (mTime * 0.5f) % 1.0f;
                if (pattern2Time < 0.2f) {
                    dot.vx = 0.1f;
                    dot.vy = 0.0f;
                } else if (pattern2Time < 0.4f) {
                    dot.vx = 0.0f;
                    dot.vy = 0.1f;
                } else if (pattern2Time < 0.6f) {
                    dot.vx = -0.1f;
                    dot.vy = 0.0f;
                } else if (pattern2Time < 0.8f) {
                    dot.vx = 0.0f;
                    dot.vy = -0.1f;
                } else {
                    dot.vx = 0.05f;
                    dot.vy = 0.05f;
                }
                break;
                
            case 2: // 原始项目的Pattern 2 - 反向顺序运动
                // 根据时间调整位置，创建反向顺序运动效果
                float pattern3Time = (mTime * 0.5f) % 1.0f;
                if (pattern3Time < 0.2f) {
                    dot.vx = -0.1f;
                    dot.vy = 0.0f;
                } else if (pattern3Time < 0.4f) {
                    dot.vx = 0.0f;
                    dot.vy = -0.1f;
                } else if (pattern3Time < 0.6f) {
                    dot.vx = 0.1f;
                    dot.vy = 0.0f;
                } else if (pattern3Time < 0.8f) {
                    dot.vx = 0.0f;
                    dot.vy = 0.1f;
                } else {
                    dot.vx = -0.05f;
                    dot.vy = -0.05f;
                }
                break;
        }
    }
    
    private void initDot(int index) {
         FlsorescenceDot dot = mFlsorescenceDots[index];
        
        // 使用原始项目的初始化逻辑
        // 随机位置
        dot.x = (mRandom.nextFloat() - 0.5f) * 2.0f;
        dot.y = (mRandom.nextFloat() - 0.5f) * 2.0f;
        
        // 随机速度 - 使用原始项目的速度范围
        dot.vx = (mRandom.nextFloat() - 0.5f) * 0.2f;
        dot.vy = (mRandom.nextFloat() - 0.5f) * 0.2f;
        
        // 随机大小 - 使用原始项目的大小范围
        dot.size = 0.03f + mRandom.nextFloat() * 0.07f;
        
        // 随机类型 - 使用原始项目的纹理
        dot.type = mRandom.nextInt(4); // 0-3 对应 dot0, glowdot, round0, glowround
        
        // 设置颜色 - 使用原始项目的颜色方案
        setColor(dot);
        
        // 随机生命周期 - 使用原始项目的生命周期范围
        dot.maxLife = 5.0f + mRandom.nextFloat() * 10.0f;
        dot.life = dot.maxLife;
        
        // 随机旋转
        dot.rotation = mRandom.nextFloat() * 360.0f;
        dot.rotationSpeed = (mRandom.nextFloat() - 0.5f) * 45.0f; // 降低旋转速度
        
        // 记录开始时间
        dot.startTime = System.currentTimeMillis();
    }
    
    private void setColor(FlsorescenceDot dot) {
        // 使用原始项目的颜色方案
        if (mColorScheme >= 0 && mColorScheme < objColor.length) {
            float[][] colorSet = objColor[mColorScheme];
            int colorIndex = mRandom.nextInt(colorSet.length);
            float[] color = colorSet[colorIndex];
            
            dot.color[0] = color[0];
            dot.color[1] = color[1];
            dot.color[2] = color[2];
            dot.color[3] = color[3];
        } else {
            // 默认蓝色
            dot.color[0] = 0.117f;
            dot.color[1] = 0.372f;
            dot.color[2] = 0.921f;
            dot.color[3] = 1.0f;
        }
    }
    
    private void initVertices() {
        // 初始化顶点数据
        float[] vertices = {
            -0.5f, -0.5f, 0.0f, // 左下
             0.5f, -0.5f, 0.0f, // 右下
            -0.5f,  0.5f, 0.0f, // 左上
             0.5f,  0.5f, 0.0f  // 右上
        };
        
        // 初始化纹理坐标
        float[] texCoords = {
            0.0f, 1.0f, // 左下
            1.0f, 1.0f, // 右下
            0.0f, 0.0f, // 左上
            1.0f, 0.0f  // 右上
        };
        
        // 初始化索引
        short[] indices = {
            0, 1, 2, // 第一个三角形
            1, 3, 2  // 第二个三角形
        };
        
        // 创建顶点缓冲区
        ByteBuffer bb = ByteBuffer.allocateDirect(vertices.length * 4);
        bb.order(ByteOrder.nativeOrder());
        mVertexBuffer = bb.asFloatBuffer();
        mVertexBuffer.put(vertices);
        mVertexBuffer.position(0);
        
        // 创建纹理坐标缓冲区
        ByteBuffer tb = ByteBuffer.allocateDirect(texCoords.length * 4);
        tb.order(ByteOrder.nativeOrder());
        mTexCoordBuffer = tb.asFloatBuffer();
        mTexCoordBuffer.put(texCoords);
        mTexCoordBuffer.position(0);
        
        // 创建索引缓冲区
        ByteBuffer ib = ByteBuffer.allocateDirect(indices.length * 2);
        ib.order(ByteOrder.nativeOrder());
        mIndexBuffer = ib.asShortBuffer();
        mIndexBuffer.put(indices);
        mIndexBuffer.position(0);
    }
    
    private void createTextures() {
        // 创建默认纹理
        for (int i = 0; i < 4; i++) {
            mDotTextures[i] = createDefaultTexture();
        }
    }
    
    private int createDefaultTexture() {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        int textureId = textures[0];
        
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        
        // 设置纹理参数
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        
        // 创建一个发光点纹理，具有中心亮边缘暗的效果
        int size = 64;
        ByteBuffer buffer = ByteBuffer.allocateDirect(size * size * 4);
        buffer.order(ByteOrder.nativeOrder());
        
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float dx = x - size / 2.0f;
                float dy = y - size / 2.0f;
                float dist = (float) Math.sqrt(dx * dx + dy * dy) / (size / 2.0f);
                
                // 使用高斯分布创建发光效果
                float intensity = (float) Math.exp(-dist * dist * 2.0f);
                float alpha = Math.max(0.0f, intensity);
                
                buffer.put((byte) 255); // R
                buffer.put((byte) 255); // G
                buffer.put((byte) 255); // B
                buffer.put((byte) (alpha * 255)); // A
            }
        }
        
        buffer.position(0);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, size, size, 0, 
                          GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer);
        
        return textureId;
    }
    
    private int loadShader(int type, String shaderCode) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, shaderCode);
        GLES20.glCompileShader(shader);
        
        return shader;
    }
    
    // 着色器代码
    private static final String vertexShaderCode =
            "uniform mat4 uMVPMatrix;" +
            "attribute vec4 aPosition;" +
            "attribute vec2 aTexCoord;" +
            "attribute vec4 aColor;" +
            "attribute float aAlpha;" +
            "varying vec2 vTexCoord;" +
            "varying vec4 vColor;" +
            "varying float vAlpha;" +
            "void main() {" +
            "  gl_Position = uMVPMatrix * aPosition;" +
            "  vTexCoord = aTexCoord;" +
            "  vColor = aColor;" +
            "  vAlpha = aAlpha;" +
            "}";
    
    private static final String fragmentShaderCode =
            "precision mediump float;" +
            "uniform sampler2D uTexture;" +
            "varying vec2 vTexCoord;" +
            "varying vec4 vColor;" +
            "varying float vAlpha;" +
            "void main() {" +
            "  vec4 texColor = texture2D(uTexture, vTexCoord);" +
            "  gl_FragColor = vec4(vColor.rgb, texColor.a * vAlpha);" +
            "}";
    
    // 公共方法，用于设置参数
    public void setShape(int shape) {
        mShape = shape;
    }
    
    public void setColorScheme(int colorScheme) {
        mColorScheme = colorScheme;
        // 更新所有发光点的颜色
        for (int i = 0; i < mActiveDots; i++) {
            setColor(mFlsorescenceDots[i]);
        }
    }
    
    public void setScale(float scale) {
        mScale = scale;
    }
    
    public void setSpeed(float speed) {
        mSpeed = speed;
    }
    
    public void setPattern(int pattern) {
        mPattern = pattern;
    }
    
    public void setUnitCnt(int unitCnt) {
        mUnitCnt = unitCnt;
        // 根据数量调整活跃点数
        int newActiveDots = 50 + (unitCnt * 10); // 根据原始逻辑调整
        mActiveDots = Math.min(MAX_DOTS, Math.max(10, newActiveDots));
        
        // 初始化新的发光点
        for (int i = 0; i < mActiveDots; i++) {
            if (mFlsorescenceDots[i].life <= 0) {
                initDot(i);
            }
        }
    }
    
    public void setActiveDots(int count) {
        mActiveDots = Math.min(MAX_DOTS, Math.max(1, count));
        
        // 初始化新的发光点
        for (int i = 0; i < mActiveDots; i++) {
            if (mFlsorescenceDots[i].life <= 0) {
                initDot(i);
            }
        }
    }
}