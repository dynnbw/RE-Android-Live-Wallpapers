package com.reandroid.wallpaper.aurora2;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.Process;
import android.util.Log;
import android.view.MotionEvent;

import com.reandroid.gles.AssetLoader;
import com.reandroid.gles.GLESScene;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

public class Aurora2GL extends GLESScene {
    private static final String TAG = "Aurora2GL";
    private static final int AURORA_STRIPS = 40;

    private final Context mContext;
    private final Aurora2Scene mScene;
    private final float[] mModelMatrix = new float[16];
    private final float[] mTempMatrix = new float[16];
    private final int[] mTextures = new int[Aurora2Scene.TEXTURE_COUNT];

    private FloatBuffer mQuadVertexBuffer;
    private FloatBuffer mQuadTexCoordBuffer;
    private FloatBuffer mAuroraVertexBuffer;
    private FloatBuffer mAuroraTexCoordBuffer;
    private ShortBuffer mAuroraIndexBuffer;

    private boolean mGlInitialized;
    private int mProgram;
    private int mPositionHandle;
    private int mTexCoordHandle;
    private int mMatrixHandle;
    private int mColorHandle;
    private int mTextureHandle;

    public Aurora2GL(Context context, int width, int height) {
        super(width, height);
        mContext = context;
        mScene = new Aurora2Scene(width, height, false);
    }

