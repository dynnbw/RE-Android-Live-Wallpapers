package com.reandroid.wallpaper.deepsea;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.util.Log;
import com.reandroid.utils.AssetLoader;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * Extracted from DeepSeaGL inner class.
 */
class Sea {
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

    public Sea() {
        this.mVerticesData = new float[]{-6.0f, 9.5f, -10.0f, 0.0f, 0.0f, -6.0f, -9.5f, -10.0f, 0.0f, 1.0f, 6.0f, -9.5f, -10.0f, 1.0f, 1.0f, 6.0f, 9.5f, -10.0f, 1.0f, 0.0f};
        this.mIndicesData = new short[]{0, 1, 2, 0, 2, 3};
        this.mBrightness = -1.0f;
        this.mBackgroundImage = null;
    }

    public Sea(Context context) {
        this.mContext = context;
        this.mVerticesData = new float[]{-6.0f, 9.5f, -10.0f, 0.0f, 0.0f, -6.0f, -9.5f, -10.0f, 0.0f, 1.0f, 6.0f, -9.5f, -10.0f, 1.0f, 1.0f, 6.0f, 9.5f, -10.0f, 1.0f, 0.0f};
        this.mIndicesData = new short[]{0, 1, 2, 0, 2, 3};
        this.mBrightness = -1.0f;
        this.mBackgroundImage = null;
    }

    public void initByteBuffer() {
        FloatBuffer asFloatBuffer = ByteBuffer.allocateDirect(this.mVerticesData.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        this.mVertices = asFloatBuffer;
        asFloatBuffer.put(this.mVerticesData).position(0);
        ShortBuffer asShortBuffer = ByteBuffer.allocateDirect(this.mIndicesData.length * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
        this.mIndices = asShortBuffer;
        asShortBuffer.put(this.mIndicesData).position(0);
    }

    public void initShader() {
        if (!isInitShader()) {
            int createdAndLinkedProgram = GLHelper.getCreatedAndLinkedProgram(GLHelper.getCompiledShader(GLES20.GL_VERTEX_SHADER, AssetLoader.readText(getContext(), "deepsea/shaders/GLES/deepsea_sea_0_vs.glsl")), GLHelper.getCompiledShader(GLES20.GL_FRAGMENT_SHADER, AssetLoader.readText(getContext(), "deepsea/shaders/GLES/deepsea_sea_1_fs.glsl")));
            this.mProgramHandle = createdAndLinkedProgram;
            this.mPositionHandle = GLES20.glGetAttribLocation(createdAndLinkedProgram, "a_position");
            this.mTexCoordHandle = GLES20.glGetAttribLocation(this.mProgramHandle, "a_texCoord");
            this.mTextureHandle = GLES20.glGetUniformLocation(this.mProgramHandle, "s_texture");
            this.mMVPMatrixHandle = GLES20.glGetUniformLocation(this.mProgramHandle, "u_MVPMatrix");
            this.mBrightnessHandle = GLES20.glGetUniformLocation(this.mProgramHandle, "u_brightness");
            this.mTextureId = getTextureId();
            Log.d("DeepSea", "Sea.initShader() - mTextureId = " + this.mTextureId + ", mBrightness = " + this.mBrightness);
            this.mIsInitShader = true;
        }
    }

    public void setForDrawing() {
        Log.d("DeepSea", "Sea.setForDrawing() - mTextureId = " + this.mTextureId + ", mBrightness = " + this.mBrightness);
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

    protected int getTextureId() {
        return GLHelper.getTextureFromAsset(getContext(), "deepsea/drawable/bg_s512x512_opt.png");
    }

    public void setBrightness(float f) {
        this.mBrightness = f;
        Log.d("DeepSea", "Sea.setBrightness called: " + f);
    }

    public void changeDisplay(int screenWidth, int screenHeight) {
        float aspectRatioScaled = (((float) screenWidth) / ((float) screenHeight)) * 10.0f;
        float finalWidth = (0.4f * aspectRatioScaled) + aspectRatioScaled;
        float[] fArr = this.mVerticesData;
        fArr[0] = -finalWidth;
        fArr[5] = -finalWidth;
        fArr[10] = finalWidth;
        fArr[15] = finalWidth;
        fArr[1] = 15.5f;
        fArr[6] = -15.5f;
        fArr[11] = -15.5f;
        fArr[16] = 15.5f;
    }

    public void remove() {
        GLHelper.deleteTextures(this.mTextureId);
        if (this.mBackgroundImage != null) {
            this.mBackgroundImage = null;
        }
        this.mContext = null;
    }
}
