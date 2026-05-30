package com.reandroid.wallpaper.deepsea;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import com.reandroid.gles.AssetLoader;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * Extracted from DeepSeaGL inner class.
 */
class Sea2 {
    private Context mContext;
    private boolean mIsInitShader;
    Context getContext() { return mContext; }
    boolean isInitShader() { return mIsInitShader; }
    private Bitmap mBackgroundImage;
    private float mBrightness;
    private int mBrightnessHandle;
    private ShortBuffer mIndices;
    private final short[] mIndicesData;
    private int mMVPMatrixHandle;
    private int mPositionHandle;
    private int mProgramHandle;
    private int mTexCoordHandle;
    private int mTextureHandle;
    private int mTextureId;
    private FloatBuffer mVertices;
    private float[] mVerticesData;

    public Sea2() {
        this.mVerticesData = new float[]{-10.0f, 10.0f, -10.0f, 0.0f, 0.0f, -10.0f, -10.0f, -10.0f, 0.0f, 1.0f, 10.0f, -10.0f, -10.0f, 1.0f, 1.0f, 10.0f, 10.0f, -10.0f, 1.0f, 0.0f};
        this.mIndicesData = new short[]{0, 1, 2, 0, 2, 3};
        this.mBrightness = 0.0f;
        this.mBackgroundImage = null;
    }

    public Sea2(Context context) {
        this.mContext = context;
        this.mVerticesData = new float[]{-10.0f, 10.0f, -10.0f, 0.0f, 0.0f, -10.0f, -10.0f, -10.0f, 0.0f, 1.0f, 10.0f, -10.0f, -10.0f, 1.0f, 1.0f, 10.0f, 10.0f, -10.0f, 1.0f, 0.0f};
        this.mIndicesData = new short[]{0, 1, 2, 0, 2, 3};
        this.mBrightness = 0.0f;
        this.mBackgroundImage = null;
    }

    public void initByteBuffer() {
        this.mVertices = ByteBuffer.allocateDirect(this.mVerticesData.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        this.mVertices.put(this.mVerticesData).position(0);
        this.mIndices = ByteBuffer.allocateDirect(this.mIndicesData.length * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
        this.mIndices.put(this.mIndicesData).position(0);
    }

    public void initShader() {
        if (!isInitShader()) {
            int createdAndLinkedProgram = GLHelper.getCreatedAndLinkedProgram(GLHelper.getCompiledShader(GLES20.GL_VERTEX_SHADER, AssetLoader.readText(getContext(), "deepsea/shaders/GLES/deepsea_sea2_0_vs.glsl")), GLHelper.getCompiledShader(GLES20.GL_FRAGMENT_SHADER, AssetLoader.readText(getContext(), "deepsea/shaders/GLES/deepsea_sea2_1_fs.glsl")));
            this.mProgramHandle = createdAndLinkedProgram;
            this.mPositionHandle = GLES20.glGetAttribLocation(createdAndLinkedProgram, "a_position");
            this.mTexCoordHandle = GLES20.glGetAttribLocation(this.mProgramHandle, "a_texCoord");
            this.mTextureHandle = GLES20.glGetUniformLocation(this.mProgramHandle, "s_texture");
            this.mMVPMatrixHandle = GLES20.glGetUniformLocation(this.mProgramHandle, "u_MVPMatrix");
            this.mBrightnessHandle = GLES20.glGetUniformLocation(this.mProgramHandle, "u_brightness");
            changeTextureToBackground();
            this.mIsInitShader = true;
        }
    }

    public void setForDrawing() {
        GLES20.glUseProgram(this.mProgramHandle);
        this.mVertices.position(0);
        GLES20.glVertexAttribPointer(this.mPositionHandle, 3, GLES20.GL_FLOAT, false, 20, (Buffer) this.mVertices);
        this.mVertices.position(3);
        GLES20.glVertexAttribPointer(this.mTexCoordHandle, 2, GLES20.GL_FLOAT, false, 20, (Buffer) this.mVertices);
        GLES20.glEnableVertexAttribArray(this.mPositionHandle);
        GLES20.glEnableVertexAttribArray(this.mTexCoordHandle);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, this.mTextureId);
        GLES20.glEnable(GLES20.GL_TEXTURE_2D);
        GLES20.glUniform1i(this.mTextureHandle, 0);
        GLES20.glUniform1f(this.mBrightnessHandle, this.mBrightness);
    }

    public void draw() {
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 6, GLES20.GL_UNSIGNED_SHORT, this.mIndices);
    }

    public void update(float[] fArr) {
        GLES20.glUniformMatrix4fv(this.mMVPMatrixHandle, 1, false, fArr, 0);
    }

    public void changeTextureToBackground() {
        if (this.mBackgroundImage != null) {
            this.mTextureId = GLHelper.getTextureByBitmap(this.mBackgroundImage);
        } else if (this.mTextureId > 0) {
            GLHelper.deleteTextures(this.mTextureId);
            this.mTextureId = 0;
        }
    }

    public void setBackgroundImage(Bitmap bitmap) {
        this.mBackgroundImage = bitmap;
    }

    public void removeBackgroundImage() {
        if (this.mBackgroundImage != null) {
            this.mBackgroundImage.recycle();
        }
        this.mBackgroundImage = null;
    }

    public boolean hasBackgroundImage() {
        return this.mBackgroundImage != null;
    }

    public void changeDisplay(int screenWidth, int screenHeight) {
        float topY;
        float bottomY;
        float widthValue;
        float aspectRatio = (float) screenWidth / (float) screenHeight;
        if (screenWidth > screenHeight) {
            float scaledWidth = 10.6f * aspectRatio;
            topY = 9.5f * aspectRatio;
            bottomY = aspectRatio * 10.2f;
            widthValue = scaledWidth;
        } else {
            topY = 9.8f;
            bottomY = 10.6f;
            widthValue = 10.6f;
        }
        this.mVerticesData[0] = -widthValue;
        this.mVerticesData[5] = -widthValue;
        this.mVerticesData[10] = widthValue;
        this.mVerticesData[15] = widthValue;
        this.mVerticesData[1] = topY;
        this.mVerticesData[6] = -bottomY;
        this.mVerticesData[11] = -bottomY;
        this.mVerticesData[16] = topY;
    }

    public void remove() {
        GLHelper.deleteTextures(this.mTextureId);
        if (this.mBackgroundImage != null) {
            this.mBackgroundImage = null;
        }
        this.mContext = null;
    }
}