    @Override
    protected void onCreate() {
        Log.d(TAG, "onCreate");
        if (mSurface != null) {
            mScene.setPreview(isPreview());
        }
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        mScene.resize(width, height);
    }

    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        mScene.setOffset(xOffset, yOffset);
    }

    @Override
    public void onTouchEvent(MotionEvent event) {
        mScene.onTouchEvent(event);
    }

    @Override
    public void release() {
        if (mProgram != 0) {
            GLES20.glDeleteProgram(mProgram);
            mProgram = 0;
        }
        GLES20.glDeleteTextures(mTextures.length, mTextures, 0);
        for (int i = 0; i < mTextures.length; i++) {
            mTextures[i] = 0;
        }
        mGlInitialized = false;
    }

    @Override
    public void drawFrame(long timeMs) {
        ensureGl();
        if (!mGlInitialized) {
            return;
        }

        mScene.update(timeMs);
        Aurora2Scene.SceneData data = mScene.getSceneData();

        GLES20.glViewport(0, 0, mWidth, mHeight);
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(mProgram);
        GLES20.glEnableVertexAttribArray(mPositionHandle);
        GLES20.glEnableVertexAttribArray(mTexCoordHandle);

        setQuadBuffers();

        GLES20.glEnable(GLES20.GL_BLEND);

        // 先绘制底层：shine + background
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ZERO);
        drawQuad(data.projectionMatrix, data.shine);

        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_DST_ALPHA);
        drawQuad(data.projectionMatrix, data.background);

        // 中层：星星与流星（在背景之上）
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        for (Aurora2Scene.Sprite sprite : data.stars1) {
            drawQuad(data.projectionMatrix, sprite);
        }
        for (Aurora2Scene.Sprite sprite : data.stars2) {
            drawQuad(data.projectionMatrix, sprite);
        }
        drawQuad(data.projectionMatrix, data.shootingStar);

        // 上层：极光（在星星/流星之上）
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE);
        drawAurora(data.projectionMatrix, data.aurora);

        // 前景层
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        drawQuad(data.projectionMatrix, data.tree);

        if (data.fadeActive) {
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            drawQuad(data.projectionMatrix, data.fadeBackground);
            drawAurora(data.projectionMatrix, data.fadeAurora);
        }

        GLES20.glDisableVertexAttribArray(mPositionHandle);
        GLES20.glDisableVertexAttribArray(mTexCoordHandle);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    private void ensureGl() {
        if (mGlInitialized || mResources == null) {
            return;
        }
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        } catch (Throwable ignored) {
        }

        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        initBuffers();
        initProgram();
        initTextures();
        mGlInitialized = true;
    }

    private void initBuffers() {
        float[] quadVertices = AssetLoader.readFloatArray(mContext, "aurora2/data/aurora2_quad_pos3.csv");
        float[] quadTexCoords = AssetLoader.readFloatArray(mContext, "aurora2/data/aurora2_quad_tex.csv");
        if (quadVertices.length != 12 || quadTexCoords.length != 8) {
            throw new IllegalStateException("Aurora2 quad data size mismatch");
        }
        mQuadVertexBuffer = ByteBuffer.allocateDirect(quadVertices.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        mQuadVertexBuffer.put(quadVertices).position(0);

        mQuadTexCoordBuffer = ByteBuffer.allocateDirect(quadTexCoords.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        mQuadTexCoordBuffer.put(quadTexCoords).position(0);

        float[] auroraVertices = new float[AURORA_STRIPS * 2 * 3];
        float[] auroraTexCoords = new float[AURORA_STRIPS * 2 * 2];
        short[] auroraIndices = new short[AURORA_STRIPS * 2];
        int vertexIndex = 0;
        int texIndex = 0;
        for (int y = 0; y < AURORA_STRIPS; y++) {
            float yRatio = ((float) y) / (AURORA_STRIPS - 1);
            auroraVertices[vertexIndex++] = 0.0f;
            auroraVertices[vertexIndex++] = yRatio;
            auroraVertices[vertexIndex++] = 0.0f;
            auroraVertices[vertexIndex++] = 1.0f;
            auroraVertices[vertexIndex++] = yRatio;
            auroraVertices[vertexIndex++] = 0.0f;

            float texY = 1.0f - yRatio;
            auroraTexCoords[texIndex++] = 0.0f;
            auroraTexCoords[texIndex++] = texY;
            auroraTexCoords[texIndex++] = 1.0f;
            auroraTexCoords[texIndex++] = texY;
        }
        for (short i = 0; i < auroraIndices.length; i++) {
            auroraIndices[i] = i;
        }
        mAuroraVertexBuffer = ByteBuffer.allocateDirect(auroraVertices.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        mAuroraTexCoordBuffer = ByteBuffer.allocateDirect(auroraTexCoords.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        mAuroraIndexBuffer = ByteBuffer.allocateDirect(auroraIndices.length * 2)
                .order(ByteOrder.nativeOrder())
                .asShortBuffer();
        mAuroraVertexBuffer.put(auroraVertices).position(0);
        mAuroraTexCoordBuffer.put(auroraTexCoords).position(0);
        mAuroraIndexBuffer.put(auroraIndices).position(0);
    }

    private void initProgram() {
        mProgram = createProgram(
                AssetLoader.readText(mContext, "aurora2/shaders/GLES/aurora2_texture_vs.glsl"),
                AssetLoader.readText(mContext, "aurora2/shaders/GLES/aurora2_texture_fs.glsl"));
        mPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
        mTexCoordHandle = GLES20.glGetAttribLocation(mProgram, "aTexCoord");
        mMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMatrix");
        mColorHandle = GLES20.glGetUniformLocation(mProgram, "uColor");
        mTextureHandle = GLES20.glGetUniformLocation(mProgram, "uTexture");
    }

    private void initTextures() {
        for (int i = 0; i < Aurora2Scene.TEXTURE_NAMES.length; i++) {
            String name = Aurora2Scene.TEXTURE_NAMES[i];
            if (name == null) {
                continue;
            }
            String assetPath = "aurora2/drawable/" + name + ".png";
            mTextures[i] = loadTexture(assetPath);
        }
    }

    private int loadTexture(String assetPath) {
        Bitmap bitmap = AssetLoader.decodeBitmap(mContext, assetPath);
        if (bitmap == null) {
            throw new IllegalStateException("Failed to decode texture " + assetPath);
        }
        int[] texture = new int[1];
        GLES20.glGenTextures(1, texture, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        bitmap.recycle();
        return texture[0];
    }

    private void setQuadBuffers() {
        mQuadVertexBuffer.position(0);
        GLES20.glVertexAttribPointer(mPositionHandle, 3, GLES20.GL_FLOAT, false, 0, mQuadVertexBuffer);
        mQuadTexCoordBuffer.position(0);
        GLES20.glVertexAttribPointer(mTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, mQuadTexCoordBuffer);
    }

    private void drawQuad(float[] projectionMatrix, Aurora2Scene.Sprite sprite) {
        if (sprite.textureId < 0 || sprite.textureId >= mTextures.length) {
            return;
        }
        int texture = mTextures[sprite.textureId];
        if (texture == 0 || sprite.alpha <= 0.001f || sprite.width <= 0.0f || sprite.height <= 0.0f) {
            return;
        }
        setQuadBuffers();
        Matrix.setIdentityM(mModelMatrix, 0);
        Matrix.translateM(mModelMatrix, 0, sprite.x, sprite.y, sprite.z);
        Matrix.scaleM(mModelMatrix, 0, sprite.width, sprite.height, 1.0f);
        Matrix.multiplyMM(mTempMatrix, 0, projectionMatrix, 0, mModelMatrix, 0);
        GLES20.glUniformMatrix4fv(mMatrixHandle, 1, false, mTempMatrix, 0);
        GLES20.glUniform4f(mColorHandle, 1.0f, 1.0f, 1.0f, sprite.alpha);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glUniform1i(mTextureHandle, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private void drawAurora(float[] projectionMatrix, Aurora2Scene.AuroraMeshState aurora) {
        if (aurora.textureId < 0 || aurora.textureId >= mTextures.length) {
            return;
        }
        int texture = mTextures[aurora.textureId];
        if (texture == 0 || aurora.alpha <= 0.001f || aurora.width <= 0.0f || aurora.height <= 0.0f) {
            return;
        }

        updateAuroraMeshVertices(aurora.width, aurora.height, aurora.rippleFrame);
        mAuroraVertexBuffer.position(0);
        GLES20.glVertexAttribPointer(mPositionHandle, 3, GLES20.GL_FLOAT, false, 0, mAuroraVertexBuffer);
        mAuroraTexCoordBuffer.position(0);
        GLES20.glVertexAttribPointer(mTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, mAuroraTexCoordBuffer);

        Matrix.setIdentityM(mModelMatrix, 0);
        Matrix.translateM(mModelMatrix, 0, aurora.x, aurora.y, aurora.z);
        Matrix.multiplyMM(mTempMatrix, 0, projectionMatrix, 0, mModelMatrix, 0);
        GLES20.glUniformMatrix4fv(mMatrixHandle, 1, false, mTempMatrix, 0);
        GLES20.glUniform4f(mColorHandle, 1.0f, 1.0f, 1.0f, aurora.alpha);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glUniform1i(mTextureHandle, 0);
        mAuroraIndexBuffer.position(0);
        GLES20.glDrawElements(GLES20.GL_TRIANGLE_STRIP, AURORA_STRIPS * 2, GLES20.GL_UNSIGNED_SHORT, mAuroraIndexBuffer);
    }

    private void updateAuroraMeshVertices(float width, float height, float frame) {
        float rippleSpeed = 360.0f / 240.0f;
        float rippleOffset = rippleSpeed * frame;
        int vertexIndex = 0;
        for (int y = 0; y < AURORA_STRIPS; y++) {
            float yRatio = ((float) y) / (AURORA_STRIPS - 1);
            float wave = (float) Math.sin((((double) (y * 6)) * Math.PI / 180.0d * 4.5d) - (Math.PI / 180.0d * rippleOffset)) * 10.0f;
            mAuroraVertexBuffer.put(vertexIndex++, wave);
            mAuroraVertexBuffer.put(vertexIndex++, yRatio * height);
            mAuroraVertexBuffer.put(vertexIndex++, 0.0f);
            mAuroraVertexBuffer.put(vertexIndex++, width + wave);
            mAuroraVertexBuffer.put(vertexIndex++, yRatio * height);
            mAuroraVertexBuffer.put(vertexIndex++, 0.0f);
        }
        mAuroraVertexBuffer.position(0);
    }

    private int createProgram(String vertexSource, String fragmentSource) {
        int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);

        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] == 0) {
            String info = GLES20.glGetProgramInfoLog(program);
            GLES20.glDeleteProgram(program);
            throw new IllegalStateException("Aurora2 program link failed: " + info);
        }
        GLES20.glDeleteShader(vertexShader);
        GLES20.glDeleteShader(fragmentShader);
        return program;
    }

    private int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            String info = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IllegalStateException("Aurora2 shader compile failed: " + info);
        }
        return shader;
    }
}
