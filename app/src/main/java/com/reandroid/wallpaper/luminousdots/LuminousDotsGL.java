package com.reandroid.wallpaper.luminousdots;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.util.Log;

import com.reandroid.gles.GLESScene;
import com.reandroid.utils.AssetLoader;

/**
 * GLES2 renderer for Samsung LuminousDots live wallpaper.
 * Pure rendering — all logic in LuminousDotsScene.
 */
public class LuminousDotsGL extends GLESScene {
    private static final String TAG = "LuminousDotsGL";

    // Original embedded GLSL shader from LuminousDotsRenderer.onSurfaceCreated()
    private static final String VERTEX_SHADER =
        "uniform int uIsGradiatObj;                         \t\t\t\n" +
        "varying  float vIsGradiatObj;                         \t\t\n" +
        "uniform float u_BatteryAlpha;                               \n" +
        "uniform float u_gradiantUpYStart;                         \n" +
        "uniform float u_gradiantDownYStart;                     \n" +
        "uniform float u_gradiantYUpGap;                     \t\t\n" +
        "uniform float u_gradiantYDownGap;                     \t\n" +
        "uniform mat4 u_mvpMatrix;                   \t\t\t\t\n" +
        "attribute vec3 a_position;                        \t\t\t\t\n" +
        "attribute vec2 a_texCoord;   \t\t\t\t\t\t\t\t\t\n" +
        "varying vec2 v_texCoord;     \t\t\t\t\t\t\t\t\t\n" +
        "varying float vAlpha; \t\t\t\t\t\t\t\t\t\t\t\n" +
        "attribute vec4 aColor;\t\t\t\t\t\t\t\t\t\t\t\n" +
        "varying vec4 vColor;\t\t\t\t\t \t\t\t\t\t\t\n" +
        "void main()                                          \t\t\t\t\n" +
        "{                                                    \t\t\t\t\t\n" +
        " \tv_texCoord = a_texCoord;  \t\t\t\t\t\t\t\t\n" +
        "  \tgl_Position.xyz = a_position;                      \t\t\n" +
        "  \tgl_Position.w = 1.0;                      \t\t\t\t\t\n" +
        " \tgl_Position = u_mvpMatrix * gl_Position;  \t\t\t\n" +
        "  \tvColor = aColor;\t\t\t\t\t\t\t\t\t\t\t\t\n" +
        "  \tvIsGradiatObj = float(uIsGradiatObj);\t\t\t\t\t\n" +
        "  \tvAlpha = 1.0;\t\t\t\t\t\t\t\t\t\t\t\t\t\n" +
        "\tif( vIsGradiatObj <  1.0 ){\t\t\t\t\t\t\t\t\t\n" +
        "\t\tvColor.a = aColor.a * u_BatteryAlpha;\t\t\t\t\n" +
        "  \t\tvAlpha = vColor.a;\t\t\t\t\t\t\t\t\t\t\n" +
        "\t} \t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\n" +
        "  \tfloat gradiantAlpha = 1.0;                \t\t\t\t\t\n" +
        "  \tif(gl_Position.y < u_gradiantDownYStart && gl_Position.y < 0.0){     \t\t\t\t\t\t\t\t\t\t\n" +
        "  \t\tif(u_gradiantYDownGap > (u_gradiantDownYStart - gl_Position.y )){\t\t\t\t\t\t\t\t\t\n" +
        "  \t\t\tgradiantAlpha = 1.0  - ( (u_gradiantDownYStart - gl_Position.y) / u_gradiantYDownGap);\t\n" +
        "  \t\t}else{\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\n" +
        "  \t\t\tgradiantAlpha = 0.0;\t\t\t\t\t\t\t\t\n" +
        "  \t\t}\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\n" +
        "  \t}\t\t\t\t\t\t\t\t\t\t\t     \t\t\t\t\t\n" +
        " \tif(gl_Position.y > u_gradiantUpYStart && gl_Position.y > 0.0){     \t\t\t\t\t\t\t\t\t\t\n" +
        "  \t\tif(u_gradiantYUpGap > ( gl_Position.y - u_gradiantUpYStart )){\t\t\t\t\t\t\t\t\t\t\n" +
        "  \t\t\tgradiantAlpha = 1.0  - ( (gl_Position.y - u_gradiantUpYStart) / u_gradiantYUpGap);\t\t\n" +
        "  \t\t}else{\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\n" +
        "  \t\t\tgradiantAlpha = 0.0;\t\t\t\t\t\t\t\t\n" +
        "  \t\t}\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\n" +
        "  \t}\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\n" +
        "  vAlpha = vAlpha * gradiantAlpha;\t\t\t\t\t\t\n" +
        "}\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\n";

