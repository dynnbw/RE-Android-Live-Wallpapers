package com.reandroid.wallpaper.bluesea;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.util.Log;
import android.view.MotionEvent;

import com.reandroid.utils.AssetLoader;
import com.reandroid.gles.GLESScene;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class BlueSeaGL extends GLESScene {
    private static final String TAG = "BlueSeaGL";

    private final Context mContext;
    private final BlueSeaScene mScene;

    private int mProgram;
    private int mPositionHandle;
    private int mTexCoordHandle;
    private int mMvpHandle;
    private int mColorHandle;
    private int mSamplerHandle;

    private FloatBuffer mVertexBuffer;
    private FloatBuffer mTexBuffer;

    private final float[] mProjection = new float[16];
    private final float[] mModel = new float[16];
    private final float[] mMvp = new float[16];

    private boolean mInitialized;
    private boolean mGlReady;

    public BlueSeaGL(Context context, int width, int height) {
        super(width, height);
        mContext = context;
        mScene = new BlueSeaScene(width, height);
    }

    @Override
    protected void onCreate() {
        if (mInitialized) {
            return;
        }
        if (mResources == null) {
            Log.w(TAG, "onCreate() called without resources");
            return;
        }
        mInitialized = true;

        initBuffers();
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        mScene.resize(width, height);
        Matrix.orthoM(mProjection, 0, 0.0f, width, height, 0.0f, -1.0f, 1.0f);
    }

    @Override
    public void drawFrame(long timeMs) {
        if (!mInitialized) {
            return;
        }
        if (!mGlReady) {
            initGlResources();
            if (!mGlReady) {
                return;
            }
        }

        mScene.update(timeMs);

        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        drawBackground();
        drawJellyPlane(2, timeMs);
        drawJellyPlane(1, timeMs);
        drawJellyPlane(0, timeMs);
        drawParticles();
    }

    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        mScene.setOffset(xOffset);
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
        deleteTexture(mScene.mBackground);
        deleteTexture(mScene.mParticle);
        if (mScene.mJellies != null) {
            for (BlueSeaScene.JellyState jelly : mScene.mJellies) {
                deleteTexture(jelly.image);
                deleteTexture(jelly.glow);
            }
        }
        mGlReady = false;
        mInitialized = false;
    }

    // --- GL buffer init ---

    private void initBuffers() {
        float[] vertices = {
            -0.5f, -0.5f,
             0.5f, -0.5f,
            -0.5f,  0.5f,
             0.5f,  0.5f
        };
        float[] tex = {
            0.0f, 1.0f,
            1.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 0.0f
        };
        mVertexBuffer = createBuffer(vertices);
        mTexBuffer = createBuffer(tex);
    }

    // --- GL program / resource init ---

    private void initProgram() {
        String vs = AssetLoader.readText(mContext, "bluesea/shaders/GLES/bluesea_sprite_vs.glsl");
        String fs = AssetLoader.readText(mContext, "bluesea/shaders/GLES/bluesea_sprite_fs.glsl");
        mProgram = createProgram(vs, fs);
        if (mProgram == 0) {
            Log.e(TAG, "Failed to create sprite program");
        }
        mPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
        mTexCoordHandle = GLES20.glGetAttribLocation(mProgram, "aTexCoord");
        mMvpHandle = GLES20.glGetUniformLocation(mProgram, "uMvpMatrix");
        mColorHandle = GLES20.glGetUniformLocation(mProgram, "uColor");
        mSamplerHandle = GLES20.glGetUniformLocation(mProgram, "uTexture");
    }

    private void initGlResources() {
        initProgram();
        if (mProgram == 0) {
            return;
        }

        mScene.mBackground = loadTexture("bluesea/drawable/bluesea_bg.png");
        mScene.mParticle = loadTexture("bluesea/drawable/bluesea_particle.png");

        if (mScene.mJellies != null) {
            for (BlueSeaScene.JellyState jelly : mScene.mJellies) {
                jelly.image = loadTexture(jelly.config.imageAsset);
                jelly.glow = loadTexture(jelly.config.glowAsset);
            }
        }

        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        mGlReady = true;
    }

    // --- Draw methods ---

    private void drawBackground() {
        if (mScene.mBackground == null) {
            return;
        }
        float scrollOffset = -mScene.mXOffset * mWidth * 1.5f;
        float bgWidth = (mHeight > mWidth) ? (mHeight * 1.5f) : (mWidth * 2.5f);
        float bgHeight = (mHeight > mWidth) ? mHeight : (mWidth * 5.0f / 3.0f);
        drawSprite(mScene.mBackground, scrollOffset + (bgWidth / 2.0f), bgHeight / 2.0f, bgWidth, bgHeight, 1.0f);
    }

    private void drawJellyPlane(int plane, long timeMs) {
        for (int i = 0; i < mScene.mJellies.length; i++) {
            BlueSeaScene.JellyState jelly = mScene.mJellies[i];
            if (mScene.getJellyPlane(i) != plane) {
                continue;
            }
            // 绘制坐标统一走 Scene(与触摸命中同公式,保证桌面滚动/跨页时点按一致)
            float drawX = mScene.jellyDrawX(jelly, i, timeMs);
            float drawY = mScene.jellyDrawY(jelly, timeMs);
            float size = jelly.config.size * mScene.mScale;
            float scale = mScene.computeSwimScale(jelly, timeMs);

            if (drawX + size < 0 || drawX - size > mWidth) {
                continue;
            }

            drawSprite(jelly.image, drawX, drawY, size * scale, size * scale, 1.0f);
            float glowAlpha = mScene.computeGlowAlpha(jelly, timeMs);
            if (glowAlpha > 0.0f) {
                drawSprite(jelly.glow, drawX, drawY, size * scale, size * scale, glowAlpha);
            }
        }
    }

    private void drawParticles() {
        if (mScene.mParticle == null) {
            return;
        }
        float scrollOffset = -mScene.mXOffset * mWidth * 1.8f;
        for (BlueSeaScene.Particle particle : mScene.mParticles) {
            float drawX = particle.x + scrollOffset;
            if (drawX + particle.size < 0 || drawX - particle.size > mWidth) {
                continue;
            }
            drawSprite(mScene.mParticle, drawX, particle.y, particle.size, particle.size, particle.alpha);
        }
    }

    private void drawSprite(BlueSeaScene.Texture texture, float x, float y, float width, float height, float alpha) {
        if (mProgram == 0 || texture == null || texture.id == 0) {
            return;
        }
        GLES20.glUseProgram(mProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture.id);
        GLES20.glUniform1i(mSamplerHandle, 0);
        GLES20.glUniform4f(mColorHandle, 1.0f, 1.0f, 1.0f, alpha);

        Matrix.setIdentityM(mModel, 0);
        Matrix.translateM(mModel, 0, x, y, 0.0f);
        Matrix.scaleM(mModel, 0, width, height, 1.0f);
        Matrix.multiplyMM(mMvp, 0, mProjection, 0, mModel, 0);
        GLES20.glUniformMatrix4fv(mMvpHandle, 1, false, mMvp, 0);

        mVertexBuffer.position(0);
        GLES20.glVertexAttribPointer(mPositionHandle, 2, GLES20.GL_FLOAT, false, 0, mVertexBuffer);
        GLES20.glEnableVertexAttribArray(mPositionHandle);

        mTexBuffer.position(0);
        GLES20.glVertexAttribPointer(mTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, mTexBuffer);
        GLES20.glEnableVertexAttribArray(mTexCoordHandle);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(mPositionHandle);
        GLES20.glDisableVertexAttribArray(mTexCoordHandle);
    }

    // --- Texture loading / GL utilities ---

    private BlueSeaScene.Texture loadTexture(String assetPath) {
        Bitmap bitmap = AssetLoader.decodeBitmap(mContext, assetPath);
        if (bitmap == null) {
            Log.e(TAG, "Failed to decode texture: " + assetPath);
            return null;
        }
        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        int textureId = ids[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        bitmap.recycle();
        return new BlueSeaScene.Texture(textureId);
    }

    private void deleteTexture(BlueSeaScene.Texture texture) {
        if (texture != null && texture.id != 0) {
            int[] ids = {texture.id};
            GLES20.glDeleteTextures(1, ids, 0);
            texture.id = 0;
        }
    }



    private static FloatBuffer createBuffer(float[] data) {
        ByteBuffer bb = ByteBuffer.allocateDirect(data.length * 4);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer buffer = bb.asFloatBuffer();
        buffer.put(data);
        buffer.position(0);
        return buffer;
    }
}
