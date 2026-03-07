/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.reandroid.wallpaper.galaxy;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.reandroid.wallpaper.R;
import com.reandroid.wallpaper.gles.GLESScene;
import com.reandroid.wallpaper.gles.RawResourceLoader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Random;

/**
 * 星系动态壁纸 - 从RenderScript完全移植到OpenGL ES 2.0
 * 100%还原视觉效果，包含12000个在椭圆轨道运行的粒子
 */
public class GalaxyGL extends GLESScene {
    // 日志标签
    private static final String TAG = "GalaxyGL";
    
    // 粒子数量相关常量（源自原始galaxy.rs）
    private static final int DEFAULT_PARTICLE_COUNT = 12000;  // 默认粒子数量
    private static final int MIN_PARTICLE_COUNT = 1000;       // 最小粒子数量
    private static final int MAX_PARTICLE_COUNT = 20000;      // 最大粒子数量
    private int mParticleCount = DEFAULT_PARTICLE_COUNT;      // 当前粒子数量
    private static final int DEFAULT_PARTICLE_ALPHA_PERCENT = 100;
    private static final int MIN_PARTICLE_ALPHA_PERCENT = 10;
    private static final int MAX_PARTICLE_ALPHA_PERCENT = 100;
    private int mParticleAlphaPercent = DEFAULT_PARTICLE_ALPHA_PERCENT;
    
    // 椭圆轨道相关常量（源自原始galaxy.rs）
    private static final float ELLIPSE_RATIO = 0.892f;        // 椭圆比例
    private static final float ELLIPSE_TWIST = 0.023333333f;  // 椭圆扭曲系数
    private static final float PI = 3.1415f;                  // 圆周率近似值
    private static final float TWO_PI = 6.283f;               // 2π
    private static final int GALAXY_RADIUS = 300;             // 星系半径

    // 精确星系参数（依据M31观测值）
    private static final float PITCH_ANGLE_DEG = 6.7f;
    private static final float PITCH_ANGLE_RAD = PITCH_ANGLE_DEG * PI / 180.0f;
    private static final float FORBIDDEN_RADIUS_KPC = 2.82f;
    private static final float GALAXY_RADIUS_KPC = 25.0f;
    private static final int ARM_COUNT = 2;
    private static final float ARM_OFFSET = PI;
    private static final float PRECISE_INNER_SCATTER = 1.35f;
    private static final float PRECISE_OUTER_SCATTER = 0.75f;
    private static final float PRECISE_TURBULENCE = 0.28f;
    private static final float PRECISE_PATTERN_SPEED = 0.0018f;

    private static final int DEFAULT_ARM_COUNT = 2;
    private static final float DEFAULT_ARM_OFFSET = PI;
    private static final float DEFAULT_PITCH_ANGLE_DEG = 6.7f;
    private static final float DEFAULT_FORBIDDEN_RADIUS_KPC = 2.82f;
    private static final float DEFAULT_PRECISE_INNER_SCATTER = 1.35f;
    private static final float DEFAULT_PRECISE_OUTER_SCATTER = 0.75f;
    private static final float DEFAULT_PRECISE_TURBULENCE = 0.28f;
    private static final float DEFAULT_ELLIPSE_RATIO = ELLIPSE_RATIO;
    private static final float DEFAULT_ELLIPSE_TWIST = ELLIPSE_TWIST;

    private static final int MIN_ARM_COUNT = 1;
    private static final int MAX_ARM_COUNT = 6;

    private static final float MIN_ARM_OFFSET = 0.0f;
    private static final float MAX_ARM_OFFSET = TWO_PI;
    private static final float MIN_PITCH_ANGLE_DEG = 0.0f;
    private static final float MAX_PITCH_ANGLE_DEG = 30.0f;
    private static final float MIN_FORBIDDEN_RADIUS_KPC = 0.0f;
    private static final float MAX_FORBIDDEN_RADIUS_KPC = 10.0f;
    private static final float MIN_SCATTER = 0.0f;
    private static final float MAX_SCATTER = 5.0f;
    private static final float MIN_TURBULENCE = 0.0f;
    private static final float MAX_TURBULENCE = 2.0f;
    private static final float MIN_ELLIPSE_RATIO = 0.5f;
    private static final float MAX_ELLIPSE_RATIO = 1.0f;
    private static final float MIN_ELLIPSE_TWIST = -0.1f;
    private static final float MAX_ELLIPSE_TWIST = 0.1f;

    private static final String KEY_ARM_COUNT = "galaxy_arm_count";
    private static final String KEY_ARM_OFFSET = "galaxy_arm_offset";
    private static final String KEY_PITCH_ANGLE_DEG = "galaxy_pitch_angle_deg";
    private static final String KEY_INNER_SCATTER = "galaxy_inner_scatter";
    private static final String KEY_OUTER_SCATTER = "galaxy_outer_scatter";
    private static final String KEY_TURBULENCE = "galaxy_turbulence";
    private static final String KEY_FORBIDDEN_RADIUS = "galaxy_forbidden_radius";
    private static final String KEY_ELLIPSE_RATIO = "galaxy_ellipse_ratio";
    private static final String KEY_ELLIPSE_TWIST = "galaxy_ellipse_twist";
    private static final int ELLIPSE_TWIST_STORAGE_OFFSET = 100;
    
    private boolean mGLInitialized = false;  // GL环境是否初始化完成
    private Context mContext;                // 上下文对象
    private boolean mUsePreciseCalculation = false;
    
    // 着色器程序句柄
    private int mBgProgram;      // 背景着色器程序
    private int mParticleProgram;// 粒子着色器程序
    private int mLightProgram;   // 光效着色器程序
    
    // 纹理句柄
    private int mTexSpace;       // 星空背景纹理
    private int mTexFlares;      // 粒子光晕纹理
    private int mTexLight1;      // 光效叠加纹理
    
    // 粒子数据（与原始实现完全一致）
    private float[] mParticlePositions;  // 每个粒子的角度、距离、Z轴坐标
    private float[] mParticleColors;     // 每个粒子的RGB颜色 + 点大小（存储在Alpha通道）
    private float[] mParticleSpeeds;     // 每个粒子的旋转速度
    
    // 粒子数据缓冲区（用于GL绘制）
    private FloatBuffer mParticlePositionBuffer;
    private FloatBuffer mParticleColorBuffer;
    
    // 矩阵相关
    private float[] mMVPMatrix = new float[16];  // 模型视图投影矩阵
    private float[] mProjMatrix = new float[16];  // 投影矩阵
    private float[] mLightMatrix = new float[16]; // 光效矩阵
    
    private Random mRandom = new Random();        // 随机数生成器
    private long mLastTime = 0;                   // 上一帧时间戳
    private long mLastSettingsSyncTime = 0;       // 上次配置同步时间
    private static final long SETTINGS_SYNC_INTERVAL_MS = 500L;

