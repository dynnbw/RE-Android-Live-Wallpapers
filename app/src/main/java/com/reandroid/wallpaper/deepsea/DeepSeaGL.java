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

public class DeepSeaGL extends GLESScene {
    public static final String PREFS_NAME = "deepsea";
    public static final String KEY_BACKGROUND_IMAGE_TYPE = "backgroundimagetype";
    public static final String KEY_BACKGROUND_UPDATED = "deepsea_background_updated";

    private final DeepSeaScene mScene;

    public DeepSeaGL(int width, int height) {
        super(width, height);
        mScene = new DeepSeaScene();
    }

    @Override
    protected void onCreate() {
        mScene.onCreate();
    }

    @Override
    public void start() {
        mScene.start();
    }

    @Override
    public void stop() {
        mScene.stop();
    }

    @Override
    public void release() {
        mScene.release();
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        mScene.resize(width, height);
    }

    @Override
    public void drawFrame(long timeMs) {
        mScene.drawFrame();
    }
}







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
            this.mProgramHandle = GLHelper.getCreatedAndLinkedProgram(GLHelper.getCompiledShader(35633, "precision mediump float;\t\t\t\t\t\t\t\t\t\nuniform mat4 u_MVPMatrix;\t\t\t\t\t\t\t\t\t\nattribute vec4 a_Position;\t\t\t\t\t\t\t\t\t\nattribute vec4 a_EmitterPosition;\t\t\t\t\t\t\t\nattribute vec4 a_move;\t\t\t\t\t\t\t\t\t\tuniform float a_time;\t\t\t\t\t\t\t\t\t\t\nattribute float a_Scale;\t\t\t\t\t\t\t\t\t\nattribute float a_life;\t\t\t\t\t\t\t\t\t\nattribute float a_age;\t\t\t\t\t\t\t\t\t\t\nattribute float a_size;\t\t\t\t\t\t\t\t\t\nattribute float a_angle;\t\t\t\t\t\t\t\t\t\nattribute float a_speed;\t\t\t\t\t\t\t\t\t\nattribute vec4 a_AddColor;\t\t\t\t\t\t\t\t\t\nvarying float alpha;\t\t\t\t\t\t\t\t\t\t\nvarying vec4 v_AddColor;\t\t\t\t\t\t\t\t\t\nfloat time;\t\t\t\t\t\t\t\t\t\t\t\t\nvoid main(){\t\t\t\t\t\t\t\t\t\t\t\t\n\t\tfloat td = a_life/a_age;\t\t\t\t\t\t\t\n\t\ttd /= 10.0;\t\t\t\t\t\t\t\t\t\t\t\n\t\tfloat df = a_time/td;\t\t\t\t\t\t\t\t\n\t\tint div = int(df);\t\t\t\t\t\t\t\t\t\n\t\tdf = float(div);\t\t\t\t\t\t\t\t\t\n\t\ttd *= df;\t\t\t\t\t\t\t\t\t\t\t\n\t\ttime = a_time - td;\t\t\t\t\t\t\t\t\t\n\tfloat tempAlpha = (time * 20.0 * a_age);\t\t\t\t\n\tif(tempAlpha >= a_life){\t\t\t\t\t\t\t\t\n\t\talpha = a_life * 2.0 - tempAlpha;\t\t\t\t\t\n\t}else{\t\t\t\t\t\t\t\t\t\t\t\t\t\n\t\talpha = tempAlpha;\t\t\t\t\t\t\t\t\t\n\t}\t\t\t\t\t\t\t\t\t\t\t\t\t\t\n\tgl_Position = a_Position;\t\t\t\t\t\t\t\t\n\tvec4 move = a_move;\t\t\t\t\t\t\t\t\t\t\n\tmove.x *= a_Scale;\t\t\t\t\t\t\t\t\t\t\n\tmove.y *= a_Scale;\t\t\t\t\t\t\t\t\t\t\n\tgl_Position += (time * move * 0.6);\t\t\t\t\t\t\n\tgl_Position = u_MVPMatrix * gl_Position;\t\t\t\t\n\tgl_PointSize = (a_size - gl_Position.z) * a_Scale;\t\t\n \tif(gl_PointSize < 0.0)gl_PointSize = 0.0;\t\t\t\t\n\tvec4 ePosition = a_EmitterPosition + gl_Position;\t\t\n\tv_AddColor = a_AddColor;\t\t\t\t\t\t\t\t\n}"), GLHelper.getCompiledShader(35632, "precision mediump float;\t\t\t\t\t\t\t\t\t\nuniform sampler2D u_texture;\t\t\t\t\t\t\t\t\nuniform sampler2D s_AlphaTexture;\t\t\t\t\t\t\t\nuniform float u_AddAlpha;\t\t\t\t\t\t\t\t\t\nvarying float alpha;\t\t\t\t\t\t\t\t\t\t\nvarying vec4 v_AddColor;\t\t\t\t\t\t\t\t\t\nvoid main(){\t\t\t\t\t\t\t\t\t\t\t\t\n\tvec4 tex = texture2D(u_texture, gl_PointCoord);\t\t\t\n\tvec4 alphaTexture;\t\t\t\t\t\t\t\t\t\t\n\talphaTexture = texture2D(s_AlphaTexture, gl_PointCoord);\n\tgl_FragColor = tex + v_AddColor;\t\t\t\t\t\t\n\tgl_FragColor.w = (alphaTexture.r * alpha + u_AddAlpha) * 0.5;\t\t\t\n}"));
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
        GLES20.glActiveTexture(33984);
        GLES20.glBindTexture(3553, this.mTextureID);
        GLES20.glUniform1i(this.mTextureHandle, 0);
        GLES20.glActiveTexture(33985);
        GLES20.glBindTexture(3553, this.mAlphaTextureId);
        GLES20.glUniform1i(this.mAlphaTextureHandle, 1);
        GLES20.glEnable(3553);
        updateTimeCounter();
        int i = this.mAddColorHandle;
        float[] fArr = this.mAddColorData;
        GLES20.glVertexAttrib4f(i, fArr[0], fArr[1], fArr[2], fArr[3]);
        GLES20.glDisableVertexAttribArray(this.mAddColorHandle);
        GLES20.glVertexAttrib1f(this.mScaleHandle, this.mScale);
        GLES20.glDisableVertexAttribArray(this.mScaleHandle);
        this.mVertexBuffer.position(0);
        GLES20.glVertexAttribPointer(this.mPositionHandle, 3, 5126, false, 52, (Buffer) this.mVertexBuffer);
        GLES20.glEnableVertexAttribArray(this.mPositionHandle);
        this.mVertexBuffer.position(3);
        GLES20.glVertexAttribPointer(this.mMoveHandle, 3, 5126, false, 52, (Buffer) this.mVertexBuffer);
        GLES20.glEnableVertexAttribArray(this.mMoveHandle);
        this.mVertexBuffer.position(6);
        GLES20.glVertexAttribPointer(this.mLifeHandle, 1, 5126, false, 52, (Buffer) this.mVertexBuffer);
        GLES20.glEnableVertexAttribArray(this.mLifeHandle);
        this.mVertexBuffer.position(7);
        GLES20.glVertexAttribPointer(this.mAgeHandle, 1, 5126, false, 52, (Buffer) this.mVertexBuffer);
        GLES20.glEnableVertexAttribArray(this.mAgeHandle);
        this.mVertexBuffer.position(8);
        GLES20.glVertexAttribPointer(this.mSizeHandle, 1, 5126, false, 52, (Buffer) this.mVertexBuffer);
        GLES20.glEnableVertexAttribArray(this.mSizeHandle);
        this.mVertexBuffer.position(9);
        GLES20.glVertexAttribPointer(this.mSpeedHandle, 1, 5126, false, 52, (Buffer) this.mVertexBuffer);
        GLES20.glEnableVertexAttribArray(this.mSpeedHandle);
        this.mVertexBuffer.position(10);
        GLES20.glVertexAttribPointer(this.mEmitterHandle, 3, 5126, false, 52, (Buffer) this.mVertexBuffer);
        GLES20.glEnableVertexAttribArray(this.mEmitterHandle);
        GLES20.glUniform1f(this.mTimesHandle, this.mTimeCounter);
        GLES20.glUniform1f(this.mAddAlphaHandle, this.mAddAlpha);
    }

    @Override // com.reandroid.wallpaper.deepsea.GLBaseView
    public void draw() {
        GLES20.glDrawArrays(0, 0, this.mNumberOfParticles);
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

class Sea2 extends GLBaseView {
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
        super(context);
        this.mVerticesData = new float[]{-10.0f, 10.0f, -10.0f, 0.0f, 0.0f, -10.0f, -10.0f, -10.0f, 0.0f, 1.0f, 10.0f, -10.0f, -10.0f, 1.0f, 1.0f, 10.0f, 10.0f, -10.0f, 1.0f, 0.0f};
        this.mIndicesData = new short[]{0, 1, 2, 0, 2, 3};
        this.mBrightness = 0.0f;
        this.mBackgroundImage = null;
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
            int createdAndLinkedProgram = GLHelper.getCreatedAndLinkedProgram(GLHelper.getCompiledShader(35633, "uniform mat4 u_MVPMatrix;\t\t\t\t\t\t\t\nattribute vec4 a_position;\t\t\t\t\t\t\t\nattribute vec2 a_texCoord;\t\t\t\t\t\t\t\nvarying vec2 v_texCoord;\t\t\t\t\t\t\t\nvoid main(){\t\t\t\t\t\t\t\t\t\t\n\tgl_Position = u_MVPMatrix * a_position;\t\t\t\n\tv_texCoord = a_texCoord;\t\t\t\t\t\t\n}"), GLHelper.getCompiledShader(35632, "precision mediump float;\t\t\t\t\t\t\t\nvarying vec2 v_texCoord;\t\t\t\t\t\t\t\nuniform float u_brightness;\t\t\t\t\t\t\nuniform sampler2D s_texture;\t\t\t\t\t\t\nvoid main(){\t\t\t\t\t\t\t\t\t\t\n\tfloat val = u_brightness;\t\t\t\t\t\t\n \tvec4 color = vec4(val, val, val, 0.0);\t\t\t\n\tgl_FragColor = texture2D(s_texture, v_texCoord) + color; \n}\t\t\t\t\t\t\t\t\t\t\t\t\t\n"));
            this.mProgramHandle = createdAndLinkedProgram;
            this.mPositionHandle = GLES20.glGetAttribLocation(createdAndLinkedProgram, "a_position");
            this.mTexCoordHandle = GLES20.glGetAttribLocation(this.mProgramHandle, "a_texCoord");
            this.mTextureHandle = GLES20.glGetUniformLocation(this.mProgramHandle, "s_texture");
            this.mMVPMatrixHandle = GLES20.glGetUniformLocation(this.mProgramHandle, "u_MVPMatrix");
            this.mBrightnessHandle = GLES20.glGetUniformLocation(this.mProgramHandle, "u_brightness");
            changeTextureToBackground();
            super.initShader();
        }
    }

    @Override // com.reandroid.wallpaper.deepsea.GLBaseView
    public void setForDrawing() {
        GLES20.glUseProgram(this.mProgramHandle);
        this.mVertices.position(0);
        GLES20.glVertexAttribPointer(this.mPositionHandle, 3, 5126, false, 20, (Buffer) this.mVertices);
        this.mVertices.position(3);
        GLES20.glVertexAttribPointer(this.mTexCoordHandle, 2, 5126, false, 20, (Buffer) this.mVertices);
        GLES20.glEnableVertexAttribArray(this.mPositionHandle);
        GLES20.glEnableVertexAttribArray(this.mTexCoordHandle);
        GLES20.glActiveTexture(33984);
        GLES20.glBindTexture(3553, this.mTextureId);
        GLES20.glEnable(3553);
        GLES20.glUniform1i(this.mTextureHandle, 0);
        GLES20.glUniform1f(this.mBrightnessHandle, this.mBrightness);
    }

    @Override // com.reandroid.wallpaper.deepsea.GLBaseView
    public void draw() {
        GLES20.glDrawElements(4, 6, 5123, this.mIndices);
    }

    @Override // com.reandroid.wallpaper.deepsea.GLBaseView
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
        System.gc();
    }

    public void setBackgroundImage(Bitmap bitmap) {
        this.mBackgroundImage = bitmap;
    }

    public void removeBackgroundImage() {
        if (this.mBackgroundImage != null) {
            this.mBackgroundImage.recycle();
            System.gc();
        }
        this.mBackgroundImage = null;
    }

    public boolean hasBackgroundImage() {
        return this.mBackgroundImage != null;
    }

    public void changeDisplay(int i, int i2) {
        float f;
        float f2;
        float f3;
        float f4 = (float) i / (float) i2;
        if (i > i2) {
            float f5 = 10.6f * f4;
            f = 9.5f * f4;
            f2 = f4 * 10.2f;
            f3 = f5;
        } else {
            f = 9.8f;
            f2 = 10.6f;
            f3 = 10.6f;
        }
        this.mVerticesData[0] = -f3;
        this.mVerticesData[5] = -f3;
        this.mVerticesData[10] = f3;
        this.mVerticesData[15] = f3;
        this.mVerticesData[1] = f;
        this.mVerticesData[6] = -f2;
        this.mVerticesData[11] = -f2;
        this.mVerticesData[16] = f;
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

class SeaWaterDrops extends GLBaseView {
    private final int DISPLAY_HEIGHT;
    private final int DISPLAY_WIDTH;
    private int mAgeHandle;
    private int mAlphaTextureHandle;
    private int mAlphaTextureId;
    private float[] mBackupVertices;
    private int mEmitterHandle;
    private Random mGenerater;
    private boolean mIsSlow;
    private int mLifeHandle;
    private int mMVPMatrixHandle;
    private int mMoveHandle;
    private float[] mParticleVertices;
    private int mPositionHandle;
    private int mProgramHandle;
    private int mSizeHandle;
    private int mSpeedHandle;
    private int mTextureHandle;
    private int mTextureID;
    private float mTimeCounter;
    private int mTimesHandle;
    private FloatBuffer mVertexBuffer;

    public SeaWaterDrops() {
        this.DISPLAY_WIDTH = 720;
        this.DISPLAY_HEIGHT = 1184;
        this.mTimeCounter = 0.0f;
        this.mParticleVertices = new float[1170];
        this.mBackupVertices = new float[1170];
        this.mGenerater = new Random(System.currentTimeMillis());
        this.mIsSlow = false;
    }

    public SeaWaterDrops(Context context) {
        super(context);
        this.DISPLAY_WIDTH = 720;
        this.DISPLAY_HEIGHT = 1184;
        this.mTimeCounter = 0.0f;
        this.mParticleVertices = new float[1170];
        this.mBackupVertices = new float[1170];
        this.mGenerater = new Random(System.currentTimeMillis());
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
            int createdAndLinkedProgram = GLHelper.getCreatedAndLinkedProgram(GLHelper.getCompiledShader(35633, "precision mediump float;\t\t\t\t\t\t\t\t\t\nuniform mat4 u_MVPMatrix;\t\t\t\t\t\t\t\t\t\nattribute vec4 a_Position;\t\t\t\t\t\t\t\t\t\nattribute vec4 a_EmitterPosition;\t\t\t\t\t\t\t\nattribute vec4 a_move;\t\t\t\t\t\t\t\t\t\t\nuniform float a_time;\t\t\t\t\t\t\t\t\t\t\nattribute float a_life;\t\t\t\t\t\t\t\t\t\nattribute float a_age;\t\t\t\t\t\t\t\t\t\t\nattribute float a_size;\t\t\t\t\t\t\t\t\t\nattribute float a_angle;\t\t\t\t\t\t\t\t\t\nattribute float a_speed;\t\t\t\t\t\t\t\t\t\nvarying float alpha;\t\t\t\t\t\t\t\t\t\t\nfloat time;\t\t\t\t\t\t\t\t\t\t\t\t\nvoid main(){\t\t\t\t\t\t\t\t\t\t\t\t\n\talpha = a_life - (a_time * 10.0 * a_age);\t\t\t\t\n\ttime = a_time;\t\t\t\t\t\t\t\t\t\t\t\n\tif(alpha < 0.0){\t\t\t\t\t\t\t\t\t\t\n\t\tfloat td = a_life/a_age;\t\t\t\t\t\t\t\n\t\ttd /= 10.0;\t\t\t\t\t\t\t\t\t\t\t\n\t\tfloat df = a_time/td;\t\t\t\t\t\t\t\t\n\t\tint div = int(df);\t\t\t\t\t\t\t\t\t\n\t\tdf = float(div);\t\t\t\t\t\t\t\t\t\n\t\ttd *= df;\t\t\t\t\t\t\t\t\t\t\t\n\t\ttime = a_time - td;\t\t\t\t\t\t\t\t\t\n\t\talpha = a_life - (time * 10.0 * a_age);\t\t\t\t\n\t}\t\t\t\t\t\t\t\t\t\t\t\t\t\t\n\tgl_PointSize = a_size;\t\t\t\t\t\t\t\t\t\n \tif(gl_PointSize < 0.0)gl_PointSize = 0.0;\t\t\t\t\n\tgl_Position = a_Position;\t\t\t\t\t\t\t\t\n\tgl_Position += (time * a_move * 0.3);\t\t\t\t\t\n\tgl_Position = u_MVPMatrix * gl_Position;\t\t\t\t\n}"), GLHelper.getCompiledShader(35632, "precision mediump float;\t\t\t\t\t\t\t\t\t\nuniform sampler2D u_texture;\t\t\t\t\t\t\t\t\nuniform sampler2D s_AlphaTexture;\t\t\t\t\t\t\t\nvarying float alpha;\t\t\t\t\t\t\t\t\t\t\nvoid main(){\t\t\t\t\t\t\t\t\t\t\t\t\n\tvec4 tex = texture2D(u_texture, gl_PointCoord);\t\t\t\n\tvec4 alphaTexture;\t\t\t\t\t\t\t\t\t\t\n\talphaTexture = texture2D(s_AlphaTexture, gl_PointCoord);\n\tgl_FragColor = tex;\t\t\t\t\t\t\t\t\t\t\n\tgl_FragColor.w = alphaTexture.r * alpha * 0.5;\t\t\t\t\t\t\t\n}"));
            this.mProgramHandle = createdAndLinkedProgram;
            this.mTextureID = GLHelper.getTexture(getContext(), R.drawable.particle_mip_0);
            this.mAlphaTextureId = GLHelper.getTexture(getContext(), R.drawable.particle_mip_0_alpha);
            this.mAlphaTextureHandle = GLES20.glGetUniformLocation(this.mProgramHandle, "s_AlphaTexture");
            this.mMVPMatrixHandle = GLES20.glGetUniformLocation(this.mProgramHandle, "u_MVPMatrix");
            this.mPositionHandle = GLES20.glGetAttribLocation(this.mProgramHandle, "a_Position");
            this.mTextureHandle = GLES20.glGetUniformLocation(this.mProgramHandle, "u_texture");
            this.mMoveHandle = GLES20.glGetAttribLocation(this.mProgramHandle, "a_move");
            this.mTimesHandle = GLES20.glGetUniformLocation(this.mProgramHandle, "a_time");
            this.mLifeHandle = GLES20.glGetAttribLocation(this.mProgramHandle, "a_life");
            this.mAgeHandle = GLES20.glGetAttribLocation(this.mProgramHandle, "a_age");
            this.mSizeHandle = GLES20.glGetAttribLocation(this.mProgramHandle, "a_size");
            this.mSpeedHandle = GLES20.glGetAttribLocation(this.mProgramHandle, "a_speed");
            this.mEmitterHandle = GLES20.glGetAttribLocation(this.mProgramHandle, "a_EmitterPosition");
            super.initShader();
        }
    }

    @Override // com.reandroid.wallpaper.deepsea.GLBaseView
    public void setForDrawing() {
        GLES20.glUseProgram(this.mProgramHandle);
        GLES20.glActiveTexture(33984);
        GLES20.glBindTexture(3553, this.mTextureID);
        GLES20.glUniform1i(this.mTextureHandle, 0);
        GLES20.glActiveTexture(33985);
        GLES20.glBindTexture(3553, this.mAlphaTextureId);
        GLES20.glUniform1i(this.mAlphaTextureHandle, 1);
        GLES20.glEnable(3553);
        updateTimeCounter();
        this.mVertexBuffer.position(0);
        GLES20.glVertexAttribPointer(this.mPositionHandle, 3, 5126, false, 52, (Buffer) this.mVertexBuffer);
        GLES20.glEnableVertexAttribArray(this.mPositionHandle);
        this.mVertexBuffer.position(3);
        GLES20.glVertexAttribPointer(this.mMoveHandle, 3, 5126, false, 52, (Buffer) this.mVertexBuffer);
        GLES20.glEnableVertexAttribArray(this.mMoveHandle);
        this.mVertexBuffer.position(6);
        GLES20.glVertexAttribPointer(this.mLifeHandle, 1, 5126, false, 52, (Buffer) this.mVertexBuffer);
        GLES20.glEnableVertexAttribArray(this.mLifeHandle);
        this.mVertexBuffer.position(7);
        GLES20.glVertexAttribPointer(this.mAgeHandle, 1, 5126, false, 52, (Buffer) this.mVertexBuffer);
        GLES20.glEnableVertexAttribArray(this.mAgeHandle);
        this.mVertexBuffer.position(8);
        GLES20.glVertexAttribPointer(this.mSizeHandle, 1, 5126, false, 52, (Buffer) this.mVertexBuffer);
        GLES20.glEnableVertexAttribArray(this.mSizeHandle);
        this.mVertexBuffer.position(9);
        GLES20.glVertexAttribPointer(this.mSpeedHandle, 1, 5126, false, 52, (Buffer) this.mVertexBuffer);
        GLES20.glEnableVertexAttribArray(this.mSpeedHandle);
        this.mVertexBuffer.position(10);
        GLES20.glVertexAttribPointer(this.mEmitterHandle, 3, 5126, false, 52, (Buffer) this.mVertexBuffer);
        GLES20.glEnableVertexAttribArray(this.mEmitterHandle);
        GLES20.glUniform1f(this.mTimesHandle, this.mTimeCounter);
    }

    @Override // com.reandroid.wallpaper.deepsea.GLBaseView
    public void draw() {
        GLES20.glDrawArrays(0, 0, 90);
    }

    @Override // com.reandroid.wallpaper.deepsea.GLBaseView
    public void update(float[] fArr) {
        GLES20.glUniformMatrix4fv(this.mMVPMatrixHandle, 1, false, fArr, 0);
    }

    private void setParticleData(float f, float f2, float f3) {
        float f4 = 0.0f;
        for (int i = 0; i < 90; i++) {
            f4 += 0.011111111f;
            float nextFloat = (this.mGenerater.nextFloat() - 0.5f) * 3.0f;
            float nextFloat2 = (this.mGenerater.nextFloat() - 0.5f) * 5.0f;
            if (nextFloat < 1.0f && nextFloat > -1.0f && nextFloat2 < 1.5f && nextFloat2 > -1.5f) {
                if (nextFloat <= 0.0f && nextFloat >= -1.0f) {
                    nextFloat -= 1.0f;
                } else if (nextFloat <= 1.0f && nextFloat >= 0.0f) {
                    nextFloat += 1.0f;
                }
            }
            float[] fArr = this.mBackupVertices;
            this.mParticleVertices[(i * 13) + 0] = nextFloat;
            fArr[(i * 13) + 0] = nextFloat;
            float[] fArr2 = this.mBackupVertices;
            this.mParticleVertices[(i * 13) + 1] = nextFloat2;
            fArr2[(i * 13) + 1] = nextFloat2;
            float[] fArr3 = this.mBackupVertices;
            float[] fArr4 = this.mParticleVertices;
            float nextFloat3 = this.mGenerater.nextFloat() - 0.5f;
            fArr4[(i * 13) + 2] = nextFloat3;
            fArr3[(i * 13) + 2] = nextFloat3;
            float[] fArr5 = this.mBackupVertices;
            float[] fArr6 = this.mParticleVertices;
            float nextFloat4 = (this.mGenerater.nextFloat() - 0.5f) * 3.0f;
            fArr6[(i * 13) + 3] = nextFloat4;
            fArr5[(i * 13) + 3] = nextFloat4;
            float[] fArr7 = this.mBackupVertices;
            float[] fArr8 = this.mParticleVertices;
            float nextFloat5 = (this.mGenerater.nextFloat() - 0.5f) * 5.0f;
            fArr8[(i * 13) + 4] = nextFloat5;
            fArr7[(i * 13) + 4] = nextFloat5;
            float[] fArr9 = this.mBackupVertices;
            float[] fArr10 = this.mParticleVertices;
            float nextFloat6 = this.mGenerater.nextFloat() - 0.5f;
            fArr10[(i * 13) + 5] = nextFloat6;
            fArr9[(i * 13) + 5] = nextFloat6;
            float[] fArr11 = this.mBackupVertices;
            float[] fArr12 = this.mParticleVertices;
            float randomFloat = MathHelper.getRandomFloat(0.5f, 1.0f);
            fArr12[(i * 13) + 6] = randomFloat;
            fArr11[(i * 13) + 6] = randomFloat;
            float[] fArr13 = this.mBackupVertices;
            float[] fArr14 = this.mParticleVertices;
            float randomFloat2 = MathHelper.getRandomFloat(0.01f, 0.1f);
            fArr14[(i * 13) + 7] = randomFloat2;
            fArr13[(i * 13) + 7] = randomFloat2;
            float[] fArr15 = this.mBackupVertices;
            float[] fArr16 = this.mParticleVertices;
            float randomFloat3 = MathHelper.getRandomFloat(10.0f, 20.0f);
            fArr16[(i * 13) + 8] = randomFloat3;
            fArr15[(i * 13) + 8] = randomFloat3;
            float[] fArr17 = this.mBackupVertices;
            float[] fArr18 = this.mParticleVertices;
            float randomFloat4 = MathHelper.getRandomFloat(20.0f, 40.0f);
            fArr18[(i * 13) + 9] = randomFloat4;
            fArr17[(i * 13) + 9] = randomFloat4;
            float[] fArr19 = this.mBackupVertices;
            float[] fArr20 = this.mParticleVertices;
            float nextFloat7 = this.mGenerater.nextFloat();
            fArr20[(i * 13) + 10] = nextFloat7;
            fArr19[(i * 13) + 10] = nextFloat7;
            float[] fArr21 = this.mBackupVertices;
            float[] fArr22 = this.mParticleVertices;
            float nextFloat8 = this.mGenerater.nextFloat();
            fArr22[(i * 13) + 11] = nextFloat8;
            fArr21[(i * 13) + 11] = nextFloat8;
            float[] fArr23 = this.mBackupVertices;
            float[] fArr24 = this.mParticleVertices;
            float nextFloat9 = this.mGenerater.nextFloat() - 0.5f;
            fArr24[(i * 13) + 12] = nextFloat9;
            fArr23[(i * 13) + 12] = nextFloat9;
        }
        this.mVertexBuffer.put(this.mParticleVertices).position(0);
    }

    private void updateTimeCounter() {
        if (this.mIsSlow) {
            this.mTimeCounter = (float) (this.mTimeCounter + 0.003d);
        } else {
            this.mTimeCounter = (float) (this.mTimeCounter + 0.01d);
        }
    }

    public void setIsSlow(boolean z) {
        this.mIsSlow = z;
    }

    public boolean isSlow() {
        return this.mIsSlow;
    }

    public void changeData(int i, int i2) {
        if (i > 720) {
            changeDataByWidth(i / 720.0f);
        } else {
            changeDataByWidth(1.0f);
        }
        if (i2 > 1184) {
            changeDataByHeight(i2 / 1184.0f);
        } else {
            changeDataByHeight(1.0f);
        }
    }

    private void changeDataByWidth(float f) {
        for (int i = 0; i < 90; i++) {
            if (f == 1.0f) {
                this.mParticleVertices[(i * 13) + 0] = this.mBackupVertices[(i * 13) + 0];
                this.mParticleVertices[(i * 13) + 3] = this.mBackupVertices[(i * 13) + 3];
            } else {
                float nextFloat = (this.mGenerater.nextFloat() - 0.5f) * f * 3.0f;
                this.mParticleVertices[(i * 13) + 0] = nextFloat;
                this.mParticleVertices[(i * 13) + 3] = nextFloat;
            }
        }
    }

    private void changeDataByHeight(float f) {
        for (int i = 0; i < 90; i++) {
            if (f == 1.0f) {
                this.mParticleVertices[(i * 13) + 1] = this.mBackupVertices[(i * 13) + 1];
                this.mParticleVertices[(i * 13) + 4] = this.mBackupVertices[(i * 13) + 4];
            } else {
                float[] fArr = this.mParticleVertices;
                float[] fArr2 = this.mBackupVertices;
                int i2 = (i * 13) + 1;
                float f2 = fArr2[i2] * f;
                fArr2[i2] = f2;
                fArr[(i * 13) + 1] = f2;
                float[] fArr3 = this.mParticleVertices;
                float[] fArr4 = this.mBackupVertices;
                int i3 = (i * 13) + 4;
                float f3 = fArr4[i3] * f;
                fArr4[i3] = f3;
                fArr3[(i * 13) + 4] = f3;
            }
        }
    }

    public void resetData() {
        this.mVertexBuffer.put(this.mParticleVertices).position(0);
    }

    @Override // com.reandroid.wallpaper.deepsea.GLBaseView
    public void remove() {
        GLHelper.deleteTextures(this.mTextureID);
        GLHelper.deleteTextures(this.mAlphaTextureId);
        super.remove();
    }
}

