package com.reandroid.wallpaper.bluesea;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.util.Log;
import android.view.MotionEvent;

import com.reandroid.gles.AssetLoader;
import com.reandroid.gles.GLESScene;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Random;

public class BlueSeaGL extends GLESScene {
    private static final String TAG = "BlueSeaGL";

    private static final int DESIGN_WIDTH = 480;
    private static final int DESIGN_HEIGHT = 800;

    private static final int PANE_COUNT = 5;
    private static final int JELLY_COUNT = 20;

    private static final long GLOW_DURATION_MS = 550L;

    private static final int[] JELLY_X = {
        200, 300, 20, 120, 140, 240, 60, 160, 80, 380,
        300, 400, 70, 170, 40, 340, 100, 250, 120, 220
    };

    private static final int[] JELLY_Y = {
        600, 100, 400, 600, 300, 500, 200, 500, 400, 300,
        400, 600, 300, 500, 100, 500, 500, 200, 500, 100
    };

    private static final int[] JELLY_PANE_OFFSET = {
        0, 0, 1, 1, 2, 2, 3, 3, 4, 4,
        0, 0, 1, 1, 2, 2, 0, 0, 1, 1
    };

    private static final JellyConfig[] JELLY_CONFIGS = new JellyConfig[] {
        new JellyConfig("bluesea/drawable/bluesea_bubble_1.png", "bluesea/drawable/bluesea_bubble_press.png", 180, 1000, 5.8f, 9.1f),
        new JellyConfig("bluesea/drawable/bluesea_bubble_4.png", "bluesea/drawable/bluesea_bubble_press.png", 162, 1300, 7.7f, 5.6f),
        new JellyConfig("bluesea/drawable/bluesea_bubble_1.png", "bluesea/drawable/bluesea_bubble_press.png", 170, 1500, 6.5f, 7.7f),
        new JellyConfig("bluesea/drawable/bluesea_bubble_4.png", "bluesea/drawable/bluesea_bubble_press.png", 200, 1100, 8.2f, 7.4f),
        new JellyConfig("bluesea/drawable/bluesea_bubble_1.png", "bluesea/drawable/bluesea_bubble_press.png", 180, 1200, 5.2f, 9.9f),
        new JellyConfig("bluesea/drawable/bluesea_bubble_4.png", "bluesea/drawable/bluesea_bubble_press.png", 160, 1600, 7.9f, 5.8f),
        new JellyConfig("bluesea/drawable/bluesea_bubble_1.png", "bluesea/drawable/bluesea_bubble_press.png", 200, 1000, 5.8f, 9.1f),
        new JellyConfig("bluesea/drawable/bluesea_bubble_4.png", "bluesea/drawable/bluesea_bubble_press.png", 160, 1300, 7.7f, 5.6f),
        new JellyConfig("bluesea/drawable/bluesea_bubble_1.png", "bluesea/drawable/bluesea_bubble_press.png", 170, 1500, 7.5f, 6.7f),
        new JellyConfig("bluesea/drawable/bluesea_bubble_4.png", "bluesea/drawable/bluesea_bubble_press.png", 190, 1100, 8.4f, 7.2f),
        new JellyConfig("bluesea/drawable/bluesea_bubble_1_blur1.png", "bluesea/drawable/bluesea_bubble_press_blur1.png", 150, 1200, 8.2f, 5.9f),
        new JellyConfig("bluesea/drawable/bluesea_bubble_4_blur1.png", "bluesea/drawable/bluesea_bubble_press_blur1.png", 140, 1600, 7.9f, 8.4f),
        new JellyConfig("bluesea/drawable/bluesea_bubble_1_blur1.png", "bluesea/drawable/bluesea_bubble_press_blur1.png", 140, 1800, 6.2f, 5.4f),
        new JellyConfig("bluesea/drawable/bluesea_bubble_4_blur1.png", "bluesea/drawable/bluesea_bubble_press_blur1.png", 150, 1600, 5.7f, 8.2f),
        new JellyConfig("bluesea/drawable/bluesea_bubble_1_blur1.png", "bluesea/drawable/bluesea_bubble_press_blur1.png", 130, 1200, 8.3f, 6.9f),
        new JellyConfig("bluesea/drawable/bluesea_bubble_4_blur1.png", "bluesea/drawable/bluesea_bubble_press_blur1.png", 150, 1500, 6.2f, 7.5f),
        new JellyConfig("bluesea/drawable/bluesea_bubble_1_blur2.png", "bluesea/drawable/bluesea_bubble_press_blur2.png", 120, 1300, 7.1f, 5.8f),
        new JellyConfig("bluesea/drawable/bluesea_bubble_4_blur2.png", "bluesea/drawable/bluesea_bubble_press_blur2.png", 110, 1700, 5.9f, 7.1f),
        new JellyConfig("bluesea/drawable/bluesea_bubble_1_blur2.png", "bluesea/drawable/bluesea_bubble_press_blur2.png", 100, 1100, 8.0f, 6.7f),
        new JellyConfig("bluesea/drawable/bluesea_bubble_4_blur2.png", "bluesea/drawable/bluesea_bubble_press_blur2.png", 120, 1000, 6.8f, 5.8f)
    };

