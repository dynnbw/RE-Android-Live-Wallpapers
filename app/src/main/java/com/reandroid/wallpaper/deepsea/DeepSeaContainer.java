package com.reandroid.wallpaper.deepsea;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.SystemClock;
import android.view.Display;
import android.view.WindowManager;
import com.reandroid.utils.AssetLoader;
import com.reandroid.wallpaper.deepsea.GLHelper;
import com.reandroid.wallpaper.deepsea.IntervalManager;
import com.reandroid.wallpaper.deepsea.IntervalVO;
import com.reandroid.wallpaper.deepsea.Jellyfish;
import com.reandroid.wallpaper.deepsea.JellyfishVO;
import com.reandroid.wallpaper.deepsea.Sea2;
import com.reandroid.wallpaper.deepsea.SeaWaterDrops;
import com.reandroid.wallpaper.deepsea.SeaWaterDrops2;
import com.reandroid.wallpaper.deepsea.SeaWaterDrops3;
import com.reandroid.wallpaper.deepsea.Sunshine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;

class Blur extends Jellyfish {
    public Blur() {
    }

    public Blur(Context context) {
        super(context);
    }

    @Override // com.reandroid.wallpaper.deepsea.Jellyfish
    protected int getTextureId() {
        return GLHelper.getTextureFromAsset(getContext(), "deepsea/drawable/unit_a_core_glow_s256x256_eeee_mip_0.png");
    }

    @Override // com.reandroid.wallpaper.deepsea.Jellyfish
    protected int getAlphaTextureId() {
        return GLHelper.getTextureFromAsset(getContext(), "deepsea/drawable/unit_a_core_glow_s256x256_eeee_mip_0_alpha.png");
    }

    @Override // com.reandroid.wallpaper.deepsea.Jellyfish
    public void remove() {
    }
}

class BlurEffect extends Jellyfish {
    public BlurEffect() {
    }

    public BlurEffect(Context context) {
        super(context);
    }

    @Override // com.reandroid.wallpaper.deepsea.Jellyfish
    protected int getTextureId() {
        return GLHelper.getTextureFromAsset(getContext(), "deepsea/drawable/unit_a_blured_12_s256x256_mip_0.png");
    }

    @Override // com.reandroid.wallpaper.deepsea.Jellyfish
    protected int getAlphaTextureId() {
        return GLHelper.getTextureFromAsset(getContext(), "deepsea/drawable/unit_a_blured_12_s256x256_mip_0_alpha.png");
    }
}

class BlurEffect2 extends BlurEffect {
    public BlurEffect2() {
    }

    public BlurEffect2(Context context) {
        super(context);
    }

    @Override // com.reandroid.wallpaper.deepsea.BlurEffect, com.reandroid.wallpaper.deepsea.Jellyfish
    protected int getTextureId() {
        return GLHelper.getTextureFromAsset(getContext(), "deepsea/drawable/unit_b_blured_12_s256x256_mip_0.png");
    }

    @Override // com.reandroid.wallpaper.deepsea.BlurEffect, com.reandroid.wallpaper.deepsea.Jellyfish
    protected int getAlphaTextureId() {
        return GLHelper.getTextureFromAsset(getContext(), "deepsea/drawable/unit_b_blured_12_s256x256_mip_0_alpha.png");
    }
}

class DeepSeaContainer {
    private Context mContext;
    private boolean mIsInitShader;

    Context getContext() { return mContext; }
    boolean isInitShader() { return mIsInitShader; }

    final int BATTERY_STATE_HIGHT;
    final int BATTERY_STATE_LOW;
    final int BATTERY_STATE_WARRING;
    float mAlphaOfSunshine;
    float mAlphaOfSunshine1;
    float mAlphaOfSunshine2;
    int mAppearingIndex;
    String mBackgroundImagePath;
    int mBatteryState;
    int mBatteryTime;
    Blur mBlur;
    float mBlurAlpha;
    float[] mBlurColor;
    BlurEffect mBlurEffect;
    BlurEffect2 mBlurEffect2;
    float mBrightness;
    Bitmap mCheckBitmap;
    private final float mCycleOfChaingDestination;
    private final float mCycleOfStartingRandomZMotion;
    float mDefaultScale;
    float mDestinationAlphaOfSunshine;
    float mDestinationAlphaOfSunshine1;
    float mDestinationAlphaOfSunshine2;
    float mForce;
    int mHeight;
    private final float mIntervalAlphaOfSunshine;
    private final float mIntervalAlphaOfSunshine1;
    IntervalManager mIntervalManager;
    boolean mIsAfterShaking;
    boolean mIsAllFinishedAppearing;
    boolean mIsAllFinishedRemoving;
    boolean mIsAppearingRemoved;
    boolean mIsBackgroundChanged;
    boolean mIsBluring;
    boolean mIsRemoving;
    boolean mIsSendedReceiver;
    boolean mIsShaking;
    Jellyfish mJellyfish;
    Jellyfish mJellyfish2;
    JellyfishVO mJellyfishVO;
    float mLight;
    float[] mMVPMatrix;
    private final float mMaxRotation;
    private final float mMaxSpeed;
    private final float mMinRotation;
    private final float mMinSpeed;
    float[] mModelMatrix;
    int mNumberOfParticles;
    int mNumberOfParticles2;
    int mNumberOfRemoving;
    float[] mProjectionMatrix;
    float mScale;
    Sea mSea;
    Sea2 mSea2;
    SeaWaterDrops mSeaWaterDrops;
    SeaWaterDrops2 mSeaWaterDrops2;
    SeaWaterDrops3 mSeaWaterDrops3;
    float mStartShakingTime;
    Sunshine mSunshine;
    Sunshine mSunshine2;
    Sunshine mSunshine3;
    int mTempBackImageCount;
    float mTime;
    int mUnitNumberVal;
    int mUnitScaleVal;
    float[] mViewMatrix;
    JellyfishWaterDrops mWaterDropsInJellyfish;
    JellyfishWaterDrops mWaterDropsInJellyfish2;
    int mWidth;

    public DeepSeaContainer() {
        this.BATTERY_STATE_HIGHT = 0;
        this.BATTERY_STATE_LOW = 1;
        this.BATTERY_STATE_WARRING = 2;
        this.mBatteryState = 0;
        this.mModelMatrix = new float[16];
        this.mViewMatrix = new float[16];
        this.mProjectionMatrix = new float[16];
        this.mMVPMatrix = new float[16];
        this.mTime = 0.0f;
        this.mMinSpeed = 0.004f;
        this.mMaxSpeed = 0.007f;
        this.mCycleOfChaingDestination = 800.0f;
        this.mCycleOfStartingRandomZMotion = 150.0f;
        this.mMaxRotation = 360.0f;
        this.mMinRotation = -360.0f;
        this.mAlphaOfSunshine = 1.0f;
        this.mAlphaOfSunshine1 = 1.0f;
        this.mAlphaOfSunshine2 = 0.0f;
        this.mIntervalAlphaOfSunshine = 0.2f;
        this.mIntervalAlphaOfSunshine1 = 0.0f;
        this.mDestinationAlphaOfSunshine = 0.2f;
        this.mDestinationAlphaOfSunshine1 = 0.0f;
        this.mDestinationAlphaOfSunshine2 = 1.0f;
        this.mBlurAlpha = 0.0f;
        this.mIsBluring = true;
        this.mLight = 0.0f;
        this.mBrightness = 0.0f;
        this.mScale = 1.0f;
        this.mDefaultScale = 1.0f;
        this.mUnitScaleVal = 0;
        this.mNumberOfParticles = 120;
        this.mNumberOfParticles2 = 50;
        this.mBlurColor = new float[]{0.0f, 0.0f, 0.0f};
        this.mIsBackgroundChanged = false;
        this.mIsShaking = false;
        this.mIsAfterShaking = false;
        this.mForce = 0.0f;
        this.mStartShakingTime = 0.0f;
        this.mIsAllFinishedAppearing = false;
        this.mAppearingIndex = 0;
        this.mIsAllFinishedRemoving = false;
        this.mNumberOfRemoving = 0;
        this.mIsRemoving = false;
        this.mIsAppearingRemoved = false;
        this.mUnitNumberVal = 0;
        this.mIsSendedReceiver = false;
        this.mBatteryTime = 0;
        this.mCheckBitmap = null;
        this.mTempBackImageCount = 0;
    }

