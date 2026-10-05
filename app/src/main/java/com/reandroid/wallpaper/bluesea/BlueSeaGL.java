package com.reandroid.wallpaper.bluesea;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.opengl.GLES30;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.util.Log;
import android.view.MotionEvent;

import com.reandroid.gles.GLESScene;
import com.reandroid.utils.AssetLoader;

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

    /**
     * 主线程发布的待处理设置；成对读写，渲染线程在 drawFrame 开头取走。
     *
     * <p>不能就地应用：注入来自设置页（主线程）而 drawFrame 在渲染线程，而
     * {@code BlueSeaScene} 在数量变化时会**重建**水母/气泡数组 —— 那不是纯字段写入。
     * 就地交换会让渲染循环读到换了一半的数组（长度与下标是两次读），重建出来的水母也没有纹理。
     */
    private final Object mPrefsLock = new Object();

    private SharedPreferences mPendingPrefs;
    private boolean mPrefsPending;

    public BlueSeaGL(Context context, int width, int height) {
        super(width, height);
        mContext = context;
        mScene = new BlueSeaScene(width, height);
    }

    // ---- Plugin prefs injection ----

    /**
     * 反射注入入口（{@code PluginPrefsInjector} 按方法名找，必须是 public）。
     *
     * <p>只登记，真正的应用在渲染线程的 drawFrame 开头 —— 理由见 {@link #mPrefsLock}。
     * 首次注入发生在第一帧之前，那时 mGlReady 还是 false，纹理由 initGlResources 一次装好。
     */
    public void setPluginPrefs(SharedPreferences prefs) {
        synchronized (mPrefsLock) {
            mPendingPrefs = prefs;
            mPrefsPending = true;
        }
    }

    /** 取走主线程发布的设置并在渲染线程应用（渲染线程调用）。 */
    private void applyPendingPrefs() {
        SharedPreferences prefs;
        synchronized (mPrefsLock) {
            if (!mPrefsPending) {
                return;
            }
            mPrefsPending = false;
            prefs = mPendingPrefs;
        }

        BlueSeaScene.JellyState[] before = mScene.mJellies;
        mScene.setPluginPrefs(prefs);
        // 只改大小的设置不重建数组，也就不该碰纹理（白重装一次会闪一下）
        if (mGlReady && before != mScene.mJellies) {
            deleteJellyTextures(before);
            loadJellyTextures(mScene.mJellies);
        }
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
        applyPendingPrefs();
        if (!mGlReady) {
            initGlResources();
            if (!mGlReady) {
                return;
            }
        }

        mScene.update(timeMs);

        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);

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
            GLES30.glDeleteProgram(mProgram);
            mProgram = 0;
        }
        deleteTexture(mScene.mBackground);
        deleteTexture(mScene.mParticle);
        deleteJellyTextures(mScene.mJellies);
        mGlReady = false;
        mInitialized = false;
    }

    // --- GL buffer init ---

    private void initBuffers() {
        float[] vertices = {-0.5f, -0.5f, 0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f};
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
        mPositionHandle = GLES30.glGetAttribLocation(mProgram, "aPosition");
        mTexCoordHandle = GLES30.glGetAttribLocation(mProgram, "aTexCoord");
        mMvpHandle = GLES30.glGetUniformLocation(mProgram, "uMvpMatrix");
        mColorHandle = GLES30.glGetUniformLocation(mProgram, "uColor");
        mSamplerHandle = GLES30.glGetUniformLocation(mProgram, "uTexture");
    }

    private void initGlResources() {
        initProgram();
        if (mProgram == 0) {
            return;
        }

        mScene.mBackground = loadTexture("bluesea/drawable/bluesea_bg.png");
        mScene.mParticle = loadTexture("bluesea/drawable/bluesea_particle.png");

        loadJellyTextures(mScene.mJellies);

        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glDisable(GLES30.GL_CULL_FACE);
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);

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
        drawSprite(
                mScene.mBackground,
                scrollOffset + (bgWidth / 2.0f),
                bgHeight / 2.0f,
                bgWidth,
                bgHeight,
                1.0f);
    }

    private void drawJellyPlane(int plane, long timeMs) {
        for (int i = 0; i < mScene.mJellies.length; i++) {
            BlueSeaScene.JellyState jelly = mScene.mJellies[i];
            if (jelly.plane != plane) {
                continue;
            }
            // 绘制坐标统一走 Scene(与触摸命中同公式,保证桌面滚动/跨页时点按一致)
            float drawX = mScene.jellyDrawX(jelly, timeMs);
            float drawY = mScene.jellyDrawY(jelly, timeMs);
            float size = jelly.config.size * mScene.mScale * mScene.mJellySizeScale;
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
            drawSprite(
                    mScene.mParticle,
                    drawX,
                    particle.y,
                    particle.size,
                    particle.size,
                    particle.alpha);
        }
    }

    private void drawSprite(
            BlueSeaScene.Texture texture,
            float x,
            float y,
            float width,
            float height,
            float alpha) {
        if (mProgram == 0 || texture == null || texture.id == 0) {
            return;
        }
        GLES30.glUseProgram(mProgram);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture.id);
        GLES30.glUniform1i(mSamplerHandle, 0);
        GLES30.glUniform4f(mColorHandle, 1.0f, 1.0f, 1.0f, alpha);

        Matrix.setIdentityM(mModel, 0);
        Matrix.translateM(mModel, 0, x, y, 0.0f);
        Matrix.scaleM(mModel, 0, width, height, 1.0f);
        Matrix.multiplyMM(mMvp, 0, mProjection, 0, mModel, 0);
        GLES30.glUniformMatrix4fv(mMvpHandle, 1, false, mMvp, 0);

        mVertexBuffer.position(0);
        GLES30.glVertexAttribPointer(mPositionHandle, 2, GLES30.GL_FLOAT, false, 0, mVertexBuffer);
        GLES30.glEnableVertexAttribArray(mPositionHandle);

        mTexBuffer.position(0);
        GLES30.glVertexAttribPointer(mTexCoordHandle, 2, GLES30.GL_FLOAT, false, 0, mTexBuffer);
        GLES30.glEnableVertexAttribArray(mTexCoordHandle);

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);

        GLES30.glDisableVertexAttribArray(mPositionHandle);
        GLES30.glDisableVertexAttribArray(mTexCoordHandle);
    }

    // --- Texture loading / GL utilities ---

    private BlueSeaScene.Texture loadTexture(String assetPath) {
        Bitmap bitmap = AssetLoader.decodeBitmap(mContext, assetPath);
        if (bitmap == null) {
            Log.e(TAG, "Failed to decode texture: " + assetPath);
            return null;
        }
        int[] ids = new int[1];
        GLES30.glGenTextures(1, ids, 0);
        int textureId = ids[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId);
        GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0);
        bitmap.recycle();
        return new BlueSeaScene.Texture(textureId);
    }

    private void deleteTexture(BlueSeaScene.Texture texture) {
        if (texture != null && texture.id != 0) {
            int[] ids = {texture.id};
            GLES30.glDeleteTextures(1, ids, 0);
            texture.id = 0;
        }
    }

    /** 水母数组是重建出来的，纹理必须跟着重装（GL 上下文只在渲染线程 current）。 */
    private void loadJellyTextures(BlueSeaScene.JellyState[] jellies) {
        for (BlueSeaScene.JellyState jelly : jellies) {
            jelly.image = loadTexture(jelly.config.imageAsset);
            jelly.glow = loadTexture(jelly.config.glowAsset);
        }
    }

    private void deleteJellyTextures(BlueSeaScene.JellyState[] jellies) {
        for (BlueSeaScene.JellyState jelly : jellies) {
            deleteTexture(jelly.image);
            deleteTexture(jelly.glow);
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
