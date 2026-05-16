package com.reandroid.wallpaper.deepsea;

import android.content.Context;
import android.opengl.GLES20;
import com.reandroid.gles.RawResourceLoader;
import com.reandroid.wallpaper.R;
import android.util.Log;
import com.reandroid.gles.GLESWallpaper;
import com.reandroid.wallpaper.R;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
class Sunshine extends GLBaseView {
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
    private FloatBuffer mVertices;
    private float[] mVerticesData;

    public Sunshine() {
        this(GLESWallpaper.getAppContext());
    }

    public Sunshine(Context context) {
        super(context);
        this.mVerticesData = new float[]{-10.5f, 16.0f, -10.0f, 0.0f, 0.0f, -10.5f, -16.0f, -10.0f, 0.0f, 1.0f, 10.5f, -16.0f, -10.0f, 1.0f, 1.0f, 10.5f, 16.0f, -10.0f, 1.0f, 0.0f};
        this.mIndicesData = new short[]{0, 1, 2, 0, 2, 3};
        this.mAddAlpha = -1.0f;
        this.mLight = -1.0f;
    }

    @Override // com.reandroid.wallpaper.deepsea.GLBaseView
    public void initByteBuffer() {
        this.mVertices = ByteBuffer.allocateDirect(this.mVerticesData.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        this.mVertices.put(this.mVerticesData).position(0);
        this.mIndices = ByteBuffer.allocateDirect(this.mIndicesData.length * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
        this.mIndices.put(this.mIndicesData).position(0);
    }

    @Override // com.reandroid.wallpaper.deepsea.GLBaseView
    public void initShader() {
        if (!isInitShader()) {
            int createdAndLinkedProgram = GLHelper.getCreatedAndLinkedProgram(GLHelper.getCompiledShader(GLES20.GL_VERTEX_SHADER, RawResourceLoader.readRawText(getContext().getResources(), R.raw.deepsea_sunshine_0_vs)), GLHelper.getCompiledShader(GLES20.GL_FRAGMENT_SHADER, RawResourceLoader.readRawText(getContext().getResources(), R.raw.deepsea_sunshine_1_fs)));
            this.mProgramHandle = createdAndLinkedProgram;
            this.mPositionHandle = GLES20.glGetAttribLocation(createdAndLinkedProgram, "a_position");
            this.mTexCoordHandle = GLES20.glGetAttribLocation(this.mProgramHandle, "a_texCoord");
            this.mTextureHandle = GLES20.glGetUniformLocation(this.mProgramHandle, "s_texture");
            this.mMVPMatrixHandle = GLES20.glGetUniformLocation(this.mProgramHandle, "u_MVPMatrix");
            this.mAddAlphaHandle = GLES20.glGetUniformLocation(this.mProgramHandle, "u_AddAlpha");
            this.mLightHandle = GLES20.glGetUniformLocation(this.mProgramHandle, "u_Light");
            this.mTextureId = getTextureId();
            super.initShader();
        }
    }

    @Override // com.reandroid.wallpaper.deepsea.GLBaseView
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

    @Override // com.reandroid.wallpaper.deepsea.GLBaseView
    public void draw() {
        Log.d("DeepSea", "Sea.draw() executing - about to call glDrawElements");
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 6, GLES20.GL_UNSIGNED_SHORT, this.mIndices);
        Log.d("DeepSea", "Sea.draw() completed");
    }

    @Override // com.reandroid.wallpaper.deepsea.GLBaseView
    public void update(float[] fArr) {
        GLES20.glUniformMatrix4fv(this.mMVPMatrixHandle, 1, false, fArr, 0);
    }

    protected int getTextureId() {
        return GLHelper.getTexture(getContext(), R.drawable.light_s512x512_opt);
    }

    public void setAddAlpha(float f) {
        this.mAddAlpha = f;
    }

    public void setLight(float f) {
        this.mLight = f;
    }

    public void changeDisplay(int i, int i2) {
        float f = ((float) i / (float) i2) * 10.0f;
        float f2 = f + (0.5f * f);
        this.mVerticesData[0] = -f2;
        this.mVerticesData[5] = -f2;
        this.mVerticesData[10] = f2;
        this.mVerticesData[15] = f2;
        float f3 = (0.55f * 10.0f) + 10.0f;
        this.mVerticesData[1] = f3;
        this.mVerticesData[6] = -f3;
        this.mVerticesData[11] = -f3;
        this.mVerticesData[16] = f3;
    }

    @Override // com.reandroid.wallpaper.deepsea.GLBaseView
    public void remove() {
        GLHelper.deleteTextures(this.mTextureId);
        super.remove();
    }
}
