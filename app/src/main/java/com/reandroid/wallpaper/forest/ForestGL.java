package com.reandroid.wallpaper.forest;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.SystemClock;
import android.view.MotionEvent;

import com.reandroid.wallpaper.R;
import com.reandroid.gles.GLESScene;
import com.reandroid.gles.RawResourceLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Forest 壁纸 GLES2 渲染核心。
 * 100% 还原原 GLES1.1 视觉效果。
 */
public class ForestGL extends GLESScene {

    // ---- Scene ----
    private final ForestScene mScene;

    // ---- GL resources ----
    private int mProgram;
    private int mAPosition, mATexCoord;
    private int mUMVPMatrix, mUAlpha;
    private int mUTexture;

    private final float[] mProjectionMatrix = new float[16];
    private final float[] mModelMatrix = new float[16];
    private final float[] mMVPMatrix = new float[16];

    private int[] mTexIndex;
    private FloatBuffer mVertexBG, mVertexOverlay;
    private FloatBuffer mVertexStem, mVertexParticle;
    private FloatBuffer mTexCoordDefault;

    private boolean mGlReady;
    private long mLastFrameMs;

    private static final float[] VERTEX_BG = {
        0,0,0, ForestScene.SCREEN_H,0,0, 0,ForestScene.SCREEN_H,0, ForestScene.SCREEN_H,ForestScene.SCREEN_H,0
    };
    private static final float[] VERTEX_OVERLAY = {
        0,0,0, ForestScene.SCREEN_H,0,0, 0,ForestScene.SCREEN_H,0, ForestScene.SCREEN_H,ForestScene.SCREEN_H,0
    };
    private static final float[] VERTEX_PARTICLE = {
        0,0,0, 20,0,0, 0,20,0, 20,20,0
    };
    private static final float[] VERTEX_STEM = {
        0,0,0, 36,0,0, 0,320,0, 36,320,0
    };
    private static final float[] TEX_COORD_DEFAULT = {
        0,1, 1,1, 0,0, 1,0
    };

    public ForestGL(int width, int height) {
        super(width, height);
        mScene = new ForestScene();
    }

    @Override
    protected void onCreate() {
        // Scene initialized lazily in initGL
    }