class SeaWaterDrops2 extends GLBaseView {
    private final int DISPLAY_HEIGHT;
    private final int DISPLAY_WIDTH;
    private int mAgeHandle;
    private int mAlphaTextureHandle;
    private int mAlphaTextureId;
    private float[] mBackupVertices;
    private Random mGenerater;
    private boolean mIsSlow;
    private int mLifeHandle;
    private int mMVPMatrixHandle;
    private int mMoveHandle;
    private float[] mParticleVertices;
    private int mPositionHandle;
    private int mProgramHandle;
    private int mSizeHandle;
    private int mSpeedHandle;
    private int mTextureHandle;
    private int mTextureID;
    private float mTimeCounter;
    private int mTimesHandle;
    private FloatBuffer mVertexBuffer;

    public SeaWaterDrops2() {
        this.DISPLAY_WIDTH = 720;
        this.DISPLAY_HEIGHT = 1184;
        this.mTimeCounter = 0.0f;
        this.mParticleVertices = new float[60];
        this.mBackupVertices = new float[60];
        this.mGenerater = new Random(System.currentTimeMillis());
        this.mIsSlow = false;
    }

    public SeaWaterDrops2(Context context) {
        super(context);
        this.DISPLAY_WIDTH = 720;
        this.DISPLAY_HEIGHT = 1184;
        this.mTimeCounter = 0.0f;
        this.mParticleVertices = new float[60];
        this.mBackupVertices = new float[60];
        this.mGenerater = new Random(System.currentTimeMillis());
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
            int createdAndLinkedProgram = GLHelper.getCreatedAndLinkedProgram(GLHelper.getCompiledShader(35633, "precision mediump float;\t\t\t\t\t\t\t\t\t\nuniform mat4 u_MVPMatrix;\t\t\t\t\t\t\t\t\t\nattribute vec4 a_Position;\t\t\t\t\t\t\t\t\t\nattribute vec4 a_move;\t\t\t\t\t\t\t\t\t\t\nuniform float a_time;\t\t\t\t\t\t\t\t\t\t\nattribute float a_life;\t\t\t\t\t\t\t\t\t\nattribute float a_age;\t\t\t\t\t\t\t\t\t\t\nattribute float a_size;\t\t\t\t\t\t\t\t\t\nattribute float a_angle;\t\t\t\t\t\t\t\t\t\nattribute float a_speed;\t\t\t\t\t\t\t\t\t\nvarying float alpha;\t\t\t\t\t\t\t\t\t\t\nfloat time;\t\t\t\t\t\t\t\t\t\t\t\t\nvoid main(){\t\t\t\t\t\t\t\t\t\t\t\t\n\talpha = a_life - (a_time * 10.0 * a_age);\t\t\t\t\n\ttime = a_time;\t\t\t\t\t\t\t\t\t\t\t\n\tif(alpha < 0.0){\t\t\t\t\t\t\t\t\t\t\n\t\tfloat td = a_life/a_age;\t\t\t\t\t\t\t\n\t\ttd /= 10.0;\t\t\t\t\t\t\t\t\t\t\t\n\t\tfloat df = a_time/td;\t\t\t\t\t\t\t\t\n\t\tint div = int(df);\t\t\t\t\t\t\t\t\t\n\t\tdf = float(div);\t\t\t\t\t\t\t\t\t\n\t\ttd *= df;\t\t\t\t\t\t\t\t\t\t\t\n\t\ttime = a_time - td;\t\t\t\t\t\t\t\t\t\n\t\talpha = a_life - (time * 10.0 * a_age);\t\t\t\t\n\t}\t\t\t\t\t\t\t\t\t\t\t\t\t\t\n\tgl_PointSize = a_size;\t\t\t\t\t\t\t\t\t\n \tif(gl_PointSize < 0.0)gl_PointSize = 0.0;\t\t\t\t\n\tgl_Position = a_Position;\t\t\t\t\t\t\t\t\n\tgl_Position += (time * a_move * 0.5);\t\t\t\t\t\n\tfloat angle = time * 6.0;\t\t\t\t\t\t\t\t\n\tfloat r = 0.2;\t\t\t\t\t\t\t\t\t\t\t\n\tfloat moveX = gl_Position.x + r * cos(angle);\t\t\t\n\tgl_Position.x += moveX;\t\t\t\t\t\t\t\t\t\n\tgl_Position = u_MVPMatrix * gl_Position;\t\t\t\t\n}"), GLHelper.getCompiledShader(35632, "precision mediump float;\t\t\t\t\t\t\t\t\t\nuniform sampler2D u_texture;\t\t\t\t\t\t\t\t\nuniform sampler2D s_AlphaTexture;\t\t\t\t\t\t\t\nvarying float alpha;\t\t\t\t\t\t\t\t\t\t\nvoid main(){\t\t\t\t\t\t\t\t\t\t\t\t\n\tvec4 tex = texture2D(u_texture, gl_PointCoord);\t\t\t\n\tvec4 alphaTexture;\t\t\t\t\t\t\t\t\t\t\n\talphaTexture = texture2D(s_AlphaTexture, gl_PointCoord);\n\tgl_FragColor = tex;\t\t\t\t\t\t\t\t\t\t\n\tgl_FragColor.w = alphaTexture.r * alpha * 0.5;\t\t\t\t\t\t\t\n}"));
            this.mProgramHandle = createdAndLinkedProgram;
            this.mTextureID = GLHelper.getTexture(getContext(), R.drawable.particle_mip_0);
            this.mAlphaTextureId = GLHelper.getTexture(getContext(), R.drawable.particle_mip_0_alpha);
            this.mAlphaTextureHandle = GLES20.glGetUniformLocation(this.mProgramHandle, "s_AlphaTexture");
            this.mMVPMatrixHandle = GLES20.glGetUniformLocation(this.mProgramHandle, "u_MVPMatrix");
            this.mPositionHandle = GLES20.glGetAttribLocation(this.mProgramHandle, "a_Position");
            this.mTextureHandle = GLES20.glGetUniformLocation(this.mProgramHandle, "u_texture");
            this.mMoveHandle = GLES20.glGetAttribLocation(this.mProgramHandle, "a_move");
            this.mTimesHandle = GLES20.glGetUniformLocation(this.mProgramHandle, "a_time");
            this.mLifeHandle = GLES20.glGetAttribLocation(this.mProgramHandle, "a_life");
            this.mAgeHandle = GLES20.glGetAttribLocation(this.mProgramHandle, "a_age");
            this.mSizeHandle = GLES20.glGetAttribLocation(this.mProgramHandle, "a_size");
            this.mSpeedHandle = GLES20.glGetAttribLocation(this.mProgramHandle, "a_speed");
            super.initShader();
        }
    }

    @Override // com.reandroid.wallpaper.deepsea.GLBaseView
    public void setForDrawing() {
        GLES20.glUseProgram(this.mProgramHandle);
        GLES20.glActiveTexture(33984);
        GLES20.glBindTexture(3553, this.mTextureID);
        GLES20.glUniform1i(this.mTextureHandle, 0);
        GLES20.glActiveTexture(33985);
        GLES20.glBindTexture(3553, this.mAlphaTextureId);
        GLES20.glUniform1i(this.mAlphaTextureHandle, 1);
        GLES20.glEnable(3553);
        updateTimeCounter();
        this.mVertexBuffer.position(0);
        GLES20.glVertexAttribPointer(this.mPositionHandle, 3, 5126, false, 40, (Buffer) this.mVertexBuffer);
        GLES20.glEnableVertexAttribArray(this.mPositionHandle);
        this.mVertexBuffer.position(3);
        GLES20.glVertexAttribPointer(this.mMoveHandle, 3, 5126, false, 40, (Buffer) this.mVertexBuffer);
        GLES20.glEnableVertexAttribArray(this.mMoveHandle);
        this.mVertexBuffer.position(6);
        GLES20.glVertexAttribPointer(this.mLifeHandle, 1, 5126, false, 40, (Buffer) this.mVertexBuffer);
        GLES20.glEnableVertexAttribArray(this.mLifeHandle);
        this.mVertexBuffer.position(7);
        GLES20.glVertexAttribPointer(this.mAgeHandle, 1, 5126, false, 40, (Buffer) this.mVertexBuffer);
        GLES20.glEnableVertexAttribArray(this.mAgeHandle);
        this.mVertexBuffer.position(8);
        GLES20.glVertexAttribPointer(this.mSizeHandle, 1, 5126, false, 40, (Buffer) this.mVertexBuffer);
        GLES20.glEnableVertexAttribArray(this.mSizeHandle);
        this.mVertexBuffer.position(9);
        GLES20.glVertexAttribPointer(this.mSpeedHandle, 1, 5126, false, 40, (Buffer) this.mVertexBuffer);
        GLES20.glEnableVertexAttribArray(this.mSpeedHandle);
        GLES20.glUniform1f(this.mTimesHandle, this.mTimeCounter);
    }

    @Override // com.reandroid.wallpaper.deepsea.GLBaseView
    public void draw() {
        GLES20.glDrawArrays(0, 0, 6);
    }

    @Override // com.reandroid.wallpaper.deepsea.GLBaseView
    public void update(float[] fArr) {
        GLES20.glUniformMatrix4fv(this.mMVPMatrixHandle, 1, false, fArr, 0);
    }

    private void setParticleData(float f, float f2, float f3) {
        float f4 = 0.0f;
        for (int i = 0; i < 6; i++) {
            f4 += 0.16666667f;
            float nextFloat = this.mGenerater.nextFloat() - 0.5f;
            float nextFloat2 = this.mGenerater.nextFloat();
            float[] fArr = this.mBackupVertices;
            this.mParticleVertices[(i * 10) + 0] = nextFloat;
            fArr[(i * 10) + 0] = nextFloat;
            float[] fArr2 = this.mBackupVertices;
            float f5 = (nextFloat2 - 0.5f) - 1.0f;
            this.mParticleVertices[(i * 10) + 1] = f5;
            fArr2[(i * 10) + 1] = f5;
            float[] fArr3 = this.mBackupVertices;
            float[] fArr4 = this.mParticleVertices;
            float nextFloat3 = this.mGenerater.nextFloat() - 0.5f;
            fArr4[(i * 10) + 2] = nextFloat3;
            fArr3[(i * 10) + 2] = nextFloat3;
            float[] fArr5 = this.mBackupVertices;
            this.mParticleVertices[(i * 10) + 3] = nextFloat;
            fArr5[(i * 10) + 3] = nextFloat;
            float[] fArr6 = this.mBackupVertices;
            this.mParticleVertices[(i * 10) + 4] = 3.0f;
            fArr6[(i * 10) + 4] = 3.0f;
            float[] fArr7 = this.mBackupVertices;
            float[] fArr8 = this.mParticleVertices;
            float nextFloat4 = this.mGenerater.nextFloat() - 0.5f;
            fArr8[(i * 10) + 5] = nextFloat4;
            fArr7[(i * 10) + 5] = nextFloat4;
            float[] fArr9 = this.mBackupVertices;
            float[] fArr10 = this.mParticleVertices;
            float randomFloat = MathHelper.getRandomFloat(0.5f, 1.0f);
            fArr10[(i * 10) + 6] = randomFloat;
            fArr9[(i * 10) + 6] = randomFloat;
            float[] fArr11 = this.mBackupVertices;
            float[] fArr12 = this.mParticleVertices;
            float randomFloat2 = MathHelper.getRandomFloat(0.01f, 0.1f);
            fArr12[(i * 10) + 7] = randomFloat2;
            fArr11[(i * 10) + 7] = randomFloat2;
            float[] fArr13 = this.mBackupVertices;
            float[] fArr14 = this.mParticleVertices;
            float randomFloat3 = MathHelper.getRandomFloat(10.0f, 20.0f);
            fArr14[(i * 10) + 8] = randomFloat3;
            fArr13[(i * 10) + 8] = randomFloat3;
            float[] fArr15 = this.mBackupVertices;
            float[] fArr16 = this.mParticleVertices;
            float randomFloat4 = MathHelper.getRandomFloat(20.0f, 40.0f);
            fArr16[(i * 10) + 9] = randomFloat4;
            fArr15[(i * 10) + 9] = randomFloat4;
        }
        this.mVertexBuffer.put(this.mParticleVertices).position(0);
    }

    private void updateTimeCounter() {
        if (this.mIsSlow) {
            this.mTimeCounter = (float) (this.mTimeCounter + 0.003d);
        } else {
            this.mTimeCounter = (float) (this.mTimeCounter + 0.01d);
        }
    }

    public void setIsSlow(boolean z) {
        this.mIsSlow = z;
    }

    public boolean isSlow() {
        return this.mIsSlow;
    }

    public void changeData(int i, int i2) {
        if (i > 720) {
            changeDataByWidth(i / 720.0f);
        } else {
            changeDataByWidth(1.0f);
        }
        if (i2 > 1184) {
            changeDataByHeight(i2 / 1184.0f);
        } else {
            changeDataByHeight(1.0f);
        }
    }

    private void changeDataByWidth(float f) {
        for (int i = 0; i < 6; i++) {
            if (f == 1.0f) {
                this.mParticleVertices[(i * 10) + 0] = this.mBackupVertices[(i * 10) + 0];
                this.mParticleVertices[(i * 10) + 3] = this.mBackupVertices[(i * 10) + 3];
            } else {
                float nextFloat = (this.mGenerater.nextFloat() - 0.5f) * f;
                this.mParticleVertices[(i * 10) + 0] = nextFloat;
                this.mParticleVertices[(i * 10) + 3] = nextFloat;
            }
        }
    }

    private void changeDataByHeight(float f) {
        for (int i = 0; i < 6; i++) {
            if (f == 1.0f) {
                this.mParticleVertices[(i * 10) + 1] = this.mBackupVertices[(i * 10) + 1];
                this.mParticleVertices[(i * 10) + 4] = this.mBackupVertices[(i * 10) + 4];
            } else {
                this.mParticleVertices[(i * 10) + 1] = this.mBackupVertices[(i * 10) + 1] * f;
                this.mParticleVertices[(i * 10) + 4] = this.mBackupVertices[(i * 10) + 4] * f;
            }
        }
    }

    public void resetData() {
        this.mVertexBuffer.put(this.mParticleVertices).position(0);
    }

    @Override // com.reandroid.wallpaper.deepsea.GLBaseView
    public void remove() {
        GLHelper.deleteTextures(this.mTextureID);
        GLHelper.deleteTextures(this.mAlphaTextureId);
        super.remove();
    }
}