    private int mArmCount = DEFAULT_ARM_COUNT;
    private float mArmOffset = DEFAULT_ARM_OFFSET;
    private float mPitchAngleDeg = DEFAULT_PITCH_ANGLE_DEG;
    private float mPitchAngleRad = DEFAULT_PITCH_ANGLE_DEG * PI / 180.0f;
    private float mForbiddenRadiusKpc = DEFAULT_FORBIDDEN_RADIUS_KPC;
    private float mPreciseInnerScatter = DEFAULT_PRECISE_INNER_SCATTER;
    private float mPreciseOuterScatter = DEFAULT_PRECISE_OUTER_SCATTER;
    private float mPreciseTurbulence = DEFAULT_PRECISE_TURBULENCE;
    private float mEllipseRatio = DEFAULT_ELLIPSE_RATIO;
    private float mEllipseTwist = DEFAULT_ELLIPSE_TWIST;
    
    // 滚动偏移量（用于响应屏幕滑动）
    private float mXOffset = 0.5f;
    
    // 屏幕尺寸
    private float mScreenWidth;
    private float mScreenHeight;
    
    /**
     * 构造函数
     * @param width  屏幕宽度
     * @param height 屏幕高度
     * @param context 上下文对象
     */
    public GalaxyGL(int width, int height, Context context) {
        super(width, height);
        mContext = context;
        loadSettingsFromPreferences(); // 从配置读取设置
    }
    
    /**
     * 生命周期回调 - 创建时触发
     * GL初始化会在GL线程中执行
     */
    @Override
    protected void onCreate() {
        Log.d(TAG, "onCreate - 将在GL线程初始化");
    }

    @Override
    public void release() {
        // 释放纹理资源
        int[] tex = new int[] { mTexSpace, mTexFlares, mTexLight1 };
        GLES20.glDeleteTextures(tex.length, tex, 0);
        mTexSpace = 0;
        mTexFlares = 0;
        mTexLight1 = 0;

        // 释放着色器程序
        if (mBgProgram != 0) {
            GLES20.glDeleteProgram(mBgProgram);
            mBgProgram = 0;
        }
        if (mParticleProgram != 0) {
            GLES20.glDeleteProgram(mParticleProgram);
            mParticleProgram = 0;
        }
        if (mLightProgram != 0) {
            GLES20.glDeleteProgram(mLightProgram);
            mLightProgram = 0;
        }

        // 释放缓冲区引用
        mParticlePositionBuffer = null;
        mParticleColorBuffer = null;
        mGLInitialized = false;
    }
    
    /**
     * 从SharedPreferences加载粒子数量配置
     */
    private void loadSettingsFromPreferences() {
        if (mContext != null) {
            Context appContext = mContext.getApplicationContext() != null ? mContext.getApplicationContext() : mContext;
            SharedPreferences defaultPrefs = PreferenceManager.getDefaultSharedPreferences(appContext);
            if (defaultPrefs.contains("galaxy_particle_count")) {
                mParticleCount = defaultPrefs.getInt("galaxy_particle_count", DEFAULT_PARTICLE_COUNT);
            } else {
                SharedPreferences legacyPrefs = appContext.getSharedPreferences("wallpaper_settings", Context.MODE_PRIVATE);
                mParticleCount = legacyPrefs.getInt("galaxy_particle_count", DEFAULT_PARTICLE_COUNT);
                if (legacyPrefs.contains("galaxy_particle_count")) {
                    defaultPrefs.edit().putInt("galaxy_particle_count", mParticleCount).apply();
                }
            }

            if (defaultPrefs.contains("galaxy_particle_alpha")) {
                mParticleAlphaPercent = defaultPrefs.getInt("galaxy_particle_alpha", DEFAULT_PARTICLE_ALPHA_PERCENT);
            } else {
                SharedPreferences legacyPrefs = appContext.getSharedPreferences("wallpaper_settings", Context.MODE_PRIVATE);
                mParticleAlphaPercent = legacyPrefs.getInt("galaxy_particle_alpha", DEFAULT_PARTICLE_ALPHA_PERCENT);
                if (legacyPrefs.contains("galaxy_particle_alpha")) {
                    defaultPrefs.edit().putInt("galaxy_particle_alpha", mParticleAlphaPercent).apply();
                }
            }

            if (defaultPrefs.contains("galaxy_precise_calc")) {
                mUsePreciseCalculation = defaultPrefs.getBoolean("galaxy_precise_calc", false);
            } else {
                SharedPreferences legacyPrefs = appContext.getSharedPreferences("wallpaper_settings", Context.MODE_PRIVATE);
                mUsePreciseCalculation = legacyPrefs.getBoolean("galaxy_precise_calc", false);
                if (legacyPrefs.contains("galaxy_precise_calc")) {
                    defaultPrefs.edit().putBoolean("galaxy_precise_calc", mUsePreciseCalculation).apply();
                }
            }

            loadPreciseShapeSettings(defaultPrefs, appContext);

            // 限制在有效范围内（MIN~MAX）
            mParticleCount = Math.max(MIN_PARTICLE_COUNT, Math.min(MAX_PARTICLE_COUNT, mParticleCount));
            mParticleAlphaPercent = Math.max(MIN_PARTICLE_ALPHA_PERCENT, Math.min(MAX_PARTICLE_ALPHA_PERCENT, mParticleAlphaPercent));
            Log.d(TAG, "加载的粒子数量: " + mParticleCount);
            Log.d(TAG, "加载的粒子透明度: " + mParticleAlphaPercent + "%");
            Log.d(TAG, "精确计算开关: " + mUsePreciseCalculation);
        }
    }