    private static final int PARTICLE_COUNT = 40;
    private static final long JELLY_TURN_INTERVAL_MIN_MS = 700L;
    private static final long JELLY_TURN_INTERVAL_MAX_MS = 1800L;
    private static final float JELLY_SPEED_MIN = 18.0f;
    private static final float JELLY_SPEED_MAX = 46.0f;

    private final Context mContext;
    private final Random mRandom = new Random();

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

    private float mXOffset;
    private float mScaleX = 1.0f;
    private float mScaleY = 1.0f;
    private float mScale = 1.0f;

    private long mLastTimeMs;
    private Texture mBackground;
    private Texture mParticle;

    private JellyState[] mJellies;
    private Particle[] mParticles;

    public BlueSeaGL(Context context, int width, int height) {
        super(width, height);
        mContext = context;
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
        initScene();
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        mScaleX = width / (float) DESIGN_WIDTH;
        mScaleY = height / (float) DESIGN_HEIGHT;
        mScale = (mScaleX + mScaleY) * 0.5f;
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
        if (mLastTimeMs == 0L) {
            mLastTimeMs = timeMs;
        }
        float dt = (timeMs - mLastTimeMs) * 0.001f;
        mLastTimeMs = timeMs;

        updateJellies(timeMs, dt);
        updateParticles(dt);

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
        mXOffset = xOffset;
    }

