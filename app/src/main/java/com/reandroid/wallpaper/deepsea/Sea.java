package com.reandroid.wallpaper.deepsea;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.ETC1Util;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import com.reandroid.gles.GLESScene;
import com.reandroid.gles.GLESWallpaper;
import com.reandroid.gles.RawResourceLoader;
import com.reandroid.wallpaper.R;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Random;

/**
 * Extracted from DeepSeaGL inner class.
 */
class Sea extends GLBaseView {
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
        super(context);
        this.mVerticesData = new float[]{-6.0f, 9.5f, -10.0f, 0.0f, 0.0f, -6.0f, -9.5f, -10.0f, 0.0f, 1.0f, 6.0f, -9.5f, -10.0f, 1.0f, 1.0f, 6.0f, 9.5f, -10.0f, 1.0f, 0.0f};
        this.mIndicesData = new short[]{0, 1, 2, 0, 2, 3};
        this.mBrightness = -1.0f;
        this.mBackgroundImage = null;
    }

    @Override // com.reandroid.wallpaper.deepsea.GLBaseView
    public void initByteBuffer() {
        FloatBuffer asFloatBuffer = ByteBuffer.allocateDirect(this.mVerticesData.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        this.mVertices = asFloatBuffer;
        asFloatBuffer.put(this.mVerticesData).position(0);
        ShortBuffer asShortBuffer = ByteBuffer.allocateDirect(this.mIndicesData.length * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
        this.mIndices = asShortBuffer;
        asShortBuffer.put(this.mIndicesData).position(0);
    }

    @Override // com.reandroid.wallpaper.deepsea.GLBaseView
    public void initShader() {
        if (!isInitShader()) {
            int createdAndLinkedProgram = GLHelper.getCreatedAndLinkedProgram(GLHelper.getCompiledShader(GLES20.GL_VERTEX_SHADER, RawResourceLoader.readRawText(getContext().getResources(), R.raw.deepsea_sea_0_vs)), GLHelper.getCompiledShader(GLES20.GL_FRAGMENT_SHADER, RawResourceLoader.readRawText(getContext().getResources(), R.raw.deepsea_sea_1_fs)));
            this.mProgramHandle = createdAndLinkedProgram;
            this.mPositionHandle = GLES20.glGetAttribLocation(createdAndLinkedProgram, "a_position");
            this.mTexCoordHandle = GLES20.glGetAttribLocation(this.mProgramHandle, "a_texCoord");
            this.mTextureHandle = GLES20.glGetUniformLocation(this.mProgramHandle, "s_texture");
            this.mMVPMatrixHandle = GLES20.glGetUniformLocation(this.mProgramHandle, "u_MVPMatrix");
            this.mBrightnessHandle = GLES20.glGetUniformLocation(this.mProgramHandle, "u_brightness");
            this.mTextureId = getTextureId();
            Log.d("DeepSea", "Sea.initShader() - mTextureId = " + this.mTextureId + ", mBrightness = " + this.mBrightness);
            super.initShader();
        }
    }

    @Override // com.reandroid.wallpaper.deepsea.GLBaseView
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

    @Override // com.reandroid.wallpaper.deepsea.GLBaseView
    public void draw() {
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 6, GLES20.GL_UNSIGNED_SHORT, this.mIndices);
    }

    @Override // com.reandroid.wallpaper.deepsea.GLBaseView
    public void update(float[] fArr) {
        GLES20.glUniformMatrix4fv(this.mMVPMatrixHandle, 1, false, fArr, 0);
    }

    protected int getTextureId() {
        return GLHelper.getTexture(getContext(), 2131034112);
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

    @Override // com.reandroid.wallpaper.deepsea.GLBaseView
    public void remove() {
        GLHelper.deleteTextures(this.mTextureId);
        if (this.mBackgroundImage != null) {
            this.mBackgroundImage = null;
        }
        super.remove();
    }
}
