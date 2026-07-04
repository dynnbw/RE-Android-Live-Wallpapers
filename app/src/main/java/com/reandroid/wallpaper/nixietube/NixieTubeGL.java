package com.reandroid.wallpaper.nixietube;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.util.Log;

import com.reandroid.utils.AssetLoader;
import com.reandroid.gles.GLESScene;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * 8-digit nixie tube display — clock / random / VU meter.
 * Renders from a single atlas texture (14 frames in a 7×2 grid).
 */
public class NixieTubeGL extends GLESScene {
    private static final String TAG = "NixieTubeGL";

    // ---- Atlas grid ----
    private static final int ATLAS_COLS = 7;
    private static final int ATLAS_ROWS = 2;
    private static final float UV_CELL_W = 1.0f / ATLAS_COLS;
    private static final float UV_CELL_H = 1.0f / ATLAS_ROWS;

    // Frame index constants (must match atlas layout)
    static final int FRAME_EMPTY = 13;
    static final int FRAME_COLON = 10;
    static final int FRAME_RD     = 11;
    static final int FRAME_LD     = 12;

    // ---- Layout ----
    private static final int TUBE_COUNT = 8;
    private static final float TUBE_W = 0.11f;
    private static final float TUBE_H = 0.28f;
    private static final float GAP     = 0.0f;
    private static final float TOTAL_W = TUBE_COUNT * (TUBE_W + GAP) - GAP;

    private final Context mContext;
    private final NixieTubeScene mScene;

    private int mProgram;
    private int mPosHandle;
    private int mTexHandle;
    private int mMvpHandle;
    private int mTexSamplerHandle;

    private int mAtlasTexture;
    private FloatBuffer[] mTubeBuffers;
    private final float[] mMvpMatrix = new float[16];
    private boolean mGlReady;

    public NixieTubeGL(int width, int height, Context context) {
        super(width, height);
        mContext = context;
        mScene = new NixieTubeScene();
        Matrix.setIdentityM(mMvpMatrix, 0);
    }

    /** Called by BasePluginEngine via reflection to inject plugin-isolated prefs. */
    public void setPluginPrefs(android.content.SharedPreferences prefs) {
        mScene.setPluginPrefs(prefs);
    }

    @Override
    protected void onCreate() {}

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        float aspect = (float) width / (float) height;
        Matrix.orthoM(mMvpMatrix, 0, -aspect, aspect, 1.0f, -1.0f, -1.0f, 1.0f);
    }

    @Override
    public void drawFrame(long timeMs) {
        if (!mGlReady) initGl();

        mScene.update(timeMs);
        int[] tubes = mScene.getDisplayValues();

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(mProgram);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glUniformMatrix4fv(mMvpHandle, 1, false, mMvpMatrix, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mAtlasTexture);
        GLES20.glUniform1i(mTexSamplerHandle, 0);

        for (int i = 0; i < TUBE_COUNT; i++) {
            buildTubeBuffer(i, tubes[i]);
            drawTube(i);
        }

        GLES20.glDisable(GLES20.GL_BLEND);
    }

    @Override
    public void release() {
        if (mAtlasTexture != 0) {
            int[] t = {mAtlasTexture};
            GLES20.glDeleteTextures(1, t, 0);
            mAtlasTexture = 0;
        }
        if (mProgram != 0) {
            GLES20.glDeleteProgram(mProgram);
            mProgram = 0;
        }
        mGlReady = false;
        mScene.stopAudio();
        mScene.stop();
    }

    @Override
    public void onCommand(String action, int x, int y, int z) {
        if ("android.wallpaper.tap".equals(action)) {
            mScene.onTap();
        }
    }

    // ---- Internal ----

    private void initGl() {
        String vs = AssetLoader.readText(mContext, "nixietube/shaders/GLES/nixietube_vs.glsl");
        String fs = AssetLoader.readText(mContext, "nixietube/shaders/GLES/nixietube_fs.glsl");
        mProgram = createProgram(vs, fs);
        if (mProgram == 0) return;

        mPosHandle      = GLES20.glGetAttribLocation(mProgram, "aPosition");
        mTexHandle      = GLES20.glGetAttribLocation(mProgram, "aTexCoord");
        mMvpHandle      = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");
        mTexSamplerHandle = GLES20.glGetUniformLocation(mProgram, "uTexture");

        mAtlasTexture = loadAtlas();
        mTubeBuffers = new FloatBuffer[TUBE_COUNT];
        for (int i = 0; i < TUBE_COUNT; i++) {
            mTubeBuffers[i] = ByteBuffer.allocateDirect(4 * 5 * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
        }

        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        resize(mWidth, mHeight);
        mScene.startAudio();
        mGlReady = true;
    }

    private int loadAtlas() {
        try (InputStream is = mContext.getAssets().open("nixietube/drawable/nixie_atlas.png")) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPremultiplied = false;
            Bitmap bmp = BitmapFactory.decodeStream(is, null, opts);
            if (bmp == null) return 0;
            int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
            bmp.recycle();
            return tex[0];
        } catch (Exception e) {
            Log.e(TAG, "Failed to load atlas", e);
            return 0;
        }
    }

    /** Populate the vertex buffer for tube i with UVs for the given frame. */
    private void buildTubeBuffer(int i, int frame) {
        float x0 = -TOTAL_W / 2.0f + i * (TUBE_W + GAP);
        float y0 = -TUBE_H / 2.0f;  // bottom
        float x1 = x0 + TUBE_W;
        float y1 =  TUBE_H / 2.0f;  // top

        int col = frame % ATLAS_COLS;
        int row = frame / ATLAS_COLS;
        float u0 = col * UV_CELL_W;
        float v0 = row * UV_CELL_H;        // top of quad → top of atlas cell
        float u1 = u0 + UV_CELL_W;
        float v1 = v0 + UV_CELL_H;         // bottom of quad → bottom of atlas cell

        float[] verts = {
            x0, y0, 0, u0, v0,   // top-left
            x1, y0, 0, u1, v0,   // top-right
            x1, y1, 0, u1, v1,   // bottom-right
            x0, y1, 0, u0, v1,   // bottom-left
        };

        FloatBuffer buf = mTubeBuffers[i];
        buf.clear();
        buf.put(verts).position(0);
    }

    private void drawTube(int i) {
        FloatBuffer buf = mTubeBuffers[i];
        buf.position(0);
        GLES20.glVertexAttribPointer(mPosHandle, 3, GLES20.GL_FLOAT, false, 20, buf);
        GLES20.glEnableVertexAttribArray(mPosHandle);
        buf.position(3);
        GLES20.glVertexAttribPointer(mTexHandle, 2, GLES20.GL_FLOAT, false, 20, buf);
        GLES20.glEnableVertexAttribArray(mTexHandle);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4);
        GLES20.glDisableVertexAttribArray(mPosHandle);
        GLES20.glDisableVertexAttribArray(mTexHandle);
    }
}