    public DeepSeaContainer(Context context) {
        this.mContext = context;
        this.BATTERY_STATE_HIGHT = 0;
        this.BATTERY_STATE_LOW = 1;
        this.BATTERY_STATE_WARRING = 2;
        this.mBatteryState = 0;
        this.mModelMatrix = new float[16];
        this.mViewMatrix = new float[16];
        this.mProjectionMatrix = new float[16];
        this.mMVPMatrix = new float[16];
        this.mTime = 0.0f;
        this.mMinSpeed = 0.004f;
        this.mMaxSpeed = 0.007f;
        this.mCycleOfChaingDestination = 800.0f;
        this.mCycleOfStartingRandomZMotion = 150.0f;
        this.mMaxRotation = 360.0f;
        this.mMinRotation = -360.0f;
        this.mAlphaOfSunshine = 1.0f;
        this.mAlphaOfSunshine1 = 1.0f;
        this.mAlphaOfSunshine2 = 0.0f;
        this.mIntervalAlphaOfSunshine = 0.2f;
        this.mIntervalAlphaOfSunshine1 = 0.0f;
        this.mDestinationAlphaOfSunshine = 0.2f;
        this.mDestinationAlphaOfSunshine1 = 0.0f;
        this.mDestinationAlphaOfSunshine2 = 1.0f;
        this.mBlurAlpha = 0.0f;
        this.mIsBluring = true;
        this.mLight = 0.0f;
        this.mBrightness = 0.0f;
        this.mScale = 1.0f;
        this.mDefaultScale = 1.0f;
        this.mUnitScaleVal = 0;
        this.mNumberOfParticles = 120;
        this.mNumberOfParticles2 = 50;
        this.mBlurColor = new float[]{0.0f, 0.0f, 0.0f};
        this.mIsBackgroundChanged = false;
        this.mIsShaking = false;
        this.mIsAfterShaking = false;
        this.mForce = 0.0f;
        this.mStartShakingTime = 0.0f;
        this.mIsAllFinishedAppearing = false;
        this.mAppearingIndex = 0;
        this.mIsAllFinishedRemoving = false;
        this.mNumberOfRemoving = 0;
        this.mIsRemoving = false;
        this.mIsAppearingRemoved = false;
        this.mUnitNumberVal = 0;
        this.mIsSendedReceiver = false;
        this.mBatteryTime = 0;
        this.mCheckBitmap = null;
        this.mTempBackImageCount = 0;
        this.mJellyfish = new Jellyfish(context);
        this.mJellyfish2 = new Jellyfish(context, "deepsea/drawable/unit_b_s256x256_e_mip_0.png", "deepsea/drawable/unit_b_s256x256_e_mip_0_alpha.png");
        this.mBlur = new Blur(context);
        this.mSea = new Sea(context);
        this.mSea2 = new Sea2(context);
        this.mWaterDropsInJellyfish = new JellyfishWaterDrops(context);
        this.mWaterDropsInJellyfish2 = new JellyfishWaterDrops(context, 25, "deepsea/shaders/GLES/deepsea_jellyfishwaterdrops2_0_vs.glsl", "deepsea/shaders/GLES/deepsea_jellyfishwaterdrops2_1_fs.glsl");
        this.mIntervalManager = new IntervalManager();
        this.mJellyfishVO = new JellyfishVO();
        this.mSunshine = new Sunshine(context);
        this.mSunshine2 = new Sunshine(context, "deepsea/drawable/light_animation_0_s512x512_opt.png");
        this.mSunshine3 = new Sunshine(context, "deepsea/drawable/light_animation_1_s512x512_opt.png");
        this.mSeaWaterDrops = new SeaWaterDrops(context);
        this.mSeaWaterDrops2 = new SeaWaterDrops2(context);
        this.mSeaWaterDrops3 = new SeaWaterDrops3(context);
        this.mBlurEffect = new BlurEffect(context);
        this.mBlurEffect2 = new BlurEffect2(context);
    }

    
    public void remove() {
        this.mJellyfish.remove();
        this.mJellyfish2.remove();
        this.mBlur.remove();
        this.mSea.remove();
        this.mSea2.remove();
        this.mWaterDropsInJellyfish.remove();
        this.mWaterDropsInJellyfish2.remove();
        this.mSunshine.remove();
        this.mSunshine2.remove();
        this.mSunshine3.remove();
        this.mSeaWaterDrops.remove();
        this.mSeaWaterDrops2.remove();
        this.mSeaWaterDrops3.remove();
        this.mBlurEffect.remove();
        this.mBlurEffect2.remove();
        this.mJellyfish = null;
        this.mJellyfish2 = null;
        this.mBlur = null;
        this.mSea = null;
        this.mSea2 = null;
        this.mWaterDropsInJellyfish = null;
        this.mWaterDropsInJellyfish2 = null;
        this.mSunshine = null;
        this.mSunshine2 = null;
        this.mSunshine3 = null;
        this.mSeaWaterDrops = null;
        this.mSeaWaterDrops2 = null;
        this.mSeaWaterDrops3 = null;
        this.mBlurEffect = null;
        this.mBlurEffect2 = null;
        this.mContext = null;
    }

    public void screenOn() {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.5f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        setNumberOfUnit(this.mUnitNumberVal);
    }

    public void screenOff() {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.5f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
    }

    void setNumberOfUnit(int unitCount) {
        int maxNumberOfJellyfish = this.mJellyfishVO.getMaxNumberOfJellyfish();
        this.mBlurAlpha = 0.0f;
        this.mIsBluring = true;
        this.mTime = 0.0f;
        this.mBatteryTime = 0;
        this.mIsSendedReceiver = false;
        this.mIntervalManager.initList();
        this.mIntervalManager.setNotOccupiedList();
        this.mJellyfishVO.setNumberOfJellyfish(maxNumberOfJellyfish + unitCount);
        this.mJellyfishVO.initList();
        this.mWaterDropsInJellyfish.initTimeCounter();
        this.mWaterDropsInJellyfish2.initTimeCounter();
        initListVO();
        if (this.mIsAllFinishedRemoving) {
            setFinishRemoving();
        }
        checkBlur();
    }

    void setScale() {
        this.mScale = (this.mUnitScaleVal * 0.1f) + this.mDefaultScale;
    }

