package com.reandroid.wallpaper.microbes;

import android.content.Context;
import android.content.SharedPreferences;
import android.opengl.GLES20;
import android.view.MotionEvent;

import com.reandroid.utils.AssetLoader;
import com.reandroid.gles.GLESScene;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import static com.reandroid.wallpaper.microbes.MicrobesScene.*;

public class MicrobesGL extends GLESScene {

    // ---- 场景逻辑层（非 GL）----
    private final Context mContext;
    private final MicrobesScene mScene;

    // ---- NIO buffers ----
    private FloatBuffer microbePosBuffer;
    private FloatBuffer microbeMiscBuffer;
    private FloatBuffer microbeColorBuffer;
    private FloatBuffer foodPosBuffer;
    private FloatBuffer decorPosBuffer;
    private FloatBuffer deadPosBuffer;

    // ---- GL program handles ----
    private int microbeProgram;
    private int foodProgram;
    private int deadProgram;
    private int decorProgram;

    private int microbeAPosition;
    private int microbeAMisc;
    private int microbeAColor;
    private int microbeUTrans;
    private int microbeUTime;

    private int foodAPosition;
    private int foodUTrans;
    private int foodUTime;
    private int foodUSizeScale;

    private int deadAPos;
    private int deadUTrans;
    private int deadUTime;

    private int decorAPosition;
    private int decorUTrans;
    private int decorUTime;
    private int decorUSizeScale;

    // ---- Frame timing ----
    private long lastFrameMs = -1L;

    public MicrobesGL(int width, int height, Context context) {
        super(width, height);
        mContext = context.getApplicationContext();
        mScene = new MicrobesScene();
    }

    @Override
    protected void onCreate() {
        ensureBuffers();
        mScene.initScene(mWidth, mHeight);
    }

    @Override
    public void release() {
        deleteProgram(microbeProgram);
        deleteProgram(foodProgram);
        deleteProgram(deadProgram);
        deleteProgram(decorProgram);
        microbeProgram = 0;
        foodProgram = 0;
        deadProgram = 0;
        decorProgram = 0;
        microbePosBuffer = null;
        microbeMiscBuffer = null;
        microbeColorBuffer = null;
        foodPosBuffer = null;
        decorPosBuffer = null;
        deadPosBuffer = null;
        lastFrameMs = -1L;
    }

