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
class JellyfishWaterDrops extends GLBaseView {
    private float mAddAlpha;
    private int mAddAlphaHandle;
    private float[] mAddColorData;
    private int mAddColorHandle;
    private int mAgeHandle;
    private int mAlphaTextureHandle;
    private int mAlphaTextureId;
    private int mEmitterHandle;
    private Random mGenerater;
    private boolean mIsSlow;
    private int mLifeHandle;
    private int mMVPMatrixHandle;
    private int mMoveHandle;
    private int mNumberOfParticles;
    private float[] mParticleVertices;
    private int mPositionHandle;
    private int mProgramHandle;
    private float mScale;
    private int mScaleHandle;
    private int mSizeHandle;
    private int mSpeedHandle;
    private int mTextureHandle;
    private int mTextureID;
    private float mTimeCounter;
    private int mTimesHandle;
    private FloatBuffer mVertexBuffer;

    public JellyfishWaterDrops() {
        this.mNumberOfParticles = 35;
        this.mTimeCounter = 0.0f;
        this.mParticleVertices = new float[35 * 13];
        this.mGenerater = new Random(System.currentTimeMillis());
        this.mAddAlpha = 0.0f;
        this.mAddColorData = new float[]{0.0f, 0.0f, 0.0f, 0.0f};
        this.mScale = 1.0f;
        this.mIsSlow = false;
    }

    public JellyfishWaterDrops(Context context) {
        super(context);
        this.mNumberOfParticles = 35;
        this.mTimeCounter = 0.0f;
        this.mParticleVertices = new float[35 * 13];
        this.mGenerater = new Random(System.currentTimeMillis());
        this.mAddAlpha = 0.0f;
        this.mAddColorData = new float[]{0.0f, 0.0f, 0.0f, 0.0f};
        this.mScale = 1.0f;
        this.mIsSlow = false;
    }

    @Override // com.reandroid.wallpaper.deepsea.GLBaseView
    public void initByteBuffer() {
        this.mVertexBuffer = ByteBuffer.allocateDirect(this.mParticleVertices.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        setParticleData(0.0f, 0.0f, 0.0f);
    }

    @Override // com.reandroid.wallpaper.deepsea.GLBaseView
    public void initShader() {
        if (!isInitShader()) {
            this.mProgramHandle = GLHelper.getCreatedAndLinkedProgram(GLHelper.getCompiledShader(GLES20.GL_VERTEX_SHADER, RawResourceLoader.readRawText(getContext().getResources(), R.raw.deepsea_jellyfishwaterdrops_0_vs)), GLHelper.getCompiledShader(GLES20.GL_FRAGMENT_SHADER, RawResourceLoader.readRawText(getContext().getResources(), R.raw.deepsea_jellyfishwaterdrops_1_fs)));
            this.mTextureID = GLHelper.getTexture(getContext(), R.drawable.particle_mip_0);
            this.mAlphaTextureId = GLHelper.getTexture(getContext(), R.drawable.particle_mip_0_alpha);
            this.mMVPMatrixHandle = GLES20.glGetUniformLocation(this.mProgramHandle, "u_MVPMatrix");
            this.mPositionHandle = GLES20.glGetAttribLocation(this.mProgramHandle, "a_Position");
            this.mTextureHandle = GLES20.glGetUniformLocation(this.mProgramHandle, "u_texture");
            this.mAlphaTextureHandle = GLES20.glGetUniformLocation(this.mProgramHandle, "s_AlphaTexture");
            this.mMoveHandle = GLES20.glGetAttribLocation(this.mProgramHandle, "a_move");
            this.mTimesHandle = GLES20.glGetUniformLocation(this.mProgramHandle, "a_time");
            this.mLifeHandle = GLES20.glGetAttribLocation(this.mProgramHandle, "a_life");
            this.mAgeHandle = GLES20.glGetAttribLocation(this.mProgramHandle, "a_age");
            this.mSizeHandle = GLES20.glGetAttribLocation(this.mProgramHandle, "a_size");
            this.mSpeedHandle = GLES20.glGetAttribLocation(this.mProgramHandle, "a_speed");
            this.mEmitterHandle = GLES20.glGetAttribLocation(this.mProgramHandle, "a_EmitterPosition");
            this.mAddAlphaHandle = GLES20.glGetUniformLocation(this.mProgramHandle, "u_AddAlpha");
            this.mAddColorHandle = GLES20.glGetAttribLocation(this.mProgramHandle, "a_AddColor");
            this.mScaleHandle = GLES20.glGetAttribLocation(this.mProgramHandle, "a_Scale");
            super.initShader();
        }
    }

    @Override // com.reandroid.wallpaper.deepsea.GLBaseView
    public void setForDrawing() {
        GLES20.glUseProgram(this.mProgramHandle);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, this.mTextureID);
        GLES20.glUniform1i(this.mTextureHandle, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, this.mAlphaTextureId);
        GLES20.glUniform1i(this.mAlphaTextureHandle, 1);
        GLES20.glEnable(GLES20.GL_TEXTURE_2D);
        updateTimeCounter();
        int i = this.mAddColorHandle;
        float[] fArr = this.mAddColorData;
        GLES20.glVertexAttrib4f(i, fArr[0], fArr[1], fArr[2], fArr[3]);
        GLES20.glDisableVertexAttribArray(this.mAddColorHandle);
        GLES20.glVertexAttrib1f(this.mScaleHandle, this.mScale);
        GLES20.glDisableVertexAttribArray(this.mScaleHandle);
        this.mVertexBuffer.position(0);
        GLES20.glVertexAttribPointer(this.mPositionHandle, 3, GLES20.GL_FLOAT, false, 52, (Buffer) this.mVertexBuffer);
        GLES20.glEnableVertexAttribArray(this.mPositionHandle);
        this.mVertexBuffer.position(3);
        GLES20.glVertexAttribPointer(this.mMoveHandle, 3, GLES20.GL_FLOAT, false, 52, (Buffer) this.mVertexBuffer);
        GLES20.glEnableVertexAttribArray(this.mMoveHandle);
        this.mVertexBuffer.position(6);
        GLES20.glVertexAttribPointer(this.mLifeHandle, 1, GLES20.GL_FLOAT, false, 52, (Buffer) this.mVertexBuffer);
        GLES20.glEnableVertexAttribArray(this.mLifeHandle);
        this.mVertexBuffer.position(7);
        GLES20.glVertexAttribPointer(this.mAgeHandle, 1, GLES20.GL_FLOAT, false, 52, (Buffer) this.mVertexBuffer);
        GLES20.glEnableVertexAttribArray(this.mAgeHandle);
        this.mVertexBuffer.position(8);
        GLES20.glVertexAttribPointer(this.mSizeHandle, 1, GLES20.GL_FLOAT, false, 52, (Buffer) this.mVertexBuffer);
        GLES20.glEnableVertexAttribArray(this.mSizeHandle);
        this.mVertexBuffer.position(9);
        GLES20.glVertexAttribPointer(this.mSpeedHandle, 1, GLES20.GL_FLOAT, false, 52, (Buffer) this.mVertexBuffer);
        GLES20.glEnableVertexAttribArray(this.mSpeedHandle);
        this.mVertexBuffer.position(10);
        GLES20.glVertexAttribPointer(this.mEmitterHandle, 3, GLES20.GL_FLOAT, false, 52, (Buffer) this.mVertexBuffer);
        GLES20.glEnableVertexAttribArray(this.mEmitterHandle);
        GLES20.glUniform1f(this.mTimesHandle, this.mTimeCounter);
        GLES20.glUniform1f(this.mAddAlphaHandle, this.mAddAlpha);
    }