    private static final String FRAGMENT_SHADER =
        "precision mediump float;                             \t\t\t\t\n" +
        "varying vec2 v_texCoord;                            \t\t\t\t\t\n" +
        "varying float vAlpha; \t\t\t\t\t\t\t\t\t\t\t\t\t\n" +
        "varying vec4 vColor; \t\t\t\t\t\t\t\t\t\t\t\t\t\n" +
        "varying float v_TexIdx;\t\t\t \t\t\t\t\t\t\t\t\t\t\n" +
        "uniform float uAlpha;                         \t\t\t\t\t\t\t\n" +
        "uniform vec4 uColor;                         \t\t\t\t\t\t\t\n" +
        "uniform sampler2D s_texture;                         \t\t\t\t\n" +
        "uniform sampler2D s_texture1;                         \t\t\t\t\n" +
        "varying float vIsGradiatObj;                         \t\t\t\t\t\n" +
        "void main()                                          \t\t\t\t\t\t\n" +
        "{                                                    \t\t\t\t\t\t\t\n" +
        "  \tvec4 baseColor;\t\t\t\t\t\t\t\t\t\t\t\t\t\t\n" +
        "\tbaseColor = texture2D( s_texture, v_texCoord );\t\t\t\n" +
        "\tif(vIsGradiatObj >= 1.0)\t\t\t\t\t\t\t\t\t\t\t\n" +
        " \t\tbaseColor = uColor;\t\t\t\t\t\t\t\t\t\t\t\t\n" +
        "\telse \t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\n" +
        "\t\tbaseColor = baseColor*vColor;\t\t\t\t\t\t\t\t\n" +
        "  \tgl_FragColor = baseColor*vAlpha*uAlpha;\t\t\t\t\t\n" +
        "}                                              \t\t\t\t\t\t\t\t\n";

    private final Context mContext;
    private final LuminousDotsScene mScene;
    private android.content.SharedPreferences mPluginPrefs;

    // GL resources
    private int mProgram;
    private int mMVPLoc, mObjTexLoc, mColorLoc, mAlphaLoc;
    private int mBatteryAlphaLoc, mGradiantDownStartLoc, mGradiantUpStartLoc;
    private int mGradiantGapUpLoc, mGradiantGapDownLoc, mIsGradiantLoc;
    private int maPositionHandle, maTextureHandle, maAlphaHandle, maColorHandle;

    // Textures
    private int mObjTexId0, mObjTexId0_1, mObjTexId0_64, mObjTexId0_1_64;
    private int mObjTexId1, mObjTexId2;
    private int mGlowTexId0, mGlowTexId1, mGlowTexId2;

    // Projection
    private final float[] mProjMatrix = new float[16];

    private boolean mGLInit;
    private int mLastScaleLevel = -1;

    public LuminousDotsGL(int width, int height, Context context) {
        super(width, height);
        mContext = context;
        mScene = new LuminousDotsScene(width, height);
    }

    /** Called by BasePluginEngine via reflection to inject plugin-isolated prefs. */
    public void setPluginPrefs(android.content.SharedPreferences prefs) {
        mPluginPrefs = prefs;
        mScene.setPluginPrefs(prefs);
    }

    /** Called by Engine to forward battery level to Scene. */
    public void setBatteryLevel(int level) {
        mScene.setBatteryLevel(level);
    }