    public void setPluginPrefs(SharedPreferences p) {
        if (mScene != null) mScene.setPluginPrefs(p);
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        mScene.resize(width, height);
    }

    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        mScene.setScroll(xPixels, mWidth);
    }

    @Override
    public void onTouchEvent(MotionEvent event) {
        if (event == null) {
            return;
        }
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            mScene.touchActive = true;
            mScene.touchMoved = false;
            mScene.touchStartX = event.getX();
            mScene.touchStartY = event.getY();
            mScene.motion(toWorldX(mScene.touchStartX), toWorldY(mScene.touchStartY));
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (!mScene.touchMoved) {
                float dx = event.getX() - mScene.touchStartX;
                float dy = event.getY() - mScene.touchStartY;
                mScene.touchMoved = (dx * dx + dy * dy) > (TOUCH_DRAG_THRESHOLD_PX * TOUCH_DRAG_THRESHOLD_PX);
            }
            mScene.motion(toWorldX(event.getX()), toWorldY(event.getY()));
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (!mScene.touchMoved) {
                mScene.touchTap(toWorldX(event.getX()), toWorldY(event.getY()));
            }
            mScene.touchActive = false;
        }
    }

    @Override
    public void drawFrame(long timeMs) {
        if (mWidth <= 0 || mHeight <= 0) {
            return;
        }
        if (microbeProgram == 0) {
            initGL();
        }

        if (lastFrameMs < 0L) {
            lastFrameMs = timeMs;
        }
        float dt = Math.min(0.1f, Math.max(0.001f, (timeMs - lastFrameMs) / 1000.0f));
        lastFrameMs = timeMs;
        mScene.timeSec += dt;

        mScene.refreshRuntimeSettingsIfNeeded(timeMs);

        mScene.updateScene(dt);

        GLES20.glViewport(0, 0, mWidth, mHeight);
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glEnable(GLES20.GL_BLEND);
        // 原版:glBlendFunc(GL_SRC_ALPHA, GL_ONE)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE);

        float width = Math.max(1.0f, mWidth);
        float height = Math.max(1.0f, mHeight);
        float sx = 2.0f / width;
        float sy = 2.0f / height;
        float tx = 2.0f * mScene.scrollXPx / width - 1.0f;
        float ty = -1.0f;

        // 原版绘制顺序:decoration → dead → food → microbe
        drawDecor(sx, sy, tx, ty);
        drawDead(sx, sy, tx, ty);
        drawFood(sx, sy, tx, ty);
        drawMicrobes(sx, sy, tx, ty);

        GLES20.glDisable(GLES20.GL_BLEND);
    }

    // ---- Coordinate helpers ----

    private float toWorldX(float x) {
        return x - mScene.scrollXPx;
    }

    private float toWorldY(float y) {
        return mHeight - y;
    }

    /** 原版运行于 480×800;按 (屏高/800) 等比放大点精灵尺寸以匹配原版视觉比例 */
    private float sizeScale() {
        return Math.max(1.0f, mHeight / 800.0f);
    }

    // ---- Buffer / GL setup ----

    private void ensureBuffers() {
        microbePosBuffer = newFloatBuffer(MICROBE_MAX * 3);
        microbeMiscBuffer = newFloatBuffer(MICROBE_MAX * 3);
        microbeColorBuffer = newFloatBuffer(MICROBE_MAX * 3);
        foodPosBuffer = newFloatBuffer(FOOD_COUNT * 3);
        decorPosBuffer = newFloatBuffer(DECOR_COUNT * 3);
        deadPosBuffer = newFloatBuffer(DEAD_COUNT * 4);
    }

    private void initGL() {
        final String shaderPath = "microbes/shaders/GLES/";
        microbeProgram = createProgram(
            AssetLoader.readText(mContext, shaderPath + "microbes_microbe_vs.glsl"),
            AssetLoader.readText(mContext, shaderPath + "microbes_microbe_fs.glsl")
        );
        foodProgram = createProgram(
            AssetLoader.readText(mContext, shaderPath + "microbes_food_vs.glsl"),
            AssetLoader.readText(mContext, shaderPath + "microbes_food_fs.glsl")
        );
        deadProgram = createProgram(
            AssetLoader.readText(mContext, shaderPath + "microbes_dead_vs.glsl"),
            AssetLoader.readText(mContext, shaderPath + "microbes_dead_fs.glsl")
        );
        decorProgram = createProgram(
            AssetLoader.readText(mContext, shaderPath + "microbes_decor_vs.glsl"),
            AssetLoader.readText(mContext, shaderPath + "microbes_decor_fs.glsl")
        );

        microbeAPosition = GLES20.glGetAttribLocation(microbeProgram, "aPosition");
        microbeAMisc = GLES20.glGetAttribLocation(microbeProgram, "miscInfo");
        microbeAColor = GLES20.glGetAttribLocation(microbeProgram, "aColor");
        microbeUTrans = GLES20.glGetUniformLocation(microbeProgram, "uTrans");
        microbeUTime = GLES20.glGetUniformLocation(microbeProgram, "time");

        foodAPosition = GLES20.glGetAttribLocation(foodProgram, "aPosition");
        foodUTrans = GLES20.glGetUniformLocation(foodProgram, "uTrans");
        foodUTime = GLES20.glGetUniformLocation(foodProgram, "time");
        foodUSizeScale = GLES20.glGetUniformLocation(foodProgram, "uSizeScale");

        deadAPos = GLES20.glGetAttribLocation(deadProgram, "aPosition");
        deadUTrans = GLES20.glGetUniformLocation(deadProgram, "uTrans");
        deadUTime = GLES20.glGetUniformLocation(deadProgram, "time");

        decorAPosition = GLES20.glGetAttribLocation(decorProgram, "pos");
        decorUTrans = GLES20.glGetUniformLocation(decorProgram, "uTrans");
        decorUTime = GLES20.glGetUniformLocation(decorProgram, "time");
        decorUSizeScale = GLES20.glGetUniformLocation(decorProgram, "uSizeScale");

        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
    }

    // ---- Draw methods ----

    private void drawMicrobes(float sx, float sy, float tx, float ty) {
        microbePosBuffer.clear();
        microbeMiscBuffer.clear();
        microbeColorBuffer.clear();

        // 原版在 480×800 设备上运行;按 (屏高/800) 等比放大点尺寸,
        // 使现代高分辨率屏上的视觉比例与原版一致(miscInfo.x 预乘,不动 aColor.b)
        float sizeScale = sizeScale();
        for (int i = 0; i < mScene.microbeCount; i++) {
            microbePosBuffer.put(mScene.microbeX[i]).put(mScene.microbeY[i]).put(mScene.microbeAngle[i]);
            microbeMiscBuffer.put(mScene.microbeSize[i] * sizeScale).put(mScene.microbeEnergy[i]).put(mScene.microbePulseTs[i]);
            // 原版 aColor = (c0, c1, size)
            microbeColorBuffer.put(mScene.microbeC0[i]).put(mScene.microbeC1[i]).put(mScene.microbeSize[i]);
        }

        microbePosBuffer.position(0);
        microbeMiscBuffer.position(0);
        microbeColorBuffer.position(0);

        GLES20.glUseProgram(microbeProgram);
        GLES20.glUniform4f(microbeUTrans, sx, sy, tx, ty);
        GLES20.glUniform1f(microbeUTime, mScene.timeSec);

        GLES20.glEnableVertexAttribArray(microbeAPosition);
        GLES20.glVertexAttribPointer(microbeAPosition, 3, GLES20.GL_FLOAT, false, 3 * 4, microbePosBuffer);
        GLES20.glEnableVertexAttribArray(microbeAMisc);
        GLES20.glVertexAttribPointer(microbeAMisc, 3, GLES20.GL_FLOAT, false, 3 * 4, microbeMiscBuffer);
        GLES20.glEnableVertexAttribArray(microbeAColor);
        GLES20.glVertexAttribPointer(microbeAColor, 3, GLES20.GL_FLOAT, false, 3 * 4, microbeColorBuffer);

        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, mScene.microbeCount);

        GLES20.glDisableVertexAttribArray(microbeAPosition);
        GLES20.glDisableVertexAttribArray(microbeAMisc);
        GLES20.glDisableVertexAttribArray(microbeAColor);
    }

    private void drawFood(float sx, float sy, float tx, float ty) {
        foodPosBuffer.clear();
        for (int i = 0; i < FOOD_COUNT; i++) {
            foodPosBuffer.put(mScene.foodX[i]).put(mScene.foodY[i]).put(mScene.foodPhase[i]);
        }
        foodPosBuffer.position(0);

        GLES20.glUseProgram(foodProgram);
        GLES20.glUniform4f(foodUTrans, sx, sy, tx, ty);
        GLES20.glUniform1f(foodUTime, mScene.timeSec);
        GLES20.glUniform1f(foodUSizeScale, sizeScale());
        GLES20.glEnableVertexAttribArray(foodAPosition);
        GLES20.glVertexAttribPointer(foodAPosition, 3, GLES20.GL_FLOAT, false, 3 * 4, foodPosBuffer);
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, FOOD_COUNT);
        GLES20.glDisableVertexAttribArray(foodAPosition);
    }

    private void drawDecor(float sx, float sy, float tx, float ty) {
        decorPosBuffer.clear();
        for (int i = 0; i < DECOR_COUNT; i++) {
            decorPosBuffer.put(mScene.decorX[i]).put(mScene.decorY[i]).put(mScene.decorZ[i]);
        }
        decorPosBuffer.position(0);

        GLES20.glUseProgram(decorProgram);
        GLES20.glUniform4f(decorUTrans, sx, sy, tx, ty);
        GLES20.glUniform1f(decorUTime, mScene.timeSec);
        // 装饰是极低 alpha 的软边光晕:全比例放大会把渐变拉成雾团导致模糊,
        // 限制最大 1.3×(260px),保留放大但不糊
        GLES20.glUniform1f(decorUSizeScale, Math.min(sizeScale(), 1.3f));
        GLES20.glEnableVertexAttribArray(decorAPosition);
        GLES20.glVertexAttribPointer(decorAPosition, 3, GLES20.GL_FLOAT, false, 3 * 4, decorPosBuffer);
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, DECOR_COUNT);
        GLES20.glDisableVertexAttribArray(decorAPosition);
    }

    private void drawDead(float sx, float sy, float tx, float ty) {
        // 原版:80 槽全量绘制(x,y,angle,breed),INVALID 槽 y=-10000 自然出屏
        // breed 仅作 aPosition.w(点尺寸),按屏高等比预乘
        float sizeScale = sizeScale();
        deadPosBuffer.clear();
        for (int i = 0; i < DEAD_COUNT; i++) {
            deadPosBuffer.put(mScene.deadX[i]).put(mScene.deadY[i])
                    .put(mScene.deadAngle[i]).put(mScene.deadBreed[i] * sizeScale);
        }
        deadPosBuffer.position(0);

        GLES20.glUseProgram(deadProgram);
        GLES20.glUniform4f(deadUTrans, sx, sy, tx, ty);
        GLES20.glUniform1f(deadUTime, mScene.timeSec);
        GLES20.glEnableVertexAttribArray(deadAPos);
        GLES20.glVertexAttribPointer(deadAPos, 4, GLES20.GL_FLOAT, false, 4 * 4, deadPosBuffer);
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, DEAD_COUNT);
        GLES20.glDisableVertexAttribArray(deadAPos);
    }

    // ---- Shader utilities ----

    private FloatBuffer newFloatBuffer(int floatCount) {
        return ByteBuffer.allocateDirect(floatCount * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
    }



    private void deleteProgram(int program) {
        if (program != 0) {
            GLES20.glDeleteProgram(program);
        }
    }
}
