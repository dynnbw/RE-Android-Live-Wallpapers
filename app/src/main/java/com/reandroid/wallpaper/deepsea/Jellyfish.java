package com.reandroid.wallpaper.deepsea;

import android.content.Context;
import android.opengl.GLES20;
import com.reandroid.gles.GLESWallpaper;
import com.reandroid.wallpaper.R;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
class Jellyfish extends GLBaseView {
    private float mAddAlpha;
    private int mAddAlphaHandle;
    private float[] mAddColorData;
    private int mAddColorHandle;
    private int mAlphaTextureHandle;
    private int mAlphaTextureId;
    private ShortBuffer mIndices;
    private final short[] mIndicesData;
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

    public Jellyfish() {
        this(GLESWallpaper.getAppContext());
    }

    public Jellyfish(Context context) {
        super(context);
        this.mScale = 1.0f;
        this.mVerticesData = new float[]{-0.6f, 0.6f, 0.0f, 0.0f, 0.0f, -0.6f, -0.6f, 0.0f, 0.0f, 1.0f, 0.6f, -0.6f, 0.0f, 1.0f, 1.0f, 0.6f, 0.6f, 0.0f, 1.0f, 0.0f};
        this.mIndicesData = new short[]{0, 1, 2, 0, 2, 3};
        this.mAddColorData = new float[]{0.0f, 0.0f, 0.0f, 0.0f};
        this.mAddAlpha = -1.0f;
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
            int createdAndLinkedProgram = GLHelper.getCreatedAndLinkedProgram(GLHelper.getCompiledShader(35633, "uniform mat4 u_MVPMatrix;\t\t\t\t\t\t\nattribute vec4 a_Position;\t\t\t\t\t\t\nattribute float a_Scale;\t\t\t\t\t\t\nattribute vec4 a_AddColor;\t\t\t\t\t\t\nattribute vec2 a_TexCoord;\t\t\t\t\t\t\nvarying vec4 v_AddColor;\t\t\t\t\t\t\nvarying vec2 v_TexCoord;\t\t\t\t\t\t\nvoid main(){\t\t\t\t\t\t\t\t\t\n\tgl_Position = a_Position;\t\t\t\t\t\n\tgl_Position.x *= a_Scale;\t\t\t\t\t\n\tgl_Position.y *= a_Scale;\t\t\t\t\t\n\tgl_Position = u_MVPMatrix * gl_Position;\t\n \tv_TexCoord = a_TexCoord;\t\t\t\t\t\n \tv_AddColor = a_AddColor;\t\t\t\t\t\n}"), GLHelper.getCompiledShader(35632, "precision mediump float;\t\t\t\t\t\t\nvarying vec4 v_AddColor;\t\t\t\t\t\t\nvarying vec2 v_TexCoord;\t\t\t\t\t\t\nuniform sampler2D s_Texture;\t\t\t\t\t\nuniform sampler2D s_AlphaTexture;\t\t\t\t\nuniform float u_AddAlpha;\t\t\t\t\t\t\nvoid main(){\t\t\t\t\t\t\t\t\t\n\tvec4 texture;\t\t\t\t\t\t\t\t\n\tvec4 alphaTexture;\t\t\t\t\t\t\t\n\ttexture = texture2D(s_Texture, v_TexCoord);\t\n\talphaTexture = texture2D(s_AlphaTexture, v_TexCoord);\n\ttexture.a = alphaTexture.r;\t\t\t\t\t\n\tgl_FragColor = texture + v_AddColor + vec4(0.0, 0.0, 0.0, u_AddAlpha); \n}"));
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
            super.initShader();
        }
    }

    @Override // com.reandroid.wallpaper.deepsea.GLBaseView
    public void setForDrawing() {
        GLES20.glUseProgram(this.mPointProgramHandle);
        GLES20.glVertexAttrib4f(this.mAddColorHandle, this.mAddColorData[0], this.mAddColorData[1], this.mAddColorData[2], this.mAddColorData[3]);
        GLES20.glDisableVertexAttribArray(this.mAddColorHandle);
        GLES20.glVertexAttrib1f(this.mScaleHandle, this.mScale);
        GLES20.glDisableVertexAttribArray(this.mScaleHandle);
        this.mVertices.position(0);
        GLES20.glVertexAttribPointer(this.mPositionHandle, 3, 5126, false, 20, (Buffer) this.mVertices);
        this.mVertices.position(3);
        GLES20.glVertexAttribPointer(this.mTexCoordHandle, 2, 5126, false, 20, (Buffer) this.mVertices);
        GLES20.glEnableVertexAttribArray(this.mPositionHandle);
        GLES20.glEnableVertexAttribArray(this.mTexCoordHandle);
        GLES20.glActiveTexture(33984);
        GLES20.glBindTexture(3553, this.mJellyfishTextureId);
        GLES20.glUniform1i(this.mPointTextureHandle, 0);
        GLES20.glActiveTexture(33985);
        GLES20.glBindTexture(3553, this.mAlphaTextureId);
        GLES20.glUniform1i(this.mAlphaTextureHandle, 1);
        GLES20.glEnable(3553);
        GLES20.glUniform1f(this.mAddAlphaHandle, this.mAddAlpha);
    }

    @Override // com.reandroid.wallpaper.deepsea.GLBaseView
    public void draw() {
        GLES20.glDrawElements(4, 6, 5123, this.mIndices);
    }

    @Override // com.reandroid.wallpaper.deepsea.GLBaseView
    public void update(float[] fArr) {
        GLES20.glUniformMatrix4fv(this.mPointMVPMatrixHandle, 1, false, fArr, 0);
    }

    protected int getTextureId() {
        return GLHelper.getTexture(getContext(), R.drawable.unit_a_s256x256_e_mip_0);
    }

    protected int getAlphaTextureId() {
        return GLHelper.getTexture(getContext(), R.drawable.unit_a_s256x256_e_mip_0_alpha);
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

    @Override // com.reandroid.wallpaper.deepsea.GLBaseView
    public void remove() {
        GLHelper.deleteTextures(this.mJellyfishTextureId);
        GLHelper.deleteTextures(this.mAlphaTextureId);
        super.remove();
    }
}
