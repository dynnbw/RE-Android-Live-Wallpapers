package com.reandroid.wallpaper.deepsea;

import android.content.Context;
import android.opengl.GLES20;
import com.reandroid.gles.AssetLoader;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Random;

/**
 * Extracted from DeepSeaGL inner class.
 */
class SeaWaterDrops2 {
    private Context mContext;
    private boolean mIsInitShader;
    Context getContext() { return mContext; }
    boolean isInitShader() { return mIsInitShader; }
        private static final int REF_WIDTH = 720;
        private static final int REF_HEIGHT = 1184;
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
        this.DISPLAY_WIDTH = REF_WIDTH;
        this.DISPLAY_HEIGHT = REF_HEIGHT;
        this.mTimeCounter = 0.0f;
        this.mParticleVertices = new float[60];
        this.mBackupVertices = new float[60];
        this.mGenerater = new Random(System.currentTimeMillis());
        this.mIsSlow = false;
    }

    public SeaWaterDrops2(Context context) {
        this.mContext = context;
        this.DISPLAY_WIDTH = REF_WIDTH;
        this.DISPLAY_HEIGHT = REF_HEIGHT;
        this.mTimeCounter = 0.0f;
        this.mParticleVertices = new float[60];
        this.mBackupVertices = new float[60];
        this.mGenerater = new Random(System.currentTimeMillis());
        this.mIsSlow = false;
    }

    public void initByteBuffer() {
        this.mVertexBuffer = ByteBuffer.allocateDirect(this.mParticleVertices.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        setParticleData(0.0f, 0.0f, 0.0f);
    }

    public void initShader() {
        if (!isInitShader()) {
            int createdAndLinkedProgram = GLHelper.getCreatedAndLinkedProgram(GLHelper.getCompiledShader(GLES20.GL_VERTEX_SHADER, AssetLoader.readText(getContext(), "deepsea/shaders/GLES/deepsea_seawaterdrops2_0_vs.glsl")), GLHelper.getCompiledShader(GLES20.GL_FRAGMENT_SHADER, AssetLoader.readText(getContext(), "deepsea/shaders/GLES/deepsea_seawaterdrops2_1_fs.glsl")));
            this.mProgramHandle = createdAndLinkedProgram;
            this.mTextureID = GLHelper.getTextureFromAsset(getContext(), "deepsea/drawable/particle_mip_0.png");
            this.mAlphaTextureId = GLHelper.getTextureFromAsset(getContext(), "deepsea/drawable/particle_mip_0_alpha.png");
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
            this.mIsInitShader = true;
        }
    }

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
        this.mVertexBuffer.position(0);
        GLES20.glVertexAttribPointer(this.mPositionHandle, 3, GLES20.GL_FLOAT, false, 40, (Buffer) this.mVertexBuffer);
        GLES20.glEnableVertexAttribArray(this.mPositionHandle);
        this.mVertexBuffer.position(3);
        GLES20.glVertexAttribPointer(this.mMoveHandle, 3, GLES20.GL_FLOAT, false, 40, (Buffer) this.mVertexBuffer);
        GLES20.glEnableVertexAttribArray(this.mMoveHandle);
        this.mVertexBuffer.position(6);
        GLES20.glVertexAttribPointer(this.mLifeHandle, 1, GLES20.GL_FLOAT, false, 40, (Buffer) this.mVertexBuffer);
        GLES20.glEnableVertexAttribArray(this.mLifeHandle);
        this.mVertexBuffer.position(7);
        GLES20.glVertexAttribPointer(this.mAgeHandle, 1, GLES20.GL_FLOAT, false, 40, (Buffer) this.mVertexBuffer);
        GLES20.glEnableVertexAttribArray(this.mAgeHandle);
        this.mVertexBuffer.position(8);
        GLES20.glVertexAttribPointer(this.mSizeHandle, 1, GLES20.GL_FLOAT, false, 40, (Buffer) this.mVertexBuffer);
        GLES20.glEnableVertexAttribArray(this.mSizeHandle);
        this.mVertexBuffer.position(9);
        GLES20.glVertexAttribPointer(this.mSpeedHandle, 1, GLES20.GL_FLOAT, false, 40, (Buffer) this.mVertexBuffer);
        GLES20.glEnableVertexAttribArray(this.mSpeedHandle);
        GLES20.glUniform1f(this.mTimesHandle, this.mTimeCounter);
    }

    public void draw() {
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, 6);
    }

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
        if (i > REF_WIDTH) {
            changeDataByWidth(i / (float) REF_WIDTH);
        } else {
            changeDataByWidth(1.0f);
        }
        if (i2 > REF_HEIGHT) {
            changeDataByHeight(i2 / (float) REF_HEIGHT);
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

    public void remove() {
        GLHelper.deleteTextures(this.mTextureID);
        GLHelper.deleteTextures(this.mAlphaTextureId);
        this.mContext = null;
    }
}