class SeaWaterDrops3 extends GLBaseView {
    private final int DISPLAY_HEIGHT;
    private final int DISPLAY_WIDTH;
    private int mAgeHandle;
    private int mAlphaTextureHandle;
    private int mAlphaTextureId;
    private float[] mBackupVertices;
    private Random mGenerater;
    private boolean mIsSlow;
    private int mLifeHandle;
    private int mMVPMatrixHandle;
    private int mMoveHandle;
    private float[] mParticleVertices;
    private int mPositionHandle;
    private int mProgramHandle;
    private int mSizeHandle;
    private int mSpeedHandle;
    private int mTextureHandle;
    private int mTextureID;
    private float mTimeCounter;
    private int mTimesHandle;
    private FloatBuffer mVertexBuffer;

    public SeaWaterDrops3() {
        this.DISPLAY_WIDTH = 720;
        this.DISPLAY_HEIGHT = 1184;
        this.mTimeCounter = 0.0f;
        this.mParticleVertices = new float[1200];
        this.mBackupVertices = new float[1200];
        this.mGenerater = new Random(System.currentTimeMillis());
        this.mIsSlow = false;
    }

    public SeaWaterDrops3(Context context) {
        super(context);
        this.DISPLAY_WIDTH = 720;
        this.DISPLAY_HEIGHT = 1184;
        this.mTimeCounter = 0.0f;
        this.mParticleVertices = new float[1200];
        this.mBackupVertices = new float[1200];
        this.mGenerater = new Random(System.currentTimeMillis());
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
            int createdAndLinkedProgram = GLHelper.getCreatedAndLinkedProgram(GLHelper.getCompiledShader(35633, "precision mediump float;\t\t\t\t\t\t\t\t\t\nuniform mat4 u_MVPMatrix;\t\t\t\t\t\t\t\t\t\nattribute vec4 a_Position;\t\t\t\t\t\t\t\t\t\nattribute vec4 a_move;\t\t\t\t\t\t\t\t\t\t\nuniform float a_time;\t\t\t\t\t\t\t\t\t\t\nattribute float a_life;\t\t\t\t\t\t\t\t\t\nattribute float a_age;\t\t\t\t\t\t\t\t\t\t\nattribute float a_size;\t\t\t\t\t\t\t\t\t\nattribute float a_angle;\t\t\t\t\t\t\t\t\t\nattribute float a_speed;\t\t\t\t\t\t\t\t\t\nvarying float alpha;\t\t\t\t\t\t\t\t\t\t\nfloat time;\t\t\t\t\t\t\t\t\t\t\t\t\nvoid main(){\t\t\t\t\t\t\t\t\t\t\t\t\n\tfloat temp;\t\t\t\t\t\t\t\t\t\t\t\t\n\talpha = a_life - (a_time * 10.0 * a_age);\t\t\t\t\n\ttemp = alpha;\t\t\t\t\t\t\t\t\t\t\t\n\ttime = a_time;\t\t\t\t\t\t\t\t\t\t\t\n\talpha = 0.0;\t\t\t\t\t\t\t\t\t\t\t\n\tif(temp < 0.0){\t\t\t\t\t\t\t\t\t\t\t\n\t\tfloat td = a_life/a_age;\t\t\t\t\t\t\t\n\t\ttd /= 10.0;\t\t\t\t\t\t\t\t\t\t\t\n\t\tfloat df = a_time/td;\t\t\t\t\t\t\t\t\n\t\tint div = int(df);\t\t\t\t\t\t\t\t\t\n\t\tdf = float(div);\t\t\t\t\t\t\t\t\t\n\t\ttd *= df;\t\t\t\t\t\t\t\t\t\t\t\n\t\ttime = a_time - td;\t\t\t\t\t\t\t\t\t\n\t\talpha = a_life - (time * 10.0 * a_age);\t\t\t\t\n\t\tif(div > 1)alpha=0.0;\t\t\t\t\t\t\t\t\n\t}\t\t\t\t\t\t\t\t\t\t\t\t\t\t\n\tgl_PointSize = a_size;\t\t\t\t\t\t\t\t\t\n \tif(gl_PointSize < 0.0)gl_PointSize = 0.0;\t\t\t\t\n\tgl_Position = a_Position;\t\t\t\t\t\t\t\t\n\tgl_Position += (time * a_move * 1.2);\t\t\t\t\t\n\tfloat angle = time * 6.0;\t\t\t\t\t\t\t\t\n\tfloat r = 0.5 * a_life;\t\t\t\t\t\t\t\t\t\t\n\tfloat moveX = gl_Position.x + r * cos(angle);\t\t\t\n\tgl_Position.x += moveX;\t\t\t\t\t\t\t\t\t\n\tgl_Position = u_MVPMatrix * gl_Position;\t\t\t\t\n}"), GLHelper.getCompiledShader(35632, "precision mediump float;\t\t\t\t\t\t\t\t\t\nuniform sampler2D u_texture;\t\t\t\t\t\t\t\t\nuniform sampler2D s_AlphaTexture;\t\t\t\t\t\t\t\nvarying float alpha;\t\t\t\t\t\t\t\t\t\t\nvoid main(){\t\t\t\t\t\t\t\t\t\t\t\t\n\tvec4 tex = texture2D(u_texture, gl_PointCoord);\t\t\t\n\tvec4 alphaTexture;\t\t\t\t\t\t\t\t\t\t\n\talphaTexture = texture2D(s_AlphaTexture, gl_PointCoord);\n\tgl_FragColor = tex;\t\t\t\t\t\t\t\t\t\t\n\tgl_FragColor.w = alphaTexture.r * alpha;\n}"));
            this.mProgramHandle = createdAndLinkedProgram;
            this.mTextureID = GLHelper.getTexture(getContext(), R.drawable.particle_mip_0);
            this.mAlphaTextureId = GLHelper.getTexture(getContext(), R.drawable.particle_mip_0_alpha);
            this.mAlphaTextureHandle = GLES20.glGetUniformLocation(this.mProgramHandle, "s_AlphaTexture");
            this.mMVPMatrixHandle = GLES20.glGetUniformLocation(this.mProgramHandle, "u_MVPMatrix");
            this.mPositionHandle = GLES20.glGetAttribLocation(this.mProgramHandle, "a_Position");
            this.mTextureHandle = GLES20.glGetUniformLocation(this.mProgramHandle, "u_texture");
            this.mMoveHandle = GLES20.glGetAttribLocation(this.mProgramHandle, "a_move");
            this.mTimesHandle = GLES20.glGetUniformLocation(this.mProgramHandle, "a_time");
            this.mLifeHandle = GLES20.glGetAttribLocation(this.mProgramHandle, "a_life");
            this.mAgeHandle = GLES20.glGetAttribLocation(this.mProgramHandle, "a_age");
            this.mSizeHandle = GLES20.glGetAttribLocation(this.mProgramHandle, "a_size");
            this.mSpeedHandle = GLES20.glGetAttribLocation(this.mProgramHandle, "a_speed");
            super.initShader();
        }
    }

    @Override // com.reandroid.wallpaper.deepsea.GLBaseView
    public void setForDrawing() {
        GLES20.glUseProgram(this.mProgramHandle);
        GLES20.glActiveTexture(33984);
        GLES20.glBindTexture(3553, this.mTextureID);
        GLES20.glUniform1i(this.mTextureHandle, 0);
        GLES20.glActiveTexture(33985);
        GLES20.glBindTexture(3553, this.mAlphaTextureId);
        GLES20.glUniform1i(this.mAlphaTextureHandle, 1);
        GLES20.glEnable(3553);
        updateTimeCounter();
        this.mVertexBuffer.position(0);
        GLES20.glVertexAttribPointer(this.mPositionHandle, 3, 5126, false, 40, (Buffer) this.mVertexBuffer);
        GLES20.glEnableVertexAttribArray(this.mPositionHandle);
        this.mVertexBuffer.position(3);
        GLES20.glVertexAttribPointer(this.mMoveHandle, 3, 5126, false, 40, (Buffer) this.mVertexBuffer);
        GLES20.glEnableVertexAttribArray(this.mMoveHandle);
        this.mVertexBuffer.position(6);
        GLES20.glVertexAttribPointer(this.mLifeHandle, 1, 5126, false, 40, (Buffer) this.mVertexBuffer);
        GLES20.glEnableVertexAttribArray(this.mLifeHandle);
        this.mVertexBuffer.position(7);
        GLES20.glVertexAttribPointer(this.mAgeHandle, 1, 5126, false, 40, (Buffer) this.mVertexBuffer);
        GLES20.glEnableVertexAttribArray(this.mAgeHandle);
        this.mVertexBuffer.position(8);
        GLES20.glVertexAttribPointer(this.mSizeHandle, 1, 5126, false, 40, (Buffer) this.mVertexBuffer);
        GLES20.glEnableVertexAttribArray(this.mSizeHandle);
        this.mVertexBuffer.position(9);
        GLES20.glVertexAttribPointer(this.mSpeedHandle, 1, 5126, false, 40, (Buffer) this.mVertexBuffer);
        GLES20.glEnableVertexAttribArray(this.mSpeedHandle);
        GLES20.glUniform1f(this.mTimesHandle, this.mTimeCounter);
    }

    @Override // com.reandroid.wallpaper.deepsea.GLBaseView
    public void draw() {
        GLES20.glDrawArrays(0, 0, 120);
    }

    @Override // com.reandroid.wallpaper.deepsea.GLBaseView
    public void update(float[] fArr) {
        GLES20.glUniformMatrix4fv(this.mMVPMatrixHandle, 1, false, fArr, 0);
    }

    private void setParticleData(float f, float f2, float f3) {
        float f4 = 0.0f;
        for (int i = 0; i < 120; i++) {
            f4 += 0.008333334f;
            float nextFloat = this.mGenerater.nextFloat() - 0.5f;
            float[] fArr = this.mBackupVertices;
            this.mParticleVertices[(i * 10) + 0] = nextFloat;
            fArr[(i * 10) + 0] = nextFloat;
            float[] fArr2 = this.mBackupVertices;
            this.mParticleVertices[(i * 10) + 1] = -1.0f;
            fArr2[(i * 10) + 1] = -1.0f;
            float[] fArr3 = this.mBackupVertices;
            float[] fArr4 = this.mParticleVertices;
            float nextFloat2 = this.mGenerater.nextFloat() - 0.5f;
            fArr4[(i * 10) + 2] = nextFloat2;
            fArr3[(i * 10) + 2] = nextFloat2;
            float[] fArr5 = this.mBackupVertices;
            this.mParticleVertices[(i * 10) + 3] = nextFloat;
            fArr5[(i * 10) + 3] = nextFloat;
            float[] fArr6 = this.mBackupVertices;
            this.mParticleVertices[(i * 10) + 4] = 3.0f;
            fArr6[(i * 10) + 4] = 3.0f;
            float[] fArr7 = this.mBackupVertices;
            float[] fArr8 = this.mParticleVertices;
            float nextFloat3 = this.mGenerater.nextFloat() - 0.5f;
            fArr8[(i * 10) + 5] = nextFloat3;
            fArr7[(i * 10) + 5] = nextFloat3;
            float[] fArr9 = this.mBackupVertices;
            float[] fArr10 = this.mParticleVertices;
            float randomFloat = MathHelper.getRandomFloat(0.04f, 0.4f);
            fArr10[(i * 10) + 6] = randomFloat;
            fArr9[(i * 10) + 6] = randomFloat;
            float[] fArr11 = this.mBackupVertices;
            float[] fArr12 = this.mParticleVertices;
            float randomFloat2 = MathHelper.getRandomFloat(0.02f, 0.03f);
            fArr12[(i * 10) + 7] = randomFloat2;
            fArr11[(i * 10) + 7] = randomFloat2;
            float[] fArr13 = this.mBackupVertices;
            float[] fArr14 = this.mParticleVertices;
            float randomFloat3 = MathHelper.getRandomFloat(10.0f, 24.0f);
            fArr14[(i * 10) + 8] = randomFloat3;
            fArr13[(i * 10) + 8] = randomFloat3;
            float[] fArr15 = this.mBackupVertices;
            float[] fArr16 = this.mParticleVertices;
            float randomFloat4 = MathHelper.getRandomFloat(20.0f, 40.0f);
            fArr16[(i * 10) + 9] = randomFloat4;
            fArr15[(i * 10) + 9] = randomFloat4;
        }
        this.mVertexBuffer.put(this.mParticleVertices).position(0);
    }

    private void updateTimeCounter() {
        if (this.mIsSlow) {
            this.mTimeCounter = (float) (this.mTimeCounter + 0.05d);
        } else {
            this.mTimeCounter = (float) (this.mTimeCounter + 0.05d);
        }
    }

    public void initTimeCounter() {
        this.mTimeCounter = 0.0f;
    }

    public void setIsSlow(boolean z) {
        this.mIsSlow = z;
    }

    public boolean isSlow() {
        return this.mIsSlow;
    }

    public void changeData(int i, int i2) {
        if (i > 720) {
            changeDataByWidth(i / 720.0f);
        } else {
            changeDataByWidth(1.0f);
        }
        if (i2 > 1184) {
            changeDataByHeight(i2 / 1184.0f);
        } else {
            changeDataByHeight(1.0f);
        }
    }

    private void changeDataByWidth(float f) {
        for (int i = 0; i < 120; i++) {
            if (f == 1.0f) {
                this.mParticleVertices[(i * 10) + 0] = this.mBackupVertices[(i * 10) + 0];
                this.mParticleVertices[(i * 10) + 3] = this.mBackupVertices[(i * 10) + 3];
            } else {
                float nextFloat = (this.mGenerater.nextFloat() - 0.5f) * f;
                this.mParticleVertices[(i * 10) + 0] = nextFloat;
                this.mParticleVertices[(i * 10) + 3] = nextFloat;
            }
        }
    }

    private void changeDataByHeight(float f) {
        for (int i = 0; i < 120; i++) {
            if (f == 1.0f) {
                this.mParticleVertices[(i * 10) + 1] = this.mBackupVertices[(i * 10) + 1];
                this.mParticleVertices[(i * 10) + 4] = this.mBackupVertices[(i * 10) + 4];
            } else {
                this.mParticleVertices[(i * 10) + 1] = this.mBackupVertices[(i * 10) + 1] * f;
                this.mParticleVertices[(i * 10) + 4] = this.mBackupVertices[(i * 10) + 4] * f;
            }
        }
    }

    public void resetData() {
        this.mVertexBuffer.put(this.mParticleVertices).position(0);
    }

    @Override // com.reandroid.wallpaper.deepsea.GLBaseView
    public void remove() {
        GLHelper.deleteTextures(this.mTextureID);
        GLHelper.deleteTextures(this.mAlphaTextureId);
        super.remove();
    }
}




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
            int createdAndLinkedProgram = GLHelper.getCreatedAndLinkedProgram(GLHelper.getCompiledShader(35633, "uniform mat4 u_MVPMatrix;\t\t\t\t\t\t\t\nattribute vec4 a_position;\t\t\t\t\t\t\t\nattribute vec2 a_texCoord;\t\t\t\t\t\t\t\nvarying vec2 v_texCoord;\t\t\t\t\t\t\t\nvoid main(){\t\t\t\t\t\t\t\t\t\t\n\tgl_Position = u_MVPMatrix * a_position;\t\t\t\n\tv_texCoord = a_texCoord;\t\t\t\t\t\t\n}"), GLHelper.getCompiledShader(35632, "precision mediump float;\t\t\t\t\t\t\t\nvarying vec2 v_texCoord;\t\t\t\t\t\t\t\nuniform float u_brightness;\t\t\t\t\t\t\nuniform sampler2D s_texture;\t\t\t\t\t\t\nvoid main(){\t\t\t\t\t\t\t\t\t\t\n\tfloat val = u_brightness;\t\t\t\t\t\t\n \tvec4 color = vec4(val, val, val, 0.0);\t\t\t\n\tgl_FragColor = texture2D(s_texture, v_texCoord) + color; \n}\t\t\t\t\t\t\t\t\t\t\t\t\t\n"));
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
        GLES20.glVertexAttribPointer(this.mPositionHandle, 3, 5126, false, 20, (Buffer) this.mVertices);
        this.mVertices.position(3);
        GLES20.glVertexAttribPointer(this.mTexCoordHandle, 2, 5126, false, 20, (Buffer) this.mVertices);
        GLES20.glEnableVertexAttribArray(this.mPositionHandle);
        GLES20.glEnableVertexAttribArray(this.mTexCoordHandle);
        GLES20.glActiveTexture(33984);
        GLES20.glBindTexture(3553, this.mTextureId);
        GLES20.glEnable(3553);
        GLES20.glUniform1i(this.mTextureHandle, 0);
        GLES20.glUniform1f(this.mBrightnessHandle, this.mBrightness);
    }

    @Override // com.reandroid.wallpaper.deepsea.GLBaseView
    public void draw() {
        GLES20.glDrawElements(4, 6, 5123, this.mIndices);
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

    public void changeDisplay(int i, int i2) {
        float f = (((float) i) / ((float) i2)) * 10.0f;
        float f2 = (0.4f * f) + f;
        float[] fArr = this.mVerticesData;
        fArr[0] = -f2;
        fArr[5] = -f2;
        fArr[10] = f2;
        fArr[15] = f2;
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

class JellyfishWaterDrops2 extends GLBaseView {
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

    public JellyfishWaterDrops2() {
        this.mNumberOfParticles = 25;
        this.mTimeCounter = 0.0f;
        this.mParticleVertices = new float[25 * 13];
        this.mGenerater = new Random(System.currentTimeMillis());
        this.mAddAlpha = 0.0f;
        this.mAddColorData = new float[]{0.0f, 0.0f, 0.0f, 0.0f};
        this.mScale = 1.0f;
        this.mIsSlow = false;
    }

    public JellyfishWaterDrops2(Context context) {
        super(context);
        this.mNumberOfParticles = 25;
        this.mTimeCounter = 0.0f;
        this.mParticleVertices = new float[25 * 13];
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
            this.mProgramHandle = GLHelper.getCreatedAndLinkedProgram(GLHelper.getCompiledShader(35633, "precision mediump float;\t\t\t\t\t\t\t\t\t\nuniform mat4 u_MVPMatrix;\t\t\t\t\t\t\t\t\t\nattribute vec4 a_Position;\t\t\t\t\t\t\t\t\t\nattribute vec4 a_EmitterPosition;\t\t\t\t\t\t\t\nattribute vec4 a_move;\t\t\t\t\t\t\t\t\t\t\nuniform float a_time;\t\t\t\t\t\t\t\t\t\t\nattribute float a_Scale;\t\t\t\t\t\t\t\t\t\nattribute float a_life;\t\t\t\t\t\t\t\t\t\nattribute float a_age;\t\t\t\t\t\t\t\t\t\t\nattribute float a_size;\t\t\t\t\t\t\t\t\t\nattribute float a_angle;\t\t\t\t\t\t\t\t\t\nattribute float a_speed;\t\t\t\t\t\t\t\t\t\nattribute vec4 a_AddColor;\t\t\t\t\t\t\t\t\t\nvarying float alpha;\t\t\t\t\t\t\t\t\t\t\nvarying vec4 v_AddColor;\t\t\t\t\t\t\t\t\t\nfloat time;\t\t\t\t\t\t\t\t\t\t\t\t\nvoid main(){\t\t\t\t\t\t\t\t\t\t\t\t\n\t\tfloat td = a_life/a_age;\t\t\t\t\t\t\t\n\t\ttd /= 10.0;\t\t\t\t\t\t\t\t\t\t\t\n\t\tfloat df = a_time/td;\t\t\t\t\t\t\t\t\n\t\tint div = int(df);\t\t\t\t\t\t\t\t\t\n\t\tdf = float(div);\t\t\t\t\t\t\t\t\t\n\t\ttd *= df;\t\t\t\t\t\t\t\t\t\t\t\n\t\ttime = a_time - td;\t\t\t\t\t\t\t\t\t\n\tfloat tempAlpha = (time * 20.0 * a_age);\t\t\t\t\n\tif(tempAlpha >= a_life){\t\t\t\t\t\t\t\t\n\t\talpha = a_life * 2.0 - tempAlpha;\t\t\t\t\t\n\t}else{\t\t\t\t\t\t\t\t\t\t\t\t\t\n\t\talpha = tempAlpha;\t\t\t\t\t\t\t\t\t\n\t}\t\t\t\t\t\t\t\t\t\t\t\t\t\t\n\tgl_Position = a_Position;\t\t\t\t\t\t\t\t\n\tvec4 move = a_move;\t\t\t\t\t\t\t\t\t\t\n\tmove.x *= a_Scale;\t\t\t\t\t\t\t\t\t\t\n\tmove.y *= a_Scale;\t\t\t\t\t\t\t\t\t\t\n\tgl_Position += (time * move * 0.3 * 0.3);\t\t\t\t\n\tgl_Position = u_MVPMatrix * gl_Position;\t\t\t\t\n\tgl_PointSize = (a_size - gl_Position.z) * a_Scale;\t\t\n \tif(gl_PointSize < 0.0)gl_PointSize = 0.0;\t\t\t\t\n\tvec4 ePosition = u_MVPMatrix * a_EmitterPosition;\t\t\n\tfloat speed = time * 0.5 * 0.4;\t\t\t\t\t\t\t\n\tgl_Position.x += (ePosition.x - gl_Position.x) * speed; \n\tgl_Position.y += (ePosition.y - gl_Position.y) * speed; \n\tv_AddColor = a_AddColor;\t\t\t\t\t\t\t\t\n}"), GLHelper.getCompiledShader(35632, "precision mediump float;\t\t\t\t\t\t\t\t\t\nuniform sampler2D u_texture;\t\t\t\t\t\t\t\t\nuniform sampler2D s_AlphaTexture;\t\t\t\t\t\t\t\nuniform float u_AddAlpha;\t\t\t\t\t\t\t\t\t\nvarying float alpha;\t\t\t\t\t\t\t\t\t\t\nvarying vec4 v_AddColor;\t\t\t\t\t\t\t\t\t\nvoid main(){\t\t\t\t\t\t\t\t\t\t\t\t\n\tvec4 tex = texture2D(u_texture, gl_PointCoord);\t\t\t\n\tvec4 alphaTexture;\t\t\t\t\t\t\t\t\t\t\n\talphaTexture = texture2D(s_AlphaTexture, gl_PointCoord);\n\tgl_FragColor = tex + v_AddColor;\t\t\t\t\t\t\n\tgl_FragColor.w = (alphaTexture.r * alpha + u_AddAlpha) * 0.25;\t\t\t\n}"));
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
        GLES20.glActiveTexture(33984);
        GLES20.glBindTexture(3553, this.mTextureID);
        GLES20.glUniform1i(this.mTextureHandle, 0);
        GLES20.glActiveTexture(33985);
        GLES20.glBindTexture(3553, this.mAlphaTextureId);
        GLES20.glUniform1i(this.mAlphaTextureHandle, 1);
        GLES20.glEnable(3553);
        updateTimeCounter();
        int i = this.mAddColorHandle;
        float[] fArr = this.mAddColorData;
        GLES20.glVertexAttrib4f(i, fArr[0], fArr[1], fArr[2], fArr[3]);
        GLES20.glDisableVertexAttribArray(this.mAddColorHandle);
        GLES20.glVertexAttrib1f(this.mScaleHandle, this.mScale);
        GLES20.glDisableVertexAttribArray(this.mScaleHandle);
        this.mVertexBuffer.position(0);
        GLES20.glVertexAttribPointer(this.mPositionHandle, 3, 5126, false, 52, (Buffer) this.mVertexBuffer);
        GLES20.glEnableVertexAttribArray(this.mPositionHandle);
        this.mVertexBuffer.position(3);
        GLES20.glVertexAttribPointer(this.mMoveHandle, 3, 5126, false, 52, (Buffer) this.mVertexBuffer);
        GLES20.glEnableVertexAttribArray(this.mMoveHandle);
        this.mVertexBuffer.position(6);
        GLES20.glVertexAttribPointer(this.mLifeHandle, 1, 5126, false, 52, (Buffer) this.mVertexBuffer);
        GLES20.glEnableVertexAttribArray(this.mLifeHandle);
        this.mVertexBuffer.position(7);
        GLES20.glVertexAttribPointer(this.mAgeHandle, 1, 5126, false, 52, (Buffer) this.mVertexBuffer);
        GLES20.glEnableVertexAttribArray(this.mAgeHandle);
        this.mVertexBuffer.position(8);
        GLES20.glVertexAttribPointer(this.mSizeHandle, 1, 5126, false, 52, (Buffer) this.mVertexBuffer);
        GLES20.glEnableVertexAttribArray(this.mSizeHandle);
        this.mVertexBuffer.position(9);
        GLES20.glVertexAttribPointer(this.mSpeedHandle, 1, 5126, false, 52, (Buffer) this.mVertexBuffer);
        GLES20.glEnableVertexAttribArray(this.mSpeedHandle);
        this.mVertexBuffer.position(10);
        GLES20.glVertexAttribPointer(this.mEmitterHandle, 3, 5126, false, 52, (Buffer) this.mVertexBuffer);
        GLES20.glEnableVertexAttribArray(this.mEmitterHandle);
        GLES20.glUniform1f(this.mTimesHandle, this.mTimeCounter);
        GLES20.glUniform1f(this.mAddAlphaHandle, this.mAddAlpha);
    }

    @Override // com.reandroid.wallpaper.deepsea.GLBaseView
    public void draw() {
        GLES20.glDrawArrays(0, 0, this.mNumberOfParticles);
    }

    @Override // com.reandroid.wallpaper.deepsea.GLBaseView
    public void update(float[] fArr) {
        GLES20.glUniformMatrix4fv(this.mMVPMatrixHandle, 1, false, fArr, 0);
    }

    private void setParticleData(float f, float f2, float f3) {
        float f4 = 0.1f / this.mNumberOfParticles;
        float f5 = 2.5f;
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
            this.mParticleVertices[(i * 13) + 7] = MathHelper.getRandomFloat(0.01f, 0.06f);
            this.mParticleVertices[(i * 13) + 8] = MathHelper.getRandomFloat(20.0f, 47.5f);
            this.mParticleVertices[(i * 13) + 9] = MathHelper.getRandomFloat(20.0f, 40.0f);
            float[] fArr2 = this.mParticleVertices;
            fArr2[(i * 13) + 10] = 0.2f;
            fArr2[(i * 13) + 11] = -0.2f;
            fArr2[(i * 13) + 12] = this.mGenerater.nextFloat() - 0.5f;
        }
        this.mVertexBuffer.put(this.mParticleVertices).position(0);
    }

    private void updateTimeCounter() {
        if (this.mIsSlow) {
            double d = this.mTimeCounter;
            Double.isNaN(d);
            this.mTimeCounter = (float) (d + 0.002d);
            return;
        }
        double d2 = this.mTimeCounter;
        Double.isNaN(d2);
        this.mTimeCounter = (float) (d2 + 0.004d);
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

    @Override 
    public void remove() {
        GLHelper.deleteTextures(this.mTextureID);
        GLHelper.deleteTextures(this.mAlphaTextureId);
        super.remove();
    }
}


