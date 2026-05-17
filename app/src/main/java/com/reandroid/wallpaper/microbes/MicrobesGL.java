package com.reandroid.wallpaper.microbes;

import android.content.res.Resources;
import android.opengl.GLES20;
import android.view.MotionEvent;

import com.reandroid.gles.GLESScene;
import com.reandroid.gles.RawResourceLoader;
import com.reandroid.wallpaper.R;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import static com.reandroid.wallpaper.microbes.MicrobesScene.*;

public class MicrobesGL extends GLESScene {

    // ---- 场景逻辑层（非 GL）----
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

    private int deadAPos;
    private int deadUTrans;
    private int deadUTime;

    private int decorAPosition;
    private int decorUTrans;
    private int decorUTime;

    // ---- Frame timing ----
    private long lastFrameMs = -1L;

    public MicrobesGL(int width, int height) {
        super(width, height);
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
            mScene.touchX = toWorldX(mScene.touchStartX);
            mScene.touchY = toWorldY(mScene.touchStartY);
            mScene.applyMotionTarget(mScene.touchX, mScene.touchY);
        } else if (action == MotionEvent.ACTION_MOVE) {
            mScene.touchX = toWorldX(event.getX());
            mScene.touchY = toWorldY(event.getY());
            if (!mScene.touchMoved) {
                float dx = event.getX() - mScene.touchStartX;
                float dy = event.getY() - mScene.touchStartY;
                mScene.touchMoved = (dx * dx + dy * dy) > (TOUCH_DRAG_THRESHOLD_PX * TOUCH_DRAG_THRESHOLD_PX);
            }
            mScene.applyMotionTarget(mScene.touchX, mScene.touchY);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            float upX = toWorldX(event.getX());
            float upY = toWorldY(event.getY());
            if (!mScene.touchMoved) {
                mScene.spawnFoodBurst(upX, upY);
                mScene.repelMicrobesOnTap(upX, upY);
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
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        float zoom = Math.max(1.0f, VIEW_CENTER_CROP_ZOOM);
        float width = Math.max(1.0f, mWidth);
        float height = Math.max(1.0f, mHeight);
        float parallaxScrollX = mScene.scrollXPx * VIEW_SCROLL_PARALLAX;
        float sx = (2.0f * zoom) / width;
        float sy = (2.0f * zoom) / height;
        float tx = -zoom + (2.0f * zoom * parallaxScrollX) / width;
        float ty = -zoom;

        drawDecor(sx, sy, tx, ty);
        drawFood(sx, sy, tx, ty);
        drawDead(sx, sy, tx, ty);
        drawMicrobes(sx, sy, tx, ty);

        GLES20.glDisable(GLES20.GL_BLEND);
    }

    // ---- Coordinate helpers ----

    private float toWorldX(float x) {
        float width = Math.max(1.0f, mWidth);
        float zoom = Math.max(1.0f, VIEW_CENTER_CROP_ZOOM);
        float parallaxScrollX = mScene.scrollXPx * VIEW_SCROLL_PARALLAX;
        return x / zoom + width * (zoom - 1.0f) / (2.0f * zoom) - parallaxScrollX;
    }

    private float toWorldY(float y) {
        float height = Math.max(1.0f, mHeight);
        float zoom = Math.max(1.0f, VIEW_CENTER_CROP_ZOOM);
        return height * (zoom + 1.0f) / (2.0f * zoom) - y / zoom;
    }

    // ---- Buffer / GL setup ----

    private void ensureBuffers() {
        microbePosBuffer = newFloatBuffer(MICROBE_COUNT * 3);
        microbeMiscBuffer = newFloatBuffer(MICROBE_COUNT * 3);
        microbeColorBuffer = newFloatBuffer(MICROBE_COUNT * 3);
        foodPosBuffer = newFloatBuffer(FOOD_COUNT * 3);
        decorPosBuffer = newFloatBuffer(DECOR_COUNT * 4);
        deadPosBuffer = newFloatBuffer(DEAD_COUNT * 4);
    }

    private void initGL() {
        Resources res = getResources();
        if (res == null) {
            return;
        }
        microbeProgram = createProgram(
            RawResourceLoader.readRawText(res, R.raw.microbes_microbe_vs),
            RawResourceLoader.readRawText(res, R.raw.microbes_microbe_fs)
        );
        foodProgram = createProgram(
            RawResourceLoader.readRawText(res, R.raw.microbes_food_vs),
            RawResourceLoader.readRawText(res, R.raw.microbes_food_fs)
        );
        deadProgram = createProgram(
            RawResourceLoader.readRawText(res, R.raw.microbes_dead_vs),
            RawResourceLoader.readRawText(res, R.raw.microbes_dead_fs)
        );
        decorProgram = createProgram(
            RawResourceLoader.readRawText(res, R.raw.microbes_decor_vs),
            RawResourceLoader.readRawText(res, R.raw.microbes_decor_fs)
        );

        microbeAPosition = GLES20.glGetAttribLocation(microbeProgram, "aPosition");
        microbeAMisc = GLES20.glGetAttribLocation(microbeProgram, "miscInfo");
        microbeAColor = GLES20.glGetAttribLocation(microbeProgram, "aColor");
        microbeUTrans = GLES20.glGetUniformLocation(microbeProgram, "uTrans");
        microbeUTime = GLES20.glGetUniformLocation(microbeProgram, "time");

        foodAPosition = GLES20.glGetAttribLocation(foodProgram, "aPosition");
        foodUTrans = GLES20.glGetUniformLocation(foodProgram, "uTrans");
        foodUTime = GLES20.glGetUniformLocation(foodProgram, "time");

        deadAPos = GLES20.glGetAttribLocation(deadProgram, "pos");
        deadUTrans = GLES20.glGetUniformLocation(deadProgram, "uTrans");
        deadUTime = GLES20.glGetUniformLocation(deadProgram, "time");

        decorAPosition = GLES20.glGetAttribLocation(decorProgram, "aPosition");
        decorUTrans = GLES20.glGetUniformLocation(decorProgram, "uTrans");
        decorUTime = GLES20.glGetUniformLocation(decorProgram, "time");

        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
    }

    // ---- Draw methods ----

    private void drawMicrobes(float sx, float sy, float tx, float ty) {
        microbePosBuffer.clear();
        microbeMiscBuffer.clear();
        microbeColorBuffer.clear();

        for (int i = 0; i < MICROBE_COUNT; i++) {
            microbePosBuffer.put(mScene.microbeX[i]).put(mScene.microbeY[i]).put(mScene.microbeAngle[i]);
            microbeMiscBuffer.put(mScene.microbeSize[i]).put(mScene.clamp(mScene.microbeEnergy[i], 0.0f, 1.8f)).put(mScene.microbePulseTime[i]);
            microbeColorBuffer.put(mScene.microbeR[i]).put(mScene.microbeG[i]).put(mScene.microbeB[i]);
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

        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, MICROBE_COUNT);

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
        GLES20.glEnableVertexAttribArray(foodAPosition);
        GLES20.glVertexAttribPointer(foodAPosition, 3, GLES20.GL_FLOAT, false, 3 * 4, foodPosBuffer);
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, FOOD_COUNT);
        GLES20.glDisableVertexAttribArray(foodAPosition);
    }