    @Override
    protected void onCreate() {
        Log.d(TAG, "onCreate");
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        int xMaxOffset = width;
        int yMaxOffset = height;
        // WallpaperManager.getDesiredMinimumWidth/Height not accessible from here
        // BasePluginEngine handles offset via setOffset
        mScene.resize(width, height, xMaxOffset, yMaxOffset);

        // Rebuild projection
        float aspect = (float) width / (float) height;
        float diag = Math.max(xMaxOffset, yMaxOffset);
        LuminousDotsScene.frustumM(mProjMatrix, -aspect, aspect, -1f, 1f, 1.4f, diag + 100f);
    }

    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        // Could adjust camera based on offset for wallpaper scrolling
    }

    @Override
    public void release() {
        int[] tex = {mObjTexId0, mObjTexId0_1, mObjTexId0_64, mObjTexId0_1_64,
                     mObjTexId1, mObjTexId2,
                     mGlowTexId0, mGlowTexId1, mGlowTexId2};
        GLES20.glDeleteTextures(tex.length, tex, 0);

        if (mProgram != 0) {
            GLES20.glDeleteProgram(mProgram);
            mProgram = 0;
        }
        mGLInit = false;
    }

    private void initGL() {
        if (mGLInit || mResources == null) return;
        mGLInit = true;

        // Compile shader
        mProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        if (mProgram == 0) {
            Log.e(TAG, "Shader program creation failed");
            return;
        }

        mMVPLoc = GLES20.glGetUniformLocation(mProgram, "u_mvpMatrix");
        mObjTexLoc = GLES20.glGetUniformLocation(mProgram, "s_texture");
        mColorLoc = GLES20.glGetUniformLocation(mProgram, "uColor");
        mAlphaLoc = GLES20.glGetUniformLocation(mProgram, "uAlpha");
        mBatteryAlphaLoc = GLES20.glGetUniformLocation(mProgram, "u_BatteryAlpha");
        mGradiantDownStartLoc = GLES20.glGetUniformLocation(mProgram, "u_gradiantDownYStart");
        mGradiantUpStartLoc = GLES20.glGetUniformLocation(mProgram, "u_gradiantUpYStart");
        mGradiantGapUpLoc = GLES20.glGetUniformLocation(mProgram, "u_gradiantYUpGap");
        mGradiantGapDownLoc = GLES20.glGetUniformLocation(mProgram, "u_gradiantYDownGap");
        mIsGradiantLoc = GLES20.glGetUniformLocation(mProgram, "uIsGradiatObj");
        maPositionHandle = GLES20.glGetAttribLocation(mProgram, "a_position");
        maTextureHandle = GLES20.glGetAttribLocation(mProgram, "a_texCoord");
        maAlphaHandle = GLES20.glGetAttribLocation(mProgram, "aAlpha");
        maColorHandle = GLES20.glGetAttribLocation(mProgram, "aColor");

        Log.d(TAG, "Shader compiled: prog=" + mProgram + " mvp=" + mMVPLoc);

        // Load textures
        loadTextures();

        // Build grid (needs textures to be loaded first? No, but needs prefs)
        mScene.buildGrid();

        // Initial projection
        float aspect = (float) mWidth / (float) mHeight;
        mScene.resize(mWidth, mHeight, mWidth, mHeight);
        LuminousDotsScene.frustumM(mProjMatrix, -aspect, aspect, -1f, 1f, 1.4f, mWidth + 100f);

        Log.d(TAG, "initGL complete. size=" + mWidth + "x" + mHeight);
    }

    private void loadTextures() {
        String base = "luminousdots/drawable/";
        mObjTexId0      = loadTex(base + "box0_128.png");
        mObjTexId0_1    = loadTex(base + "box0_1_128.png");
        mObjTexId0_64   = loadTex(base + "box0_64.png");
        mObjTexId0_1_64 = loadTex(base + "box0_1_64.png");
        mObjTexId1      = loadTex(base + "round0.png");
        mObjTexId2      = loadTex(base + "dot0.png");
        mGlowTexId0  = loadTex(base + "glowbox.png");
        mGlowTexId1  = loadTex(base + "glowdot.png");
        mGlowTexId2  = loadTex(base + "glowround.png");

        Log.d(TAG, "Textures loaded: obj0=" + mObjTexId0 + " glow0=" + mGlowTexId0);
    }

    private int loadTex(String path) {
        try {
            Bitmap bmp = AssetLoader.decodeBitmap(mContext, path);
            if (bmp == null) {
                Log.e(TAG, "Failed to decode: " + path);
                return 0;
            }
            int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
            bmp.recycle();
            return tex[0];
        } catch (Exception e) {
            Log.e(TAG, "Texture load failed: " + path, e);
            return 0;
        }
    }

    @Override
    public void drawFrame(long timeMs) {
        if (!mGLInit) {
            initGL();
            if (!mGLInit) return;
        }

        // Reload settings if changed
        mScene.readSettings();

        // Update scene logic
        mScene.update(timeMs);
        LuminousDotsScene.SceneData data = mScene.getSceneData();
        if (data == null || data.verticesUp == null) return;

        GLES20.glViewport(0, 0, mWidth, mHeight);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT); // 16640 in original
        GLES20.glUseProgram(mProgram);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        // --- Bind object texture based on shape ---
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        int objTex;
        switch (data.shape) {
            case 0:
                // Original: colorState selects variant, scale selects resolution
                if (data.mScale >= 1.9f) {
                    objTex = (data.colorState != 0) ? mObjTexId0_1_64 : mObjTexId0_64;
                } else {
                    objTex = (data.colorState != 0) ? mObjTexId0_1 : mObjTexId0;
                }
                break;
            case 1: objTex = mObjTexId1; break;
            case 2: objTex = mObjTexId2; break;
            default: objTex = mObjTexId0; break;
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, objTex);
        GLES20.glUniform1i(mObjTexLoc, 0);

        // --- Set uColor based on colorState ---
        switch (data.colorState) {
            case 1:
                GLES20.glUniform4f(mColorLoc, 0.031f, 0.07f, 0.0f, 1.0f);
                break;
            case 2:
                GLES20.glUniform4f(mColorLoc, 0.094f, 0.05f, 0.008f, 1.0f);
                break;
            default: // 0
                GLES20.glUniform4f(mColorLoc, 0.015f, 0.027f, 0.055f, 1.0f);
                break;
        }

        // --- Gradient uniform ---
        GLES20.glUniform1f(mGradiantDownStartLoc, data.gradDownStart);
        GLES20.glUniform1f(mGradiantUpStartLoc, data.gradUpStart);
        GLES20.glUniform1f(mGradiantGapUpLoc, data.gradGapUp);
        GLES20.glUniform1f(mGradiantGapDownLoc, data.gradGapDown);

        // --- Scissor enable ---
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
        GLES20.glScissor(0, 0, mWidth, mHeight);
        GLES20.glUniform1i(mIsGradiantLoc, 0);

        // --- Battery alpha ---
        GLES20.glUniform1f(mBatteryAlphaLoc, data.batteryAlpha);

        // --- 8 rendering passes ---
        float f2 = data.halfLayoutSize * data.mScale;

        // Pass 1: Left-Up, mMUpPattern, alpha = mBgAlphaLeftUp
        if (data.alphaLU > 0f) {
            renderPass(data.verticesUp, data.indices, data,
                -f2 / 2f, f2, 0f,
                data.rotIdxLU0, data.alphaLU);
        }
        // Pass 2: Left-Up cross-fade, mMUpPattern, alpha = 1 - mBgAlphaLeftUp
        if (1f - data.alphaLU > 0f) {
            renderPass(data.verticesUp, data.indices, data,
                -f2 / 2f, f2, 0f,
                data.rotIdxLU1, 1f - data.alphaLU);
        }

        // Pass 3: Right-Up, mMDownPattern, alpha = mBgAlphaRightUp
        if (data.alphaRU > 0f) {
            renderPass(data.verticesDown, data.indices, data,
                f2 / 2f, f2, 0f,
                data.rotIdxRU0, data.alphaRU);
        }
        // Pass 4: Right-Up cross-fade, mMDownPattern
        if (1f - data.alphaRU > 0f) {
            renderPass(data.verticesDown, data.indices, data,
                f2 / 2f, f2, 0f,
                data.rotIdxRU1, 1f - data.alphaRU);
        }

        // Pass 5: Left-Down, mMDownPattern, alpha = mBgAlphaLeftDown
        if (data.alphaLD > 0f) {
            renderPass(data.verticesDown, data.indices, data,
                -f2 / 2f, -f2, 0f,
                data.rotIdxLD0, data.alphaLD);
        }
        // Pass 6: Left-Down cross-fade, mMDownPattern
        if (1f - data.alphaLD > 0f) {
            renderPass(data.verticesDown, data.indices, data,
                -f2 / 2f, -f2, 0f,
                data.rotIdxLD1, 1f - data.alphaLD);
        }

        // Pass 7: Right-Down, mMUpPattern, alpha = mBgAlphaRightDown
        if (data.alphaRD > 0f) {
            renderPass(data.verticesUp, data.indices, data,
                f2 / 2f, -f2, 0f,
                data.rotIdxRD0, data.alphaRD);
        }
        // Pass 8: Right-Down cross-fade, mMUpPattern
        if (1f - data.alphaRD > 0f) {
            renderPass(data.verticesUp, data.indices, data,
                f2 / 2f, -f2, 0f,
                data.rotIdxRD1, 1f - data.alphaRD);
        }

        // --- Glow passes ---
        GLES20.glUniform1f(mAlphaLoc, data.glowAlpha);
        for (int gi = 0; gi < data.glowList.size(); gi++) {
            LuminousDotsScene.GlowData glow = data.glowList.get(gi);
            if (glow == null || glow.vertices == null) continue;

            // Bind glow texture based on wallpaper shape (original: all glows use same texture = mShape)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            switch (data.shape) {
                case 0: GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mGlowTexId0); break;
                case 1: GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mGlowTexId1); break;
                case 2: GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mGlowTexId2); break;
                default: GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mGlowTexId0); break;
            }

            float[] rot = LuminousDotsScene.ROTATE_TYPE[LuminousDotsScene.ROTATE_IDX[data.rotIdxGlow][0]];
            float[] mvp = new float[16];
            // Glow uses rotate→scale with NO translate (matching original Glow.draw())
            LuminousDotsScene.buildMVPGlow(mvp, mProjMatrix, mHeight, data.mScale, rot, data.camPx, data.camPy);

            bindVertexAttribs(glow.vertices);
            GLES20.glUniformMatrix4fv(mMVPLoc, 1, false, mvp, 0);
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, 6, GLES20.GL_UNSIGNED_SHORT, data.indices);
        }

        // --- Gradient mask passes (matching original rendering) ---
        GLES20.glUniform1i(mIsGradiantLoc, 1);
        GLES20.glUniform1f(mAlphaLoc, 1.0f);
        float[] rotMask = LuminousDotsScene.ROTATE_TYPE[LuminousDotsScene.ROTATE_IDX[0][1]];

        if (mWidth > mHeight) {
            // Wide screen: 3 passes matching original
            // Layer 2 at Y = yMaxOffset/2
            float[] mvp0 = new float[16];
            LuminousDotsScene.buildMVP(mvp0, mProjMatrix, mHeight,
                -data.xMaxOffset, data.yMaxOffset / 2f, 0f, 1f, rotMask, 0, 50f);
            if (data.verticesGrad2 != null) {
                bindVertexAttribs(data.verticesGrad2);
                GLES20.glUniformMatrix4fv(mMVPLoc, 1, false, mvp0, 0);
                GLES20.glDrawElements(GLES20.GL_TRIANGLES, 6, GLES20.GL_UNSIGNED_SHORT, data.indices);
            }
            // Layer 0 at Y = yMaxOffset/4
            float[] mvp1 = new float[16];
            LuminousDotsScene.buildMVP(mvp1, mProjMatrix, mHeight,
                -data.xMaxOffset, data.yMaxOffset / 4f, 0f, 1f, rotMask, 0, 50f);
            if (data.verticesGrad0 != null) {
                bindVertexAttribs(data.verticesGrad0);
                GLES20.glUniformMatrix4fv(mMVPLoc, 1, false, mvp1, 0);
                GLES20.glDrawElements(GLES20.GL_TRIANGLES, 6, GLES20.GL_UNSIGNED_SHORT, data.indices);
            }
            // Layer 1 at Y = -yMaxOffset/5
            float[] mvp2 = new float[16];
            LuminousDotsScene.buildMVP(mvp2, mProjMatrix, mHeight,
                -data.xMaxOffset, -data.yMaxOffset / 5f, 0f, 1f, rotMask, 0, 50f);
            if (data.verticesGrad1 != null) {
                bindVertexAttribs(data.verticesGrad1);
                GLES20.glUniformMatrix4fv(mMVPLoc, 1, false, mvp2, 0);
                GLES20.glDrawElements(GLES20.GL_TRIANGLES, 6, GLES20.GL_UNSIGNED_SHORT, data.indices);
            }
        } else {
            // Tall screen: matching original per-colorState rendering
            // Layer 2 at Y = yMaxOffset/1.518f
            float[] mvp0 = new float[16];
            LuminousDotsScene.buildMVP(mvp0, mProjMatrix, mHeight,
                -data.xMaxOffset, data.yMaxOffset / 1.518f, 0f, 1f, rotMask, 0, 50f);
            if (data.verticesGrad2 != null) {
                bindVertexAttribs(data.verticesGrad2);
                GLES20.glUniformMatrix4fv(mMVPLoc, 1, false, mvp0, 0);
                GLES20.glDrawElements(GLES20.GL_TRIANGLES, 6, GLES20.GL_UNSIGNED_SHORT, data.indices);
            }
            // Layer 0 at Y = yMaxOffset/2.18f
            float[] mvp1 = new float[16];
            LuminousDotsScene.buildMVP(mvp1, mProjMatrix, mHeight,
                -data.xMaxOffset, data.yMaxOffset / 2.18f, 0f, 1f, rotMask, 0, 50f);
            if (data.verticesGrad0 != null) {
                bindVertexAttribs(data.verticesGrad0);
                GLES20.glUniformMatrix4fv(mMVPLoc, 1, false, mvp1, 0);
                GLES20.glDrawElements(GLES20.GL_TRIANGLES, 6, GLES20.GL_UNSIGNED_SHORT, data.indices);
            }
            // Layer 1 at Y = -yMaxOffset/8.9f (colorState=0) or -yMaxOffset/2.999f (otherwise)
            float y1 = data.colorState == 0 ? -data.yMaxOffset / 8.9f : -data.yMaxOffset / 2.999f;
            float[] mvp2 = new float[16];
            LuminousDotsScene.buildMVP(mvp2, mProjMatrix, mHeight,
                -data.xMaxOffset, y1, 0f, 1f, rotMask, 0, 50f);
            if (data.verticesGrad1 != null) {
                bindVertexAttribs(data.verticesGrad1);
                GLES20.glUniformMatrix4fv(mMVPLoc, 1, false, mvp2, 0);
                GLES20.glDrawElements(GLES20.GL_TRIANGLES, 6, GLES20.GL_UNSIGNED_SHORT, data.indices);
            }
        }

        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
    }

    private void renderPass(java.nio.FloatBuffer vertices, java.nio.ShortBuffer indices,
                             LuminousDotsScene.SceneData data,
                             float tx, float ty, float tz,
                             int rotIdx, float alpha) {
        if (vertices == null || indices == null) return;

        float[] rot = LuminousDotsScene.ROTATE_TYPE[LuminousDotsScene.ROTATE_IDX[rotIdx][0]];
        float[] mvp = new float[16];
        LuminousDotsScene.buildMVP(mvp, mProjMatrix, mHeight, tx, ty, tz, data.mScale, rot, data.camPx, data.camPy);

        bindVertexAttribs(vertices);
        GLES20.glUniformMatrix4fv(mMVPLoc, 1, false, mvp, 0);
        GLES20.glUniform1f(mAlphaLoc, alpha);
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, LuminousDotsScene.QUADS * 6, GLES20.GL_UNSIGNED_SHORT, indices);
    }

    private void bindVertexAttribs(java.nio.FloatBuffer buf) {
        if (buf == null) return;
        buf.position(0);
        // a_position: offset 0, 3 floats, stride 48
        GLES20.glVertexAttribPointer(maPositionHandle, 3, GLES20.GL_FLOAT, false, 48, buf);
        GLES20.glEnableVertexAttribArray(maPositionHandle);

        buf.position(3);
        // a_texCoord: offset 3, 2 floats, stride 48
        GLES20.glVertexAttribPointer(maTextureHandle, 2, GLES20.GL_FLOAT, false, 48, buf);
        GLES20.glEnableVertexAttribArray(maTextureHandle);

        buf.position(5);
        // aAlpha: offset 5, 1 float, stride 48
        if (maAlphaHandle >= 0) {
            GLES20.glVertexAttribPointer(maAlphaHandle, 1, GLES20.GL_FLOAT, false, 48, buf);
            GLES20.glEnableVertexAttribArray(maAlphaHandle);
        }

        buf.position(6);
        // aColor: offset 6, 4 floats (r*alpha, g*alpha, b*alpha, a*alpha), stride 48
        GLES20.glVertexAttribPointer(maColorHandle, 4, GLES20.GL_FLOAT, false, 48, buf);
        GLES20.glEnableVertexAttribArray(maColorHandle);
    }
}
