package com.reandroid.wallpaper.magicsmoke;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.util.Log;

import com.reandroid.wallpaper.R;
import com.reandroid.gles.GLESScene;
import com.reandroid.gles.RawResourceLoader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Magic Smoke OpenGL ES 2.0 implementation
 * Ported from original RenderScript version
 */
public class MagicSmokeGL extends GLESScene {
    private static final String TAG = "MagicSmokeGL";

    // ---- 场景逻辑层（非 GL）----
    private final MagicSmokeScene mScene;

    // Shader programs
    private int mProgram5Tex;
    private int mProgram4Tex;

    // Uniform/Attribute handles for 5-texture program
    private int mPositionHandle5;
    private int mLayer0Handle5, mLayer1Handle5, mLayer2Handle5, mLayer3Handle5, mLayer4Handle5;
    private int mPanOffsetHandle5;
    private int mAspectScaleHandle5;
    private int mClearColorHandle5;
    private int mTexture0Handle5, mTexture1Handle5, mTexture2Handle5, mTexture3Handle5, mTexture4Handle5;

    // Uniform/Attribute handles for 4-texture program
    private int mPositionHandle4;
    private int mLayer0Handle4, mLayer1Handle4, mLayer2Handle4, mLayer3Handle4;
    private int mPanOffsetHandle4;
    private int mAspectScaleHandle4;
    private int mClearColorHandle4;
    private int mTexture0Handle4, mTexture1Handle4, mTexture2Handle4, mTexture3Handle4;

    // Geometry
    private FloatBuffer mQuadVertices;

    // Textures
    private int[] mTextures = new int[5];

    // GL initialization guard
    private boolean mInitialized;

    public MagicSmokeGL(Context context, int width, int height) {
        super(width, height);
        mScene = new MagicSmokeScene(context);
    }

    @Override
    protected void onCreate() {
        if (mInitialized) {
            Log.d(TAG, "onCreate() already initialized, skipping");
            return;
        }
        if (mResources == null) {
            Log.w(TAG, "onCreate() called without resources");
            return;
        }
        mInitialized = true;
        Log.d(TAG, "onCreate() - Starting initialization");

        // Initialize quad vertices (full-screen quad)
        float[] quadCoords = {
            -1.0f, -1.0f,
             1.0f, -1.0f,
            -1.0f,  1.0f,
             1.0f,  1.0f
        };
        ByteBuffer bb = ByteBuffer.allocateDirect(quadCoords.length * 4);
        bb.order(ByteOrder.nativeOrder());
        mQuadVertices = bb.asFloatBuffer();
        mQuadVertices.put(quadCoords);
        mQuadVertices.position(0);

        // Create shader programs
        createPrograms();

        // Get uniform/attribute locations
        getUniformLocations();

        // Load preferences and initial preset
        mScene.init();

        // Load and process textures
        loadTextures();

        // Set OpenGL state
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ZERO);