    @Override // com.reandroid.wallpaper.deepsea.GLBaseView
    public void draw() {
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, this.mNumberOfParticles);
    }

    @Override // com.reandroid.wallpaper.deepsea.GLBaseView
    public void update(float[] fArr) {
        GLES20.glUniformMatrix4fv(this.mMVPMatrixHandle, 1, false, fArr, 0);
    }

    private void setParticleData(float f, float f2, float f3) {
        float f4 = 0.1f / this.mNumberOfParticles;
        float f5 = 0.2f;
        for (int i = 0; i < this.mNumberOfParticles; i++) {
            f5 += f4;
            int nextFloat = (int) (this.mGenerater.nextFloat() * 360.0f);
            float[] fArr = this.mParticleVertices;
            fArr[(i * 13) + 0] = f;
            fArr[(i * 13) + 1] = f2;
            fArr[(i * 13) + 2] = f3;
            double cos = Math.cos(Math.toRadians(nextFloat));
            double d = f5;
            Double.isNaN(d);
            fArr[(i * 13) + 3] = (float) (cos * d);
            double sin = Math.sin(Math.toRadians(nextFloat));
            double d2 = f5;
            Double.isNaN(d2);
            this.mParticleVertices[(i * 13) + 4] = (float) (sin * d2);
            this.mParticleVertices[(i * 13) + 5] = this.mGenerater.nextFloat() - 0.5f;
            this.mParticleVertices[(i * 13) + 6] = MathHelper.getRandomFloat(0.8f, 1.0f);
            this.mParticleVertices[(i * 13) + 7] = MathHelper.getRandomFloat(0.005f, 0.02f);
            this.mParticleVertices[(i * 13) + 8] = MathHelper.getRandomFloat(20.0f, 40.0f);
            this.mParticleVertices[(i * 13) + 9] = MathHelper.getRandomFloat(10.0f, 20.0f);
            float[] fArr2 = this.mParticleVertices;
            fArr2[(i * 13) + 10] = 0.0f;
            fArr2[(i * 13) + 11] = 0.0f;
            fArr2[(i * 13) + 12] = this.mGenerater.nextFloat() - 0.5f;
        }
        this.mVertexBuffer.put(this.mParticleVertices).position(0);
    }

    private void updateTimeCounter() {
        if (this.mIsSlow) {
            double d = this.mTimeCounter;
            Double.isNaN(d);
            this.mTimeCounter = (float) (d + 0.0025d);
            return;
        }
        double d2 = this.mTimeCounter;
        Double.isNaN(d2);
        this.mTimeCounter = (float) (d2 + 0.005d);
    }

    public void initTimeCounter() {
        this.mTimeCounter = 0.0f;
    }

    public void setAddAlpha(float f) {
        this.mAddAlpha = f;
    }

    public void setScale(float f) {
        this.mScale = f;
    }

    public void setNumberOfParticles(int i) {
        this.mNumberOfParticles = i;
        this.mParticleVertices = null;
        this.mParticleVertices = new float[i * 13];
        this.mVertexBuffer = null;
        initByteBuffer();
    }

    public void setIsSlow(boolean z) {
        this.mIsSlow = z;
    }

    public boolean isSlow() {
        return this.mIsSlow;
    }

    @Override // com.reandroid.wallpaper.deepsea.GLBaseView
    public void remove() {
        GLHelper.deleteTextures(this.mTextureID);
        GLHelper.deleteTextures(this.mAlphaTextureId);
        super.remove();
    }
}
