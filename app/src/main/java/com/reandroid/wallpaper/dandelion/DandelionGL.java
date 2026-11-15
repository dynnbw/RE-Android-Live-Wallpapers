package com.reandroid.wallpaper.dandelion;

import android.content.Context;
import android.content.SharedPreferences;
import android.opengl.GLES30;
import android.view.MotionEvent;

import com.reandroid.gles.GLESScene;
import com.reandroid.utils.AssetLoader;
import com.reandroid.utils.GLTextureUtils;
import com.reandroid.utils.Mat4;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.Random;

/**
 * 蒲公英的 GLES 渲染：一片程序化天空，加上若干张四边形贴图。
 *
 * <p>菱形变换（茎的摆动、花头的倾角、整片浮尘的旋转）都在 CPU 上算成一个 2D 仿射矩阵，
 * 顶点直接落成屏幕像素 —— 数量就几十个，没必要上模型矩阵。
 */
public class DandelionGL extends GLESScene {

    private static final int MAX_QUADS = 512;
    private static final int VERTICES_PER_QUAD = 6;
    /** x, y, u, v, alpha。 */
    private static final int FLOATS_PER_VERTEX = 5;

    private static final int STRIDE_BYTES = FLOATS_PER_VERTEX * 4;

    private static final int TEX_DUST_SHEET = 0;
    private static final int TEX_DUST_SMALL = 1;
    private static final int TEX_DUST_LARGE = 2;
    private static final int TEX_HEAD = 3;
    private static final int TEX_STEM_A = 4;
    private static final int TEX_STEM_B = 5;
    private static final int TEX_STEM_C = 6;
    private static final int TEX_SPORE_FIRST = 7;

    private static final String[] TEXTURE_PATHS = {
        "dandelion/drawable/dust_sheet.png",
        "dandelion/drawable/dust_small.png",
        "dandelion/drawable/dust_large.png",
        "dandelion/drawable/head_sheet.png",
        "dandelion/drawable/stem_a.png",
        "dandelion/drawable/stem_b.png",
        "dandelion/drawable/stem_c.png",
        "dandelion/drawable/spore_1.png",
        "dandelion/drawable/spore_2.png",
        "dandelion/drawable/spore_3.png",
        "dandelion/drawable/spore_4.png",
        "dandelion/drawable/spore_5.png",
        "dandelion/drawable/spore_6.png",
        "dandelion/drawable/spore_7.png",
    };

    /** 参考实现 bg.png 采样出来的色标：位置（设计稿行号）与颜色。 */
    private static final float[] SKY_STOPS = {
        0f, 40f, 100f, 200f, 300f, 400f, 500f, 600f, 700f, 760f, 800f,
    };

    private static final float[] SKY_COLORS = {
        0.290f, 0.490f, 1.000f,
        0.322f, 0.525f, 0.969f,
        0.353f, 0.573f, 0.906f,
        0.420f, 0.729f, 0.808f,
        0.451f, 0.827f, 0.776f,
        0.518f, 0.875f, 0.741f,
        0.612f, 0.890f, 0.678f,
        0.741f, 0.890f, 0.612f,
        0.871f, 0.906f, 0.549f,
        0.937f, 0.906f, 0.549f,
        0.969f, 0.906f, 0.549f,
    };

    private static final float GUST_DISTANCE_PX = 90f;
    private static final long GUST_COOLDOWN_MS = 700L;
    private static final float SCROLL_GUST_PX = 120f;

    private final Context mContext;
    private final DandelionScene mScene = new DandelionScene(new Random());
    private final float[] mProj = new float[16];
    private final FloatBuffer mVertexBuffer;
    private final FloatBuffer mSkyBuffer;

    private int mSpriteProgram;
    private int mSkyProgram;
    private int mPosLoc;
    private int mUvLoc;
    private int mAlphaLoc;
    private int mMvpLoc;
    private int mTexLoc;

    private int mSkyPosLoc;
    private int mSkyDesignYLoc;
    private int mSkyMvpLoc;
    private int mSkyStopsLoc;
    private int mSkyStopPosLoc;

