package com.reandroid.wallpaper.deepsea;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
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
import androidx.annotation.Nullable;
import com.reandroid.wallpaper.deepsea.Blur;
import com.reandroid.wallpaper.deepsea.BlurEffect;
import com.reandroid.wallpaper.deepsea.BlurEffect2;
import com.reandroid.wallpaper.deepsea.DeepSeaContainer;
import com.reandroid.wallpaper.deepsea.DeepSeaSettings;
import com.reandroid.wallpaper.deepsea.GLBaseView;
import com.reandroid.wallpaper.deepsea.GLHelper;
import com.reandroid.wallpaper.deepsea.IntervalManager;
import com.reandroid.wallpaper.deepsea.IntervalVO;
import com.reandroid.wallpaper.deepsea.Jellyfish;
import com.reandroid.wallpaper.deepsea.Jellyfish2;
import com.reandroid.wallpaper.deepsea.JellyfishVO;
import com.reandroid.wallpaper.deepsea.Sea2;
import com.reandroid.wallpaper.deepsea.SeaWaterDrops;

import com.reandroid.wallpaper.deepsea.SeaWaterDrops2;
import com.reandroid.wallpaper.deepsea.SeaWaterDrops3;
import com.reandroid.wallpaper.deepsea.Sunshine;
import com.reandroid.wallpaper.deepsea.Sunshine2;
import com.reandroid.wallpaper.deepsea.Sunshine3;
import com.reandroid.gles.GLESScene;
import com.reandroid.gles.GLESWallpaper;
import com.reandroid.gles.RawResourceLoader;
import com.reandroid.wallpaper.R;
import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Random;

public class DeepSeaGL extends GLESScene implements SharedPreferences.OnSharedPreferenceChangeListener, SensorEventListener {
    public static final String PREFS_NAME = "deepsea";
    public static final String KEY_BACKGROUND_IMAGE_TYPE = "backgroundimagetype";
    public static final String KEY_BACKGROUND_UPDATED = "deepsea_background_updated";

    private DeepSeaContainer mContainer;
    private SharedPreferences mPrefs;
    private SensorManager mSensorManager;
    private Sensor mGyro;
    private Bitmap mCachedBackground;
    private boolean mReceiversRegistered;
    private BroadcastReceiver mBatteryReceiver;
    private BroadcastReceiver mScreenReceiver;

    public DeepSeaGL(int width, int height) {
        super(width, height);
    }

    @Override
    protected void onCreate() {
        Context context = GLESWallpaper.getAppContext();
        if (context == null) {
            return;
        }

        if (mContainer == null) {
            mContainer = new DeepSeaContainer(context);
            mContainer.initByteBuffer();
            mContainer.setViewMatrix();
            String cachePath = getCachePath(context);
            mContainer.setBackgroundImagePath(cachePath);
        }

        if (mPrefs == null) {
            mPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            mPrefs.registerOnSharedPreferenceChangeListener(this);
        }

        // 主动刷新缓存图片，保证预览页也能显示
        String type = mPrefs.getString(KEY_BACKGROUND_IMAGE_TYPE, "0");
        if ("0".equals(type)) {
            mContainer.setCheckBitmap(null);
        } else {
            String cachePath = getCachePath(context);
            Bitmap bmp = DeepSeaSettings.loadBitmap(cachePath);
            mContainer.setCheckBitmap(bmp);
        }

        onSharedPreferenceChanged(mPrefs, null);
    }

    @Override
    public void start() {
        registerReceivers();
        if (mContainer != null) {
            mContainer.screenOn();
        }
    }

    @Override
    public void stop() {
        unregisterReceivers();
    }

    @Override
    public void release() {
        if (mContainer != null) {
            mContainer.remove();
            mContainer = null;
        }
        if (mPrefs != null) {
            mPrefs.unregisterOnSharedPreferenceChangeListener(this);
            mPrefs = null;
        }
        if (mCachedBackground != null) {
            mCachedBackground.recycle();
            mCachedBackground = null;
        }
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        if (mContainer != null) {
            mContainer.setProjectionMatrix(width, height);
        }
    }

    @Override
    public void drawFrame(long timeMs) {
        if (mContainer == null) {
            return;
        }
        if (!mContainer.isInitShader()) {
            mContainer.initShader();
        }
        mContainer.draw();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, @Nullable String key) {
        if (mContainer == null || sharedPreferences == null) {
            return;
        }

        if (key == null || KEY_BACKGROUND_IMAGE_TYPE.equals(key) || KEY_BACKGROUND_UPDATED.equals(key)) {
            updateCachedBackground(sharedPreferences);
        }

        if (KEY_BACKGROUND_UPDATED.equals(key)) {
            mContainer.onSharedPreferenceChanged(sharedPreferences, KEY_BACKGROUND_IMAGE_TYPE);
            return;
        }

        mContainer.onSharedPreferenceChanged(sharedPreferences, key);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (mContainer == null || event == null || event.values == null || event.values.length < 3) {
            return;
        }
        float z = event.values[2];
        if (z > 2.5f || z < -2.5f) {
            if (z > 0.0f) {
                z += 10.0f;
            }
            float force = -(z < 0.0f ? z - 10.0f : z);
            mContainer.setByShake(force);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void updateCachedBackground(SharedPreferences prefs) {
        Context context = GLESWallpaper.getAppContext();
        if (context == null) {
            return;
        }

        String type = prefs.getString(KEY_BACKGROUND_IMAGE_TYPE, "0");
        if ("0".equals(type)) {
            if (mCachedBackground != null) {
                mCachedBackground.recycle();
                mCachedBackground = null;
            }
            mContainer.setCheckBitmap(null);
            return;
        }

        if (mCachedBackground != null) {
            mCachedBackground.recycle();
            mCachedBackground = null;
        }
        mCachedBackground = DeepSeaSettings.loadBitmap(getCachePath(context));
        mContainer.setCheckBitmap(mCachedBackground);
    }

    private void registerReceivers() {
        if (mReceiversRegistered) {
            return;
        }
        Context context = GLESWallpaper.getAppContext();
        if (context == null) {
            return;
        }

        if (mSensorManager == null) {
            mSensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            if (mSensorManager != null) {
                mGyro = mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            }
        }

        if (mSensorManager != null && mGyro != null) {
            mSensorManager.registerListener(this, mGyro, SensorManager.SENSOR_DELAY_GAME);
        }

        mBatteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (mContainer == null || intent == null) {
                    return;
                }
                int plugged = intent.getIntExtra("plugged", 0);
                int level = intent.getIntExtra("level", 0);
                int state;
                if (plugged > 0 || level > 30) {
                    state = 0;
                } else if (level > 15) {
                    state = 1;
                } else {
                    state = 2;
                }
                mContainer.setStateByBattery(state);
            }
        };

        mScreenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (mContainer == null || intent == null) {
                    return;
                }
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    mContainer.screenOff();
                } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    mContainer.screenOn();
                }
            }
        };

        context.registerReceiver(mBatteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        IntentFilter screenFilter = new IntentFilter();
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        context.registerReceiver(mScreenReceiver, screenFilter);

        mReceiversRegistered = true;
    }

    private void unregisterReceivers() {
        if (!mReceiversRegistered) {
            return;
        }
        Context context = GLESWallpaper.getAppContext();
        if (context == null) {
            return;
        }

        if (mSensorManager != null) {
            mSensorManager.unregisterListener(this);
        }

        if (mBatteryReceiver != null) {
            context.unregisterReceiver(mBatteryReceiver);
            mBatteryReceiver = null;
        }
        if (mScreenReceiver != null) {
            context.unregisterReceiver(mScreenReceiver);
            mScreenReceiver = null;
        }

        mReceiversRegistered = false;
    }

    private static String getCachePath(Context context) {
        if (context.getExternalCacheDir() != null) {
            return context.getExternalCacheDir().getAbsolutePath();
        }
        return context.getCacheDir().getAbsolutePath();
    }
}

class Blur extends Jellyfish {
    public Blur() {
    }

    public Blur(Context context) {
        super(context);
    }

    @Override // com.reandroid.wallpaper.deepsea.Jellyfish
    protected int getTextureId() {
        return GLHelper.getTexture(getContext(), R.drawable.unit_a_core_glow_s256x256_eeee_mip_0);
    }