    void setBrightness(int brightnessValue) {
        if (brightnessValue < 0) {
            this.mBrightness = ((brightnessValue * 0.2f) / 5.0f) - 0.1f;
        } else if (brightnessValue == 0) {
            this.mBrightness = -0.1f;
        } else {
            this.mBrightness = ((brightnessValue * 0.3f) / 5.0f) - 0.1f;
        }
    }

    void setLight(int lightValue) {
        if (lightValue < 0) {
            this.mLight = ((lightValue * 0.52f) / 5.0f) + 0.22f;
        } else if (lightValue == 0) {
            this.mLight = 0.22f;
        } else {
            this.mLight = ((lightValue * 0.65f) / 5.0f) + 0.22f;
        }
    }

    void setBackgroundImageType(int imageType) {
        if (imageType == 0) {
            this.mSea2.removeBackgroundImage();
            DeepSeaSettings.deleteBitmap(this.mBackgroundImagePath);
            this.mIsBackgroundChanged = true;
            this.mTempBackImageCount = 0;
        } else if (imageType == 1) {
            this.mIsBackgroundChanged = true;
            this.mTempBackImageCount = 0;
        }
    }

    private void changeBackground() {
        if (this.mCheckBitmap == null) {
            this.mSea2.setBackgroundImage(DeepSeaSettings.loadBitmap(this.mBackgroundImagePath));
        } else {
            this.mSea2.setBackgroundImage(this.mCheckBitmap);
        }
        this.mSea2.changeTextureToBackground();
    }

    
    public void initByteBuffer() {
        this.mJellyfish.initByteBuffer();
        this.mJellyfish2.initByteBuffer();
        this.mBlur.initByteBuffer();
        this.mWaterDropsInJellyfish.initByteBuffer();
        this.mWaterDropsInJellyfish2.initByteBuffer();
        this.mBlurEffect.initByteBuffer();
        this.mBlurEffect2.initByteBuffer();
    }

    
    public void initShader() {
        if (!isInitShader()) {
            this.mJellyfish.initShader();
            this.mJellyfish2.initShader();
            this.mBlur.initShader();
            this.mWaterDropsInJellyfish.initShader();
            this.mWaterDropsInJellyfish2.initShader();
            this.mBlurEffect.initShader();
            this.mBlurEffect2.initShader();
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.5f);
            this.mIsInitShader = true;
        }
    }

    
    public void draw() {
        if (this.mTempBackImageCount == 0 && this.mIsBackgroundChanged) {
            this.mTempBackImageCount++;
            return;
        }
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        GLES20.glEnable(3042);
        GLES20.glBlendFunc(770, 1);
        if (this.mIsBackgroundChanged) {
            changeBackground();
            this.mIsBackgroundChanged = false;
            this.mTempBackImageCount++;
        }
        if (!this.mSea2.hasBackgroundImage()) {
            Matrix.setIdentityM(this.mModelMatrix, 0);
            this.mSea.setForDrawing();
            this.mSea.setBrightness(this.mBrightness);
            multiplyMVPMatrix();
            this.mSea.update(this.mMVPMatrix);
            this.mSea.draw();
            if (this.mBatteryState == 0) {
                this.mAlphaOfSunshine += 0.01f * (this.mDestinationAlphaOfSunshine - this.mAlphaOfSunshine);
                this.mAlphaOfSunshine1 += 0.02f * (this.mDestinationAlphaOfSunshine1 - this.mAlphaOfSunshine1);
                this.mAlphaOfSunshine2 += 0.012f * (this.mDestinationAlphaOfSunshine2 - this.mAlphaOfSunshine2);
            } else {
                this.mAlphaOfSunshine += 0.001f * (this.mDestinationAlphaOfSunshine - this.mAlphaOfSunshine);
                this.mAlphaOfSunshine1 += 0.002f * (this.mDestinationAlphaOfSunshine1 - this.mAlphaOfSunshine1);
                this.mAlphaOfSunshine2 += 0.0012f * (this.mDestinationAlphaOfSunshine2 - this.mAlphaOfSunshine2);
            }
            if (this.mDestinationAlphaOfSunshine == 0.2f) {
                if (this.mAlphaOfSunshine < 0.78f) {
                    this.mDestinationAlphaOfSunshine = 1.0f;
                }
            } else if (this.mAlphaOfSunshine > 0.9f) {
                this.mDestinationAlphaOfSunshine = 0.2f;
            }
            if (this.mDestinationAlphaOfSunshine1 == 0.0f) {
                if (this.mAlphaOfSunshine1 < 0.4f) {
                    this.mDestinationAlphaOfSunshine1 = 1.0f;
                }
            } else if (this.mAlphaOfSunshine1 > 0.8f) {
                this.mDestinationAlphaOfSunshine1 = 0.0f;
            }
            if (this.mDestinationAlphaOfSunshine2 == 0.0f) {
                if (this.mAlphaOfSunshine2 < 0.4f) {
                    this.mDestinationAlphaOfSunshine2 = 1.0f;
                }
            } else if (this.mAlphaOfSunshine2 > 0.9f) {
                this.mDestinationAlphaOfSunshine2 = 0.0f;
            }
            this.mSunshine.setForDrawing();
            this.mSunshine.setAddAlpha(this.mAlphaOfSunshine - 1.0f);
            this.mSunshine.setLight(this.mLight);
            this.mSunshine.update(this.mMVPMatrix);
            this.mSunshine.draw();
            this.mSunshine2.setForDrawing();
            this.mSunshine2.setAddAlpha(this.mAlphaOfSunshine1 - 1.0f);
            this.mSunshine2.setLight(this.mLight);
            this.mSunshine2.update(this.mMVPMatrix);
            this.mSunshine2.draw();
            this.mSunshine3.setForDrawing();
            this.mSunshine3.setAddAlpha(this.mAlphaOfSunshine2 - 1.0f);
            this.mSunshine3.setLight(this.mLight);
            this.mSunshine3.update(this.mMVPMatrix);
            this.mSunshine3.draw();
        } else {
            Matrix.setIdentityM(this.mModelMatrix, 0);
            this.mSea2.setForDrawing();
            multiplyMVPMatrix();
            this.mSea2.update(this.mMVPMatrix);
            this.mSea2.draw();
        }
        Matrix.setIdentityM(this.mModelMatrix, 0);
        Matrix.translateM(this.mModelMatrix, 0, 0.0f, 0.0f, -2.0f);
        multiplyMVPMatrix();
        this.mSeaWaterDrops.setForDrawing();
        this.mSeaWaterDrops.update(this.mMVPMatrix);
        this.mSeaWaterDrops.draw();
        this.mSeaWaterDrops2.setForDrawing();
        this.mSeaWaterDrops2.update(this.mMVPMatrix);
        this.mSeaWaterDrops2.draw();
        if (this.mIsAfterShaking) {
            this.mSeaWaterDrops3.setForDrawing();
            this.mSeaWaterDrops3.update(this.mMVPMatrix);
            this.mSeaWaterDrops3.draw();
        }
        if (this.mTime % 1.0f == 0.0f && !this.mIsAllFinishedAppearing) {
            checkAppearing();
        }
        if (!this.mIsShaking) {
            updateForGoingToMove();
        } else {
            updateForGoingToMoveWhenShaking(this.mForce);
        }
        this.mTime += 1.0f;
        if (this.mTime % 150.0f == 0.0f && !this.mIsShaking) {
            startZMove();
        }
        if (this.mIsBluring) {
            this.mBlurAlpha += 0.006f;
            if (this.mBlurAlpha >= 1.0f) {
                this.mBlurAlpha = 1.0f;
                this.mIsBluring = false;
            }
        } else {
            this.mBlurAlpha -= 0.006f;
            if (this.mIsRemoving) {
                this.mBlurAlpha -= 0.01f;
            }
            if (this.mBlurAlpha <= 0.0f) {
                this.mBlurAlpha = 0.0f;
                this.mIsBluring = true;
                checkBlur();
            }
        }
        drawForGoingToMove();
        changeDestination();
        if (this.mTime > 800.0f) {
            this.mTime = 0.0f;
        }
        if (this.mBatteryTime > 200 && this.mBatteryState == 2 && !this.mIsSendedReceiver) {
            Intent intent = new Intent();
            intent.setAction("deepsea.container.action.ALL_REMOVING");
            getContext().sendBroadcast(intent);
            this.mIsSendedReceiver = true;
        }
        this.mBatteryTime++;
    }

    
    public void update(float[] fArr) {
        this.mJellyfish.update(fArr);
        this.mJellyfish2.update(fArr);
        this.mBlur.update(fArr);
        this.mWaterDropsInJellyfish.update(fArr);
        this.mWaterDropsInJellyfish2.update(fArr);
        this.mSunshine.update(fArr);
        this.mSunshine2.update(fArr);
        this.mSunshine3.update(fArr);
        this.mSeaWaterDrops.update(fArr);
        this.mSeaWaterDrops2.update(fArr);
        this.mSeaWaterDrops3.update(fArr);
        this.mBlurEffect.update(fArr);
        this.mBlurEffect2.update(fArr);
    }

    public void setByShake(float f) {
        if (this.mIsAllFinishedAppearing) {
            this.mForce = f;
            this.mJellyfishVO.initMovingZ();
            this.mStartShakingTime = (float) SystemClock.uptimeMillis();
            this.mSeaWaterDrops3.initTimeCounter();
            int numberOfJellyfish = this.mJellyfishVO.getNumberOfJellyfish();
            for (int i = 0; i < numberOfJellyfish; i++) {
                JellyfishVO vOByIndex = this.mJellyfishVO.getVOByIndex(i);
                vOByIndex.setIsShaking(true);
                float positionX = vOByIndex.getPositionX();
                vOByIndex.setShakingX(positionX);
                float forceX = vOByIndex.getForceX();
                if (vOByIndex.getPositionY() > 2.0f) {
                    vOByIndex.setIsShakeUp(false);
                }
                if (vOByIndex.getPositionY() < -2.0f) {
                    vOByIndex.setIsShakeUp(true);
                }
                if (positionX < -2.0f) {
                    positionX = -2.0f;
                }
                if (positionX > 2.0f) {
                    positionX = 2.0f;
                }
                if (f > 0.0f) {
                    vOByIndex.setForceX(forceX + f);
                    vOByIndex.setForceY(f - (((-2.0f) - positionX) * 4.0f));
                } else {
                    vOByIndex.setForceX(forceX + f);
                    vOByIndex.setForceY(f - ((positionX + 2.0f) * 4.0f));
                }
                if (!this.mIsShaking) {
                    vOByIndex.setShakingO(0.0f);
                    vOByIndex.setShakingR(2.0f);
                    vOByIndex.setShakingAngle(0.0f);
                }
            }
            this.mIsShaking = true;
        }
    }

    public void setStateByBattery(int batteryState) {
        this.mBatteryTime = 0;
        this.mIsSendedReceiver = false;
        switch (batteryState) {
            case 0:
                this.mBatteryState = 0;
                if (this.mSeaWaterDrops.isSlow()) {
                    this.mSeaWaterDrops.setIsSlow(false);
                }
                if (this.mSeaWaterDrops2.isSlow()) {
                    this.mSeaWaterDrops2.setIsSlow(false);
                }
                if (this.mSeaWaterDrops3.isSlow()) {
                    this.mSeaWaterDrops3.setIsSlow(false);
                }
                if (this.mWaterDropsInJellyfish.isSlow()) {
                    this.mWaterDropsInJellyfish.setIsSlow(false);
                }
                if (this.mWaterDropsInJellyfish2.isSlow()) {
                    this.mWaterDropsInJellyfish2.setIsSlow(false);
                }
                if (this.mIsAllFinishedRemoving || this.mIsRemoving) {
                    setAppearingRemoved();
                    return;
                }
                return;
            case 1:
            case 2:
                this.mBatteryState = batteryState;
                if (!this.mSeaWaterDrops.isSlow()) {
                    this.mSeaWaterDrops.setIsSlow(true);
                }
                if (!this.mSeaWaterDrops2.isSlow()) {
                    this.mSeaWaterDrops2.setIsSlow(true);
                }
                if (!this.mSeaWaterDrops3.isSlow()) {
                    this.mSeaWaterDrops3.setIsSlow(true);
                }
                if (!this.mWaterDropsInJellyfish.isSlow()) {
                    this.mWaterDropsInJellyfish.setIsSlow(true);
                }
                if (!this.mWaterDropsInJellyfish2.isSlow()) {
                    this.mWaterDropsInJellyfish2.setIsSlow(true);
                }
                if (!this.mIsAllFinishedRemoving || this.mIsAppearingRemoved) {
                    setRemoving();
                    return;
                }
                return;
            default:
                return;
        }
    }

    private void setFinishRemoving() {
        int numberOfJellyfish = this.mJellyfishVO.getNumberOfJellyfish();
        int i = (int) (numberOfJellyfish * 0.2d);
        for (int i2 = 0; i2 < i; i2++) {
            JellyfishVO vOByIndex = this.mJellyfishVO.getVOByIndex((numberOfJellyfish - 1) - i2);
            vOByIndex.setReverseAlpha(1.0f);
            vOByIndex.setIsFinishedRemoving(true);
        }
    }

    private void setRemoving() {
        int numberOfJellyfish = this.mJellyfishVO.getNumberOfJellyfish();
        int i = (int) (numberOfJellyfish * 0.2d);
        this.mNumberOfRemoving = i;
        this.mIsAppearingRemoved = false;
        this.mIsRemoving = true;
        this.mIsBluring = false;
        for (int i2 = 0; i2 < i; i2++) {
            JellyfishVO vOByIndex = this.mJellyfishVO.getVOByIndex((numberOfJellyfish - 1) - i2);
            vOByIndex.setReverseAlpha(0.0f);
            vOByIndex.setIsRemoving(true);
            vOByIndex.setIsFinishedRemoving(false);
            vOByIndex.setIsFinishedAppearing(false);
        }
    }

    private void setAppearingRemoved() {
        int numberOfJellyfish = this.mJellyfishVO.getNumberOfJellyfish();
        this.mIsAppearingRemoved = true;
        this.mIsAllFinishedRemoving = false;
        for (int i = 0; i < numberOfJellyfish; i++) {
            JellyfishVO vOByIndex = this.mJellyfishVO.getVOByIndex(i);
            if (vOByIndex.isFinishedRemoving() || vOByIndex.isRemoving()) {
                vOByIndex.setIsAppearing(true);
                vOByIndex.setReverseAlpha(1.0f);
                vOByIndex.setIsFinishedAppearing(false);
                vOByIndex.setIsFinishedRemoving(false);
                vOByIndex.setIsRemoving(false);
            }
        }
    }

    public void setViewMatrix() {
        Matrix.setLookAtM(this.mViewMatrix, 0, 0.0f, 0.0f, -0.5f, 0.0f, 0.0f, -5.0f, 0.0f, 1.0f, 0.0f);
    }

    public void setProjectionMatrix(int width, int height) {
        Display defaultDisplay = ((WindowManager) getContext().getSystemService("window")).getDefaultDisplay();
        this.mWidth = width;
        this.mHeight = height;
        if (width <= 0 || height <= 0) {
            return;
        }
        this.mBatteryTime = 0;
        this.mIsSendedReceiver = false;
        GLES20.glViewport(GLES20.GL_POINTS, 0, width, height);
        DeepSeaSettings.setScale(width, height);
        float aspectRatio = (float) width / (float) height;
        float negativeAspectRatio = -aspectRatio;
        if (width > 720) {
            this.mDefaultScale = aspectRatio;
        } else {
            this.mDefaultScale = 1.0f;
        }
        setScale();
        PositionVO.changePositions(defaultDisplay.getWidth(), defaultDisplay.getHeight());
        this.mIntervalManager.changeMove(defaultDisplay.getWidth(), defaultDisplay.getHeight());
        this.mIntervalManager.initList();
        this.mIntervalManager.setNotOccupiedList();
        this.mSea.changeDisplay(width, height);
        this.mSea2.changeDisplay(width, height);
        this.mSunshine.changeDisplay(width, height);
        this.mSunshine2.changeDisplay(width, height);
        this.mSunshine3.changeDisplay(width, height);
        this.mSea.initByteBuffer();
        this.mSea2.initByteBuffer();
        this.mSunshine.initByteBuffer();
        this.mSunshine2.initByteBuffer();
        this.mSunshine3.initByteBuffer();
        this.mSea.initShader();
        this.mSea2.initShader();
        this.mSunshine.initShader();
        this.mSunshine2.initShader();
        this.mSunshine3.initShader();
        this.mSeaWaterDrops.initByteBuffer();
        this.mSeaWaterDrops2.initByteBuffer();
        this.mSeaWaterDrops3.initByteBuffer();
        this.mSeaWaterDrops.changeData(width, height);
        this.mSeaWaterDrops2.changeData(width, height);
        this.mSeaWaterDrops3.changeData(width, height);
        this.mSeaWaterDrops.resetData();
        this.mSeaWaterDrops2.resetData();
        this.mSeaWaterDrops3.resetData();
        this.mSeaWaterDrops.initShader();
        this.mSeaWaterDrops2.initShader();
        this.mSeaWaterDrops3.initShader();
        Matrix.frustumM(this.mProjectionMatrix, 0, negativeAspectRatio, aspectRatio, -1.0f, 1.0f, 1.0f, 10.0f);
    }

    private void multiplyMVPMatrix() {
        Matrix.multiplyMM(this.mMVPMatrix, 0, this.mViewMatrix, 0, this.mModelMatrix, 0);
        Matrix.multiplyMM(this.mMVPMatrix, 0, this.mProjectionMatrix, 0, this.mMVPMatrix, 0);
    }

    private void initListVO() {
        IntervalVO notOccupiedVOByZUp;
        this.mIsAllFinishedAppearing = false;
        this.mAppearingIndex = 0;
        int numberOfJellyfish = this.mJellyfishVO.getNumberOfJellyfish();
        PositionVO.initRandomPositions();
        int numberOfZs = PositionVO.getNumberOfZs();
        for (int i = 0; i < numberOfJellyfish; i++) {
            JellyfishVO vOByIndex = this.mJellyfishVO.getVOByIndex(i);
            float zByIndex = PositionVO.getZByIndex(i % numberOfZs);
            if (zByIndex < -4.0f) {
                notOccupiedVOByZUp = this.mIntervalManager.getNotOccupiedVOByZDown();
            } else {
                notOccupiedVOByZUp = this.mIntervalManager.getNotOccupiedVOByZUp();
            }
            if (vOByIndex != null && notOccupiedVOByZUp != null) {
                vOByIndex.setIndexOfInterval(notOccupiedVOByZUp.getID());
                float randomFloat = MathHelper.getRandomFloat(notOccupiedVOByZUp.getMinX(), notOccupiedVOByZUp.getMaxX());
                float randomFloat2 = MathHelper.getRandomFloat(notOccupiedVOByZUp.getMinY(), notOccupiedVOByZUp.getMaxY());
                float randomFloat3 = MathHelper.getRandomFloat(0.004f, 0.007f);
                vOByIndex.setPosition(randomFloat, randomFloat2, zByIndex);
                vOByIndex.setStartPosition(randomFloat, randomFloat2, zByIndex);
                vOByIndex.setDestination(randomFloat, randomFloat2, zByIndex);
                vOByIndex.setDestinationRotate(((((float) Math.atan2(vOByIndex.getDestinationY() - vOByIndex.getStartPositionY(), vOByIndex.getDestinationX() - vOByIndex.getStartPositionX())) * 180.0f) / 3.1415927f) - 120.0f);
                vOByIndex.setSpeed(randomFloat3);
                vOByIndex.setStartTime((float) SystemClock.uptimeMillis());
                vOByIndex.setIsAppearing(false);
                vOByIndex.setIsFinishedAppearing(false);
                vOByIndex.setReverseAlpha(1.0f);
            }
        }
        this.mJellyfishVO.setABList();
    }

    private void changeDestination() {
        float destinationX;
        float destinationY;
        float destinationZ;
        IntervalVO notOccupiedVOByZUp;
        int numberOfJellyfish = this.mJellyfishVO.getNumberOfJellyfish();
        for (int i = 0; i < numberOfJellyfish; i++) {
            JellyfishVO vOByIndex = this.mJellyfishVO.getVOByIndex(i);
            float positionX = vOByIndex.getPositionX();
            float positionY = vOByIndex.getPositionY();
            float positionZ = vOByIndex.getPositionZ();
            boolean isMovingZ = vOByIndex.isMovingZ();
            if (isMovingZ) {
                destinationX = vOByIndex.getMovingZDestinationX();
                destinationY = vOByIndex.getMovingZDestinationY();
                destinationZ = vOByIndex.getMovingZDestinationZ();
            } else {
                destinationX = vOByIndex.getDestinationX();
                destinationY = vOByIndex.getDestinationY();
                destinationZ = vOByIndex.getDestinationZ();
            }
            if (positionX <= destinationX + 0.3f && positionX >= destinationX - 0.3f && positionY <= destinationY + 0.3f && positionY >= destinationY - 0.3f && positionZ <= destinationZ + 0.3f && positionZ >= destinationZ - 0.3f) {
                if (isMovingZ) {
                    vOByIndex.setIsMovingZ(false);
                }
                float positionZ2 = vOByIndex.getPositionZ();
                if (positionZ2 < -4.0f) {
                    notOccupiedVOByZUp = this.mIntervalManager.getNotOccupiedVOByZDown();
                } else {
                    notOccupiedVOByZUp = this.mIntervalManager.getNotOccupiedVOByZUp();
                }
                if (notOccupiedVOByZUp == null) {
                    continue;
                }
                float randomFloat = MathHelper.getRandomFloat(notOccupiedVOByZUp.getMinX(), notOccupiedVOByZUp.getMaxX());
                float randomFloat2 = MathHelper.getRandomFloat(notOccupiedVOByZUp.getMinY(), notOccupiedVOByZUp.getMaxY());
                float randomFloat3 = MathHelper.getRandomFloat(0.004f, 0.007f);
                vOByIndex.setStartPosition(vOByIndex.getPositionX(), vOByIndex.getPositionY(), vOByIndex.getPositionZ());
                vOByIndex.setDestination(randomFloat, randomFloat2, positionZ2);
                vOByIndex.setSpeed(randomFloat3);
                vOByIndex.setDestinationRotate(((((float) Math.atan2(vOByIndex.getDestinationY() - vOByIndex.getStartPositionY(), vOByIndex.getDestinationX() - vOByIndex.getStartPositionX())) * 180.0f) / 3.1415927f) - 120.0f);
                this.mIntervalManager.setIsOccupiedToFalseByIndex(vOByIndex.getIndexOfInterval());
                vOByIndex.setIndexOfInterval(notOccupiedVOByZUp.getID());
                vOByIndex.setStartTime((float) SystemClock.uptimeMillis());
            }
        }
    }

    private void changeDestinationByIndex(int jellyfishIndex) {
        IntervalVO notOccupiedVOByZUp;
        JellyfishVO vOByIndex = this.mJellyfishVO.getVOByIndex(jellyfishIndex);
        if (vOByIndex.isShaking()) {
            float positionZ = vOByIndex.getPositionZ();
            if (positionZ < -4.0f) {
                notOccupiedVOByZUp = this.mIntervalManager.getNotOccupiedVOByZDown();
            } else {
                notOccupiedVOByZUp = this.mIntervalManager.getNotOccupiedVOByZUp();
            }
            if (notOccupiedVOByZUp == null) {
                vOByIndex.setIsShaking(false);
                return;
            }
            float randomFloat = MathHelper.getRandomFloat(notOccupiedVOByZUp.getMinX(), notOccupiedVOByZUp.getMaxX());
            float randomFloat2 = MathHelper.getRandomFloat(notOccupiedVOByZUp.getMinY(), notOccupiedVOByZUp.getMaxY());
            float randomFloat3 = MathHelper.getRandomFloat(0.004f, 0.007f);
            vOByIndex.setStartPosition(vOByIndex.getPositionX(), vOByIndex.getPositionY(), vOByIndex.getPositionZ());
            vOByIndex.setDestination(randomFloat, randomFloat2, positionZ);
            vOByIndex.setSpeed(randomFloat3);
            this.mIntervalManager.setIsOccupiedToFalseByIndex(vOByIndex.getIndexOfInterval());
            vOByIndex.setIndexOfInterval(notOccupiedVOByZUp.getID());
            vOByIndex.setStartTime((float) SystemClock.uptimeMillis());
            vOByIndex.setIsShaking(false);
        }
    }

    private void drawForGoingToMove() {
        boolean z;
        JellyfishVO jellyfishVO;
        float f;
        float f2;
        int numberOfJellyfish = this.mJellyfishVO.getNumberOfJellyfish();
        ArrayList<JellyfishVO> aList = this.mJellyfishVO.getAList();
        ArrayList<JellyfishVO> bList = this.mJellyfishVO.getBList();
        int aBDivide = this.mJellyfishVO.getABDivide();
        for (int i = 0; i < numberOfJellyfish; i++) {
            JellyfishVO vOByIndex = this.mJellyfishVO.getVOByIndex(i);
            if (i < aBDivide) {
                z = false;
                jellyfishVO = aList.get(MathHelper.getIndexForFragmentShader(i, aList.size()));
            } else {
                z = true;
                jellyfishVO = bList.get(MathHelper.getIndexForFragmentShader(i - aBDivide, bList.size()));
            }
            boolean isAppearing = jellyfishVO.isAppearing();
            boolean isFinishedAppearing = jellyfishVO.isFinishedAppearing();
            boolean isRemoving = jellyfishVO.isRemoving();
            boolean isFinishedRemoving = jellyfishVO.isFinishedRemoving();
            float reverseAlpha = jellyfishVO.getReverseAlpha();
            float depthBasedAlpha = 1.0f - (((-(jellyfishVO.getPositionZ() + 3.0f)) * 1.0f) / 7.0f);
            if (depthBasedAlpha < 0.0f) {
                depthBasedAlpha = 0.0f;
            }
            float invertedDepthAlpha = 1.0f - depthBasedAlpha;
            if (isAppearing || isRemoving || isFinishedRemoving || !isFinishedAppearing) {
                f = invertedDepthAlpha - reverseAlpha;
                f2 = depthBasedAlpha - reverseAlpha;
            } else {
                f = invertedDepthAlpha;
                f2 = depthBasedAlpha;
            }
            if (!z) {
                this.mJellyfish.setForDrawing();
                Matrix.setIdentityM(this.mModelMatrix, 0);
                Matrix.translateM(this.mModelMatrix, 0, vOByIndex.getPositionX(), vOByIndex.getPositionY(), vOByIndex.getPositionZ());
                Matrix.rotateM(this.mModelMatrix, 0, vOByIndex.getRotate(), 0.0f, 0.0f, 1.0f);
                multiplyMVPMatrix();
                this.mJellyfish.update(this.mMVPMatrix);
                this.mJellyfish.setAddAlpha(f2 - 1.0f);
                this.mJellyfish.setScale(this.mScale);
                this.mJellyfish.draw();
                this.mBlurEffect.setForDrawing();
                this.mBlurEffect.setAddAlpha(f - 1.0f);
                this.mBlurEffect.setScale(this.mScale);
                this.mBlurEffect.update(this.mMVPMatrix);
                this.mBlurEffect.draw();
            } else {
                this.mJellyfish2.setForDrawing();
                Matrix.setIdentityM(this.mModelMatrix, 0);
                Matrix.translateM(this.mModelMatrix, 0, vOByIndex.getPositionX(), vOByIndex.getPositionY(), vOByIndex.getPositionZ());
                Matrix.rotateM(this.mModelMatrix, 0, vOByIndex.getRotate(), 0.0f, 0.0f, 1.0f);
                multiplyMVPMatrix();
                this.mJellyfish2.update(this.mMVPMatrix);
                this.mJellyfish2.setAddAlpha(f2 - 1.0f);
                this.mJellyfish2.setScale(this.mScale);
                this.mJellyfish2.draw();
                this.mBlurEffect2.setForDrawing();
                this.mBlurEffect2.setAddAlpha(f - 1.0f);
                this.mBlurEffect2.setScale(this.mScale);
                this.mBlurEffect2.update(this.mMVPMatrix);
                this.mBlurEffect2.draw();
            }
            if (vOByIndex.hasBlur()) {
                this.mBlur.setForDrawing();
                this.mBlur.setAddAlpha(this.mBlurAlpha - 1.0f);
                this.mBlur.setScale(this.mScale);
                this.mBlur.update(this.mMVPMatrix);
                this.mBlur.draw();
            }
            JellyfishVO vOByIndex2 = this.mJellyfishVO.getVOByIndex(MathHelper.getIndexForFragmentShader(i, numberOfJellyfish));
            boolean isAppearing2 = vOByIndex2.isAppearing();
            boolean isFinishedAppearing2 = vOByIndex2.isFinishedAppearing();
            boolean isRemoving2 = vOByIndex2.isRemoving();
            boolean isFinishedRemoving2 = vOByIndex2.isFinishedRemoving();
            float positionZ = vOByIndex2.getPositionZ();
            float reverseAlpha2 = vOByIndex2.getReverseAlpha();
            float positionDepthAlpha = 1.0f - (((-(positionZ + 3.0f)) * 1.0f) / 7.0f);
            if (positionDepthAlpha < 0.0f) {
                positionDepthAlpha = 0.0f;
            }
            if (isAppearing2 || isRemoving2 || isFinishedRemoving2 || !isFinishedAppearing2) {
                positionDepthAlpha -= 0.4f + reverseAlpha2;
            }
            this.mWaterDropsInJellyfish.setForDrawing();
            this.mWaterDropsInJellyfish.setAddAlpha(positionDepthAlpha - 1.0f);
            this.mWaterDropsInJellyfish.setScale(this.mScale);
            this.mWaterDropsInJellyfish.update(this.mMVPMatrix);
            this.mWaterDropsInJellyfish.draw();
            this.mWaterDropsInJellyfish2.setForDrawing();
            this.mWaterDropsInJellyfish2.setAddAlpha(positionDepthAlpha - 1.0f);
            this.mWaterDropsInJellyfish2.setScale(this.mScale);
            this.mWaterDropsInJellyfish2.update(this.mMVPMatrix);
            this.mWaterDropsInJellyfish2.draw();
        }
    }

    private void updateForGoingToMove() {
        int numberOfJellyfish = this.mJellyfishVO.getNumberOfJellyfish();
        for (int i = 0; i < numberOfJellyfish; i++) {
            updateForGoingToMoveByIndex(i);
        }
        if (this.mIsRemoving) {
            this.mIsAllFinishedRemoving = this.mJellyfishVO.isAllFinishedRemoving(this.mNumberOfRemoving);
            if (this.mIsAllFinishedRemoving) {
                this.mIsRemoving = false;
            }
        }
        if (this.mIsAppearingRemoved && this.mJellyfishVO.isAllFinishedAppearing()) {
            this.mIsAppearingRemoved = false;
        }
    }

    private void updateForGoingToMoveByIndex(int jellyfishIndex) {
        float rotationIncrement;
        float destinationZ;
        float newPositionY;
        float newPositionX;
        float resistance;
        float baseIncrement = 8.0E-4f;
        JellyfishVO vOByIndex = this.mJellyfishVO.getVOByIndex(jellyfishIndex);
        float rotate = vOByIndex.getRotate();
        float destinationRotate = vOByIndex.getDestinationRotate();
        if (this.mBatteryState == 0) {
            rotationIncrement = 0.02f;
        } else {
            rotationIncrement = this.mBatteryState == 1 ? 0.002f : 8.0E-4f;
        }
        vOByIndex.setRotate((rotationIncrement * (destinationRotate - rotate)) + rotate);
        if (vOByIndex.isMovingZ()) {
            float positionX = vOByIndex.getPositionX();
            float positionY = vOByIndex.getPositionY();
            float positionZ = vOByIndex.getPositionZ();
            float movingZDestinationX = vOByIndex.getMovingZDestinationX();
            float movingZDestinationY = vOByIndex.getMovingZDestinationY();
            float movingZDestinationZ = vOByIndex.getMovingZDestinationZ();
            float movingZStartY = vOByIndex.getMovingZStartY();
            float progressRatio = 0.01f + ((positionY - movingZStartY) / (movingZDestinationY - movingZStartY));
            if (this.mBatteryState == 0) {
                resistance = vOByIndex.getResistance();
            } else if (this.mBatteryState == 1) {
                resistance = 600.0f;
            } else {
                resistance = 300.0f;
            }
            float zStep = (movingZDestinationZ - positionZ) / resistance;
            newPositionX = positionX + (((movingZDestinationX - positionX) / resistance) * progressRatio);
            newPositionY = (((movingZDestinationY - positionY) / resistance) * progressRatio * vOByIndex.getDisport()) + positionY;
            destinationZ = (zStep * progressRatio) + positionZ;
        } else {
            if (this.mBatteryState == 0) {
                baseIncrement = vOByIndex.getSpeed();
            } else if (this.mBatteryState == 1) {
                baseIncrement = vOByIndex.getLowBatterySpeed();
            }
            float positionX2 = vOByIndex.getPositionX();
            float positionY2 = vOByIndex.getPositionY();
            float positionZ2 = vOByIndex.getPositionZ();
            float destinationX = vOByIndex.getDestinationX();
            float destinationY = vOByIndex.getDestinationY();
            destinationZ = positionZ2 + ((vOByIndex.getDestinationZ() - positionZ2) * baseIncrement);
            newPositionY = positionY2 + ((destinationY - positionY2) * baseIncrement);
            newPositionX = (baseIncrement * (destinationX - positionX2)) + positionX2;
        }
        vOByIndex.setPosition(newPositionX, newPositionY, destinationZ);
        boolean isAppearing = vOByIndex.isAppearing();
        boolean isRemoving = vOByIndex.isRemoving();
        float reverseAlpha = vOByIndex.getReverseAlpha();
        if (isAppearing) {
            reverseAlpha += (0.0f - reverseAlpha) * 0.1f;
            if (reverseAlpha < 0.1f) {
                vOByIndex.setIsAppearing(false);
                vOByIndex.setIsFinishedAppearing(true);
                reverseAlpha = 0.0f;
            }
            vOByIndex.setReverseAlpha(reverseAlpha);
        }
        if (isRemoving) {
            float f8 = reverseAlpha + ((1.0f - reverseAlpha) * 0.1f);
            if (f8 > 0.92f) {
                vOByIndex.setIsRemoving(false);
                vOByIndex.setIsFinishedRemoving(true);
                f8 = 1.0f;
            }
            vOByIndex.setReverseAlpha(f8);
        }
    }

    private void updateForGoingToMoveWhenShaking(float f) {
        int numberOfJellyfish = this.mJellyfishVO.getNumberOfJellyfish();
        float uptimeMillis = (((float) SystemClock.uptimeMillis()) - this.mStartShakingTime) * 0.001f;
        if (uptimeMillis > 1.0f) {
            this.mIsAfterShaking = true;
        }
        for (int i = 0; i < numberOfJellyfish; i++) {
            if (uptimeMillis < (i * 0.6f) + 5.0f) {
                updateWhenShakingByIndex(i, f, uptimeMillis);
            } else {
                changeDestinationByIndex(i);
                updateForGoingToMoveByIndex(i);
            }
        }
        if (uptimeMillis > (((float) numberOfJellyfish) * 0.6f) + 5.0f) {
            this.mIsShaking = false;
            this.mIsAfterShaking = false;
        }
    }

    private void updateWhenShakingByIndex(int i, float f, float f2) {
        float f3;
        float f4;
        JellyfishVO vOByIndex = this.mJellyfishVO.getVOByIndex(i);
        float positionX = vOByIndex.getPositionX();
        float positionY = vOByIndex.getPositionY();
        float positionZ = vOByIndex.getPositionZ();
        float rotate = vOByIndex.getRotate();
        float forceX = vOByIndex.getForceX();
        int i2 = (int) positionZ;
        float f5 = forceX + ((((i2 + 11) * 4.0E-5f) + 0.18f) * (0.0f - forceX));
        vOByIndex.setForceX(f5);
        float[] minMaxXYByZ = PositionVO.getMinMaxXYByZ(positionZ);
        float f6 = positionX + (f5 * 0.04f * 0.7f);
        if (this.mWidth < this.mHeight) {
            if (f > 0.0f) {
                if (f6 > minMaxXYByZ[1] - 0.5f) {
                    f6 += 0.02f * ((minMaxXYByZ[1] - 0.5f) - f6);
                }
                if (f6 > minMaxXYByZ[1] + 0.3f) {
                    f6 = minMaxXYByZ[1] + 0.3f;
                }
            } else {
                if (f6 < minMaxXYByZ[0] + 0.5f) {
                    f6 += 0.02f * ((minMaxXYByZ[0] + 0.5f) - f6);
                }
                if (f6 < minMaxXYByZ[0] - 0.3f) {
                    f6 = minMaxXYByZ[0] - 0.3f;
                }
            }
        } else if (f > 0.0f) {
            if (f6 > minMaxXYByZ[1] - (minMaxXYByZ[1] * 0.45f)) {
                f6 = minMaxXYByZ[1] - (minMaxXYByZ[1] * 0.45f);
            }
        } else if (f6 < minMaxXYByZ[0] + (minMaxXYByZ[1] * 0.45f)) {
            f6 = minMaxXYByZ[0] + (minMaxXYByZ[1] * 0.45f);
        }
        float forceY = vOByIndex.getForceY();
        float f7 = forceY + ((((i2 + 11) * 5.0E-4f) + 0.006f) * (0.0f - forceY));
        vOByIndex.setForceY(f7);
        float abs = Math.abs(f7);
        float f8 = abs - ((abs / f2) * 0.05f);
        if (f8 < 0.0f) {
            f8 = 0.0f;
        }
        if (vOByIndex.isShakeUp()) {
            f3 = positionY + (0.0016f * f8);
            f4 = (f8 * 0.1f) + rotate;
            if (f3 > minMaxXYByZ[3] - 0.8f) {
                f3 += 0.02f * ((minMaxXYByZ[3] - 0.8f) - f3);
            }
        } else {
            f3 = positionY - (0.0016f * f8);
            f4 = rotate - (f8 * 0.1f);
            if (f3 < minMaxXYByZ[2] + 0.8f) {
                f3 += 0.02f * ((minMaxXYByZ[2] + 0.8f) - f3);
            }
        }
        vOByIndex.setPosition(f6, f3, positionZ);
        vOByIndex.setRotate(f4);
    }

    private void startZMove() {
        float destinationX;
        float destinationY;
        float destinationZ;
        JellyfishVO jellyfishVO;
        float destinationX2;
        JellyfishVO jellyfishVO2;
        float destinationY2;
        float destinationZ2;
        float f;
        float f2;
        float f3;
        float randomFloat = MathHelper.getRandomFloat(-360.0f, 360.0f);
        JellyfishVO randomVOByZDown = this.mJellyfishVO.getRandomVOByZDown();
        JellyfishVO randomVOByZUp = this.mJellyfishVO.getRandomVOByZUp();
        if (randomVOByZDown == null) {
            JellyfishVO randomVOByZUp2 = this.mJellyfishVO.getRandomVOByZUp();
            if (randomVOByZUp2 != null) {
                destinationX = randomVOByZUp2.getDestinationX();
                f3 = randomVOByZUp2.getDestinationY();
            } else {
                f3 = 0.0f;
                destinationX = 0.0f;
            }
            destinationZ = -8.0f;
            jellyfishVO = randomVOByZUp2;
            destinationY = f3;
        } else {
            destinationX = randomVOByZDown.getDestinationX();
            destinationY = randomVOByZDown.getDestinationY();
            destinationZ = randomVOByZDown.getDestinationZ();
            jellyfishVO = randomVOByZDown;
        }
        if (randomVOByZUp == null) {
            JellyfishVO randomVOByZDown2 = this.mJellyfishVO.getRandomVOByZDown();
            if (randomVOByZDown2 != null) {
                float destinationX3 = randomVOByZDown2.getDestinationX();
                f2 = randomVOByZDown2.getDestinationY();
                f = destinationX3;
            } else {
                f = 0.0f;
                f2 = 0.0f;
            }
            destinationZ2 = -4.0f;
            jellyfishVO2 = randomVOByZDown2;
            destinationY2 = f2;
            destinationX2 = f;
        } else {
            destinationX2 = randomVOByZUp.getDestinationX();
            jellyfishVO2 = randomVOByZUp;
            destinationY2 = randomVOByZUp.getDestinationY();
            destinationZ2 = randomVOByZUp.getDestinationZ();
        }
        if (!jellyfishVO.isMovingZ() && !jellyfishVO2.isMovingZ()) {
            updateMoveingZInfoByVO(jellyfishVO2, destinationX, destinationY, destinationZ, randomFloat);
            updateMoveingZInfoByVO(jellyfishVO, destinationX2, destinationY2, destinationZ2, randomFloat);
        }
    }

    private void checkBlur() {
        int numberOfJellyfish = this.mJellyfishVO.getNumberOfJellyfish();
        for (int i = 0; i < numberOfJellyfish; i++) {
            this.mJellyfishVO.getVOByIndex(i).setHasBlur(false);
        }
        int random = (int) (Math.random() * this.mJellyfishVO.getNumberOfJellyfish());
        JellyfishVO vOByIndex = this.mJellyfishVO.getVOByIndex((int) (Math.random() * this.mJellyfishVO.getNumberOfJellyfish()));
        JellyfishVO vOByIndex2 = this.mJellyfishVO.getVOByIndex(random);
        if (!vOByIndex.isRemoving() && !vOByIndex.isFinishedRemoving()) {
            vOByIndex.setHasBlur(true);
        }
        if (vOByIndex2.isRemoving() || vOByIndex2.isFinishedRemoving()) {
            return;
        }
        vOByIndex2.setHasBlur(true);
    }

    private void updateMoveingZInfoByVO(JellyfishVO jellyfishVO, float f, float f2, float f3, float f4) {
        jellyfishVO.setIsMovingZ(true);
        jellyfishVO.setMovingZStartPosition(jellyfishVO.getPositionX(), jellyfishVO.getPositionY(), jellyfishVO.getPositionZ());
        jellyfishVO.setMovingZDestination(f, f2, f3);
        jellyfishVO.setDestinationRotate(((((float) Math.atan2(jellyfishVO.getMovingZDestinationY() - jellyfishVO.getMovingZStartY(), jellyfishVO.getMovingZDestinationX() - jellyfishVO.getMovingZStartX())) * 180.0f) / 3.1415927f) - 120.0f);
    }

    public void setBackgroundImagePath(String str) {
        this.mBackgroundImagePath = str;
    }

    private void checkAppearing() {
        this.mIsAllFinishedAppearing = this.mJellyfishVO.isAllFinishedAppearing();
        if (this.mAppearingIndex < this.mJellyfishVO.getNumberOfJellyfish()) {
            JellyfishVO vOByIndex = this.mJellyfishVO.getVOByIndex(this.mAppearingIndex);
            if (!vOByIndex.isRemoving() && !vOByIndex.isFinishedRemoving()) {
                vOByIndex.setIsAppearing(true);
            }
            this.mAppearingIndex++;
        }
    }

    public int getStateByBattery() {
        return this.mBatteryState;
    }

    public void setCheckBitmap(Bitmap bitmap) {
        this.mCheckBitmap = bitmap;
    }
}