            private void loadPreciseShapeSettings(SharedPreferences defaultPrefs, Context appContext) {
            SharedPreferences legacyPrefs = appContext.getSharedPreferences("wallpaper_settings", Context.MODE_PRIVATE);
            SharedPreferences.Editor migrateEditor = defaultPrefs.edit();

            mArmCount = clampInt(readIntWithLegacy(defaultPrefs, legacyPrefs, KEY_ARM_COUNT, DEFAULT_ARM_COUNT, migrateEditor),
                MIN_ARM_COUNT, MAX_ARM_COUNT);
            mArmOffset = clampFloat(readScaledFloatWithLegacy(defaultPrefs, legacyPrefs, KEY_ARM_OFFSET, DEFAULT_ARM_OFFSET,
                1000.0f, migrateEditor), MIN_ARM_OFFSET, MAX_ARM_OFFSET);
            mPitchAngleDeg = clampFloat(readScaledFloatWithLegacy(defaultPrefs, legacyPrefs, KEY_PITCH_ANGLE_DEG,
                DEFAULT_PITCH_ANGLE_DEG, 10.0f, migrateEditor), MIN_PITCH_ANGLE_DEG, MAX_PITCH_ANGLE_DEG);
            mForbiddenRadiusKpc = clampFloat(readScaledFloatWithLegacy(defaultPrefs, legacyPrefs, KEY_FORBIDDEN_RADIUS,
                DEFAULT_FORBIDDEN_RADIUS_KPC, 100.0f, migrateEditor), MIN_FORBIDDEN_RADIUS_KPC,
                MAX_FORBIDDEN_RADIUS_KPC);
            mPreciseInnerScatter = clampFloat(readScaledFloatWithLegacy(defaultPrefs, legacyPrefs, KEY_INNER_SCATTER,
                DEFAULT_PRECISE_INNER_SCATTER, 100.0f, migrateEditor), MIN_SCATTER, MAX_SCATTER);
            mPreciseOuterScatter = clampFloat(readScaledFloatWithLegacy(defaultPrefs, legacyPrefs, KEY_OUTER_SCATTER,
                DEFAULT_PRECISE_OUTER_SCATTER, 100.0f, migrateEditor), MIN_SCATTER, MAX_SCATTER);
            mPreciseTurbulence = clampFloat(readScaledFloatWithLegacy(defaultPrefs, legacyPrefs, KEY_TURBULENCE,
                DEFAULT_PRECISE_TURBULENCE, 100.0f, migrateEditor), MIN_TURBULENCE, MAX_TURBULENCE);
            mEllipseRatio = clampFloat(readScaledFloatWithLegacy(defaultPrefs, legacyPrefs, KEY_ELLIPSE_RATIO,
                DEFAULT_ELLIPSE_RATIO, 1000.0f, migrateEditor), MIN_ELLIPSE_RATIO, MAX_ELLIPSE_RATIO);
            mEllipseTwist = clampFloat(readEllipseTwistWithLegacy(defaultPrefs, legacyPrefs, migrateEditor),
                MIN_ELLIPSE_TWIST, MAX_ELLIPSE_TWIST);

            mPitchAngleRad = mPitchAngleDeg * PI / 180.0f;

            migrateEditor.apply();
            }

            private int readIntWithLegacy(SharedPreferences defaultPrefs, SharedPreferences legacyPrefs, String key,
                int fallback, SharedPreferences.Editor migrateEditor) {
            if (defaultPrefs.contains(key)) {
                return defaultPrefs.getInt(key, fallback);
            }
            if (legacyPrefs.contains(key)) {
                int value = legacyPrefs.getInt(key, fallback);
                migrateEditor.putInt(key, value);
                return value;
            }
            return fallback;
            }

            private float readScaledFloatWithLegacy(SharedPreferences defaultPrefs, SharedPreferences legacyPrefs, String key,
                float fallback, float scale, SharedPreferences.Editor migrateEditor) {
            if (defaultPrefs.contains(key)) {
                return defaultPrefs.getInt(key, Math.round(fallback * scale)) / scale;
            }
            if (legacyPrefs.contains(key)) {
                    Object legacyValue = legacyPrefs.getAll().get(key);
                    float legacyFloat;
                    if (legacyValue instanceof Number) {
                        legacyFloat = ((Number) legacyValue).floatValue();
                    } else {
                        legacyFloat = fallback;
                    }
                migrateEditor.putInt(key, Math.round(legacyFloat * scale));
                return legacyFloat;
            }
            return fallback;
            }

        private float readEllipseTwistWithLegacy(SharedPreferences defaultPrefs, SharedPreferences legacyPrefs,
                SharedPreferences.Editor migrateEditor) {
            if (defaultPrefs.contains(KEY_ELLIPSE_TWIST)) {
                int raw = defaultPrefs.getInt(KEY_ELLIPSE_TWIST,
                        encodeEllipseTwist(DEFAULT_ELLIPSE_TWIST));
                return decodeEllipseTwist(raw);
            }

            if (legacyPrefs.contains(KEY_ELLIPSE_TWIST)) {
                Object legacyValue = legacyPrefs.getAll().get(KEY_ELLIPSE_TWIST);
                float legacyFloat;
                if (legacyValue instanceof Number) {
                    legacyFloat = ((Number) legacyValue).floatValue();
                } else {
                    legacyFloat = DEFAULT_ELLIPSE_TWIST;
                }
                migrateEditor.putInt(KEY_ELLIPSE_TWIST, encodeEllipseTwist(legacyFloat));
                return legacyFloat;
            }

            return DEFAULT_ELLIPSE_TWIST;
        }

        private int encodeEllipseTwist(float twist) {
            return Math.round(clampFloat(twist, MIN_ELLIPSE_TWIST, MAX_ELLIPSE_TWIST) * 1000.0f)
                    + ELLIPSE_TWIST_STORAGE_OFFSET;
        }

        private float decodeEllipseTwist(int raw) {
            if (raw >= 0 && raw <= 200) {
                return (raw - ELLIPSE_TWIST_STORAGE_OFFSET) / 1000.0f;
            }
            return raw / 1000.0f;
        }