    private final int[] mTextures = new int[TEXTURE_PATHS.length];

    private int mCurrentTexture;
    private int mVertexCount;
    private int mSceneWidth = -1;
    private int mSceneHeight = -1;

    private long mLastGustMs;
    private float mDownX;
    private float mDownY;
    private float mGestureDistance;
    private boolean mGestureGusted;
    private int mLastOffsetPixels;
    private boolean mOffsetSeeded;

    public DandelionGL(int width, int height, Context context) {
        super(width, height);
        mContext = context;
        mVertexBuffer = ByteBuffer.allocateDirect(
                        MAX_QUADS * VERTICES_PER_QUAD * FLOATS_PER_VERTEX * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        mSkyBuffer = ByteBuffer.allocateDirect(VERTICES_PER_QUAD * 3 * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
    }

    @Override
    protected void onCreate() {}

    public DandelionScene scene() {
        return mScene;
    }

    public void setBatteryLevel(int level) {
        mScene.setBatteryLevel(level);
    }

    /** 设置页按方法名反射注入，转发给纯逻辑的场景。 */
    public void setPluginPrefs(SharedPreferences prefs) {
        mScene.setPluginPrefs(prefs);
    }

    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        if (!mOffsetSeeded) {
            mOffsetSeeded = true;
            mLastOffsetPixels = xPixels;
            return;
        }
        int delta = Math.abs(xPixels - mLastOffsetPixels);
        mLastOffsetPixels = xPixels;
        if (delta >= SCROLL_GUST_PX) {
            applyGust();
            mScene.puffAll();
        }
    }

    @Override
    public void onCommand(String action, int x, int y, int z) {
        if ("android.wallpaper.tap".equals(action)) {
            handleTap(x, y);
        }
    }

    @Override
    public void onTouchEvent(MotionEvent event) {
        if (event == null) {
            return;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = event.getX();
                mDownY = event.getY();
                mGestureDistance = 0f;
                mGestureGusted = false;
                handleTap(event.getX(), event.getY());
                break;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getX() - mDownX;
                float dy = event.getY() - mDownY;
                mGestureDistance = Math.max(mGestureDistance, (float) Math.hypot(dx, dy));
                if (!mGestureGusted && mGestureDistance >= GUST_DISTANCE_PX) {
                    mGestureGusted = true;
                    applyGust();
                    mScene.puffAll();
                }
                break;
            default:
                break;
        }
    }

    private void handleTap(float x, float y) {
        int flower = mScene.flowerAt(x, y);
        if (flower >= 0) {
            mScene.puff(flower);
            applyGust();
        }
    }

    private void applyGust() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - mLastGustMs < GUST_COOLDOWN_MS) {
            return;
        }
        mLastGustMs = now;
        mScene.gust();
    }

    @Override
    public void drawFrame(long timeMs) {
        initGLIfNeeded();
        ensureSceneSize();

        mScene.update(timeMs);

        GLES30.glClearColor(0.29f, 0.49f, 1f, 1f);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        if (mSpriteProgram == 0 || mSkyProgram == 0) {
            return;
        }

        drawSky();
        drawSprites(timeMs);
    }

    private void ensureSceneSize() {
        if (mWidth == mSceneWidth && mHeight == mSceneHeight) {
            return;
        }
        mSceneWidth = mWidth;
        mSceneHeight = mHeight;
        mScene.resize(mWidth, mHeight);
        Mat4.orthoM(mProj, 0.0f, mWidth, mHeight, 0.0f, -1.0f, 1.0f);
    }

    private void drawSky() {
        float scale = mScene.scale();
        float designTop = -mScene.offsetY() / scale;
        mSkyBuffer.clear();
        mSkyBuffer.put(0f).put(0f).put(designTop);
        mSkyBuffer.put(mWidth).put(0f).put(designTop);
        mSkyBuffer.put(mWidth).put(mHeight).put(DandelionScene.DESIGN_HEIGHT);
        mSkyBuffer.put(0f).put(0f).put(designTop);
        mSkyBuffer.put(mWidth).put(mHeight).put(DandelionScene.DESIGN_HEIGHT);
        mSkyBuffer.put(0f).put(mHeight).put(DandelionScene.DESIGN_HEIGHT);
        mSkyBuffer.position(0);

        GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glUseProgram(mSkyProgram);
        GLES30.glUniformMatrix4fv(mSkyMvpLoc, 1, false, mProj, 0);
        GLES30.glUniform3fv(mSkyStopsLoc, SKY_COLORS.length / 3, SKY_COLORS, 0);
        GLES30.glUniform1fv(mSkyStopPosLoc, SKY_STOPS.length, SKY_STOPS, 0);

        GLES30.glEnableVertexAttribArray(mSkyPosLoc);
        GLES30.glVertexAttribPointer(mSkyPosLoc, 2, GLES30.GL_FLOAT, false, 12, mSkyBuffer);
        GLES30.glEnableVertexAttribArray(mSkyDesignYLoc);
        mSkyBuffer.position(2);
        GLES30.glVertexAttribPointer(mSkyDesignYLoc, 1, GLES30.GL_FLOAT, false, 12, mSkyBuffer);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, VERTICES_PER_QUAD);
        GLES30.glDisableVertexAttribArray(mSkyPosLoc);
        GLES30.glDisableVertexAttribArray(mSkyDesignYLoc);
    }

    private void drawSprites(long timeMs) {
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        GLES30.glUseProgram(mSpriteProgram);
        GLES30.glUniformMatrix4fv(mMvpLoc, 1, false, mProj, 0);
        GLES30.glUniform1i(mTexLoc, 0);
        GLES30.glEnableVertexAttribArray(mPosLoc);
        GLES30.glEnableVertexAttribArray(mUvLoc);
        GLES30.glEnableVertexAttribArray(mAlphaLoc);
        mVertexCount = 0;
        mCurrentTexture = 0;

        drawDustLayers(timeMs);
        drawParticles(mScene.dust(), true, timeMs);
        drawParticles(mScene.spores(), false, timeMs);
        drawFlowers();

        flush();
        GLES30.glDisableVertexAttribArray(mPosLoc);
        GLES30.glDisableVertexAttribArray(mUvLoc);
        GLES30.glDisableVertexAttribArray(mAlphaLoc);
    }

    private void drawDustLayers(long timeMs) {
        float[] screen = designToScreen();
        for (int layer = 0; layer < 2; layer++) {
            float[] m = concat(
                    screen,
                    translate(mScene.dustLayerOffsetX(layer), mScene.dustLayerOffsetY(layer)));
            m = concat(
                    m,
                    rotateAbout(
                            mScene.dustLayerRotation(layer, timeMs),
                            DandelionScene.DESIGN_WIDTH * 0.5f,
                            DandelionScene.DESIGN_HEIGHT * 0.5f));
            setTexture(mTextures[TEX_DUST_SHEET]);
            addQuad(
                    m,
                    0f,
                    0f,
                    DandelionScene.DESIGN_WIDTH,
                    DandelionScene.DESIGN_HEIGHT,
                    0f,
                    0f,
                    1f,
                    1f,
                    1f);
        }
    }

    /** 浮尘用两张大小不一的点点图，孢子用七张姿态各异的种子图。 */
    private void drawParticles(List<DandelionParticle> particles, boolean dust, long timeMs) {
        float[] screen = designToScreen();
        for (int i = 0; i < particles.size(); i++) {
            DandelionParticle p = particles.get(i);
            float alpha = p.alpha(timeMs);
            if (alpha <= 0f) {
                continue;
            }
            float half = p.size * p.sizeFactor(timeMs) * 0.5f;
            if (half <= 0.01f) {
                continue;
            }
            if (p.x + half < -40f
                    || p.x - half > DandelionScene.DESIGN_WIDTH + 40f
                    || p.y + half < -40f
                    || p.y - half > DandelionScene.DESIGN_HEIGHT + 40f) {
                continue;
            }
            int texture = dust
                    ? (p.sprite == 0 ? TEX_DUST_SMALL : TEX_DUST_LARGE)
                    : TEX_SPORE_FIRST + p.sprite;
            setTexture(mTextures[texture]);
            addQuad(
                    concat(screen, translate(p.x, p.y)),
                    -half,
                    -half,
                    half,
                    half,
                    0f,
                    0f,
                    1f,
                    1f,
                    alpha);
        }
    }

    private void drawFlowers() {
        float[] screen = designToScreen();
        int headFrame = mScene.headFrame();
        float frameV0 = headFrame / (float) DandelionScene.HEAD_FRAMES;
        float frameV1 = (headFrame + 1) / (float) DandelionScene.HEAD_FRAMES;
        // 多出来的小株先画（更远），再按参考实现的顺序画三株主花
        for (int index = DandelionScene.MAX_FLOWERS - 1;
                index >= DandelionScene.EMITTING_FLOWERS;
                index--) {
            drawFlower(index, screen, frameV0, frameV1);
        }
        for (int index : DandelionScene.DRAW_ORDER) {
            drawFlower(index, screen, frameV0, frameV1);
        }
    }

    private void drawFlower(int index, float[] screen, float frameV0, float frameV1) {
        if (!mScene.isFlowerVisible(index)) {
            return;
        }
        DandelionScene.Flower f = DandelionScene.flower(index);
        float scale = f.scale;
        float[] stem = concat(screen, translate(f.rootX, f.rootY));
        stem = concat(stem, rotate(mScene.swayDegrees(index)));
        stem = concat(stem, translate(0f, -f.pivotY * scale));

        setTexture(mTextures[TEX_HEAD]);
        float[] head = concat(stem, rotate(f.headRotation));
        addQuad(
                head,
                f.headX * scale,
                f.headY * scale,
                (f.headX + DandelionScene.HEAD_WIDTH) * scale,
                (f.headY + DandelionScene.HEAD_HEIGHT) * scale,
                0f,
                frameV0,
                1f,
                frameV1,
                1f);

        setTexture(mTextures[TEX_STEM_A + f.stem]);
        addQuad(stem, 0f, 0f, f.stemWidth * scale, f.stemHeight * scale, 0f, 0f, 1f, 1f, 1f);
    }

    private float[] designToScreen() {
        float s = mScene.scale();
        float[] m = new float[6];
        m[0] = s;
        m[1] = 0f;
        m[2] = 0f;
        m[3] = s;
        m[4] = mScene.offsetX();
        m[5] = mScene.offsetY();
        return m;
    }

    private void setTexture(int texture) {
        if (texture == mCurrentTexture) {
            return;
        }
        flush();
        mCurrentTexture = texture;
    }

    private void flush() {
        if (mVertexCount == 0 || mCurrentTexture == 0) {
            return;
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mCurrentTexture);
        mVertexBuffer.position(0);
        GLES30.glVertexAttribPointer(
                mPosLoc, 2, GLES30.GL_FLOAT, false, STRIDE_BYTES, mVertexBuffer);
        mVertexBuffer.position(2);
        GLES30.glVertexAttribPointer(
                mUvLoc, 2, GLES30.GL_FLOAT, false, STRIDE_BYTES, mVertexBuffer);
        mVertexBuffer.position(4);
        GLES30.glVertexAttribPointer(
                mAlphaLoc, 1, GLES30.GL_FLOAT, false, STRIDE_BYTES, mVertexBuffer);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, mVertexCount);
        mVertexCount = 0;
        mVertexBuffer.position(0);
    }

    private void addQuad(
            float[] m,
            float x0,
            float y0,
            float x1,
            float y1,
            float u0,
            float v0,
            float u1,
            float v1,
            float alpha) {
        if (mVertexCount + VERTICES_PER_QUAD > MAX_QUADS * VERTICES_PER_QUAD) {
            flush();
        }
        putVertex(m, x0, y0, u0, v0, alpha);
        putVertex(m, x1, y0, u1, v0, alpha);
        putVertex(m, x1, y1, u1, v1, alpha);
        putVertex(m, x0, y0, u0, v0, alpha);
        putVertex(m, x1, y1, u1, v1, alpha);
        putVertex(m, x0, y1, u0, v1, alpha);
        mVertexCount += VERTICES_PER_QUAD;
    }

    private void putVertex(float[] m, float x, float y, float u, float v, float alpha) {
        mVertexBuffer.put(m[0] * x + m[2] * y + m[4]);
        mVertexBuffer.put(m[1] * x + m[3] * y + m[5]);
        mVertexBuffer.put(u);
        mVertexBuffer.put(v);
        mVertexBuffer.put(alpha);
    }

    private static float[] translate(float tx, float ty) {
        return new float[] {1f, 0f, 0f, 1f, tx, ty};
    }

    private static float[] rotate(float degrees) {
        double rad = Math.toRadians(degrees);
        float c = (float) Math.cos(rad);
        float s = (float) Math.sin(rad);
        return new float[] {c, s, -s, c, 0f, 0f};
    }

    private static float[] rotateAbout(float degrees, float cx, float cy) {
        return concat(concat(translate(cx, cy), rotate(degrees)), translate(-cx, -cy));
    }

    /** 先作用 b，再作用 a。 */
    private static float[] concat(float[] a, float[] b) {
        float[] r = new float[6];
        r[0] = a[0] * b[0] + a[2] * b[1];
        r[1] = a[1] * b[0] + a[3] * b[1];
        r[2] = a[0] * b[2] + a[2] * b[3];
        r[3] = a[1] * b[2] + a[3] * b[3];
        r[4] = a[0] * b[4] + a[2] * b[5] + a[4];
        r[5] = a[1] * b[4] + a[3] * b[5] + a[5];
        return r;
    }

    private void initGLIfNeeded() {
        if (mSpriteProgram != 0 || mContext == null) {
            return;
        }
        String spriteVs =
                AssetLoader.readText(mContext, "dandelion/shaders/GLES/dandelion_sprite_vs.glsl");
        String spriteFs =
                AssetLoader.readText(mContext, "dandelion/shaders/GLES/dandelion_sprite_fs.glsl");
        String skyVs =
                AssetLoader.readText(mContext, "dandelion/shaders/GLES/dandelion_sky_vs.glsl");
        String skyFs =
                AssetLoader.readText(mContext, "dandelion/shaders/GLES/dandelion_sky_fs.glsl");

        mSpriteProgram = createProgram(spriteVs, spriteFs);
        mSkyProgram = createProgram(skyVs, skyFs);
        if (mSpriteProgram == 0 || mSkyProgram == 0) {
            return;
        }
        mPosLoc = GLES30.glGetAttribLocation(mSpriteProgram, "aPos");
        mUvLoc = GLES30.glGetAttribLocation(mSpriteProgram, "aUV");
        mAlphaLoc = GLES30.glGetAttribLocation(mSpriteProgram, "aAlpha");
        mMvpLoc = GLES30.glGetUniformLocation(mSpriteProgram, "uMVP");
        mTexLoc = GLES30.glGetUniformLocation(mSpriteProgram, "uTex");

        mSkyPosLoc = GLES30.glGetAttribLocation(mSkyProgram, "aPos");
        mSkyDesignYLoc = GLES30.glGetAttribLocation(mSkyProgram, "aDesignY");
        mSkyMvpLoc = GLES30.glGetUniformLocation(mSkyProgram, "uMVP");
        mSkyStopsLoc = GLES30.glGetUniformLocation(mSkyProgram, "uStops[0]");
        mSkyStopPosLoc = GLES30.glGetUniformLocation(mSkyProgram, "uStopPos[0]");

        for (int i = 0; i < TEXTURE_PATHS.length; i++) {
            mTextures[i] =
                    GLTextureUtils.loadTextureLinearMipmapPremult(mContext, TEXTURE_PATHS[i]);
        }
    }
}
