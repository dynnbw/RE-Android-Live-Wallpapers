package com.reandroid.wallpaper.deepsea;

import android.content.Context;
import android.opengl.GLES20;
import android.util.Log;
import com.reandroid.gles.AssetLoader;
import com.reandroid.gles.GLESWallpaper;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
class Sunshine {
    private Context mContext;
    private boolean mIsInitShader;
    Context getContext() { return mContext; }
    boolean isInitShader() { return mIsInitShader; }
    private float mAddAlpha;
    private int mAddAlphaHandle;
    private ShortBuffer mIndices;
    private final short[] mIndicesData;
    private float mLight;
    private int mLightHandle;
    private int mMVPMatrixHandle;
    private int mPositionHandle;
    private int mProgramHandle;
    private int mTexCoordHandle;
    private int mTextureHandle;
    private int mTextureId;
    private final String mCustomTextureAsset;
    private FloatBuffer mVertices;
    private float[] mVerticesData;

    public Sunshine() { this(GLESWallpaper.getAppContext(), null); }
    public Sunshine(Context context) { this(context, null); }

    public Sunshine(Context context, String textureAsset) {
        this.mContext = context;
        this.mCustomTextureAsset = textureAsset;
        this.mVerticesData = new float[]{-10.5f, 16.0f, -10.0f, 0.0f, 0.0f, -10.5f, -16.0f, -10.0f, 0.0f, 1.0f, 10.5f, -16.0f, -10.0f, 1.0f, 1.0f, 10.5f, 16.0f, -10.0f, 1.0f, 0.0f};
        this.mIndicesData = new short[]{0, 1, 2, 0, 2, 3};
        this.mAddAlpha = -1.0f;
        this.mLight = -1.0f;
    }

    public void initByteBuffer() {
        this.mVertices = ByteBuffer.allocateDirect(this.mVerticesData.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        this.mVertices.put(this.mVerticesData).position(0);
        this.mIndices = ByteBuffer.allocateDirect(this.mIndicesData.length * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
        this.mIndices.put(this.mIndicesData).position(0);
    }

    public void initShader() {
        if (!isInitShader()) {
            int createdAndLinkedProgram = GLHelper.getCreatedAndLinkedProgram(GLHelper.getCompiledShader(GLES20.GL_VERTEX_SHADER, AssetLoader.readText(getContext(), "deepsea/shaders/GLES/deepsea_sunshine_0_vs.glsl")), GLHelper.getCompiledShader(GLES20.GL_FRAGMENT_SHADER, AssetLoader.readText(getContext(), "deepsea/shaders/GLES/deepsea_sunshine_1_fs.glsl")));
            this.mProgramHandle = createdAndLinkedProgram;
            this.mPositionHandle = GLES20.glGetAttribLocation(createdAndLinkedProgram, "a_position");
            this.mTexCoordHandle = GLES20.glGetAttribLocation(this.mProgramHandle, "a_texCoord");
            this.mTextureHandle = GLES20.glGetUniformLocation(this.mProgramHandle, "s_texture");
            this.mMVPMatrixHandle = GLES20.glGetUniformLocation(this.mProgramHandle, "u_MVPMatrix");
            this.mAddAlphaHandle = GLES20.glGetUniformLocation(this.mProgramHandle, "u_AddAlpha");
            this.mLightHandle = GLES20.glGetUniformLocation(this.mProgramHandle, "u_Light");
            this.mTextureId = getTextureId();
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
        GLES20.glUniform1f(this.mAddAlphaHandle, this.mAddAlpha);
        GLES20.glUniform1f(this.mLightHandle, this.mLight);
    }

    public void draw() {
        Log.d("DeepSea", "Sea.draw() executing - about to call glDrawElements");
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 6, GLES20.GL_UNSIGNED_SHORT, this.mIndices);
        Log.d("DeepSea", "Sea.draw() completed");
    }

    public void update(float[] fArr) {
        GLES20.glUniformMatrix4fv(this.mMVPMatrixHandle, 1, false, fArr, 0);
    }

    protected int getTextureId() {
        String asset = mCustomTextureAsset != null ? mCustomTextureAsset : "deepsea/drawable/light_s512x512_opt.png";
        return GLHelper.getTextureFromAsset(getContext(), asset);
    }

    public void setAddAlpha(float f) {
        this.mAddAlpha = f;
    }

    public void setLight(float f) {
        this.mLight = f;
    }

    public void changeDisplay(int screenWidth, int screenHeight) {
        float aspectRatioScaled = ((float) screenWidth / (float) screenHeight) * 10.0f;
        float finalWidth = aspectRatioScaled + (0.5f * aspectRatioScaled);
        this.mVerticesData[0] = -finalWidth;
        this.mVerticesData[5] = -finalWidth;
        this.mVerticesData[10] = finalWidth;
        this.mVerticesData[15] = finalWidth;
        float heightValue = (0.55f * 10.0f) + 10.0f;
        this.mVerticesData[1] = heightValue;
        this.mVerticesData[6] = -heightValue;
        this.mVerticesData[11] = -heightValue;
        this.mVerticesData[16] = heightValue;
    }

    public void remove() {
        GLHelper.deleteTextures(this.mTextureId);
        this.mContext = null;
    }
}