    @Override
    public void release() {
        if (mTexIndex != null) {
            GLES20.glDeleteTextures(mTexIndex.length, mTexIndex, 0);
            mTexIndex = null;
        }
        if (mProgram != 0) {
            GLES20.glDeleteProgram(mProgram);
            mProgram = 0;
        }
        mGlReady = false;
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        if (mGlReady) {
            setupProjection(width, height);
        }
    }

    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        mScene.setOffset(xOffset, isPreview());
    }

    @Override
    public void onTouchEvent(MotionEvent event) {
        if (event == null) return;
        int action;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: action = ForestScene.ACTION_DOWN; break;
            case MotionEvent.ACTION_UP:   action = ForestScene.ACTION_UP; break;
            case MotionEvent.ACTION_MOVE: action = ForestScene.ACTION_MOVE; break;
            default: return;
        }
        // Scale touch coords from screen space to 240x320 reference
        float sx = ForestScene.SCREEN_W / mWidth;
        float sy = ForestScene.SCREEN_H / mHeight;
        mScene.onTouchEvent(action, event.getX() * sx, event.getY() * sy);
    }

    @Override
    public void drawFrame(long timeMs) {
        if (!mGlReady) {
            initGL();
            return;
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        mScene.update();

        GLES20.glUseProgram(mProgram);

        // enable vertex/texcoord arrays
        GLES20.glEnableVertexAttribArray(mAPosition);
        GLES20.glEnableVertexAttribArray(mATexCoord);

        drawBackground();
        drawStems();
        drawParticles();

        GLES20.glDisableVertexAttribArray(mAPosition);
        GLES20.glDisableVertexAttribArray(mATexCoord);

        mLastFrameMs = timeMs;
    }

    // ---- GL init ----

    private void initGL() {
        String vs = "attribute vec4 aPosition;\nattribute vec2 aTexCoord;\nvarying vec2 vTexCoord;\nuniform mat4 uMVPMatrix;\nvoid main() {\n  gl_Position = uMVPMatrix * aPosition;\n  vTexCoord = aTexCoord;\n}";
        String fs = "precision mediump float;\nvarying vec2 vTexCoord;\nuniform sampler2D uTexture;\nuniform float uAlpha;\nvoid main() {\n  vec4 c = texture2D(uTexture, vTexCoord);\n  gl_FragColor = vec4(c.rgb, c.a * uAlpha);\n}";

        mProgram = createProgram(vs, fs);
        mAPosition = GLES20.glGetAttribLocation(mProgram, "aPosition");
        mATexCoord = GLES20.glGetAttribLocation(mProgram, "aTexCoord");
        mUMVPMatrix = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");
        mUAlpha = GLES20.glGetUniformLocation(mProgram, "uAlpha");
        mUTexture = GLES20.glGetUniformLocation(mProgram, "uTexture");

        GLES20.glClearColor(0, 0, 0, 1);

        setupProjection(mWidth, mHeight);
        initBuffers();
        loadTextures();
        mScene.init(mWidth, mHeight);

        mGlReady = true;
    }

    private void setupProjection(int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        Matrix.orthoM(mProjectionMatrix, 0, 0, ForestScene.SCREEN_W, 0, ForestScene.SCREEN_H, -10, 1000);
    }

    private void initBuffers() {
        mVertexBG = makeBuffer(VERTEX_BG);
        mVertexOverlay = makeBuffer(VERTEX_OVERLAY);
        mVertexStem = makeBuffer(VERTEX_STEM);
        mVertexParticle = makeBuffer(VERTEX_PARTICLE);
        mTexCoordDefault = makeBuffer(TEX_COORD_DEFAULT);
    }

    private FloatBuffer makeBuffer(float[] data) {
        FloatBuffer fb = ByteBuffer.allocateDirect(data.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(data).position(0);
        return fb;
    }

    // ---- texture loading ----

    private void loadTextures() {
        String[] assets = {"forest_bg_16_16", "forest_bg_overlay_256_512", "forest_bg2_16_16",
                "forest_particle_32_32", "forest_stem_a_64_512", "forest_stem_b_01_64_512",
                "forest_stem_c_64_512", "forest_stem_b_02_64_512"};
        int[] ids = {R.drawable.forest_bg_16_16, R.drawable.forest_bg_overlay_256_512,
                R.drawable.forest_bg2_16_16, R.drawable.forest_particle_32_32,
                R.drawable.forest_stem_a_64_512, R.drawable.forest_stem_b_01_64_512,
                R.drawable.forest_stem_c_64_512, R.drawable.forest_stem_b_02_64_512};

        mTexIndex = new int[assets.length];
        GLES20.glGenTextures(assets.length, mTexIndex, 0);
        for (int i = 0; i < assets.length; i++) {
            mTexIndex[i] = loadTexture(ids[i]);
        }
    }

    private int loadTexture(int resId) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inScaled = false;
        opts.inPremultiplied = false;
        Bitmap bmp = BitmapFactory.decodeResource(mResources, resId, opts);
        if (bmp == null) return 0;

        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
        bmp.recycle();
        return tex[0];
    }

    // ---- draw calls ----

    private void drawBackground() {
        // bg layer (tex 0) — opaque, disable blend
        GLES20.glDisable(GLES20.GL_BLEND);
        bindTexture(mTexIndex[0]);
        setAlpha(1.0f);
        Matrix.setIdentityM(mModelMatrix, 0);
        Matrix.translateM(mModelMatrix, 0, 0, mScene.mLandscapeY, -100);
        drawQuad(mVertexBG);

        // overlay layer (tex 1) with additive blend + pulsing alpha
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE);
        bindTexture(mTexIndex[1]);
        setAlpha(mScene.mOverlayOutTime);
        Matrix.setIdentityM(mModelMatrix, 0);
        Matrix.translateM(mModelMatrix, 0, 30 + mScene.mXOffset, mScene.mLandscapeY, -100);
        drawQuad(mVertexOverlay);

        // restore blend
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
    }

    private void drawStems() {
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        bindVertex(mVertexStem);
        bindTexCoord(mTexCoordDefault);

        for (int i = 0; i < ForestScene.MAX_STEM; i++) {
            ForestScene.Stem s = mScene.mStems[i];
            bindTexture(mTexIndex[s.texIndex]);
            setAlpha(1.0f);

            Matrix.setIdentityM(mModelMatrix, 0);
            Matrix.translateM(mModelMatrix, 0, mScene.mXOffset + s.x, mScene.mLandscapeY, 0);
            Matrix.translateM(mModelMatrix, 0, ForestScene.STEM_WIDTH, ForestScene.STEM_HEIGHT, 0);
            Matrix.rotateM(mModelMatrix, 0, s.angle, 0, 0, 1);
            Matrix.translateM(mModelMatrix, 0, -ForestScene.STEM_WIDTH, -ForestScene.STEM_HEIGHT, 0);

            computeMVP();
            GLES20.glUniformMatrix4fv(mUMVPMatrix, 1, false, mMVPMatrix, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }
    }

    private void drawParticles() {
        // 粒子使用标准非预乘混合
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        bindTexture(mTexIndex[3]);
        bindVertex(mVertexParticle);
        bindTexCoord(mTexCoordDefault);

        for (int i = 0; i < ForestScene.MAX_PARTICLES; i++) {
            ForestScene.Particle p = mScene.mParticles[i];
            if (p.active != 1) continue;

            Matrix.setIdentityM(mModelMatrix, 0);
            Matrix.translateM(mModelMatrix, 0, p.x, p.y, 0);
            Matrix.translateM(mModelMatrix, 0, p.moveX, p.moveY, 0);
            Matrix.scaleM(mModelMatrix, 0, p.scaleWeight, p.scaleWeight, 0);
            Matrix.translateM(mModelMatrix, 0, -ForestScene.PARTICLE_GAP_X, -ForestScene.PARTICLE_GAP_Y, 1);

            setAlpha(p.outTime);
            computeMVP();
            GLES20.glUniformMatrix4fv(mUMVPMatrix, 1, false, mMVPMatrix, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }
    }

    // ---- helpers ----

    private void bindTexture(int tex) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
        GLES20.glUniform1i(mUTexture, 0);
    }

    private void bindVertex(FloatBuffer buf) {
        buf.position(0);
        GLES20.glVertexAttribPointer(mAPosition, 3, GLES20.GL_FLOAT, false, 0, buf);
    }

    private void bindTexCoord(FloatBuffer buf) {
        buf.position(0);
        GLES20.glVertexAttribPointer(mATexCoord, 2, GLES20.GL_FLOAT, false, 0, buf);
    }

    private void setAlpha(float a) {
        GLES20.glUniform1f(mUAlpha, a);
    }

    private void drawQuad(FloatBuffer verts) {
        verts.position(0);
        GLES20.glVertexAttribPointer(mAPosition, 3, GLES20.GL_FLOAT, false, 0, verts);
        bindTexCoord(mTexCoordDefault);
        computeMVP();
        GLES20.glUniformMatrix4fv(mUMVPMatrix, 1, false, mMVPMatrix, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private void computeMVP() {
        Matrix.multiplyMM(mMVPMatrix, 0, mProjectionMatrix, 0, mModelMatrix, 0);
    }

    private int createProgram(String vs, String fs) {
        int v = loadShader(GLES20.GL_VERTEX_SHADER, vs);
        int f = loadShader(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        GLES20.glDeleteShader(v);
        GLES20.glDeleteShader(f);
        return p;
    }

    private int loadShader(int type, String source) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, source);
        GLES20.glCompileShader(s);
        return s;
    }
}