    private void syncSettingsFromPreferencesIfNeeded() {
        if (mContext == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (mLastSettingsSyncTime != 0L && (now - mLastSettingsSyncTime) < SETTINGS_SYNC_INTERVAL_MS) {
            return;
        }
        mLastSettingsSyncTime = now;

        Context appContext = mContext.getApplicationContext() != null ? mContext.getApplicationContext() : mContext;
        SharedPreferences defaultPrefs = PreferenceManager.getDefaultSharedPreferences(appContext);

        int prefParticleCount = defaultPrefs.getInt("galaxy_particle_count", mParticleCount);
        int prefParticleAlpha = defaultPrefs.getInt("galaxy_particle_alpha", mParticleAlphaPercent);
        boolean prefPreciseCalc = defaultPrefs.getBoolean("galaxy_precise_calc", mUsePreciseCalculation);

        int prefArmCount = clampInt(defaultPrefs.getInt(KEY_ARM_COUNT, Math.round(mArmCount)), MIN_ARM_COUNT,
            MAX_ARM_COUNT);
        float prefArmOffset = clampFloat(defaultPrefs.getInt(KEY_ARM_OFFSET, Math.round(mArmOffset * 1000.0f)) / 1000.0f,
            MIN_ARM_OFFSET, MAX_ARM_OFFSET);
        float prefPitchAngleDeg = clampFloat(
            defaultPrefs.getInt(KEY_PITCH_ANGLE_DEG, Math.round(mPitchAngleDeg * 10.0f)) / 10.0f,
            MIN_PITCH_ANGLE_DEG, MAX_PITCH_ANGLE_DEG);
        float prefInnerScatter = clampFloat(
            defaultPrefs.getInt(KEY_INNER_SCATTER, Math.round(mPreciseInnerScatter * 100.0f)) / 100.0f,
            MIN_SCATTER, MAX_SCATTER);
        float prefOuterScatter = clampFloat(
            defaultPrefs.getInt(KEY_OUTER_SCATTER, Math.round(mPreciseOuterScatter * 100.0f)) / 100.0f,
            MIN_SCATTER, MAX_SCATTER);
        float prefTurbulence = clampFloat(
            defaultPrefs.getInt(KEY_TURBULENCE, Math.round(mPreciseTurbulence * 100.0f)) / 100.0f,
            MIN_TURBULENCE, MAX_TURBULENCE);
        float prefForbiddenRadius = clampFloat(
            defaultPrefs.getInt(KEY_FORBIDDEN_RADIUS, Math.round(mForbiddenRadiusKpc * 100.0f)) / 100.0f,
            MIN_FORBIDDEN_RADIUS_KPC, MAX_FORBIDDEN_RADIUS_KPC);
        float prefEllipseRatio = clampFloat(
            defaultPrefs.getInt(KEY_ELLIPSE_RATIO, Math.round(mEllipseRatio * 1000.0f)) / 1000.0f,
            MIN_ELLIPSE_RATIO, MAX_ELLIPSE_RATIO);
        float prefEllipseTwist = clampFloat(
            decodeEllipseTwist(defaultPrefs.getInt(KEY_ELLIPSE_TWIST, encodeEllipseTwist(mEllipseTwist))),
            MIN_ELLIPSE_TWIST, MAX_ELLIPSE_TWIST);

        prefParticleCount = Math.max(MIN_PARTICLE_COUNT, Math.min(MAX_PARTICLE_COUNT, prefParticleCount));
        prefParticleAlpha = Math.max(MIN_PARTICLE_ALPHA_PERCENT, Math.min(MAX_PARTICLE_ALPHA_PERCENT, prefParticleAlpha));

        boolean needRebuildParticles = false;

        if (prefParticleCount != mParticleCount) {
            mParticleCount = prefParticleCount;
            needRebuildParticles = true;
            Log.d(TAG, "检测到桌面粒子数量变更: " + mParticleCount);
        }

        if (prefPreciseCalc != mUsePreciseCalculation) {
            mUsePreciseCalculation = prefPreciseCalc;
            needRebuildParticles = true;
            Log.d(TAG, "检测到桌面精确计算开关变更: " + mUsePreciseCalculation);
        }

        if (prefArmCount != mArmCount) {
            mArmCount = prefArmCount;
            needRebuildParticles = true;
        }
        if (prefArmOffset != mArmOffset) {
            mArmOffset = prefArmOffset;
            needRebuildParticles = true;
        }
        if (prefPitchAngleDeg != mPitchAngleDeg) {
            mPitchAngleDeg = prefPitchAngleDeg;
            mPitchAngleRad = mPitchAngleDeg * PI / 180.0f;
            needRebuildParticles = true;
        }
        if (prefInnerScatter != mPreciseInnerScatter) {
            mPreciseInnerScatter = prefInnerScatter;
            needRebuildParticles = true;
        }
        if (prefOuterScatter != mPreciseOuterScatter) {
            mPreciseOuterScatter = prefOuterScatter;
            needRebuildParticles = true;
        }
        if (prefTurbulence != mPreciseTurbulence) {
            mPreciseTurbulence = prefTurbulence;
            needRebuildParticles = true;
        }
        if (prefForbiddenRadius != mForbiddenRadiusKpc) {
            mForbiddenRadiusKpc = prefForbiddenRadius;
            needRebuildParticles = true;
        }
        if (prefEllipseRatio != mEllipseRatio) {
            mEllipseRatio = prefEllipseRatio;
            needRebuildParticles = true;
        }
        if (prefEllipseTwist != mEllipseTwist) {
            mEllipseTwist = prefEllipseTwist;
            needRebuildParticles = true;
        }

        if (prefParticleAlpha != mParticleAlphaPercent) {
            mParticleAlphaPercent = prefParticleAlpha;
            Log.d(TAG, "检测到桌面粒子透明度变更: " + mParticleAlphaPercent + "%");
        }

        if (needRebuildParticles) {
            mGLInitialized = false;
        }
    }
    
    /**
     * 设置粒子数量（对外提供的接口）
     * @param count 目标粒子数量
     */
    public void setParticleCount(int count) {
        // 先限制数量在有效范围
        count = Math.max(MIN_PARTICLE_COUNT, Math.min(MAX_PARTICLE_COUNT, count));
        if (count != mParticleCount) {
            mParticleCount = count;
            if (mContext != null) {
                Context appContext = mContext.getApplicationContext() != null ? mContext.getApplicationContext() : mContext;
                PreferenceManager.getDefaultSharedPreferences(appContext)
                        .edit()
                        .putInt("galaxy_particle_count", mParticleCount)
                        .apply();
            }
            // 标记GL未初始化，触发下一帧重新初始化粒子
            mGLInitialized = false;
            Log.d(TAG, "粒子数量已修改为: " + mParticleCount);
        }
    }

    public void setPreciseCalculation(boolean enabled) {
        if (enabled != mUsePreciseCalculation) {
            mUsePreciseCalculation = enabled;
            if (mContext != null) {
                Context appContext = mContext.getApplicationContext() != null ? mContext.getApplicationContext() : mContext;
                PreferenceManager.getDefaultSharedPreferences(appContext)
                        .edit()
                        .putBoolean("galaxy_precise_calc", mUsePreciseCalculation)
                        .apply();
            }
            mGLInitialized = false;
            Log.d(TAG, "精确计算已切换为: " + mUsePreciseCalculation);
        }
    }

    public void setParticleAlphaPercent(int alphaPercent) {
        alphaPercent = Math.max(MIN_PARTICLE_ALPHA_PERCENT, Math.min(MAX_PARTICLE_ALPHA_PERCENT, alphaPercent));
        if (alphaPercent != mParticleAlphaPercent) {
            mParticleAlphaPercent = alphaPercent;
            if (mContext != null) {
                Context appContext = mContext.getApplicationContext() != null ? mContext.getApplicationContext() : mContext;
                PreferenceManager.getDefaultSharedPreferences(appContext)
                        .edit()
                        .putInt("galaxy_particle_alpha", mParticleAlphaPercent)
                        .apply();
            }
            Log.d(TAG, "粒子透明度已修改为: " + mParticleAlphaPercent + "%");
        }
    }

    public void setArmCount(int armCount) {
        int clamped = clampInt(armCount, MIN_ARM_COUNT, MAX_ARM_COUNT);
        if (clamped != mArmCount) {
            mArmCount = clamped;
            persistInt(KEY_ARM_COUNT, mArmCount);
            if (mUsePreciseCalculation) mGLInitialized = false;
        }
    }

    public void setArmOffset(float armOffset) {
        float clamped = clampFloat(armOffset, MIN_ARM_OFFSET, MAX_ARM_OFFSET);
        if (clamped != mArmOffset) {
            mArmOffset = clamped;
            persistScaledFloat(KEY_ARM_OFFSET, mArmOffset, 1000.0f);
            if (mUsePreciseCalculation) mGLInitialized = false;
        }
    }

    public void setPitchAngleDeg(float pitchAngleDeg) {
        float clamped = clampFloat(pitchAngleDeg, MIN_PITCH_ANGLE_DEG, MAX_PITCH_ANGLE_DEG);
        if (clamped != mPitchAngleDeg) {
            mPitchAngleDeg = clamped;
            mPitchAngleRad = mPitchAngleDeg * PI / 180.0f;
            persistScaledFloat(KEY_PITCH_ANGLE_DEG, mPitchAngleDeg, 10.0f);
            if (mUsePreciseCalculation) mGLInitialized = false;
        }
    }

    public void setInnerScatter(float innerScatter) {
        float clamped = clampFloat(innerScatter, MIN_SCATTER, MAX_SCATTER);
        if (clamped != mPreciseInnerScatter) {
            mPreciseInnerScatter = clamped;
            persistScaledFloat(KEY_INNER_SCATTER, mPreciseInnerScatter, 100.0f);
            if (mUsePreciseCalculation) mGLInitialized = false;
        }
    }

    public void setOuterScatter(float outerScatter) {
        float clamped = clampFloat(outerScatter, MIN_SCATTER, MAX_SCATTER);
        if (clamped != mPreciseOuterScatter) {
            mPreciseOuterScatter = clamped;
            persistScaledFloat(KEY_OUTER_SCATTER, mPreciseOuterScatter, 100.0f);
            if (mUsePreciseCalculation) mGLInitialized = false;
        }
    }

    public void setTurbulence(float turbulence) {
        float clamped = clampFloat(turbulence, MIN_TURBULENCE, MAX_TURBULENCE);
        if (clamped != mPreciseTurbulence) {
            mPreciseTurbulence = clamped;
            persistScaledFloat(KEY_TURBULENCE, mPreciseTurbulence, 100.0f);
            if (mUsePreciseCalculation) mGLInitialized = false;
        }
    }

    public void setForbiddenRadius(float forbiddenRadius) {
        float clamped = clampFloat(forbiddenRadius, MIN_FORBIDDEN_RADIUS_KPC, MAX_FORBIDDEN_RADIUS_KPC);
        if (clamped != mForbiddenRadiusKpc) {
            mForbiddenRadiusKpc = clamped;
            persistScaledFloat(KEY_FORBIDDEN_RADIUS, mForbiddenRadiusKpc, 100.0f);
            if (mUsePreciseCalculation) mGLInitialized = false;
        }
    }

    public void setEllipseRatio(float ellipseRatio) {
        float clamped = clampFloat(ellipseRatio, MIN_ELLIPSE_RATIO, MAX_ELLIPSE_RATIO);
        if (clamped != mEllipseRatio) {
            mEllipseRatio = clamped;
            persistScaledFloat(KEY_ELLIPSE_RATIO, mEllipseRatio, 1000.0f);
            if (mUsePreciseCalculation) mGLInitialized = false;
        }
    }

    public void setEllipseTwist(float ellipseTwist) {
        float clamped = clampFloat(ellipseTwist, MIN_ELLIPSE_TWIST, MAX_ELLIPSE_TWIST);
        if (clamped != mEllipseTwist) {
            mEllipseTwist = clamped;
            persistInt(KEY_ELLIPSE_TWIST, encodeEllipseTwist(mEllipseTwist));
            if (mUsePreciseCalculation) mGLInitialized = false;
        }
    }

    private void persistInt(String key, int value) {
        if (mContext == null) return;
        Context appContext = mContext.getApplicationContext() != null ? mContext.getApplicationContext() : mContext;
        PreferenceManager.getDefaultSharedPreferences(appContext).edit().putInt(key, value).apply();
    }

    private void persistScaledFloat(String key, float value, float scale) {
        persistInt(key, Math.round(value * scale));
    }
    
    /**
     * 初始化GL环境
     * 包含：清屏颜色、混合模式、着色器程序、纹理、粒子数据初始化
     */
    private void initGL() {
        if (mGLInitialized || mResources == null) return; // 已初始化或资源为空则直接返回
        
        Log.d(TAG, "initGL 开始执行");
        mGLInitialized = true;
        
        // 设置清屏颜色为纯黑
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        // 禁用深度测试（2D场景无需深度）
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        // 启用混合模式（实现透明/光晕效果）
        GLES20.glEnable(GLES20.GL_BLEND);
        // 设置混合因子：源Alpha * 目标1（加法混合，增强光晕效果）
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE);
        
        // 创建着色器程序
        createPrograms();
        // 加载纹理资源
        loadTextures();
        // 初始化粒子数据
        initParticles();
        
        // 记录初始化完成时间
        mLastTime = System.currentTimeMillis();
        
        Log.d(TAG, "initGL 执行完成");
    }
    