    private void drawDecor(float sx, float sy, float tx, float ty) {
        decorPosBuffer.clear();
        for (int i = 0; i < DECOR_COUNT; i++) {
            decorPosBuffer.put(mScene.decorX[i]).put(mScene.decorY[i]).put(mScene.decorAngle[i]).put(mScene.decorSize[i]);
        }
        decorPosBuffer.position(0);

        GLES20.glUseProgram(decorProgram);
        GLES20.glUniform4f(decorUTrans, sx, sy, tx, ty);
        GLES20.glUniform1f(decorUTime, mScene.timeSec);
        GLES20.glEnableVertexAttribArray(decorAPosition);
        GLES20.glVertexAttribPointer(decorAPosition, 4, GLES20.GL_FLOAT, false, 4 * 4, decorPosBuffer);
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, DECOR_COUNT);
        GLES20.glDisableVertexAttribArray(decorAPosition);
    }

    private void drawDead(float sx, float sy, float tx, float ty) {
        deadPosBuffer.clear();
        int active = 0;
        for (int i = 0; i < DEAD_COUNT; i++) {
            if (mScene.deadAge[i] >= DEAD_VISIBLE_AGE_MAX) {
                continue;
            }
            deadPosBuffer.put(mScene.deadX[i]).put(mScene.deadY[i]).put(mScene.deadDrift[i]).put(0.0f);
            active++;
        }
        if (active <= 0) {
            return;
        }
        deadPosBuffer.position(0);

        GLES20.glUseProgram(deadProgram);
        GLES20.glUniform4f(deadUTrans, sx, sy, tx, ty);
        GLES20.glUniform1f(deadUTime, mScene.timeSec);
        GLES20.glEnableVertexAttribArray(deadAPos);
        GLES20.glVertexAttribPointer(deadAPos, 4, GLES20.GL_FLOAT, false, 4 * 4, deadPosBuffer);
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, active);
        GLES20.glDisableVertexAttribArray(deadAPos);
    }

    // ---- Shader utilities ----

    private FloatBuffer newFloatBuffer(int floatCount) {
        return ByteBuffer.allocateDirect(floatCount * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
    }

    private int createProgram(String vertexSource, String fragmentSource) {
        int vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        if (vs == 0 || fs == 0) {
            return 0;
        }

        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);

        int[] ok = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0);
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        if (ok[0] == 0) {
            GLES20.glDeleteProgram(program);
            return 0;
        }
        return program;
    }

    private int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    private void deleteProgram(int program) {
        if (program != 0) {
            GLES20.glDeleteProgram(program);
        }
    }
}