    @Override // com.reandroid.wallpaper.deepsea.Jellyfish
    protected int getAlphaTextureId() {
        return GLHelper.getTexture(getContext(), R.drawable.unit_a_core_glow_s256x256_eeee_mip_0_alpha);
    }

    @Override // com.reandroid.wallpaper.deepsea.Jellyfish, com.reandroid.wallpaper.deepsea.GLBaseView
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
        return GLHelper.getTexture(getContext(), R.drawable.unit_a_blured_12_s256x256_mip_0);
    }

    @Override // com.reandroid.wallpaper.deepsea.Jellyfish
    protected int getAlphaTextureId() {
        return GLHelper.getTexture(getContext(), R.drawable.unit_a_blured_12_s256x256_mip_0_alpha);
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
        return GLHelper.getTexture(getContext(), R.drawable.unit_b_blured_12_s256x256_mip_0);
    }

    @Override // com.reandroid.wallpaper.deepsea.BlurEffect, com.reandroid.wallpaper.deepsea.Jellyfish
    protected int getAlphaTextureId() {
        return GLHelper.getTexture(getContext(), R.drawable.unit_b_blured_12_s256x256_mip_0_alpha);
    }
}

class DeepSeaContainer extends GLBaseView {
    private final int BATTERY_STATE_HIGHT;
    private final int BATTERY_STATE_LOW;
    private final int BATTERY_STATE_WARRING;
    private float mAlphaOfSunshine;
    private float mAlphaOfSunshine1;
    private float mAlphaOfSunshine2;
    private int mAppearingIndex;
    private String mBackgroundImagePath;
    private int mBatteryState;
    private int mBatteryTime;
    private Blur mBlur;
    private float mBlurAlpha;
    private float[] mBlurColor;
    private BlurEffect mBlurEffect;
    private BlurEffect2 mBlurEffect2;
    private float mBrightness;
    private Bitmap mCheckBitmap;
    private final float mCycleOfChaingDestination;
    private final float mCycleOfStartingRandomZMotion;
    private float mDefaultScale;
    private float mDestinationAlphaOfSunshine;
    private float mDestinationAlphaOfSunshine1;
    private float mDestinationAlphaOfSunshine2;
    private float mForce;
    private int mHeight;
    private final float mIntervalAlphaOfSunshine;
    private final float mIntervalAlphaOfSunshine1;
    IntervalManager mIntervalManager;
    private boolean mIsAfterShaking;
    private boolean mIsAllFinishedAppearing;
    private boolean mIsAllFinishedRemoving;
    private boolean mIsAppearingRemoved;
    private boolean mIsBackgroundChanged;
    private boolean mIsBluring;
    private boolean mIsRemoving;
    private boolean mIsSendedReceiver;
    private boolean mIsShaking;
    private Jellyfish mJellyfish;
    private Jellyfish2 mJellyfish2;
    private JellyfishVO mJellyfishVO;
    private float mLight;
    private float[] mMVPMatrix;
    private final float mMaxRotation;
    private final float mMaxSpeed;
    private final float mMinRotation;
    private final float mMinSpeed;
    private float[] mModelMatrix;
    private int mNumberOfParticles;
    private int mNumberOfParticles2;
    private int mNumberOfRemoving;
    private float[] mProjectionMatrix;
    private float mScale;
    private Sea mSea;
    private Sea2 mSea2;
    private SeaWaterDrops mSeaWaterDrops;
    private SeaWaterDrops2 mSeaWaterDrops2;
    private SeaWaterDrops3 mSeaWaterDrops3;
    private float mStartShakingTime;
    private Sunshine mSunshine;
    private Sunshine2 mSunshine2;
    private Sunshine3 mSunshine3;
    private int mTempBackImageCount;
    private float mTime;
    private int mUnitNumberVal;
    private int mUnitScaleVal;
    private float[] mViewMatrix;
    private JellyfishWaterDrops mWaterDropsInJellyfish;
    private JellyfishWaterDrops2 mWaterDropsInJellyfish2;
    private int mWidth;

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
        super(context);
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
        this.mJellyfish2 = new Jellyfish2(context);
        this.mBlur = new Blur(context);
        this.mSea = new Sea(context);
        this.mSea2 = new Sea2(context);
        this.mWaterDropsInJellyfish = new JellyfishWaterDrops(context);
        this.mWaterDropsInJellyfish2 = new JellyfishWaterDrops2(context);
        this.mIntervalManager = new IntervalManager();
        this.mJellyfishVO = new JellyfishVO();
        this.mSunshine = new Sunshine(context);
        this.mSunshine2 = new Sunshine2(context);
        this.mSunshine3 = new Sunshine3(context);
        this.mSeaWaterDrops = new SeaWaterDrops(context);
        this.mSeaWaterDrops2 = new SeaWaterDrops2(context);
        this.mSeaWaterDrops3 = new SeaWaterDrops3(context);
        this.mBlurEffect = new BlurEffect(context);
        this.mBlurEffect2 = new BlurEffect2(context);
    }

    @Override // com.reandroid.wallpaper.deepsea.GLBaseView
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
        super.remove();
        System.gc();
    }

    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String str) {
        if (str == null) {
            this.mUnitScaleVal = readIntPreference(sharedPreferences, "unitscale", 0);
            int i = readIntPreference(sharedPreferences, "unitnumber", 0);
            int i2 = readIntPreference(sharedPreferences, "backgroundbrightness", 0);
            int i3 = readIntPreference(sharedPreferences, "backgroundlighting", 0);
            int i4 = readIntPreference(sharedPreferences, "particlecolor", 8);
            int parseInt = Integer.parseInt(sharedPreferences.getString("backgroundimagetype", "0"));
            setScale();
            this.mUnitNumberVal = i;
            setNumberOfUnit(i);
            setBrightness(i2);
            setLight(i3);
            this.mNumberOfParticles = 35;
            this.mNumberOfParticles2 = 25;
            this.mWaterDropsInJellyfish.setNumberOfParticles(this.mNumberOfParticles);
            this.mWaterDropsInJellyfish2.setNumberOfParticles(this.mNumberOfParticles2);
            setColorByColorValue(i4);
            setBackgroundImageType(parseInt);
        } else if (str.equals("unitscale")) {
            this.mUnitScaleVal = readIntPreference(sharedPreferences, str, 0);
            setScale();
        } else if (str.equals("unitnumber")) {
            int i5 = readIntPreference(sharedPreferences, str, 0);
            this.mUnitNumberVal = i5;
            setNumberOfUnit(i5);
        } else if (str.equals("backgroundbrightness")) {
            setBrightness(readIntPreference(sharedPreferences, str, 0));
        } else if (str.equals("backgroundlighting")) {
            setLight(readIntPreference(sharedPreferences, str, 0));
        } else if (str.equals("particlenumber")) {
            this.mNumberOfParticles = 35;
            this.mNumberOfParticles2 = 25;
            this.mWaterDropsInJellyfish.setNumberOfParticles(this.mNumberOfParticles);
            this.mWaterDropsInJellyfish2.setNumberOfParticles(this.mNumberOfParticles2);
        } else if (str.equals("particlecolor")) {
            setColorByColorValue(readIntPreference(sharedPreferences, str, 8));
        } else if (str.equals("backgroundimagetype")) {
            setBackgroundImageType(Integer.parseInt(sharedPreferences.getString(str, "0")));
        }
    }

    private static int readIntPreference(SharedPreferences prefs, String key, int defaultValue) {
        try {
            return prefs.getInt(key, defaultValue);
        } catch (ClassCastException e) {
            String value = prefs.getString(key, null);
            if (value == null) {
                return defaultValue;
            }
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException ex) {
                return defaultValue;
            }
        }
    }

    public void screenOn() {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.5f);
        GLES20.glClear(16640);
        setNumberOfUnit(this.mUnitNumberVal);
    }

    public void screenOff() {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.5f);
        GLES20.glClear(16640);
    }

    private void setNumberOfUnit(int i) {
        int maxNumberOfJellyfish = this.mJellyfishVO.getMaxNumberOfJellyfish();
        this.mBlurAlpha = 0.0f;
        this.mIsBluring = true;
        this.mTime = 0.0f;
        this.mBatteryTime = 0;
        this.mIsSendedReceiver = false;
        this.mIntervalManager.initList();
        this.mIntervalManager.setNotOccupiedList();
        this.mJellyfishVO.setNumberOfJellyfish(maxNumberOfJellyfish + i);
        this.mJellyfishVO.initList();
        this.mWaterDropsInJellyfish.initTimeCounter();
        this.mWaterDropsInJellyfish2.initTimeCounter();
        initListVO();
        if (this.mIsAllFinishedRemoving) {
            setFinishRemoving();
        }
        checkBlur();
    }

    private void setScale() {
        this.mScale = (this.mUnitScaleVal * 0.1f) + this.mDefaultScale;
    }

    private void setBrightness(int i) {
        if (i < 0) {
            this.mBrightness = ((i * 0.2f) / 5.0f) - 0.1f;
        } else if (i == 0) {
            this.mBrightness = -0.1f;
        } else {
            this.mBrightness = ((i * 0.3f) / 5.0f) - 0.1f;
        }
    }

    private void setLight(int i) {
        if (i < 0) {
            this.mLight = ((i * 0.52f) / 5.0f) + 0.22f;
        } else if (i == 0) {
            this.mLight = 0.22f;
        } else {
            this.mLight = ((i * 0.65f) / 5.0f) + 0.22f;
        }
    }

    private void setBackgroundImageType(int i) {
        if (i == 0) {
            this.mSea2.removeBackgroundImage();
            DeepSeaSettings.deleteBitmap(this.mBackgroundImagePath);
            this.mIsBackgroundChanged = true;
            this.mTempBackImageCount = 0;
        } else if (i == 1) {
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

    @Override // com.reandroid.wallpaper.deepsea.GLBaseView
    public void initByteBuffer() {
        this.mJellyfish.initByteBuffer();
        this.mJellyfish2.initByteBuffer();
        this.mBlur.initByteBuffer();
        this.mWaterDropsInJellyfish.initByteBuffer();
        this.mWaterDropsInJellyfish2.initByteBuffer();
        this.mBlurEffect.initByteBuffer();
        this.mBlurEffect2.initByteBuffer();
    }

    @Override // com.reandroid.wallpaper.deepsea.GLBaseView
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
            super.initShader();
        }
    }

    @Override // com.reandroid.wallpaper.deepsea.GLBaseView
    public void draw() {
        if (this.mTempBackImageCount == 0 && this.mIsBackgroundChanged) {
            this.mTempBackImageCount++;
            return;
        }
        GLES20.glClear(16640);
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

    @Override // com.reandroid.wallpaper.deepsea.GLBaseView
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

    public void setStateByBattery(int i) {
        this.mBatteryTime = 0;
        this.mIsSendedReceiver = false;
        switch (i) {
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
                this.mBatteryState = i;
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

    public void setProjectionMatrix(int i, int i2) {
        Display defaultDisplay = ((WindowManager) getContext().getSystemService("window")).getDefaultDisplay();
        this.mWidth = i;
        this.mHeight = i2;
        if (i <= 0 || i2 <= 0) {
            return;
        }
        this.mBatteryTime = 0;
        this.mIsSendedReceiver = false;
        GLES20.glViewport(0, 0, i, i2);
        DeepSeaSettings.setScale(i, i2);
        float f = (float) i / (float) i2;
        float f2 = -f;
        if (i > 720) {
            this.mDefaultScale = f;
        } else {
            this.mDefaultScale = 1.0f;
        }
        setScale();
        PositionVO.changePositions(defaultDisplay.getWidth(), defaultDisplay.getHeight());
        this.mIntervalManager.changeMove(defaultDisplay.getWidth(), defaultDisplay.getHeight());
        this.mIntervalManager.initList();
        this.mIntervalManager.setNotOccupiedList();
        this.mSea.changeDisplay(i, i2);
        this.mSea2.changeDisplay(i, i2);
        this.mSunshine.changeDisplay(i, i2);
        this.mSunshine2.changeDisplay(i, i2);
        this.mSunshine3.changeDisplay(i, i2);
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
        this.mSeaWaterDrops.changeData(i, i2);
        this.mSeaWaterDrops2.changeData(i, i2);
        this.mSeaWaterDrops3.changeData(i, i2);
        this.mSeaWaterDrops.resetData();
        this.mSeaWaterDrops2.resetData();
        this.mSeaWaterDrops3.resetData();
        this.mSeaWaterDrops.initShader();
        this.mSeaWaterDrops2.initShader();
        this.mSeaWaterDrops3.initShader();
        Matrix.frustumM(this.mProjectionMatrix, 0, f2, f, -1.0f, 1.0f, 1.0f, 10.0f);
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

    private void changeDestinationByIndex(int i) {
        IntervalVO notOccupiedVOByZUp;
        JellyfishVO vOByIndex = this.mJellyfishVO.getVOByIndex(i);
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
            float f3 = 1.0f - (((-(jellyfishVO.getPositionZ() + 3.0f)) * 1.0f) / 7.0f);
            if (f3 < 0.0f) {
                f3 = 0.0f;
            }
            float f4 = 1.0f - f3;
            if (isAppearing || isRemoving || isFinishedRemoving || !isFinishedAppearing) {
                f = f4 - reverseAlpha;
                f2 = f3 - reverseAlpha;
            } else {
                f = f4;
                f2 = f3;
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
            float f5 = 1.0f - (((-(positionZ + 3.0f)) * 1.0f) / 7.0f);
            if (f5 < 0.0f) {
                f5 = 0.0f;
            }
            if (isAppearing2 || isRemoving2 || isFinishedRemoving2 || !isFinishedAppearing2) {
                f5 -= 0.4f + reverseAlpha2;
            }
            this.mWaterDropsInJellyfish.setForDrawing();
            this.mWaterDropsInJellyfish.setAddAlpha(f5 - 1.0f);
            this.mWaterDropsInJellyfish.setScale(this.mScale);
            this.mWaterDropsInJellyfish.update(this.mMVPMatrix);
            this.mWaterDropsInJellyfish.draw();
            this.mWaterDropsInJellyfish2.setForDrawing();
            this.mWaterDropsInJellyfish2.setAddAlpha(f5 - 1.0f);
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

    private void updateForGoingToMoveByIndex(int i) {
        float f;
        float destinationZ;
        float f2;
        float f3;
        float f4;
        float f5 = 8.0E-4f;
        JellyfishVO vOByIndex = this.mJellyfishVO.getVOByIndex(i);
        float rotate = vOByIndex.getRotate();
        float destinationRotate = vOByIndex.getDestinationRotate();
        if (this.mBatteryState == 0) {
            f = 0.02f;
        } else {
            f = this.mBatteryState == 1 ? 0.002f : 8.0E-4f;
        }
        vOByIndex.setRotate((f * (destinationRotate - rotate)) + rotate);
        if (vOByIndex.isMovingZ()) {
            float positionX = vOByIndex.getPositionX();
            float positionY = vOByIndex.getPositionY();
            float positionZ = vOByIndex.getPositionZ();
            float movingZDestinationX = vOByIndex.getMovingZDestinationX();
            float movingZDestinationY = vOByIndex.getMovingZDestinationY();
            float movingZDestinationZ = vOByIndex.getMovingZDestinationZ();
            float movingZStartY = vOByIndex.getMovingZStartY();
            float f6 = 0.01f + ((positionY - movingZStartY) / (movingZDestinationY - movingZStartY));
            if (this.mBatteryState == 0) {
                f4 = vOByIndex.getResistance();
            } else if (this.mBatteryState == 1) {
                f4 = 600.0f;
            } else {
                f4 = 300.0f;
            }
            float f7 = (movingZDestinationZ - positionZ) / f4;
            f3 = positionX + (((movingZDestinationX - positionX) / f4) * f6);
            f2 = (((movingZDestinationY - positionY) / f4) * f6 * vOByIndex.getDisport()) + positionY;
            destinationZ = (f7 * f6) + positionZ;
        } else {
            if (this.mBatteryState == 0) {
                f5 = vOByIndex.getSpeed();
            } else if (this.mBatteryState == 1) {
                f5 = vOByIndex.getLowBatterySpeed();
            }
            float positionX2 = vOByIndex.getPositionX();
            float positionY2 = vOByIndex.getPositionY();
            float positionZ2 = vOByIndex.getPositionZ();
            float destinationX = vOByIndex.getDestinationX();
            float destinationY = vOByIndex.getDestinationY();
            destinationZ = positionZ2 + ((vOByIndex.getDestinationZ() - positionZ2) * f5);
            f2 = positionY2 + ((destinationY - positionY2) * f5);
            f3 = (f5 * (destinationX - positionX2)) + positionX2;
        }
        vOByIndex.setPosition(f3, f2, destinationZ);
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

    private void setColorByColorValue(int i) {
        float[] rgb = MathHelper.getRGB(Palette.mValuesOfColor[i]);
        float[] rgb2 = MathHelper.getRGB(-5789785);
        this.mBlurColor[0] = rgb[0] - rgb2[0];
        this.mBlurColor[1] = rgb[1] - rgb2[1];
        this.mBlurColor[2] = rgb[2] - rgb2[2];
        this.mBlur.setAddColor(this.mBlurColor[0], this.mBlurColor[1], this.mBlurColor[2], 0.0f);
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

class GLBaseView {
    private Context mContext;
    private boolean mIsInitShader = false;

    public GLBaseView() {
    }

    public GLBaseView(Context context) {
        this.mContext = context;
    }

    public void remove() {
        this.mContext = null;
    }

    public void initByteBuffer() {
    }

    public void initShader() {
        this.mIsInitShader = true;
    }

    public void setForDrawing() {
    }

    public void draw() {
    }

    public void update(float[] fArr) {
    }

    public boolean isInitShader() {
        return this.mIsInitShader;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public Context getContext() {
        return this.mContext;
    }
}

class GLHelper {
    /* JADX WARN: Removed duplicated region for block: B:10:0x0043 A[RETURN] */
    /* JADX WARN: Removed duplicated region for block: B:8:0x003b  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
    */
    public static int getCompiledShader(int i, String str) {
        int shader = GLES20.glCreateShader(i);
        if (shader == 0) {
            return 0;
        }
        GLES20.glShaderSource(shader, str);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, 35713, compiled, 0);
        if (compiled[0] == 0) {
            Log.e("Test", "Error compiling shader: " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    /* JADX WARN: Removed duplicated region for block: B:10:0x0046  */
    /* JADX WARN: Removed duplicated region for block: B:8:0x003e  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
    */
    public static int getCreatedAndLinkedProgram(int i, int i2) {
        int program = GLES20.glCreateProgram();
        if (program == 0) {
            return 0;
        }
        GLES20.glAttachShader(program, i);
        GLES20.glAttachShader(program, i2);
        GLES20.glLinkProgram(program);
        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, 35714, linkStatus, 0);
        if (linkStatus[0] == 0) {
            Log.e("Test", "Error compiling program: " + GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            GLES20.glDeleteShader(i);
            GLES20.glDeleteShader(i2);
            return 0;
        }
        return program;
    }

    public static int getTexture(Context context, int i) {
        Bitmap bitmap = null;
        int[] iArr = new int[1];
        int resolvedId = resolveResourceId(i);
        Log.d("DeepSea", "getTexture called with ID: " + i + " -> " + resolvedId);
        try {
            // 使用 BitmapFactory.decodeResource 来加载 drawable 目录中的资源
            bitmap = BitmapFactory.decodeResource(context.getResources(), resolvedId);
            if (bitmap != null) {
                Log.d("DeepSea", "Bitmap decoded successfully: " + bitmap.getWidth() + "x" + bitmap.getHeight());
            } else {
                Log.e("DeepSea", "BitmapFactory.decodeResource returned null for resource ID: " + resolvedId);
            }
            GLES20.glGenTextures(1, iArr, 0);
            GLES20.glBindTexture(3553, iArr[0]);
            GLES20.glTexParameterf(3553, 10240, 9729.0f);
            GLES20.glTexParameterf(3553, 10241, 9729.0f);
            if (bitmap != null) {
                GLUtils.texImage2D(3553, 0, bitmap, 0);
                Log.d("DeepSea", "Texture created with ID: " + iArr[0]);
            } else {
                Log.e("DeepSea", "Cannot create texture because bitmap is null");
            }
        } catch (Exception e) {
            Log.e("DeepSea", "Error loading texture: " + e.toString(), e);
        }
        if (bitmap != null) {
            bitmap.recycle();
        }
        Log.d("DeepSea", "Returning texture ID: " + iArr[0]);
        return iArr[0];
    }

    public static int getCompressedTexture(Context context, int i) {
        int[] iArr = new int[1];
        GLES20.glGenTextures(1, iArr, 0);
        GLES20.glBindTexture(3553, iArr[0]);
        GLES20.glTexParameterf(3553, 10240, 9729.0f);
        GLES20.glTexParameterf(3553, 10241, 9729.0f);
        GLES20.glTexParameterf(3553, 10242, 33071.0f);
        GLES20.glTexParameterf(3553, 10243, 33071.0f);
        InputStream openRawResource = context.getResources().openRawResource(resolveResourceId(i));
        try {
            try {
                ETC1Util.loadTexture(3553, 0, 0, 6407, 33635, openRawResource);
            } catch (IOException e) {
                Log.d("Test", "error compressing texture : " + e.toString());
                try {
                    openRawResource.close();
                } catch (IOException e2) {
                    Log.d("Test", "error closing input compressing texture : " + e2.toString());
                }
            }
            return iArr[0];
        } finally {
            try {
                openRawResource.close();
            } catch (IOException e3) {
                Log.d("Test", "error closing input compressing texture : " + e3.toString());
            }
        }
    }

    public static int getTextureByBitmap(Bitmap bitmap) {
        int[] iArr = new int[1];
        try {
            GLES20.glGenTextures(1, iArr, 0);
            GLES20.glBindTexture(3553, iArr[0]);
            GLES20.glTexParameterf(3553, 10240, 9729.0f);
            GLES20.glTexParameterf(3553, 10241, 9729.0f);
            GLUtils.texImage2D(3553, 0, bitmap, 0);
        } catch (Exception e) {
            Log.d("Test", e.toString() + ":" + e.getMessage() + ":" + e.getLocalizedMessage());
        }
        return iArr[0];
    }

    public static void deleteTextures(int i) {
        GLES20.glDeleteTextures(1, new int[]{i}, 0);
    }

    private static int resolveResourceId(int resId) {
        switch (resId) {
            case 2131034112:
                return R.drawable.bg_s512x512_opt;
            case 2131034113:
                return R.drawable.light_animation_0_s512x512_opt;
            case 2131034114:
                return R.drawable.light_animation_1_s512x512_opt;
            case 2131034115:
                return R.drawable.light_s512x512_opt;
            case 2131034116:
                return R.drawable.particle_mip_0;
            case 2131034117:
                return R.drawable.particle_mip_0_alpha;
            case 2131034118:
                return R.drawable.thumbnail;
            case 2131034119:
                return R.drawable.unit_a_blured_12_s256x256_mip_0;
            case 2131034120:
                return R.drawable.unit_a_blured_12_s256x256_mip_0_alpha;
            case 2131034121:
                return R.drawable.unit_a_core_glow_s256x256_eeee_mip_0;
            case 2131034122:
                return R.drawable.unit_a_core_glow_s256x256_eeee_mip_0_alpha;
            case 2131034123:
                return R.drawable.unit_a_s256x256_e_mip_0;
            case 2131034124:
                return R.drawable.unit_a_s256x256_e_mip_0_alpha;
            case 2131034125:
                return R.drawable.unit_b_blured_12_s256x256_mip_0;
            case 2131034126:
                return R.drawable.unit_b_blured_12_s256x256_mip_0_alpha;
            case 2131034127:
                return R.drawable.unit_b_core_glow_s256x256_e_mip_0;
            case 2131034128:
                return R.drawable.unit_b_core_glow_s256x256_e_mip_0_alpha;
            case 2131034129:
                return R.drawable.unit_b_s256x256_e_mip_0;
            case 2131034130:
                return R.drawable.unit_b_s256x256_e_mip_0_alpha;
            default:
                return resId;
        }
    }
}

class IntervalManager {
    private final float MOVE_X = -4.5f;
    private final float MOVE_Y = -8.1f;
    private final float INTERVAL = 1.8f;
    private final float MARGIN = 0.1f;
    private float mIntervalX = 1.8f;
    private float mIntervalY = 1.8f;
    private float mMarginX = 0.1f;
    private float mMarginY = 0.1f;
    private float mMoveX = -4.5f;
    private float mMoveY = -8.1f;
    private final int mDevideX = 5;
    private final int mDevideY = 9;
    private ArrayList<IntervalVO> mList = null;
    private ArrayList<IntervalVO> mNotOccupiedList = null;

    public void initList() {
        this.mList = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            int i2 = i % 5;
            int i3 = i % 9;
            float f = this.mIntervalX;
            float f2 = this.mMoveX;
            float f3 = this.mIntervalX;
            float f4 = this.mMoveX;
            float f5 = this.mIntervalY;
            float f6 = this.mMoveY;
            float f7 = this.mIntervalY;
            float f8 = this.mMoveY;
            IntervalVO intervalVO = new IntervalVO();
            intervalVO.setID(i);
            intervalVO.setMinX((i2 * f) + f2 + this.mMarginX);
            intervalVO.setMaxX((((i2 + 1) * f3) + f4) - this.mMarginX);
            intervalVO.setMinY(this.mMarginY + (i3 * f5) + f6);
            intervalVO.setMaxY(this.mMarginY + ((i3 + 1) * f7) + f8);
            this.mList.add(intervalVO);
        }
    }

    public void setIsOccupiedToFalseByIndex(int i) {
        IntervalVO intervalVO = this.mList.get(i);
        intervalVO.setIsOccupied(false);
        if (this.mNotOccupiedList == null) {
            this.mNotOccupiedList = new ArrayList<>();
        }
        this.mNotOccupiedList.add(intervalVO);
    }

    public void setNotOccupiedList() {
        int size = this.mList.size();
        this.mNotOccupiedList = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            IntervalVO intervalVO = this.mList.get(i);
            if (!intervalVO.isOccupied()) {
                this.mNotOccupiedList.add(intervalVO);
            }
        }
    }

    public IntervalVO getNotOccupiedVO() {
        if (this.mNotOccupiedList == null || this.mNotOccupiedList.isEmpty()) {
            return null;
        }
        int random = (int) (Math.random() * this.mNotOccupiedList.size());
        IntervalVO intervalVO = this.mNotOccupiedList.get(random);
        this.mNotOccupiedList.remove(random);
        return intervalVO;
    }

    public IntervalVO getNotOccupiedVOByZUp() {
        IntervalVO intervalVO;
        if (this.mNotOccupiedList == null || this.mNotOccupiedList.isEmpty()) {
            return null;
        }
        int size = this.mNotOccupiedList.size();
        int i = 0;
        while (true) {
            if (i >= size) {
                intervalVO = null;
                break;
            }
            intervalVO = this.mNotOccupiedList.get(i);
            int id = intervalVO.getID();
            int i2 = id % 9;
            if (id % 5 != 2 || i2 < 3 || i2 > 5) {
                i++;
            } else {
                this.mNotOccupiedList.remove(i);
                break;
            }
        }
        return intervalVO == null ? getNotOccupiedVO() : intervalVO;
    }

    public IntervalVO getNotOccupiedVOByZDown() {
        IntervalVO intervalVO;
        if (this.mNotOccupiedList == null || this.mNotOccupiedList.isEmpty()) {
            return null;
        }
        int size = this.mNotOccupiedList.size();
        for (int i = 0; i < size; i++) {
            intervalVO = this.mNotOccupiedList.get(i);
            int id = intervalVO.getID();
            int i2 = id % 9;
            if (id % 5 != 2 || i2 < 3 || i2 > 5) {
                this.mNotOccupiedList.remove(i);
                break;
            }
        }
        intervalVO = null;
        return intervalVO == null ? getNotOccupiedVO() : intervalVO;
    }

    public void changeMove(int i, int i2) {
        if (i > 720 || i2 > 1280) {
            float f = (i / 720.0f) * 2.0f;
            this.mMoveX = (-4.5f) * f;
            this.mIntervalX = 1.8f * f;
            this.mMarginX = f * 0.1f;
            float f2 = (i2 / 1280.0f) * 2.0f;
            this.mMoveY = (-8.1f) * f2;
            this.mIntervalY = 1.8f * f2;
            this.mMarginY = f2 * 0.1f;
            return;
        }
        this.mMoveX = -4.5f;
        this.mIntervalX = 1.8f;
        this.mMarginX = 0.1f;
        this.mMoveY = -8.1f;
        this.mIntervalY = 1.8f;
        this.mMarginY = 0.1f;
    }
}

class IntervalVO {
    private int mID = -1;
    private float mMinX = 0.0f;
    private float mMaxX = 0.0f;
    private float mMinY = 0.0f;
    private float mMaxY = 0.0f;
    private boolean mIsOccupied = false;

    public void setID(int i) {
        this.mID = i;
    }

    public int getID() {
        return this.mID;
    }

    public void setMinX(float f) {
        this.mMinX = f;
    }

    public float getMinX() {
        return this.mMinX;
    }

    public void setMaxX(float f) {
        this.mMaxX = f;
    }

    public float getMaxX() {
        return this.mMaxX;
    }

    public void setMinY(float f) {
        this.mMinY = f;
    }

    public float getMinY() {
        return this.mMinY;
    }

    public void setMaxY(float f) {
        this.mMaxY = f;
    }

    public float getMaxY() {
        return this.mMaxY;
    }

    public void setIsOccupied(boolean z) {
        this.mIsOccupied = z;
    }

    public boolean isOccupied() {
        return this.mIsOccupied;
    }
}

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

class Jellyfish2 extends Jellyfish {
    public Jellyfish2() {
    }

    public Jellyfish2(Context context) {
        super(context);
    }

    @Override // com.reandroid.wallpaper.deepsea.Jellyfish
    protected int getTextureId() {
        return GLHelper.getTexture(getContext(), R.drawable.unit_b_s256x256_e_mip_0);
    }

    @Override // com.reandroid.wallpaper.deepsea.Jellyfish
    protected int getAlphaTextureId() {
        return GLHelper.getTexture(getContext(), R.drawable.unit_b_s256x256_e_mip_0_alpha);
    }
}

class JellyfishVO {
    private ArrayList<JellyfishVO> mAList;
    private ArrayList<JellyfishVO> mBList;
    private int mNumberOfJellyfish;
    private int mNumberOfParticle;
    private float mScaleOfJellyfish;
    private float mValueOfLight;
    private ArrayList<JellyfishVO> mList = null;
    private int mMaxNumberOfJellyfish = 10;
    private int mMaxNumberOfParticle = 200;
    private float[] mBlurColor = {0.0f, 0.0f, 1.0f, 1.0f};
    private float mRotate = 0.0f;
    private float mDestinationRotate = 0.0f;
    private float mPositionX = 0.0f;
    private float mPositionY = 0.0f;
    private float mPositionZ = 0.0f;
    private float mDestinationX = 0.0f;
    private float mDestinationY = 0.0f;
    private float mDestinationZ = 0.0f;
    private float mStartPositionX = 0.0f;
    private float mStartPositionY = 0.0f;
    private float mStartPositionZ = 0.0f;
    private float mMovingZDestinationX = 0.0f;
    private float mMovingZDestinationY = 0.0f;
    private float mMovingZDestinationZ = 0.0f;
    private float mMovingZStartX = 0.0f;
    private float mMovingZStartY = 0.0f;
    private float mMovingZStartZ = 0.0f;
    private float mDisport = 5.0f;
    private float mResistance = 120.0f;
    private float mShakingX = 0.0f;
    private float mShakingY = 0.0f;
    private float mShakingZ = 0.0f;
    private float mMaxDestinationX = 0.0f;
    private float mMinDestinationX = 0.0f;
    private float mMaxDestinationY = 0.0f;
    private float mMinDestinationY = 0.0f;
    private float mMaxDestinationZ = 0.0f;
    private float mMinDestinationZ = 0.0f;
    private float mAlpha = 1.0f;
    private boolean mIsRemoving = false;
    private boolean mIsAppearing = false;
    private boolean mIsRotating = false;
    private boolean mIsMovingZ = false;
    private boolean mHasBlur = false;
    private float mStartTime = 0.0f;
    private float mBlurTime = 0.0f;
    private int mIndexOfInterval = -1;
    private boolean mIsShakeUp = false;
    private boolean mIsShakeLeft = false;
    private boolean mIsShaking = false;
    private float mForceX = 0.0f;
    private float mForceY = 0.0f;
    private float mShakingR = 0.0f;
    private float mShakingO = 0.0f;
    private float mShakingAngle = 0.0f;
    private float mSpeed = 0.003f;
    private float mReverseAlpha = 1.0f;
    private boolean mIsFinishedAppearing = false;
    private boolean mIsFinishedRemoving = false;
    private final float mLowBatteryDuration = 50000.0f;
    private final float mLowBatteryDurationZ = 2000.0f;
    private final float mLowBatterySpeed = 0.002f;
    private int mABDivide = 0;

    public JellyfishVO() {
        this.mNumberOfJellyfish = 0;
        this.mNumberOfParticle = 0;
        this.mScaleOfJellyfish = 1.0f;
        this.mValueOfLight = 1.0f;
        this.mNumberOfJellyfish = 11;
        this.mNumberOfParticle = 200;
        this.mScaleOfJellyfish = 1.0f;
        this.mValueOfLight = 1.0f;
    }

    public void initList() {
        if (this.mList != null) {
            this.mList = null;
        }
        this.mList = new ArrayList<>();
        int i = this.mNumberOfJellyfish;
        for (int i2 = 0; i2 < i; i2++) {
            JellyfishVO jellyfishVO = new JellyfishVO();
            jellyfishVO.setPosition(0.0f, 0.9f - (i2 * 0.21f), -0.5f);
            if (i2 < i * 0.5d) {
                jellyfishVO.setIsShakeUp(true);
            }
            this.mList.add(jellyfishVO);
        }
    }

    public void initMovingZ() {
        int size = this.mList.size();
        for (int i = 0; i < size; i++) {
            this.mList.get(i).setIsMovingZ(false);
        }
    }

    public void setNumberOfJellyfish(int i) {
        this.mNumberOfJellyfish = i;
    }

    public int getNumberOfJellyfish() {
        return this.mNumberOfJellyfish;
    }

    public JellyfishVO getVOByIndex(int i) {
        return this.mList.get(i);
    }

    public JellyfishVO getRandomVOByZDown() {
        ArrayList<JellyfishVO> listByZDown = getListByZDown();
        int size = listByZDown.size();
        if (size % 2 != 0) {
            size--;
        }
        int random = (int) (Math.random() * size);
        if (size <= 0) {
            return null;
        }
        return listByZDown.get(random);
    }

    public JellyfishVO getRandomVOByZUp() {
        ArrayList<JellyfishVO> listByZUp = getListByZUp();
        int size = listByZUp.size();
        if (size % 2 != 0) {
            size--;
        }
        int random = (int) (Math.random() * size);
        if (size <= 0) {
            return null;
        }
        return listByZUp.get(random);
    }

    private ArrayList<JellyfishVO> getListByZDown() {
        int size = this.mList.size();
        ArrayList<JellyfishVO> arrayList = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            JellyfishVO jellyfishVO = this.mList.get(i);
            if (jellyfishVO.getPositionZ() < -6.0f) {
                arrayList.add(jellyfishVO);
            }
        }
        return arrayList;
    }

    private ArrayList<JellyfishVO> getListByZUp() {
        int size = this.mList.size();
        ArrayList<JellyfishVO> arrayList = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            JellyfishVO jellyfishVO = this.mList.get(i);
            if (jellyfishVO.getPositionZ() >= -6.0f) {
                arrayList.add(jellyfishVO);
            }
        }
        return arrayList;
    }

    public int getMaxNumberOfJellyfish() {
        return this.mMaxNumberOfJellyfish;
    }

    public boolean isAllFinishedAppearing() {
        boolean z = true;
        int i = this.mNumberOfJellyfish;
        int i2 = 0;
        while (i2 < i) {
            boolean z2 = !this.mList.get(i2).isFinishedAppearing() ? false : z;
            i2++;
            z = z2;
        }
        return z;
    }

    public boolean isAllFinishedRemoving(int i) {
        int i2 = this.mNumberOfJellyfish;
        int i3 = 0;
        int i4 = 0;
        while (i4 < i2) {
            int i5 = this.mList.get(i4).isFinishedRemoving() ? i3 + 1 : i3;
            i4++;
            i3 = i5;
        }
        return i3 >= i;
    }

    public void setABList() {
        int size = this.mList.size();
        this.mABDivide = (size * 8) / 10;
        this.mAList = new ArrayList<>();
        this.mBList = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            if (i < this.mABDivide) {
                this.mAList.add(this.mList.get(i));
            } else {
                this.mBList.add(this.mList.get(i));
            }
        }
    }

    public ArrayList<JellyfishVO> getAList() {
        return this.mAList;
    }

    public ArrayList<JellyfishVO> getBList() {
        return this.mBList;
    }

    public int getABDivide() {
        return this.mABDivide;
    }

    public void setPosition(float f, float f2, float f3) {
        this.mPositionX = f;
        this.mPositionY = f2;
        this.mPositionZ = f3;
    }

    public float getPositionX() {
        return this.mPositionX;
    }

    public float getPositionY() {
        return this.mPositionY;
    }

    public float getPositionZ() {
        return this.mPositionZ;
    }

    public void setDestination(float f, float f2, float f3) {
        this.mDestinationX = f;
        this.mDestinationY = f2;
        this.mDestinationZ = f3;
    }

    public float getDestinationX() {
        return this.mDestinationX;
    }

    public float getDestinationY() {
        return this.mDestinationY;
    }

    public float getDestinationZ() {
        return this.mDestinationZ;
    }

    public void setStartPosition(float f, float f2, float f3) {
        this.mStartPositionX = f;
        this.mStartPositionY = f2;
        this.mStartPositionZ = f3;
    }

    public float getStartPositionX() {
        return this.mStartPositionX;
    }

    public float getStartPositionY() {
        return this.mStartPositionY;
    }

    public void setMovingZDestination(float f, float f2, float f3) {
        this.mMovingZDestinationX = f;
        this.mMovingZDestinationY = f2;
        this.mMovingZDestinationZ = f3;
    }

    public float getMovingZDestinationX() {
        return this.mMovingZDestinationX;
    }

    public float getMovingZDestinationY() {
        return this.mMovingZDestinationY;
    }

    public float getMovingZDestinationZ() {
        return this.mMovingZDestinationZ;
    }

    public void setMovingZStartPosition(float f, float f2, float f3) {
        this.mMovingZStartX = f;
        this.mMovingZStartY = f2;
        this.mMovingZStartZ = f3;
    }

    public float getMovingZStartX() {
        return this.mMovingZStartX;
    }

    public float getMovingZStartY() {
        return this.mMovingZStartY;
    }

    public void setShakingX(float f) {
        this.mShakingX = f;
    }

    public void setRotate(float f) {
        this.mRotate = f;
    }

    public float getRotate() {
        return this.mRotate;
    }

    public void setDestinationRotate(float f) {
        this.mDestinationRotate = f;
    }

    public float getDestinationRotate() {
        return this.mDestinationRotate;
    }

    public void setIsRemoving(boolean z) {
        this.mIsRemoving = z;
    }

    public boolean isRemoving() {
        return this.mIsRemoving;
    }

    public void setIsAppearing(boolean z) {
        this.mIsAppearing = z;
    }

    public boolean isAppearing() {
        return this.mIsAppearing;
    }

    public void setIsMovingZ(boolean z) {
        this.mIsMovingZ = z;
    }

    public boolean isMovingZ() {
        if (this.mIsMovingZ && this.mPositionZ <= this.mMovingZDestinationZ + 0.01f && this.mPositionZ >= this.mMovingZDestinationZ - 0.01f) {
            this.mIsMovingZ = false;
            this.mDestinationZ = this.mMovingZDestinationZ;
            if (this.mMovingZDestinationX < 0.0f) {
                this.mDestinationX = this.mMovingZDestinationX;
            } else {
                this.mDestinationX = this.mMovingZDestinationX;
            }
            if (this.mMovingZDestinationY < 0.0f) {
                this.mDestinationY = this.mMovingZDestinationY;
            } else {
                this.mDestinationY = this.mMovingZDestinationY;
            }
        }
        return this.mIsMovingZ;
    }

    public void setHasBlur(boolean z) {
        this.mHasBlur = z;
    }

    public boolean hasBlur() {
        return this.mHasBlur;
    }

    public void setStartTime(float f) {
        this.mStartTime = f;
    }

    public void setIndexOfInterval(int i) {
        this.mIndexOfInterval = i;
    }

    public int getIndexOfInterval() {
        return this.mIndexOfInterval;
    }

    public boolean isShakeUp() {
        return this.mIsShakeUp;
    }

    public void setIsShakeUp(boolean z) {
        this.mIsShakeUp = z;
    }

    public void setForceX(float f) {
        this.mForceX = f;
    }

    public float getForceX() {
        return this.mForceX;
    }

    public void setForceY(float f) {
        this.mForceY = f;
    }

    public float getForceY() {
        return this.mForceY;
    }

    public void setIsShaking(boolean z) {
        this.mIsShaking = z;
    }

    public boolean isShaking() {
        return this.mIsShaking;
    }

    public void setShakingR(float f) {
        this.mShakingR = f;
    }

    public void setShakingO(float f) {
        this.mShakingO = f;
    }

    public void setShakingAngle(float f) {
        this.mShakingAngle = f;
    }

    public void setSpeed(float f) {
        this.mSpeed = f;
    }

    public float getSpeed() {
        return this.mSpeed;
    }

    public void setReverseAlpha(float f) {
        this.mReverseAlpha = f;
    }

    public float getReverseAlpha() {
        return this.mReverseAlpha;
    }

    public void setIsFinishedAppearing(boolean z) {
        this.mIsFinishedAppearing = z;
    }

    public boolean isFinishedAppearing() {
        return this.mIsFinishedAppearing;
    }

    public void setIsFinishedRemoving(boolean z) {
        this.mIsFinishedRemoving = z;
    }

    public boolean isFinishedRemoving() {
        return this.mIsFinishedRemoving;
    }

    public float getLowBatterySpeed() {
        return 0.002f;
    }

    public float getDisport() {
        return this.mDisport;
    }

    public float getResistance() {
        return this.mResistance;
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
            int createdAndLinkedProgram = GLHelper.getCreatedAndLinkedProgram(GLHelper.getCompiledShader(35633, "uniform mat4 u_MVPMatrix;\t\t\t\t\t\t\t\nattribute vec4 a_position;\t\t\t\t\t\t\t\nattribute vec2 a_texCoord;\t\t\t\t\t\t\t\nvarying vec2 v_texCoord;\t\t\t\t\t\t\t\nvoid main(){\t\t\t\t\t\t\t\t\t\t\n\tgl_Position = u_MVPMatrix * a_position;\t\t\t\n\tv_texCoord = a_texCoord;\t\t\t\t\t\t\n}"), GLHelper.getCompiledShader(35632, "precision mediump float;\t\t\t\t\t\t\t\nuniform sampler2D s_texture;\t\t\t\t\t\t\nuniform float u_Light;\t\t\t\t\t\t\t\t\nuniform float u_AddAlpha;\t\t\t\t\t\t\t\nvarying vec2 v_texCoord;\t\t\t\t\t\t\t\nvoid main(){\t\t\t\t\t\t\t\t\t\t\n\tfloat val = u_Light;\t\t\t\t\t\t\t\n\tvec4 addColor = vec4(val, val, val, u_AddAlpha);\t\t\n\tgl_FragColor = texture2D(s_texture, v_texCoord) + addColor; \n}\t\t\t\t\t\t\t\t\t\t\t\t\t\n"));
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
        GLES20.glVertexAttribPointer(this.mPositionHandle, 3, 5126, false, 20, (Buffer) this.mVertices);
        this.mVertices.position(3);
        GLES20.glVertexAttribPointer(this.mTexCoordHandle, 2, 5126, false, 20, (Buffer) this.mVertices);
        GLES20.glEnableVertexAttribArray(this.mPositionHandle);
        GLES20.glEnableVertexAttribArray(this.mTexCoordHandle);
        GLES20.glActiveTexture(33984);
        GLES20.glBindTexture(3553, this.mTextureId);
        GLES20.glEnable(3553);
        GLES20.glUniform1i(this.mTextureHandle, 0);
        GLES20.glUniform1f(this.mAddAlphaHandle, this.mAddAlpha);
        GLES20.glUniform1f(this.mLightHandle, this.mLight);
    }

    @Override // com.reandroid.wallpaper.deepsea.GLBaseView
    public void draw() {
        Log.d("DeepSea", "Sea.draw() executing - about to call glDrawElements");
        GLES20.glDrawElements(4, 6, 5123, this.mIndices);
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

class Sunshine2 extends Sunshine {
    public Sunshine2() {
    }

    public Sunshine2(Context context) {
        super(context);
    }

    @Override // com.reandroid.wallpaper.deepsea.Sunshine
    protected int getTextureId() {
        return GLHelper.getTexture(getContext(), R.drawable.light_animation_0_s512x512_opt);
    }
}

class Sunshine3 extends Sunshine {
    public Sunshine3() {
    }

    public Sunshine3(Context context) {
        super(context);
    }

    @Override // com.reandroid.wallpaper.deepsea.Sunshine
    protected int getTextureId() {
        return GLHelper.getTexture(getContext(), R.drawable.light_animation_1_s512x512_opt);
    }
}

class MathHelper {
    MathHelper() {
    }

    public static float getRandomFloat(float f, float f2) {
        return (((float) Math.random()) * (f2 - f)) + f;
    }

    public static int getIndexForFragmentShader(int i, int i2) {
        if (i < i2 - 1) {
            return i + 1;
        }
        return 0;
    }

    public static float[] getRGB(int i) {
        return new float[]{((i >> 16) & 255) / 255.0f, ((i >> 8) & 255) / 255.0f, (i & 255) / 255.0f};
    }
}

class PositionVO {
    private static float[] mRandomPositions;
    private static float[] mPositions = {-3.0f, -0.88f, 0.88f, -2.0f, 1.8f, -4.0f, -1.46f, 1.46f, -3.0f, 2.72f, -5.0f, -2.0f, 2.0f, -4.0f, 3.64f, -6.0f, -2.6f, 2.6f, -5.0f, 4.58f, -7.0f, -3.13f, 3.13f, -6.0f, 5.5f, -8.0f, -3.7f, 3.7f, -7.0f, 6.4f, -9.0f, -4.25f, 4.25f, -8.0f, 7.3f, -10.0f, -4.8f, 4.8f, -9.0f, 8.25f};
    private static final float[] mDefaultPositions = {-3.0f, -0.88f, 0.88f, -2.0f, 1.8f, -4.0f, -1.46f, 1.46f, -3.0f, 2.72f, -5.0f, -2.0f, 2.0f, -4.0f, 3.64f, -6.0f, -2.6f, 2.6f, -5.0f, 4.58f, -7.0f, -3.13f, 3.13f, -6.0f, 5.5f, -8.0f, -3.7f, 3.7f, -7.0f, 6.4f, -9.0f, -4.25f, 4.25f, -8.0f, 7.3f, -10.0f, -4.8f, 4.8f, -9.0f, 8.25f};

    PositionVO() {
    }

    public static void changePositions(int i, int i2) {
        if (i > 720 || i2 > 1280) {
            float f = (i / 720.0f) * 2.0f;
            float[] fArr = mPositions;
            float[] fArr2 = mDefaultPositions;
            float f2 = fArr2[2];
            fArr[1] = (-(f2 - fArr2[1])) * f;
            fArr[2] = (f2 - fArr2[1]) * f;
            float f3 = fArr2[7];
            fArr[6] = (-(f3 - fArr2[6])) * f;
            fArr[7] = (f3 - fArr2[6]) * f;
            float f4 = fArr2[12];
            fArr[11] = (-(f4 - fArr2[11])) * f;
            fArr[12] = (f4 - fArr2[11]) * f;
            float f5 = fArr2[17];
            fArr[16] = (-(f5 - fArr2[16])) * f;
            fArr[17] = (f5 - fArr2[16]) * f;
            float f6 = fArr2[22];
            fArr[21] = (-(f6 - fArr2[21])) * f;
            fArr[22] = (f6 - fArr2[21]) * f;
            float f7 = fArr2[27];
            fArr[26] = (-(f7 - fArr2[26])) * f;
            fArr[27] = (f7 - fArr2[26]) * f;
            float f8 = fArr2[32];
            fArr[31] = (-(f8 - fArr2[31])) * f;
            fArr[32] = (f8 - fArr2[31]) * f;
            float f9 = fArr2[37];
            fArr[36] = (-(f9 - fArr2[36])) * f;
            fArr[37] = (f9 - fArr2[36]) * f;
            float f22 = (i2 / 1280.0f) * 2.0f;
            float f10 = fArr2[4];
            fArr[3] = (-(f10 - fArr2[3])) * f22;
            fArr[4] = (f10 - fArr2[3]) * f22;
            float f11 = fArr2[9];
            fArr[8] = (-(f11 - fArr2[8])) * f22;
            fArr[9] = (f11 - fArr2[8]) * f22;
            float f12 = fArr2[14];
            fArr[13] = (-(f12 - fArr2[13])) * f22;
            fArr[14] = (f12 - fArr2[13]) * f22;
            float f13 = fArr2[19];
            fArr[18] = (-(f13 - fArr2[18])) * f22;
            fArr[19] = (f13 - fArr2[18]) * f22;
            float f14 = fArr2[24];
            fArr[23] = (-(f14 - fArr2[23])) * f22;
            fArr[24] = (f14 - fArr2[23]) * f22;
            float f15 = fArr2[29];
            fArr[28] = (-(f15 - fArr2[28])) * f22;
            fArr[29] = (f15 - fArr2[28]) * f22;
            float f16 = fArr2[34];
            fArr[33] = (-(f16 - fArr2[33])) * f22;
            fArr[34] = (f16 - fArr2[33]) * f22;
            float f17 = fArr2[39];
            fArr[38] = (-(f17 - fArr2[38])) * f22;
            fArr[39] = (f17 - fArr2[38]) * f22;
            return;
        }
        float[] fArr3 = mPositions;
        float[] fArr4 = mDefaultPositions;
        fArr3[1] = fArr4[1];
        fArr3[2] = fArr4[2];
        fArr3[6] = fArr4[6];
        fArr3[7] = fArr4[7];
        fArr3[11] = fArr4[11];
        fArr3[12] = fArr4[12];
        fArr3[16] = fArr4[16];
        fArr3[17] = fArr4[17];
        fArr3[21] = fArr4[21];
        fArr3[22] = fArr4[22];
        fArr3[26] = fArr4[26];
        fArr3[27] = fArr4[27];
        fArr3[31] = fArr4[31];
        fArr3[32] = fArr4[32];
        fArr3[36] = fArr4[36];
        fArr3[37] = fArr4[37];
        fArr3[3] = fArr4[3];
        fArr3[4] = fArr4[4];
        fArr3[8] = fArr4[8];
        fArr3[9] = fArr4[9];
        fArr3[13] = fArr4[13];
        fArr3[14] = fArr4[14];
        fArr3[18] = fArr4[18];
        fArr3[19] = fArr4[19];
        fArr3[23] = fArr4[23];
        fArr3[24] = fArr4[24];
        fArr3[28] = fArr4[28];
        fArr3[29] = fArr4[29];
        fArr3[33] = fArr4[33];
        fArr3[34] = fArr4[34];
        fArr3[38] = fArr4[38];
        fArr3[39] = fArr4[39];
    }

    public static float[] getMinMaxXYByZ(float f) {
        float f2 = f <= -2.0f ? f : -2.0f;
        float f3 = f2 >= -10.0f ? f2 : -10.0f;
        int length = mPositions.length / 5;
        int i = 0;
        float f4 = 0.0f;
        float f5 = 0.0f;
        float f6 = 0.0f;
        float f7 = 0.0f;
        while (true) {
            if (i >= length) {
                break;
            }
            int i2 = i * 5;
            if (i == length - 1) {
                float[] fArr = mPositions;
                f6 = fArr[i2 + 1];
                f4 = fArr[i2 + 2];
                f7 = fArr[i2 + 3];
                f5 = fArr[i2 + 4];
            } else {
                float[] fArr2 = mPositions;
                if (f3 > fArr2[i2 + 5]) {
                    f6 = fArr2[i2 + 1];
                    f4 = fArr2[i2 + 2];
                    f7 = fArr2[i2 + 3];
                    f5 = fArr2[i2 + 4];
                    break;
                }
            }
            i++;
        }
        return new float[]{f6, f4, f7, f5};
    }

    public static void initRandomPositions() {
        mRandomPositions = null;
        mRandomPositions = new float[80];
        for (int i = 0; i < 8; i++) {
            int i2 = i * 5 * 2;
            float[] randomPositionByZindex = getRandomPositionByZindex(i);
            float f = randomPositionByZindex[0];
            float f2 = randomPositionByZindex[1];
            float f3 = randomPositionByZindex[2];
            float f4 = randomPositionByZindex[3];
            float f5 = randomPositionByZindex[4];
            float[] fArr = mRandomPositions;
            fArr[i2] = f;
            fArr[i2 + 1] = f2;
            fArr[i2 + 2] = f3;
            fArr[i2 + 3] = f4;
            fArr[i2 + 4] = f5;
            float f6 = randomPositionByZindex[5];
            float f7 = randomPositionByZindex[6];
            float f8 = randomPositionByZindex[7];
            float f9 = randomPositionByZindex[8];
            fArr[i2 + 5] = f;
            fArr[i2 + 6] = f6;
            fArr[i2 + 7] = f7;
            fArr[i2 + 8] = f8;
            fArr[i2 + 9] = f9;
        }
    }

    public static float getZByIndex(int i) {
        return getZs()[i];
    }

    public static int getNumberOfZs() {
        return 8;
    }

    private static float[] getZs() {
        float[] fArr = new float[8];
        int length = mPositions.length / 5;
        for (int i = 0; i < length; i++) {
            fArr[i] = mPositions[i * 5];
        }
        return fArr;
    }  private static float[] getRandomPositionByZindex(int i) {
        char c;
        float f;
        float f2;
        float f3;
        float f4;
        float f5;
        float f6;
        float f7;
        float f8;
        float f9 = mPositions[i * 5];
        int random = (int) (Math.random() * 4.0d);
        float[] minMaxXYByZ = getMinMaxXYByZ(f9);
        float f10 = minMaxXYByZ[0];
        float f11 = minMaxXYByZ[1];
        float f12 = minMaxXYByZ[2];
        float f13 = minMaxXYByZ[3];
        if (random == 0) {
            c = 3;
            f = 0.0f;
            f2 = f10 - 0.2f;
            f3 = f13 + 0.2f;
            f4 = 0.2f;
        } else if (random == 1) {
            c = 2;
            f = f12 - 0.2f;
            f2 = f10 - 0.2f;
            f3 = 0.0f;
            f4 = 0.2f;
        } else if (random == 2) {
            c = 1;
            f = 0.0f;
            f2 = 0.2f;
            f3 = f13 + 0.2f;
            f4 = f11 + 0.2f;
        } else {
            c = 0;
            f = f12 - 0.2f;
            f2 = 0.2f;
            f3 = 0.0f;
            f4 = f11 + 0.2f;
        }
        if (c == 0) {
            f5 = f10 - 0.2f;
            f6 = 0.2f;
            f7 = 0.0f;
            f8 = 0.2f + f13;
        } else if (c == 1) {
            f5 = f10 - 0.2f;
            f6 = 0.2f;
            f7 = f12 - 0.2f;
            f8 = 0.0f;
        } else if (c == 2) {
            f5 = 0.2f;
            f6 = f11 + 0.2f;
            f7 = 0.0f;
            f8 = 0.2f + f13;
        } else {
            f5 = 0.2f;
            f6 = f11 + 0.2f;
            f7 = f12 - 0.2f;
            f8 = 0.0f;
        }
        return new float[]{f9, f2, f4, f, f3, f5, f6, f7, f8};
    }
}

class Palette {
    public static final int[] mValuesOfColor = {-1, -1237980, -891614, -551907, -3584, -7485889, -16734639, -16733795, -12847618, -16747844, -16755546, -13749870, -10080879, -7198833, -1310580};
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

    @Override // com.reandroid.wallpaper.deepsea.GLBaseView
    public void remove() {
        GLHelper.deleteTextures(this.mTextureID);
        GLHelper.deleteTextures(this.mAlphaTextureId);
        super.remove();
    }
}

