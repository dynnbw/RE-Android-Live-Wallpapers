package com.reandroid.wallpaper.deepsea;

import android.content.Context;
import android.opengl.GLES20;
import com.reandroid.gles.RawResourceLoader;
import com.reandroid.wallpaper.R;
import com.reandroid.gles.GLESWallpaper;
import com.reandroid.wallpaper.R;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
class Jellyfish {
    private Context mContext;
    private boolean mIsInitShader;
    Context getContext() { return mContext; }
    boolean isInitShader() { return mIsInitShader; }
    private float mAddAlpha;
    private int mAddAlphaHandle;
    private float[] mAddColorData;
    private int mAddColorHandle;
    private int mAlphaTextureHandle;
    private int mAlphaTextureId;
    private ShortBuffer mIndices;
    private final short[] mIndicesData;
    private final int mCustomTextureId;
    private final int mCustomAlphaTextureId;
    private int mJellyfishTextureId;
    private int mPointMVPMatrixHandle;
    private int mPointProgramHandle;
    private int mPointTextureHandle;
    private int mPositionHandle;
    private float mScale;
    private int mScaleHandle;
    private int mTexCoordHandle;
    private FloatBuffer mVertices;
    private final float[] mVerticesData;

    public Jellyfish() { this(GLESWallpaper.getAppContext(), 0, 0); }
    public Jellyfish(Context context) { this(context, 0, 0); }

    public Jellyfish(Context context, int textureId, int alphaTextureId) {
        this.mContext = context;
        this.mCustomTextureId = textureId;
        this.mCustomAlphaTextureId = alphaTextureId;
        this.mScale = 1.0f;
        this.mVerticesData = new float[]{-0.6f, 0.6f, 0.0f, 0.0f, 0.0f, -0.6f, -0.6f, 0.0f, 0.0f, 1.0f, 0.6f, -0.6f, 0.0f, 1.0f, 1.0f, 0.6f, 0.6f, 0.0f, 1.0f, 0.0f};
        this.mIndicesData = new short[]{0, 1, 2, 0, 2, 3};
        this.mAddColorData = new float[]{0.0f, 0.0f, 0.0f, 0.0f};
        this.mAddAlpha = -1.0f;
    }

    public void initByteBuffer() {
        this.mVertices = ByteBuffer.allocateDirect(this.mVerticesData.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        this.mVertices.put(this.mVerticesData).position(0);
        this.mIndices = ByteBuffer.allocateDirect(this.mIndicesData.length * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
        this.mIndices.put(this.mIndicesData).position(0);
    }

    public void initShader() {
        if (!isInitShader()) {
            int createdAndLinkedProgram = GLHelper.getCreatedAndLinkedProgram(GLHelper.getCompiledShader(GLES20.GL_VERTEX_SHADER, RawResourceLoader.readRawText(getContext().getResources(), R.raw.deepsea_jellyfish_0_vs)), GLHelper.getCompiledShader(GLES20.GL_FRAGMENT_SHADER, RawResourceLoader.readRawText(getContext().getResources(), R.raw.deepsea_jellyfish_1_fs)));
            this.mPointProgramHandle = createdAndLinkedProgram;
            this.mPointMVPMatrixHandle = GLES20.glGetUniformLocation(this.mPointProgramHandle, "u_MVPMatrix");
            this.mPointTextureHandle = GLES20.glGetUniformLocation(this.mPointProgramHandle, "s_Texture");
            this.mAlphaTextureHandle = GLES20.glGetUniformLocation(this.mPointProgramHandle, "s_AlphaTexture");
            this.mPositionHandle = GLES20.glGetAttribLocation(this.mPointProgramHandle, "a_Position");
            this.mTexCoordHandle = GLES20.glGetAttribLocation(this.mPointProgramHandle, "a_TexCoord");
            this.mScaleHandle = GLES20.glGetAttribLocation(this.mPointProgramHandle, "a_Scale");
            this.mAddColorHandle = GLES20.glGetAttribLocation(this.mPointProgramHandle, "a_AddColor");
            this.mAddAlphaHandle = GLES20.glGetUniformLocation(this.mPointProgramHandle, "u_AddAlpha");
            this.mJellyfishTextureId = getTextureId();
            this.mAlphaTextureId = getAlphaTextureId();
            this.mIsInitShader = true;
        }
    }

    public void setForDrawing() {
        GLES20.glUseProgram(this.mPointProgramHandle);
        GLES20.glVertexAttrib4f(this.mAddColorHandle, this.mAddColorData[0], this.mAddColorData[1], this.mAddColorData[2], this.mAddColorData[3]);
        GLES20.glDisableVertexAttribArray(this.mAddColorHandle);
        GLES20.glVertexAttrib1f(this.mScaleHandle, this.mScale);
        GLES20.glDisableVertexAttribArray(this.mScaleHandle);
        this.mVertices.position(0);
        GLES20.glVertexAttribPointer(this.mPositionHandle, 3, GLES20.GL_FLOAT, false, 20, (Buffer) this.mVertices);
        this.mVertices.position(3);
        GLES20.glVertexAttribPointer(this.mTexCoordHandle, 2, GLES20.GL_FLOAT, false, 20, (Buffer) this.mVertices);
        GLES20.glEnableVertexAttribArray(this.mPositionHandle);
        GLES20.glEnableVertexAttribArray(this.mTexCoordHandle);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, this.mJellyfishTextureId);
        GLES20.glUniform1i(this.mPointTextureHandle, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, this.mAlphaTextureId);
        GLES20.glUniform1i(this.mAlphaTextureHandle, 1);
        GLES20.glEnable(GLES20.GL_TEXTURE_2D);
        GLES20.glUniform1f(this.mAddAlphaHandle, this.mAddAlpha);
    }

    public void draw() {
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 6, GLES20.GL_UNSIGNED_SHORT, this.mIndices);
    }

    public void update(float[] fArr) {
        GLES20.glUniformMatrix4fv(this.mPointMVPMatrixHandle, 1, false, fArr, 0);
    }

    protected int getTextureId() {
        int resId = mCustomTextureId != 0 ? mCustomTextureId : R.drawable.unit_a_s256x256_e_mip_0;
        return GLHelper.getTexture(getContext(), resId);
    }

    protected int getAlphaTextureId() {
        int resId = mCustomAlphaTextureId != 0 ? mCustomAlphaTextureId : R.drawable.unit_a_s256x256_e_mip_0_alpha;
        return GLHelper.getTexture(getContext(), resId);
    }

    public void setScale(float f) {
        this.mScale = f;
    }

    public void setAddColor(float f, float f2, float f3, float f4) {
        this.mAddColorData = null;
        this.mAddColorData = new float[]{f, f2, f3, f4};
    }

    public void setAddAlpha(float f) {
        this.mAddAlpha = f;
    }

    public void remove() {
        GLHelper.deleteTextures(this.mJellyfishTextureId);
        GLHelper.deleteTextures(this.mAlphaTextureId);
        this.mContext = null;
    }
}