    @Override
    public void onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            triggerNearestGlow(event.getX(), event.getY());
        }
    }

    @Override
    public void release() {
        if (mProgram != 0) {
            GLES20.glDeleteProgram(mProgram);
            mProgram = 0;
        }
        deleteTexture(mBackground);
        deleteTexture(mParticle);
        if (mJellies != null) {
            for (JellyState jelly : mJellies) {
                deleteTexture(jelly.image);
                deleteTexture(jelly.glow);
            }
        }
        mGlReady = false;
        mInitialized = false;
    }

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

    private void initScene() {
        mJellies = new JellyState[JELLY_COUNT];
        for (int i = 0; i < JELLY_COUNT; i++) {
            JellyConfig config = JELLY_CONFIGS[i];
            JellyState jelly = new JellyState();
            jelly.config = config;
            jelly.x = JELLY_X[i] * mScaleX;
            jelly.y = JELLY_Y[i] * mScaleY;
            jelly.pane = JELLY_PANE_OFFSET[i];
            jelly.swimPhase = mRandom.nextFloat();
            jelly.driftPhaseX = mRandom.nextFloat();
            jelly.driftPhaseY = mRandom.nextFloat();
            resetJellyVelocity(jelly, 0L);
            mJellies[i] = jelly;
        }

        mParticles = new Particle[PARTICLE_COUNT];
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            mParticles[i] = createParticle();
        }
    }

    private void initGlResources() {
        initProgram();
        if (mProgram == 0) {
            return;
        }
        mBackground = loadTexture("bluesea/drawable/bluesea_bg.png");
        mParticle = loadTexture("bluesea/drawable/bluesea_particle.png");

        if (mJellies != null) {
            for (JellyState jelly : mJellies) {
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

    private void updateJellies(long timeMs, float dt) {
        if (mJellies == null) {
            return;
        }

        for (JellyState jelly : mJellies) {
            if (timeMs >= jelly.nextTurnMs) {
                resetJellyVelocity(jelly, timeMs);
                jelly.glowStartMs = timeMs;
            }

            jelly.x += jelly.vx * dt;
            jelly.y += jelly.vy * dt;

            if (jelly.x < 0.0f) {
                jelly.x = 0.0f;
                jelly.vx = Math.abs(jelly.vx);
            } else if (jelly.x > mWidth) {
                jelly.x = mWidth;
                jelly.vx = -Math.abs(jelly.vx);
            }

            if (jelly.y < 0.0f) {
                jelly.y = 0.0f;
                jelly.vy = Math.abs(jelly.vy);
            } else if (jelly.y > mHeight) {
                jelly.y = mHeight;
                jelly.vy = -Math.abs(jelly.vy);
            }
        }
    }

    private void resetJellyVelocity(JellyState jelly, long timeMs) {
        float angle = mRandom.nextFloat() * (float) (Math.PI * 2.0);
        float speed = (JELLY_SPEED_MIN + mRandom.nextFloat() * (JELLY_SPEED_MAX - JELLY_SPEED_MIN)) * mScale;
        jelly.vx = (float) Math.cos(angle) * speed;
        jelly.vy = (float) Math.sin(angle) * speed;

        long interval = JELLY_TURN_INTERVAL_MIN_MS
            + mRandom.nextInt((int) (JELLY_TURN_INTERVAL_MAX_MS - JELLY_TURN_INTERVAL_MIN_MS + 1L));
        jelly.nextTurnMs = timeMs + interval;
    }

    private void updateParticles(float dt) {
        float totalWidth = mWidth * (float) PANE_COUNT;
        for (Particle particle : mParticles) {
            particle.y -= particle.speed * dt;
            if (particle.y < -particle.size) {
                particle.x = mRandom.nextFloat() * totalWidth;
                particle.y = mHeight + particle.size + mRandom.nextFloat() * mHeight;
                particle.speed = 15.0f + mRandom.nextFloat() * 25.0f;
                particle.size = 20.0f + mRandom.nextFloat() * 40.0f;
                particle.alpha = 0.3f + mRandom.nextFloat() * 0.5f;
            }
        }
    }

    private void drawBackground() {
        if (mBackground == null) {
            return;
        }
        float scrollOffset = -mXOffset * mWidth * 1.5f;
        float bgWidth = (mHeight > mWidth) ? (mHeight * 1.5f) : (mWidth * 2.5f);
        float bgHeight = (mHeight > mWidth) ? mHeight : (mWidth * 5.0f / 3.0f);
        drawSprite(mBackground, scrollOffset + (bgWidth / 2.0f), bgHeight / 2.0f, bgWidth, bgHeight, 1.0f);
    }

    private void drawJellyPlane(int plane, long timeMs) {
        float scrollOffset = -mXOffset * mWidth;
        float factor;
        if (plane == 2) {
            factor = (mWidth > 600) ? 0.5f : 1.0f;
        } else if (plane == 1) {
            factor = (mWidth > 600) ? 0.8f : 1.5f;
        } else {
            factor = (mWidth > 600) ? 1.2f : 2.5f;
        }
        float planeOffset = scrollOffset * factor;

        for (int i = 0; i < JELLY_COUNT; i++) {
            JellyState jelly = mJellies[i];
            if (getJellyPlane(i) != plane) {
                continue;
            }
            float baseX = jelly.x + (jelly.pane * mWidth);
            float driftX = computeDrift(jelly.driftPhaseX, jelly.config.driftDurX, timeMs);
            float driftY = computeDrift(jelly.driftPhaseY, jelly.config.driftDurY, timeMs);
            float size = jelly.config.size * mScale;
            float scale = computeSwimScale(jelly, timeMs);
            float drawX = baseX + planeOffset + driftX;
            float drawY = jelly.y + driftY;

            if (drawX + size < 0 || drawX - size > mWidth) {
                continue;
            }

            drawSprite(jelly.image, drawX, drawY, size * scale, size * scale, 1.0f);
            float glowAlpha = computeGlowAlpha(jelly, timeMs);
            if (glowAlpha > 0.0f) {
                drawSprite(jelly.glow, drawX, drawY, size * scale, size * scale, glowAlpha);
            }
        }
    }

    private void drawParticles() {
        if (mParticle == null) {
            return;
        }
        float scrollOffset = -mXOffset * mWidth * 1.8f;
        for (Particle particle : mParticles) {
            float drawX = particle.x + scrollOffset;
            if (drawX + particle.size < 0 || drawX - particle.size > mWidth) {
                continue;
            }
            drawSprite(mParticle, drawX, particle.y, particle.size, particle.size, particle.alpha);
        }
    }

    private float computeSwimScale(JellyState jelly, long timeMs) {
        float period = Math.max(300.0f, jelly.config.swimTimeMs);
        float phase = ((timeMs * 0.001f) / (period * 0.001f)) + jelly.swimPhase;
        return 1.0f + 0.1f * (float) Math.sin(phase * Math.PI * 2.0);
    }

    private float computeDrift(float phaseOffset, float durationSeconds, long timeMs) {
        float driftSize = 20.0f * mScale;
        float period = Math.max(2.0f, durationSeconds);
        float phase = ((timeMs * 0.001f) / period) + phaseOffset;
        return (float) Math.sin(phase * Math.PI * 2.0) * driftSize;
    }

    private float computeGlowAlpha(JellyState jelly, long timeMs) {
        if (jelly.glowStartMs == 0L) {
            return 0.0f;
        }
        float elapsed = (timeMs - jelly.glowStartMs) / (float) GLOW_DURATION_MS;
        if (elapsed >= 1.0f) {
            return 0.0f;
        }
        float value = 1.0f - Math.abs((elapsed * 2.0f) - 1.0f);
        return value * 0.8f;
    }

    private void triggerNearestGlow(float x, float y) {
        float minDist = Float.MAX_VALUE;
        JellyState nearest = null;
        for (int i = 0; i < JELLY_COUNT; i++) {
            JellyState jelly = mJellies[i];
            float dx = jelly.x - x;
            float dy = jelly.y - y;
            float dist = dx * dx + dy * dy;
            if (dist < minDist) {
                minDist = dist;
                nearest = jelly;
            }
        }
        if (nearest != null) {
            nearest.glowStartMs = mLastTimeMs;
        }
    }

    private int getJellyPlane(int index) {
        if (index >= 16) {
            return 2;
        }
        if (index >= 10) {
            return 1;
        }
        return 0;
    }

    private void drawSprite(Texture texture, float x, float y, float width, float height, float alpha) {
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

    private Texture loadTexture(String assetPath) {
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
        return new Texture(textureId);
    }

    private void deleteTexture(Texture texture) {
        if (texture != null && texture.id != 0) {
            int[] ids = {texture.id};
            GLES20.glDeleteTextures(1, ids, 0);
            texture.id = 0;
        }
    }

    private int createProgram(String vs, String fs) {
        int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vs);
        int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fs);
        if (vertexShader == 0 || fragmentShader == 0) {
            return 0;
        }
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);
        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] == 0) {
            Log.e(TAG, "Program link failed: " + GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            program = 0;
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
            Log.e(TAG, "Shader compile failed: " + GLES20.glGetShaderInfoLog(shader));
            Log.e(TAG, "Shader source:\n" + source);
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    private FloatBuffer createBuffer(float[] data) {
        ByteBuffer bb = ByteBuffer.allocateDirect(data.length * 4);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer buffer = bb.asFloatBuffer();
        buffer.put(data);
        buffer.position(0);
        return buffer;
    }

    private Particle createParticle() {
        Particle particle = new Particle();
        float totalWidth = mWidth * (float) PANE_COUNT;
        particle.x = mRandom.nextFloat() * totalWidth;
        particle.y = mRandom.nextFloat() * mHeight;
        particle.size = 20.0f + mRandom.nextFloat() * 40.0f;
        particle.speed = 15.0f + mRandom.nextFloat() * 25.0f;
        particle.alpha = 0.3f + mRandom.nextFloat() * 0.5f;
        return particle;
    }

    private static class JellyConfig {
        final String imageAsset;
        final String glowAsset;
        final float size;
        final float swimTimeMs;
        final float driftDurX;
        final float driftDurY;

        JellyConfig(String imageAsset, String glowAsset, float size, float swimTimeMs,
                float driftDurX, float driftDurY) {
            this.imageAsset = imageAsset;
            this.glowAsset = glowAsset;
            this.size = size;
            this.swimTimeMs = swimTimeMs;
            this.driftDurX = driftDurX;
            this.driftDurY = driftDurY;
        }
    }

    private static class JellyState {
        JellyConfig config;
        Texture image;
        Texture glow;
        float x;
        float y;
        float vx;
        float vy;
        int pane;
        float swimPhase;
        float driftPhaseX;
        float driftPhaseY;
        long nextTurnMs;
        long glowStartMs;
    }

    private static class Particle {
        float x;
        float y;
        float size;
        float speed;
        float alpha;
    }

    private static class Texture {
        int id;

        Texture(int id) {
            this.id = id;
        }
    }
}
