package com.reandroid.wallpaper.oceanwaves;

import android.content.Context;
import android.opengl.GLES30;

import com.reandroid.gles.GLESScene;
import com.reandroid.utils.AssetLoader;
import com.reandroid.utils.GLTextureUtils;
import com.reandroid.utils.Mat4;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * 海浪：一张 32×32 的网格铺满屏幕，水面着色器在顶点上算波浪、在片元上合成焦散与反射。
 *
 * <p>参考实现是把它当成一张 3D 平面放进视口里画的（视角很窄，透视几乎等于正交），
 * 这里直接用 2D 网格，把俯仰和缩放折算进设计单位，画面效果一致。
 */
public class OceanWavesGL extends GLESScene {

    private static final int SUBDIVISIONS = 32;
    private static final int VERTS_PER_SIDE = SUBDIVISIONS + 1;
    /** x, y, u, v。 */
    private static final int FLOATS_PER_VERTEX = 4;

    private static final String TEX_SEABED = "oceanwaves/drawable/seabed.webp";
    private static final String TEX_ENVMAP = "oceanwaves/drawable/envmap.png";
    private static final String TEX_RIPPLE = "oceanwaves/drawable/ripple.png";

    /** 海床贴图铺满一次要多少设计单位（参考实现的 imagewidth），再除以缩放设置。 */
    private static final float IMAGE_WIDTH = 1440f;

    private final Context mContext;
    private final OceanWavesScene mScene = new OceanWavesScene();
    private final float[] mProj = new float[16];
    private final FloatBuffer mVertexBuffer;
    private final ShortBuffer mIndexBuffer;
    private int mIndexCount;

    private int mProgram;
    private int mPosLoc;
    private int mUvLoc;
    private int mMvpLoc;
    private int mScaleLoc;
    private int mTimeLoc;
    private int mPanLoc;
    private int mDesignSizeLoc;
    private int mImageWidthLoc;
    private int mSeabedLoc;
    private int mEnvmapLoc;
    private int mRippleLoc;

    private int mSeabedTexture;
    private int mEnvmapTexture;
    private int mRippleTexture;

    private int mSceneWidth = -1;
    private int mSceneHeight = -1;
    private boolean mGridBuilt;

    public OceanWavesGL(int width, int height, Context context) {
        super(width, height);
        mContext = context;
        mVertexBuffer = ByteBuffer.allocateDirect(
                        VERTS_PER_SIDE * VERTS_PER_SIDE * FLOATS_PER_VERTEX * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        mIndexBuffer = ByteBuffer.allocateDirect(SUBDIVISIONS * SUBDIVISIONS * 6 * 2)
                .order(ByteOrder.nativeOrder())
                .asShortBuffer();
    }

    @Override
    protected void onCreate() {}

    public void setPluginPrefs(android.content.SharedPreferences prefs) {
        mScene.setPluginPrefs(prefs);
    }

    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        mScene.setOffset(xPixels);
    }

    @Override
    public void drawFrame(long timeMs) {
        initGLIfNeeded();
        ensureSceneSize();
        mScene.update(timeMs);

        GLES30.glClearColor(0.05f, 0.12f, 0.2f, 1f);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        if (mProgram == 0) {
            return;
        }

        GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glUseProgram(mProgram);
        GLES30.glUniformMatrix4fv(mMvpLoc, 1, false, mProj, 0);
        GLES30.glUniform1f(mScaleLoc, mScene.scale());
        GLES30.glUniform1f(mTimeLoc, mScene.time());
        GLES30.glUniform1f(mPanLoc, mScene.panX());
        GLES30.glUniform2f(mDesignSizeLoc, OceanWavesScene.DESIGN_WIDTH, mScene.viewHeightDesign());
        // 缩放值越大越近：一张贴图占的设计单位跟着变大，屏幕上看到的范围变小。
        GLES30.glUniform1f(mImageWidthLoc, IMAGE_WIDTH * mScene.zoom());
        bindTexture(GLES30.GL_TEXTURE0, mSeabedTexture, mSeabedLoc);
        bindTexture(GLES30.GL_TEXTURE1, mEnvmapTexture, mEnvmapLoc);
        bindTexture(GLES30.GL_TEXTURE2, mRippleTexture, mRippleLoc);

        mVertexBuffer.position(0);
        GLES30.glEnableVertexAttribArray(mPosLoc);
        GLES30.glVertexAttribPointer(
                mPosLoc, 2, GLES30.GL_FLOAT, false, FLOATS_PER_VERTEX * 4, mVertexBuffer);
        mVertexBuffer.position(2);
        GLES30.glEnableVertexAttribArray(mUvLoc);
        GLES30.glVertexAttribPointer(
                mUvLoc, 2, GLES30.GL_FLOAT, false, FLOATS_PER_VERTEX * 4, mVertexBuffer);

        mIndexBuffer.position(0);
        GLES30.glDrawElements(
                GLES30.GL_TRIANGLES, mIndexCount, GLES30.GL_UNSIGNED_SHORT, mIndexBuffer);
        GLES30.glDisableVertexAttribArray(mPosLoc);
        GLES30.glDisableVertexAttribArray(mUvLoc);
    }

