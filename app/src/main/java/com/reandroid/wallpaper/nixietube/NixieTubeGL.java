package com.reandroid.wallpaper.nixietube;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES30;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.util.Log;
import android.view.MotionEvent;

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
    /** buildTubeBuffer 每帧每根管子调一次，顶点数组复用。 */
    private final float[] mTubeVerts = new float[20];
    private boolean mGlReady;
    private float mScale = 1.0f;

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

    /** Start audio capture (called on visibility / GL ready). */
    void startAudio() { mScene.startAudio(); }

    /** Stop audio capture (called on invisibility / release). */
    void stopAudio() { mScene.stopAudio(); }

    /** Force display back to clock mode (called when wallpaper goes invisible). */
    void resetModeToTime() { mScene.stop(); }

    @Override
    protected void onCreate() {}

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        float aspect = (float) width / (float) height;
        Matrix.orthoM(mMvpMatrix, 0, -aspect, aspect, 1.0f, -1.0f, -1.0f, 1.0f);

        // The ortho projection ties the coordinate unit to half the screen height,
        // so in landscape (tall aspect) the fixed-width tube row shrinks to a small
        // fraction of the wide screen. Scale the row to fill ~70% of the screen
        // width — same as it already does in portrait — without shrinking portrait
        // and without letting tube height overflow the screen.
        float fillWidth = 1.4f * aspect;                  // 70% of the 2*aspect width
        float scale = fillWidth / TOTAL_W;
        scale = Math.max(1.0f, scale);                    // never shrink (portrait stays as-is)
        scale = Math.min(scale, 1.6f / TUBE_H);           // cap: keep tube height ≤ 80% of screen
        mScale = scale;
    }

    @Override
    public void drawFrame(long timeMs) {
        if (!mGlReady) initGl();

        mScene.update(timeMs);
        int[] tubes = mScene.getDisplayValues();

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        GLES30.glUseProgram(mProgram);
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        GLES30.glUniformMatrix4fv(mMvpHandle, 1, false, mMvpMatrix, 0);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mAtlasTexture);
        GLES30.glUniform1i(mTexSamplerHandle, 0);

        for (int i = 0; i < TUBE_COUNT; i++) {
            buildTubeBuffer(i, tubes[i]);
            drawTube(i);
        }

        GLES30.glDisable(GLES30.GL_BLEND);
    }

    @Override
    public void release() {
        if (mAtlasTexture != 0) {
            int[] t = {mAtlasTexture};
            GLES30.glDeleteTextures(1, t, 0);
            mAtlasTexture = 0;
        }
        if (mProgram != 0) {
            GLES30.glDeleteProgram(mProgram);
            mProgram = 0;
        }
        mGlReady = false;
        mScene.stopAudio();
        mScene.stop();
    }

    @Override
    public void onCommand(String action, int x, int y, int z) {
        // 桌面路径：系统发送 tap 命令
        if ("android.wallpaper.tap".equals(action)) {
            mScene.onTap();
        }
    }

    @Override
    public void onTouchEvent(MotionEvent event) {
        // 应用内预览路径：预览只转发 onTouchEvent，不派发 tap 命令。
        // 仅在预览模式接管，桌面继续走 onCommand，避免双重触发。
        if (isPreview() && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mScene.onTap();
        }
    }

    // ---- Internal ----

    private void initGl() {
        String vs = AssetLoader.readText(mContext, "nixietube/shaders/GLES/nixietube_vs.glsl");
        String fs = AssetLoader.readText(mContext, "nixietube/shaders/GLES/nixietube_fs.glsl");
        mProgram = createProgram(vs, fs);
        if (mProgram == 0) return;

        mPosHandle      = GLES30.glGetAttribLocation(mProgram, "aPosition");
        mTexHandle      = GLES30.glGetAttribLocation(mProgram, "aTexCoord");
        mMvpHandle      = GLES30.glGetUniformLocation(mProgram, "uMVPMatrix");
        mTexSamplerHandle = GLES30.glGetUniformLocation(mProgram, "uTexture");

        mAtlasTexture = loadAtlas();
        mTubeBuffers = new FloatBuffer[TUBE_COUNT];
        for (int i = 0; i < TUBE_COUNT; i++) {
            mTubeBuffers[i] = ByteBuffer.allocateDirect(4 * 5 * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
        }

        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
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
            GLES30.glGenTextures(1, tex, 0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0]);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0);
            bmp.recycle();
            return tex[0];
        } catch (Exception e) {
            Log.e(TAG, "Failed to load atlas", e);
            return 0;
        }
    }

    /** Populate the vertex buffer for tube i with UVs for the given frame. */
    private void buildTubeBuffer(int i, int frame) {
        float tubeW  = TUBE_W * mScale;
        float tubeH  = TUBE_H * mScale;
        float gap    = GAP * mScale;
        float totalW = TOTAL_W * mScale;
        float x0 = -totalW / 2.0f + i * (tubeW + gap);
        float y0 = -tubeH / 2.0f;  // bottom
        float x1 = x0 + tubeW;
        float y1 =  tubeH / 2.0f;  // top

        int col = frame % ATLAS_COLS;
        int row = frame / ATLAS_COLS;
        float u0 = col * UV_CELL_W;
        float v0 = row * UV_CELL_H;        // top of quad → top of atlas cell
        float u1 = u0 + UV_CELL_W;
        float v1 = v0 + UV_CELL_H;         // bottom of quad → bottom of atlas cell

        float[] verts = mTubeVerts;
        verts[0] = x0;  verts[1] = y0;  verts[2] = 0;  verts[3] = u0;  verts[4] = v0;   // top-left
        verts[5] = x1;  verts[6] = y0;  verts[7] = 0;  verts[8] = u1;  verts[9] = v0;   // top-right
        verts[10] = x1; verts[11] = y1; verts[12] = 0; verts[13] = u1; verts[14] = v1;  // bottom-right
        verts[15] = x0; verts[16] = y1; verts[17] = 0; verts[18] = u0; verts[19] = v1;  // bottom-left

        FloatBuffer buf = mTubeBuffers[i];
        buf.clear();
        buf.put(verts).position(0);
    }

    private void drawTube(int i) {
        FloatBuffer buf = mTubeBuffers[i];
        buf.position(0);
        GLES30.glVertexAttribPointer(mPosHandle, 3, GLES30.GL_FLOAT, false, 20, buf);
        GLES30.glEnableVertexAttribArray(mPosHandle);
        buf.position(3);
        GLES30.glVertexAttribPointer(mTexHandle, 2, GLES30.GL_FLOAT, false, 20, buf);
        GLES30.glEnableVertexAttribArray(mTexHandle);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_FAN, 0, 4);
        GLES30.glDisableVertexAttribArray(mPosHandle);
        GLES30.glDisableVertexAttribArray(mTexHandle);
    }
}