    /**
     * 创建所有着色器程序
     * 包含：背景、粒子、光效三个着色器程序
     */
    private void createPrograms() {
        // 1. 背景着色器程序 - 绘制带纹理的全屏四边形
        mBgProgram = createShaderProgram(
            RawResourceLoader.readRawText(mResources, R.raw.galaxy_bg_vs),
            RawResourceLoader.readRawText(mResources, R.raw.galaxy_bg_fs)
        );
        
        // 2. 粒子着色器程序 - 完全移植自原始RenderScript实现
        mParticleProgram = createShaderProgram(
            RawResourceLoader.readRawText(mResources, R.raw.galaxy_particle_vs),
            RawResourceLoader.readRawText(mResources, R.raw.galaxy_particle_fs)
        );
        
        // 3. 光效着色器程序 - 绘制带变换的光效叠加层
        mLightProgram = createShaderProgram(
            RawResourceLoader.readRawText(mResources, R.raw.galaxy_light_vs),
            RawResourceLoader.readRawText(mResources, R.raw.galaxy_light_fs)
        );
        
        Log.d(TAG, "着色器程序创建完成");
    }
    
    /**
     * 创建并链接着色器程序
     * @param vertexSource 顶点着色器源码
     * @param fragmentSource 片段着色器源码
     * @return 链接成功的程序句柄（失败返回0）
     */
    private int createShaderProgram(String vertexSource, String fragmentSource) {
        // 编译顶点着色器
        int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        // 编译片段着色器
        int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        
        // 创建程序并附加着色器
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        // 链接程序
        GLES20.glLinkProgram(program);
        
        // 检查链接状态
        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] == 0) {
            Log.e(TAG, "程序链接失败: " + GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            return 0;
        }
        
        // 链接成功后删除着色器（已附加到程序，无需保留）
        GLES20.glDeleteShader(vertexShader);
        GLES20.glDeleteShader(fragmentShader);
        
        return program;
    }
    
    /**
     * 编译单个着色器
     * @param type 着色器类型（GL_VERTEX_SHADER / GL_FRAGMENT_SHADER）
     * @param source 着色器源码
     * @return 编译成功的着色器句柄（失败返回0）
     */
    private int compileShader(int type, String source) {
        // 创建着色器
        int shader = GLES20.glCreateShader(type);
        // 设置源码并编译
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        
        // 检查编译状态
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e(TAG, "着色器编译失败: " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }
        
        return shader;
    }
    
    /**
     * 加载所有纹理资源
     * 包含：星空背景、粒子光晕、光效叠加纹理
     */
    private void loadTextures() {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false; // 禁用位图缩放（保持原始尺寸）
        
        // 加载纹理并获取句柄
        mTexSpace = loadTexture(R.drawable.galaxy_space, options);
        mTexFlares = loadTexture(R.drawable.galaxy_flares, options);
        mTexLight1 = loadTexture(R.drawable.light1, options);
        
        Log.d(TAG, "纹理加载完成");
    }
    
    /**
     * 加载单个纹理资源
     * @param resourceId 资源ID
     * @param options 位图解码选项
     * @return 纹理句柄
     */
    private int loadTexture(int resourceId, BitmapFactory.Options options) {
        // 从资源解码位图
        Bitmap bitmap = BitmapFactory.decodeResource(mResources, resourceId, options);
        
        // 生成纹理句柄
        int[] textureHandle = new int[1];
        GLES20.glGenTextures(1, textureHandle, 0);
        
        // 绑定纹理并设置参数
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle[0]);
        // 缩小/放大过滤：线性过滤（平滑）
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        // 纹理环绕模式：边缘夹紧（避免重复）
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        
        // 将位图数据上传到纹理
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        
        // 回收位图（释放内存）
        bitmap.recycle();
        
        return textureHandle[0];
    }
    
    /**
     * 初始化粒子数据（完全移植自galaxy.rs的createParticle方法）
     * 1:1还原原始粒子生成算法
     */
    private void initParticles() {
        // 计算缩放比例（适配屏幕尺寸）
        float scale = GALAXY_RADIUS / (mWidth * 0.5f);
        
        // 初始化粒子数据数组
        mParticlePositions = new float[mParticleCount * 3];  // 角度、距离、Z轴
        mParticleColors = new float[mParticleCount * 4];     // RGB + 点大小
        mParticleSpeeds = new float[mParticleCount];         // 旋转速度
        
        // 逐个生成粒子数据
        for (int i = 0; i < mParticleCount; i++) {
            // 计算粒子距离（高斯随机分布 + 随机偏移）
            // 对应galaxy.rs line93: d = fabs(randomGauss()) * gGalaxyRadius * 0.5f + rsRand(64.0f);
            float d = Math.abs(randomGauss()) * GALAXY_RADIUS * 0.5f + mRandom.nextFloat() * 64.0f;
            float id = d / GALAXY_RADIUS; // 归一化距离
            float z = randomGauss() * 0.4f * (1.0f - id); // Z轴坐标（高斯分布）
            
            // 颜色分配（核心区域偏黄白，外围偏蓝）
            // 对应galaxy.rs lines97-107
            int idx = i * 4;
            if (d < GALAXY_RADIUS * 0.33f) {
                // 核心区域 - 黄白色调
                mParticleColors[idx] = 220 + id * 35;     // 红通道
                mParticleColors[idx + 1] = 220;           // 绿通道
                mParticleColors[idx + 2] = 220;           // 蓝通道
            } else {
                // 外围区域 - 蓝色调
                mParticleColors[idx] = 180;               // 红通道
                mParticleColors[idx + 1] = 180;           // 绿通道
                mParticleColors[idx + 2] = Math.min(140.0f + id * 115.0f, 255.0f);  // 蓝通道（限制最大值255）
            }
            
            // 点大小计算（存储在Alpha通道）
            // 对应galaxy.rs line108: rsRand(1.2f, 2.1f) * 60
            mParticleColors[idx + 3] = (mRandom.nextFloat() * 0.9f + 1.2f) * 6.0f;
            
            // Z轴深度修正（根据距离调整）
            // 对应galaxy.rs lines110-114
            if (d > GALAXY_RADIUS * 0.15f) {
                z *= 0.6f * (1.0f - id);
            } else {
                z *= 0.72f;
            }
            
            // 将距离映射到投影坐标
            // 对应galaxy.rs line117
            float mappedDistance = mapf(-4.0f, GALAXY_RADIUS + 4.0f, 0.0f, scale, d);

            float angle;
            if (mUsePreciseCalculation) {
                angle = calculatePreciseInitialAngle(d, i);
            } else {
                angle = mRandom.nextFloat() * TWO_PI;
            }
            
            // 存储粒子位置数据
            int posIdx = i * 3;
            mParticlePositions[posIdx] = angle;
            mParticlePositions[posIdx + 1] = mappedDistance;
            mParticlePositions[posIdx + 2] = z / 5.0f;                  // Z轴坐标（缩放）
            
            // 计算粒子旋转速度
            // 对应galaxy.rs line119: rsRand(0.0015f, 0.0025f) * (0.5f + (scale / d)) * 0.8f
            if (mUsePreciseCalculation) {
                mParticleSpeeds[i] = PRECISE_PATTERN_SPEED;
            } else {
                mParticleSpeeds[i] = (mRandom.nextFloat() * 0.001f + 0.0015f) * (0.5f + (scale / mappedDistance)) * 0.8f;
            }
        }
        
        // 创建粒子位置缓冲区
        ByteBuffer bb = ByteBuffer.allocateDirect(mParticlePositions.length * 4);
        bb.order(ByteOrder.nativeOrder()); // 使用本地字节序
        mParticlePositionBuffer = bb.asFloatBuffer();
        mParticlePositionBuffer.put(mParticlePositions);
        mParticlePositionBuffer.position(0); // 重置缓冲区指针
        
        // 创建粒子颜色缓冲区
        bb = ByteBuffer.allocateDirect(mParticleColors.length * 4);
        bb.order(ByteOrder.nativeOrder());
        mParticleColorBuffer = bb.asFloatBuffer();
        mParticleColorBuffer.put(mParticleColors);
        mParticleColorBuffer.position(0);
        
        // 设置投影矩阵（匹配GalaxyRS.java的getProjectionNormalized方法）
        if (mWidth > mHeight) {
            float aspect = (float) mWidth / mHeight;
            Matrix.frustumM(mProjMatrix, 0, -aspect, aspect, -1, 1, 1, 100);
        } else {
            float aspect = (float) mHeight / mWidth;
            Matrix.frustumM(mProjMatrix, 0, -1, 1, -aspect, aspect, 1, 100);
        }
        
        // 应用变换：Y轴旋转180° + 缩放 + 平移
        float[] rotMatrix = new float[16];
        float[] tempMatrix = new float[16];
        
        // 旋转180°
        Matrix.setRotateM(rotMatrix, 0, 180, 0, 1, 0);
        Matrix.multiplyMM(tempMatrix, 0, mProjMatrix, 0, rotMatrix, 0);
        
        // 缩放
        Matrix.setIdentityM(rotMatrix, 0);
        Matrix.scaleM(rotMatrix, 0, -2, 2, 1);
        Matrix.multiplyMM(mProjMatrix, 0, tempMatrix, 0, rotMatrix, 0);
        
        // 平移
        Matrix.setIdentityM(rotMatrix, 0);
        Matrix.translateM(rotMatrix, 0, 0, 0, 2);
        Matrix.multiplyMM(mMVPMatrix, 0, mProjMatrix, 0, rotMatrix, 0);
        
        Log.d(TAG, "粒子初始化完成");
    }

    private float calculatePreciseInitialAngle(float radiusInScreenSpace, int particleIndex) {
        float radiusKpc = radiusInScreenSpace * (GALAXY_RADIUS_KPC / GALAXY_RADIUS);
        float safeForbiddenRadius = Math.max(0.001f, mForbiddenRadiusKpc);
        float radiusRatio = radiusKpc / safeForbiddenRadius;
        float angle;

        if (radiusRatio > 1.0f) {
            float tanI = (float) Math.tan(mPitchAngleRad);
            if (Math.abs(tanI) < 1.0e-4f) {
                tanI = 1.0e-4f;
            }
            float baseAngle = (1.0f / tanI) * (float) Math.log(radiusRatio);

            int armCount = Math.max(1, mArmCount);
            int armIndex = mRandom.nextInt(armCount);
            float armAngleOffset = armIndex * mArmOffset;

            float radiusLerp = (radiusKpc - safeForbiddenRadius) / (GALAXY_RADIUS_KPC - safeForbiddenRadius);
            radiusLerp = Math.max(0.0f, Math.min(1.0f, radiusLerp));
            float scatterAmp = mPreciseInnerScatter + (mPreciseOuterScatter - mPreciseInnerScatter) * radiusLerp;

            float uniformScatter = (mRandom.nextFloat() - 0.5f) * scatterAmp;
            float gaussianScatter = randomGauss() * (scatterAmp * 0.42f);
            float turbulence = (mRandom.nextFloat() - 0.5f) * mPreciseTurbulence * (1.0f - radiusLerp);
            float armJitter = (mRandom.nextFloat() - 0.5f) * 0.28f;
            armAngleOffset += armJitter;

            float ellipseStrength = (1.0f - mEllipseRatio) * 1.3f;
            float twistTerm = mEllipseTwist * radiusKpc * 8.0f;
            armAngleOffset += ellipseStrength * (float) Math.sin(2.0f * (baseAngle + armAngleOffset) + twistTerm);
            baseAngle += mEllipseTwist * radiusLerp * 0.9f;

            if (radiusKpc < 5.0f) {
                float ellipticityFactor = 0.14f * (1.0f - radiusKpc / 5.0f);
                float sin2theta = (float) Math.sin(2.0f * (baseAngle + armAngleOffset));
                armAngleOffset += ellipticityFactor * sin2theta;

                float innerChaos = randomGauss() * 0.36f * (1.0f - radiusKpc / 5.0f);
                turbulence += innerChaos;
            }

            angle = baseAngle + armAngleOffset + uniformScatter + gaussianScatter + turbulence;
        } else {
            angle = mRandom.nextFloat() * TWO_PI;
        }

        angle = angle % TWO_PI;
        if (angle < 0.0f) {
            angle += TWO_PI;
        }
        return angle;
    }

    private int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private float clampFloat(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
    
    /**
     * 生成高斯分布的随机数（Box-Muller算法）
     * @return 高斯随机数
     */
    private float randomGauss() {
        float x1 = 0.0f, x2, w;
        
        w = 2.0f;
        // 生成单位圆内的随机点
        while (w >= 1.0f) {
            x1 = mRandom.nextFloat() * 2.0f - 1.0f;
            x2 = mRandom.nextFloat() * 2.0f - 1.0f;
            w = x1 * x1 + x2 * x2;
        }
        
        // 转换为高斯分布
        w = (float) Math.sqrt(-2.0f * Math.log(w) / w);
        return x1 * w;
    }
    
    /**
     * 将值从一个范围映射到另一个范围
     * @param minStart 原范围最小值
     * @param minStop  原范围最大值
     * @param maxStart 目标范围最小值
     * @param maxStop  目标范围最大值
     * @param value    要映射的值
     * @return 映射后的值
     */
    private float mapf(float minStart, float minStop, float maxStart, float maxStop, float value) {
        return maxStart + (maxStart - maxStop) * ((value - minStart) / (minStop - minStart));
    }
    
    /**
     * 计算带偏移的变换矩阵（移植自galaxy.rs的calcMatrix方法）
     * @param matrix  输出矩阵
     * @param offset  偏移量（-1 ~ 1）
     */
    private void calcMatrix(float[] matrix, float offset) {
        float angle = 50.0f;  // 原始galaxy.rs的固定角度
        float a = offset * angle;
        float absoluteAngle = Math.abs(a);
        
        // 初始化单位矩阵
        Matrix.setIdentityM(matrix, 0);
        // 平移Z轴（根据偏移量调整深度）
        Matrix.translateM(matrix, 0, 0.0f, 0.0f, 10.0f - 6.0f * absoluteAngle / 50.0f);
        
        // 根据屏幕宽高比缩放
        if (mHeight > mWidth) {
            Matrix.scaleM(matrix, 0, 6.6f, 6.0f, 1.0f);
        } else {
            Matrix.scaleM(matrix, 0, 12.6f, 12.0f, 1.0f);
        }
        
        // 应用旋转（X轴）
        Matrix.rotateM(matrix, 0, absoluteAngle, 1.0f, 0.0f, 0.0f);
        // 应用旋转（自定义轴）
        Matrix.rotateM(matrix, 0, a, 0.0f, 0.4f, 0.1f);
        
        // 与投影矩阵相乘
        float[] tempMatrix = new float[16];
        Matrix.multiplyMM(tempMatrix, 0, mProjMatrix, 0, matrix, 0);
        System.arraycopy(tempMatrix, 0, matrix, 0, 16);
    }
    
    /**
     * 设置屏幕偏移（响应壁纸滑动）
     * @param xOffset X轴偏移（0 ~ 1）
     * @param yOffset Y轴偏移（未使用）
     * @param xPixels X轴像素偏移（未使用）
     * @param yPixels Y轴像素偏移（未使用）
     */
    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        mXOffset = xOffset;
    }
    
    /**
     * 更新粒子角度（移植自galaxy.rs line185）
     * 每个粒子按自身速度旋转
     */
    private void updateParticles() {
        for (int i = 0; i < mParticleCount; i++) {
            // 角度 += 速度
            mParticlePositions[i * 3] += mParticleSpeeds[i];
        }
        
        // 更新缓冲区数据
        mParticlePositionBuffer.position(0);
        mParticlePositionBuffer.put(mParticlePositions);
        mParticlePositionBuffer.position(0);
    }
    
    /**
     * 绘制每一帧（核心渲染逻辑）
     * @param timeMs 帧时间戳
     */
    @Override
    public void drawFrame(long timeMs) {
        syncSettingsFromPreferencesIfNeeded();

        if (!mGLInitialized) {
            initGL(); // 未初始化则先初始化GL环境
            return;
        }
        
        // 清除颜色缓冲区（清屏）
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        
        // 1. 绘制背景
        drawBackground();
        
        // 2. 计算带偏移的MVP矩阵（移植自galaxy.rs root() line192）
        float offsetValue = mXOffset * 2.0f - 1.0f;  // 将0~1映射到-1~1
        calcMatrix(mMVPMatrix, offsetValue);
        
        // 3. 更新粒子角度并绘制粒子
        updateParticles();
        drawParticles();
        
        // 4. 绘制光效叠加层
        drawLights();
    }
    
    /**
     * 绘制背景（全屏纹理四边形）
     */
    private void drawBackground() {
        // 使用背景着色器程序
        GLES20.glUseProgram(mBgProgram);
        
        // 全屏四边形顶点数据（XY坐标 + 纹理坐标）
        float[] vertices = {
            -1, -1, 0, 1,  // 左下
             1, -1, 1, 1,  // 右下
            -1,  1, 0, 0,  // 左上
             1,  1, 1, 0   // 右上
        };
        
        // 创建顶点缓冲区
        FloatBuffer vertexBuffer = createFloatBuffer(vertices);
        
        // 获取着色器属性/统一变量句柄
        int posHandle = GLES20.glGetAttribLocation(mBgProgram, "aPosition");
        int texHandle = GLES20.glGetAttribLocation(mBgProgram, "aTexCoord");
        int samplerHandle = GLES20.glGetUniformLocation(mBgProgram, "uTexture");
        
        // 启用顶点属性数组
        GLES20.glEnableVertexAttribArray(posHandle);
        GLES20.glEnableVertexAttribArray(texHandle);
        
        // 设置顶点坐标属性
        vertexBuffer.position(0);
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer);
        // 设置纹理坐标属性
        vertexBuffer.position(2);
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer);
        
        // 绑定纹理并设置采样器
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTexSpace);
        GLES20.glUniform1i(samplerHandle, 0);
        
        // 绘制四边形（三角带方式）
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        
        // 禁用顶点属性数组（优化性能）
        GLES20.glDisableVertexAttribArray(posHandle);
        GLES20.glDisableVertexAttribArray(texHandle);
    }
    
    /**
     * 绘制粒子（GL_POINTS方式）
     */
    private void drawParticles() {
        // 使用粒子着色器程序
        GLES20.glUseProgram(mParticleProgram);
        
        // 获取着色器属性/统一变量句柄
        int posHandle = GLES20.glGetAttribLocation(mParticleProgram, "aPosition");
        int colorHandle = GLES20.glGetAttribLocation(mParticleProgram, "aColor");
        int mvpHandle = GLES20.glGetUniformLocation(mParticleProgram, "uMVPMatrix");
        int samplerHandle = GLES20.glGetUniformLocation(mParticleProgram, "uTexture");
        int alphaHandle = GLES20.glGetUniformLocation(mParticleProgram, "uAlphaMultiplier");
        
        // 设置MVP矩阵
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mMVPMatrix, 0);
        GLES20.glUniform1f(alphaHandle, mParticleAlphaPercent / 100.0f);
        
        // 启用顶点属性数组
        GLES20.glEnableVertexAttribArray(posHandle);
        GLES20.glEnableVertexAttribArray(colorHandle);
        
        // 设置粒子位置属性
        mParticlePositionBuffer.position(0);
        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, mParticlePositionBuffer);
        
        // 设置粒子颜色属性
        mParticleColorBuffer.position(0);
        GLES20.glVertexAttribPointer(colorHandle, 4, GLES20.GL_FLOAT, false, 0, mParticleColorBuffer);
        
        // 绑定粒子光晕纹理
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTexFlares);
        GLES20.glUniform1i(samplerHandle, 0);
        
        // 绘制所有粒子（点模式）
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, mParticleCount);
        
        // 禁用顶点属性数组
        GLES20.glDisableVertexAttribArray(posHandle);
        GLES20.glDisableVertexAttribArray(colorHandle);
    }
    
    /**
     * 绘制光效叠加层（移植自galaxy.rs的drawLights方法）
     * 使用MVP矩阵实现3D变换效果
     */
    private void drawLights() {
        // 使用光效着色器程序
        GLES20.glUseProgram(mLightProgram);
        
        // 获取着色器属性/统一变量句柄
        int posHandle = GLES20.glGetAttribLocation(mLightProgram, "aPosition");
        int texHandle = GLES20.glGetAttribLocation(mLightProgram, "aTexCoord");
        int mvpHandle = GLES20.glGetUniformLocation(mLightProgram, "uMVPMatrix");
        int samplerHandle = GLES20.glGetUniformLocation(mLightProgram, "uTexture");
        
        // 计算光效尺寸（移植自galaxy.rs line164）
        float sx = (512.0f / mWidth) * 1.1f;
        float sy = (512.0f / mWidth) * 1.2f;
        
        // 创建光效四边形顶点数据（XYZ坐标 + 纹理坐标）
        float[] vertices = {
            -sx, -sy, 0.0f, 0, 0,  // 左下
             sx, -sy, 0.0f, 1, 0,  // 右下
            -sx,  sy, 0.0f, 0, 1,  // 左上
             sx,  sy, 0.0f, 1, 1   // 右上
        };
        
        // 创建顶点缓冲区
        FloatBuffer vertexBuffer = createFloatBuffer(vertices);
        
        // 启用顶点属性数组
        GLES20.glEnableVertexAttribArray(posHandle);
        GLES20.glEnableVertexAttribArray(texHandle);
        
        // 设置MVP矩阵
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mMVPMatrix, 0);
        
        // 设置顶点坐标属性
        vertexBuffer.position(0);
        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 20, vertexBuffer);
        // 设置纹理坐标属性
        vertexBuffer.position(3);
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 20, vertexBuffer);
        
        // 绑定光效纹理
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTexLight1);
        GLES20.glUniform1i(samplerHandle, 0);
        
        // 绘制光效四边形
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        
        // 禁用顶点属性数组
        GLES20.glDisableVertexAttribArray(posHandle);
        GLES20.glDisableVertexAttribArray(texHandle);
    }
    
    /**
     * 创建FloatBuffer（封装字节序和内存分配）
     * @param data 浮点数组
     * @return 初始化完成的FloatBuffer
     */
    private FloatBuffer createFloatBuffer(float[] data) {
        // 分配直接内存（避免JVM堆拷贝）
        ByteBuffer bb = ByteBuffer.allocateDirect(data.length * 4);
        bb.order(ByteOrder.nativeOrder()); // 使用本地字节序
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(data); // 写入数据
        fb.position(0); // 重置指针
        return fb;
    }
}