    private void bindTexture(int unit, int texture, int samplerLoc) {
        GLES30.glActiveTexture(unit);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture);
        GLES30.glUniform1i(samplerLoc, unit - GLES30.GL_TEXTURE0);
    }

    private void ensureSceneSize() {
        if (mWidth == mSceneWidth && mHeight == mSceneHeight) {
            return;
        }
        mSceneWidth = mWidth;
        mSceneHeight = mHeight;
        mScene.resize(mWidth, mHeight);
        Mat4.orthoM(mProj, 0.0f, mWidth, mHeight, 0.0f, -1.0f, 1.0f);
        buildGrid();
    }

    /** 顶点铺满屏幕（屏幕像素），uv 是整块网格上的 0..1。 */
    private void buildGrid() {
        mVertexBuffer.clear();
        for (int y = 0; y <= SUBDIVISIONS; y++) {
            float v = y / (float) SUBDIVISIONS;
            for (int x = 0; x <= SUBDIVISIONS; x++) {
                float u = x / (float) SUBDIVISIONS;
                mVertexBuffer.put(u * mWidth).put(v * mHeight).put(u).put(v);
            }
        }
        mVertexBuffer.position(0);

        mIndexBuffer.clear();
        for (int y = 0; y < SUBDIVISIONS; y++) {
            for (int x = 0; x < SUBDIVISIONS; x++) {
                int i0 = y * VERTS_PER_SIDE + x;
                int i1 = i0 + 1;
                int i2 = i0 + VERTS_PER_SIDE;
                int i3 = i2 + 1;
                mIndexBuffer.put((short) i0).put((short) i2).put((short) i1);
                mIndexBuffer.put((short) i1).put((short) i2).put((short) i3);
            }
        }
        mIndexCount = mIndexBuffer.position();
        mIndexBuffer.position(0);
        mGridBuilt = true;
    }

    private void initGLIfNeeded() {
        if (mProgram != 0 || mContext == null) {
            return;
        }
        String vs =
                AssetLoader.readText(mContext, "oceanwaves/shaders/GLES/oceanwaves_water_vs.glsl");
        String fs =
                AssetLoader.readText(mContext, "oceanwaves/shaders/GLES/oceanwaves_water_fs.glsl");
        mProgram = createProgram(vs, fs);
        if (mProgram == 0) {
            return;
        }
        mPosLoc = GLES30.glGetAttribLocation(mProgram, "aPos");
        mUvLoc = GLES30.glGetAttribLocation(mProgram, "aUV");
        mMvpLoc = GLES30.glGetUniformLocation(mProgram, "uMVP");
        mScaleLoc = GLES30.glGetUniformLocation(mProgram, "uScale");
        mTimeLoc = GLES30.glGetUniformLocation(mProgram, "uTime");
        mPanLoc = GLES30.glGetUniformLocation(mProgram, "uPanX");
        mDesignSizeLoc = GLES30.glGetUniformLocation(mProgram, "uDesignSize");
        mImageWidthLoc = GLES30.glGetUniformLocation(mProgram, "uImageWidth");
        mSeabedLoc = GLES30.glGetUniformLocation(mProgram, "uSeabed");
        mEnvmapLoc = GLES30.glGetUniformLocation(mProgram, "uEnvmap");
        mRippleLoc = GLES30.glGetUniformLocation(mProgram, "uRipple");

        /*
         * 海床不是无缝贴图：上下两端像素对不齐，平铺到第二张时接缝会落在屏幕中间
         * （缩放拉到最远端时一定会铺到第二张）。用镜像重复，接缝两侧是同一列像素，
         * 看不出分界；横向跟着桌面滚动平铺时同理。
         */
        mSeabedTexture = GLTextureUtils.loadTextureFromAsset(
                mContext, TEX_SEABED, true, GLES30.GL_MIRRORED_REPEAT);
        mEnvmapTexture = GLTextureUtils.loadTextureFromAsset(mContext, TEX_ENVMAP, true, false);
        mRippleTexture = GLTextureUtils.loadTextureFromAsset(mContext, TEX_RIPPLE, true, true);
        if (!mGridBuilt) {
            buildGrid();
        }
    }
}