        Log.d(TAG, "onCreate() completed successfully");
    }

    private void createPrograms() {
        // Load shader source from raw resources
        String vertex5Src = RawResourceLoader.readRawText(mResources, R.raw.magicsmoke_5tex_vs);
        String fragment5Src = RawResourceLoader.readRawText(mResources, R.raw.magicsmoke_5tex_fs);
        String vertex4Src = RawResourceLoader.readRawText(mResources, R.raw.magicsmoke_4tex_vs);
        String fragment4Src = RawResourceLoader.readRawText(mResources, R.raw.magicsmoke_4tex_fs);

        // Create programs
        mProgram5Tex = createShaderProgram(vertex5Src, fragment5Src);
        mProgram4Tex = createShaderProgram(vertex4Src, fragment4Src);
        if (mProgram5Tex == 0 || mProgram4Tex == 0) {
            Log.e(TAG, "Failed to create shader programs");
        }

        Log.d(TAG, "Shader programs created successfully");
    }

    private int createShaderProgram(String vertexSource, String fragmentSource) {
        int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
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
            return 0;
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
            Log.e(TAG, "Shader compilation failed: " + GLES20.glGetShaderInfoLog(shader));
            Log.e(TAG, "Shader source:\n" + source);
            GLES20.glDeleteShader(shader);
            return 0;
        }

        return shader;
    }

    private void getUniformLocations() {
        // 5-texture program
        mPositionHandle5 = GLES20.glGetAttribLocation(mProgram5Tex, "aPosition");
        mLayer0Handle5 = GLES20.glGetUniformLocation(mProgram5Tex, "uLayer0");
        mLayer1Handle5 = GLES20.glGetUniformLocation(mProgram5Tex, "uLayer1");
        mLayer2Handle5 = GLES20.glGetUniformLocation(mProgram5Tex, "uLayer2");
        mLayer3Handle5 = GLES20.glGetUniformLocation(mProgram5Tex, "uLayer3");
        mLayer4Handle5 = GLES20.glGetUniformLocation(mProgram5Tex, "uLayer4");
        mPanOffsetHandle5 = GLES20.glGetUniformLocation(mProgram5Tex, "uPanOffset");
        mAspectScaleHandle5 = GLES20.glGetUniformLocation(mProgram5Tex, "uAspectScale");
        mClearColorHandle5 = GLES20.glGetUniformLocation(mProgram5Tex, "uClearColor");
        mTexture0Handle5 = GLES20.glGetUniformLocation(mProgram5Tex, "uTexture0");
        mTexture1Handle5 = GLES20.glGetUniformLocation(mProgram5Tex, "uTexture1");
        mTexture2Handle5 = GLES20.glGetUniformLocation(mProgram5Tex, "uTexture2");
        mTexture3Handle5 = GLES20.glGetUniformLocation(mProgram5Tex, "uTexture3");
        mTexture4Handle5 = GLES20.glGetUniformLocation(mProgram5Tex, "uTexture4");

        // 4-texture program
        mPositionHandle4 = GLES20.glGetAttribLocation(mProgram4Tex, "aPosition");
        mLayer0Handle4 = GLES20.glGetUniformLocation(mProgram4Tex, "uLayer0");
        mLayer1Handle4 = GLES20.glGetUniformLocation(mProgram4Tex, "uLayer1");
        mLayer2Handle4 = GLES20.glGetUniformLocation(mProgram4Tex, "uLayer2");
        mLayer3Handle4 = GLES20.glGetUniformLocation(mProgram4Tex, "uLayer3");
        mPanOffsetHandle4 = GLES20.glGetUniformLocation(mProgram4Tex, "uPanOffset");
        mAspectScaleHandle4 = GLES20.glGetUniformLocation(mProgram4Tex, "uAspectScale");
        mClearColorHandle4 = GLES20.glGetUniformLocation(mProgram4Tex, "uClearColor");
        mTexture0Handle4 = GLES20.glGetUniformLocation(mProgram4Tex, "uTexture0");
        mTexture1Handle4 = GLES20.glGetUniformLocation(mProgram4Tex, "uTexture1");
        mTexture2Handle4 = GLES20.glGetUniformLocation(mProgram4Tex, "uTexture2");
        mTexture3Handle4 = GLES20.glGetUniformLocation(mProgram4Tex, "uTexture3");
    }

    private void loadTextures() {
        MagicSmokeScene.Preset preset = MagicSmokeScene.PRESETS[mScene.mCurrentPreset];

        // Update clear color
        mScene.mClearColor[0] = ((preset.backColor >> 16) & 0xff) / 255.0f;
        mScene.mClearColor[1] = ((preset.backColor >> 8) & 0xff) / 255.0f;
        mScene.mClearColor[2] = (preset.backColor & 0xff) / 255.0f;
        mScene.mClearColor[3] = 1.0f;
        GLES20.glClearColor(mScene.mClearColor[0], mScene.mClearColor[1], mScene.mClearColor[2], mScene.mClearColor[3]);

        // Generate GL textures
        deleteTextures();
        GLES20.glGenTextures(5, mTextures, 0);

        // Load and process each noise texture
        float alphaFactor = 1.0f;
        for (int i = 0; i < 5; i++) {
            processAndLoadTexture(i, preset, alphaFactor);
            alphaFactor *= preset.alphaMul;
        }

        Log.d(TAG, "Textures loaded and processed");
    }

    private void processAndLoadTexture(int index, MagicSmokeScene.Preset preset, float alphaFactor) {
        // Load source bitmap
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap sourceBitmap = BitmapFactory.decodeResource(mResources, MagicSmokeScene.NOISE_RES_IDS[index], opts);
        if (sourceBitmap == null) {
            Log.e(TAG, "Failed to decode texture: " + MagicSmokeScene.NOISE_RES_IDS[index]);
            return;
        }

        int width = sourceBitmap.getWidth();
        int height = sourceBitmap.getHeight();
        int[] pixels = new int[width * height];
        sourceBitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        // Process pixels based on mode (delegated to Scene)
        mScene.processPixels(pixels, preset, alphaFactor);

        // Create processed bitmap
        Bitmap processedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        boolean isPremultiplied = preset.preMul || preset.processTextureMode == 0;
        processedBitmap.setPremultiplied(isPremultiplied);
        processedBitmap.setPixels(pixels, 0, width, 0, 0, width, height);

        // Upload to GL texture
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextures[index]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, processedBitmap, 0);

        sourceBitmap.recycle();
        processedBitmap.recycle();
    }

    @Override
    public void drawFrame(long timeMs) {
        if (!mInitialized) {
            return;
        }
        if (mScene.updatePresetIfNeeded()) {
            loadTextures();
        }
        mScene.updateAnimation(timeMs);
        GLES20.glClearColor(mScene.mClearColor[0], mScene.mClearColor[1], mScene.mClearColor[2], mScene.mClearColor[3]);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        // Choose program based on texture mask
        MagicSmokeScene.Preset preset = MagicSmokeScene.PRESETS[mScene.mCurrentPreset];
        int textureCount = mScene.countTextures(preset.textureMask);

        if (textureCount == 5) {
            if (mProgram5Tex == 0) {
                return;
            }
            drawWith5Textures(preset);
        } else {
            if (mProgram4Tex == 0) {
                return;
            }
            drawWith4Textures(preset);
        }
    }

    private void drawWith5Textures(MagicSmokeScene.Preset preset) {
        GLES20.glUseProgram(mProgram5Tex);

        // Set layer uniforms (rotation, scale, xshift)
        float m = 0.35f;
        setLayerUniform(mLayer0Handle5, mScene.mRotation[0], mScene.mScale[0] * m, mScene.mXShift[0]);
        setLayerUniform(mLayer1Handle5, mScene.mRotation[1], mScene.mScale[1] * m, mScene.mXShift[1]);
        setLayerUniform(mLayer2Handle5, mScene.mRotation[2], mScene.mScale[2] * m, mScene.mXShift[2]);
        setLayerUniform(mLayer3Handle5, mScene.mRotation[3], mScene.mScale[3] * m, mScene.mXShift[3]);
        setLayerUniform(mLayer4Handle5, mScene.mRotation[4], mScene.mScale[4] * m, mScene.mXShift[4]);

        // Set pan offset
        GLES20.glUniform2f(mPanOffsetHandle5, mScene.mXOffset, -mScene.mYOffset);

        // Keep visual proportions consistent with the original 3:4 tuning.
        float aspect = (float) mWidth / Math.max(1.0f, (float) mHeight);
        float aspectScaleX = aspect / MagicSmokeScene.REF_ASPECT;
        GLES20.glUniform2f(mAspectScaleHandle5, aspectScaleX, 1.0f);

        // Set clear color
        GLES20.glUniform4fv(mClearColorHandle5, 1, mScene.mClearColor, 0);

        // Bind textures
        bindTextures5(preset);

        // Draw quad
        mQuadVertices.position(0);
        GLES20.glVertexAttribPointer(mPositionHandle5, 2, GLES20.GL_FLOAT, false, 0, mQuadVertices);
        GLES20.glEnableVertexAttribArray(mPositionHandle5);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(mPositionHandle5);
    }

    private void drawWith4Textures(MagicSmokeScene.Preset preset) {
        GLES20.glUseProgram(mProgram4Tex);

        // Set layer uniforms
        float m = 0.35f;
        setLayerUniform(mLayer0Handle4, mScene.mRotation[0], mScene.mScale[0] * m, mScene.mXShift[0]);
        setLayerUniform(mLayer1Handle4, mScene.mRotation[1], mScene.mScale[1] * m, mScene.mXShift[1]);
        setLayerUniform(mLayer2Handle4, mScene.mRotation[2], mScene.mScale[2] * m, mScene.mXShift[2]);
        setLayerUniform(mLayer3Handle4, mScene.mRotation[3], mScene.mScale[3] * m, mScene.mXShift[3]);

        // Set pan offset
        GLES20.glUniform2f(mPanOffsetHandle4, mScene.mXOffset, -mScene.mYOffset);

        // Keep visual proportions consistent with the original 3:4 tuning.
        float aspect = (float) mWidth / Math.max(1.0f, (float) mHeight);
        float aspectScaleX = aspect / MagicSmokeScene.REF_ASPECT;
        GLES20.glUniform2f(mAspectScaleHandle4, aspectScaleX, 1.0f);

        // Set clear color
        GLES20.glUniform4fv(mClearColorHandle4, 1, mScene.mClearColor, 0);

        // Bind textures
        bindTextures4(preset);

        // Draw quad
        mQuadVertices.position(0);
        GLES20.glVertexAttribPointer(mPositionHandle4, 2, GLES20.GL_FLOAT, false, 0, mQuadVertices);
        GLES20.glEnableVertexAttribArray(mPositionHandle4);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(mPositionHandle4);
    }

    private void setLayerUniform(int handle, float rotation, float scale, float xshift) {
        float radians = (float)Math.toRadians(rotation);
        float sin = (float)Math.sin(radians);
        float cos = (float)Math.cos(radians);
        GLES20.glUniform4f(handle, sin, cos, scale, xshift);
    }

    private void bindTextures5(MagicSmokeScene.Preset preset) {
        int pos = 0;
        int mask = preset.textureMask;

        for (int i = 0; i < 5; i++) {
            if ((mask & (1 << i)) != 0) {
                int texIndex = i;
                // Swap texture 0 and 4 if textureSwap is enabled
                if (i == 0 && preset.textureSwap) texIndex = 4;

                GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + pos);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextures[texIndex]);

                // Set corresponding sampler uniform
                switch (pos) {
                    case 0: GLES20.glUniform1i(mTexture0Handle5, pos); break;
                    case 1: GLES20.glUniform1i(mTexture1Handle5, pos); break;
                    case 2: GLES20.glUniform1i(mTexture2Handle5, pos); break;
                    case 3: GLES20.glUniform1i(mTexture3Handle5, pos); break;
                    case 4: GLES20.glUniform1i(mTexture4Handle5, pos); break;
                }
                pos++;
            }
        }
    }

    private void bindTextures4(MagicSmokeScene.Preset preset) {
        int pos = 0;
        int mask = preset.textureMask;

        for (int i = 0; i < 5; i++) {
            if ((mask & (1 << i)) != 0) {
                int texIndex = i;
                if (i == 0 && preset.textureSwap) texIndex = 4;

                GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + pos);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextures[texIndex]);

                switch (pos) {
                    case 0: GLES20.glUniform1i(mTexture0Handle4, pos); break;
                    case 1: GLES20.glUniform1i(mTexture1Handle4, pos); break;
                    case 2: GLES20.glUniform1i(mTexture2Handle4, pos); break;
                    case 3: GLES20.glUniform1i(mTexture3Handle4, pos); break;
                }
                pos++;
            }
        }
    }

    @Override
    public void release() {
        deleteTextures();
        if (mProgram5Tex != 0) {
            GLES20.glDeleteProgram(mProgram5Tex);
            mProgram5Tex = 0;
        }
        if (mProgram4Tex != 0) {
            GLES20.glDeleteProgram(mProgram4Tex);
            mProgram4Tex = 0;
        }
        mInitialized = false;
    }

    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        mScene.mXOffset = xOffset;
        mScene.mYOffset = yOffset;
    }

    private void deleteTextures() {
        if (mTextures[0] != 0) {
            GLES20.glDeleteTextures(5, mTextures, 0);
            for (int i = 0; i < mTextures.length; i++) {
                mTextures[i] = 0;
            }
        }
    }
}
