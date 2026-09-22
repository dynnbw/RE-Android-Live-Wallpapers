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
package com.reandroid.wallpaper.grass;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.opengl.Matrix;
import android.os.SystemClock;

import com.reandroid.astronomy.SunCalculator;
import com.reandroid.settings.WallpaperSettings;
import com.reandroid.weather.WeatherCondition;
import com.reandroid.weather.WeatherState;

import java.util.Calendar;
import java.util.Random;
import java.util.TimeZone;

import static com.reandroid.wallpaper.grass.GrassConstants.*;
import com.reandroid.utils.MathUtils;

/**
 * Grass 壁纸场景逻辑层（纯 Java，无 GL 调用）。
 * 负责所有状态管理、天文计算、粒子/草叶动画逻辑，并通过 SceneData 向 GrassGL 暴露数据。
 */
final class GrassScene {

    private static final long CELESTIAL_CACHE_INTERVAL_MS = 60000L;
    /** 日食几何的实机更新间隔。见 {@link #updateSolarEclipseState}。 */
    private static final long SOLAR_ECLIPSE_UPDATE_INTERVAL_MS = 15000L;
    /** 预览用的天文量缓存间隔：0 = 每帧都算（月亮要连续移动，不能按实机的 60 秒缓存）。 */
    private static final long PREVIEW_CELESTIAL_CACHE_MS = 0L;

    // ---- Plugin prefs ----
    private SharedPreferences mPluginPrefs;

    void setPluginPrefs(SharedPreferences prefs) {
        mPluginPrefs = prefs;
        mPrefsDirty = true; // 引擎重注入（设置变更）时触发下一帧重读
    }

    // ---- Instance fields ----
    int mWidth, mHeight;
    private boolean mIsPreview;
    private boolean mInitialized = false;

    final Random mRandom = new Random(System.currentTimeMillis());
    private final Calendar mCalendar = Calendar.getInstance();
    /** 草叶上的水珠。只认雨量强度，不自己判断天气。 */
    private final GrassWaterDroplets mWaterDroplets =
            new GrassWaterDroplets(new java.util.Random(), 160, 96);

    private final GrassWindField mWindField = new GrassWindField();
    private final GrassBladeSystem mBladeSystem;
    private final GrassDayNightSystem mDayNightSystem = new GrassDayNightSystem();
    final GrassRenderDataBuilder mRenderDataBuilder;

    // Sun state
    private float mXOffset = 0.0f;
    private int mSettingsHash = 0;
    private volatile boolean mPrefsDirty = true;

    // Settings
    private boolean mGrassEnabled = true;
    private boolean mNightInvert = false;
    private boolean mNightDesaturateGrass = false;
    private boolean mSunEnabled = false;
    private boolean mMoonEnabled = false;
    /** 草叶逆光开关。默认关 —— 默认还原 AOSP，增强做成开关。 */
    private boolean mGrassLightEnabled = false;
    /** HDR + 辉光开关。默认关。 */
    private boolean mGrassGlowEnabled = false;
    /** 光源位置的选择结果，避免每帧新建数组。 */
    private final float[] mLightPosScratch = new float[2];
    /** 上次重算逐叶遮挡的时刻。遮挡变化慢，限频算。 */
    private long mLastOcclusionMs;
    private boolean mProceduralSun = true;
    private float mGrassHeightScale = 1.0f;
    private float mGrassWidthScale = 1.0f;
    private float mGrassHardnessScale = 1.0f;
    private boolean mUseGrassTint = false;
    private float mGrassTintR = 1.0f, mGrassTintG = 1.0f, mGrassTintB = 1.0f;
    private float mGrassTintH = 0.0f, mGrassTintS = 0.0f, mGrassTintV = 1.0f;

    // Accurate sun / eclipse state
    private long mLastSolarEclipseUpdateMs = 0L;
    private float mSolarEclipseWeight = 0.0f;
    private long mLastCelestialComputeMs = 0L;
    private MoonCalculator.MoonData mCachedMoonData;

    int mBladeCount = DEFAULT_BLADE_COUNT;
    int mVertexCount, mIndexCount;

    // Dandelions / Fireflies
    Dandelion[] mDandelions;
    Firefly[] mFireflies;
    private int mDandelionCount = DEFAULT_DANDELION_COUNT;
    private int mFireflyCount = DEFAULT_FIREFLY_COUNT;
    private float mDandelionSpeedScale = 1.0f;
    private long mLastAnimTimeMs = 0L;
    private boolean mDandelionEnabled = false;
    private boolean mFireflyEnabled = false;

    /**
     * 天气对粒子的放行系数（0~1，淡入淡出）。见 {@link #update} —— 天气的放行是 0/1 的
     * 硬开关，直接乘上去会让粒子在切到阵雨/雷暴的那一帧凭空消失。
     */
    private float mDandelionWeatherGate = 1.0f;
    private float mFireflyWeatherGate = 1.0f;

    /** 天气切换时粒子淡入淡出的时长（秒）。 */
    private static final float WEATHER_PARTICLE_FADE_SEC = 1.2f;

    /** 天气色调的淡入淡出系数（0~1）。见 {@link #updateParticleVisibility}。 */
    private float mWeatherToneGate;

    /*
     * 本帧的可见度，由 updateParticleVisibility 算出，后面两个步骤（推进粒子位置、发布
     * SceneData）都要用。拆方法之前它们是 update() 里的局部变量，跨方法就得提成字段。
     */
    private float mDandelionVisibility;
    private float mFireflyVisibility;
    private float mStarVisibility;
    /** 夜空权重（不含天气遮蔽），天气色调与传统粒子都用它。 */
    private float mNightWeight;

    /** 天气色调淡入淡出的时长（秒）。比粒子慢一些 —— 整片天空换色本来就该更缓。 */
    private static final float WEATHER_TONE_FADE_SEC = 2.0f;

    /**
     * 风相位，也就是 {@code turbulencef2} 的 y 参数。
     *
     * <p>它是按当前倍率对时间**积分**出来的，不是拿开机时间乘倍率（见 {@link #update}）。
     * 积分式的相位在天气/日夜切换时是连续的，只有"转速"变化。
     */
    private float mWindPhase;

    /** 风相位每秒推进量（即原式里 uptimeMillis 的系数 0.00004/ms）。 */
    private static final float WIND_PHASE_PER_SEC = 0.04f;

    /**
     * 噪声场在 y 上的周期。
     *
     * <p>{@code noisef2} 内部对 y 取 {@code & 0xff}，而 {@code turbulencef2} 最高取到
     * 八度 f=4，三者(256/128/64)的最小公倍数就是 256。实测（{@code WindPhasePeriodTest}）：
     * 取模后的回绕帧变化 6.9E-4，比普通帧的 4.0E-3 还小，看不出接缝。
     *
     * <p>取模还有个副作用是好的：相位始终停在 [0,256)，而 {@code noisef2} 里
     * {@code t = f*y + 4096} 的 float 精度随 y 增大而变差，不取模跑上几天摆动会量化。
     */
    private static final float WIND_PHASE_PERIOD = 256.0f;

    // Weather-driven runtime overrides
    private WeatherCondition mWeatherCondition = WeatherCondition.D1_CLEAR;
    private boolean mHasWeatherNightOverride = false;
    private boolean mWeatherNightOverride = false;

    // Legacy particles（传统粒子，按类型独立开关，传统优先于现代粒子）
    private boolean mLegacyDandelionEnabled = false;
    private boolean mLegacyFireflyEnabled = false;
    int legacyDirection = 0;
    long legacyBlowTime = 0, legacyNow = 0;
    // 双套粒子常驻：蒲公英（白天）+ 萤火虫（夜晚），可见度 = 日夜权重 × 天气放行
    // 交叉淡入淡出。原版在日夜边界硬切换类型并整体重建，粒子突兀出现/消失。
    LegacyParticle[] legacyNormal = new LegacyParticle[LEGACY_MAX_NORMAL];
    LegacyParticle[] legacyExtras = new LegacyParticle[LEGACY_MAX_EXTRAS];
    LegacyParticle[] legacyNormalNight = new LegacyParticle[LEGACY_MAX_NORMAL];
    LegacyParticle[] legacyExtrasNight = new LegacyParticle[LEGACY_MAX_EXTRAS];

    // Rebuild signal
    private boolean mBladeIndexRebuildNeeded = false;
    private boolean mGrassGeometryDirty = true;

    // Tap 效果的实体轮换游标（非传统模式重置于点击点）
    private int mTapEntityIndex = 0;

    // Cached SceneData (reused to avoid allocations)
    private final SceneData mSceneData = new SceneData();

    // ---- Constructor ----
    GrassScene(int width, int height) {
        mWidth = width;
        mHeight = height;
        mBladeSystem = new GrassBladeSystem(mRandom, mWindField, width, height, mBladeCount);
        mRenderDataBuilder = new GrassRenderDataBuilder(new GrassRenderDataBuilder.LegacyParticleOps() {
            @Override
            public LegacyParticle createLegacyParticle(int type) {
                return GrassScene.this.createLegacyParticle(type);
            }

            @Override
            public void flyLegacyFirefly(LegacyParticle p, boolean isInit) {
                GrassScene.this.flyLegacyFirefly(p, isInit);
            }

            @Override
            public void flyLegacyDandelion(LegacyParticle p, boolean isInit) {
                GrassScene.this.flyLegacyDandelion(p, isInit);
            }
        });
    }

    // ---- Lifecycle ----

    void init(boolean isPreview) {
        if (mInitialized) return;
        mInitialized = true;
        mIsPreview = isPreview;
        mDayNightSystem.setPreview(isPreview);

        mWindField.init(mRandom);
        updateSettingsFromPrefs();
        mBladeSystem.initBlades();
        syncBladeBuffersFromSystem(true);
        initDandelions();
        initFireflies();

        mDayNightSystem.initDefaultLocation();

        Matrix.orthoM(mSceneData.projectionMatrix, 0, 0, mWidth, mHeight, 0, -1.0f, 1.0f);
        mXOffset = isPreview ? 0.5f : 0.0f;
    }

    void resize(int width, int height) {
        mWidth = width;
        mHeight = height;
        mBladeSystem.setViewport(width, height);
        Matrix.orthoM(mSceneData.projectionMatrix, 0, 0, width, height, 0, -1.0f, 1.0f);
        mBladeSystem.updateBladePositionsForViewport();
        syncBladeBuffersFromSystem(false);
        mGrassGeometryDirty = true;
        initDandelions();
        initFireflies();
    }

    void setOffset(float xOffset) {
        mXOffset = xOffset;
    }

    // ---- Tap interaction（移植自原版 MTK grass.rs addTap）----

    /**
     * 点击效果：在点击位置放出一颗粒子。
     * 传统模式：激活空闲 extra 粒子（原版 addTap 语义，随风吹走）；
     * 非传统模式：把一粒蒲公英/萤火虫重置于点击点（按夜空权重选昼夜类型）。
     */
    void addTap(float x, float y) {
        float nightWeight = computeStarVisibility();
        // 传统优先：对应类型的原版 extras 粒子优先，否则现代粒子
        if (nightWeight > 0.5f) {
            if (mLegacyFireflyEnabled) {
                LegacyParticle[] extras = legacyExtrasNight;
                for (int i = 0; i < extras.length; i++) {
                    LegacyParticle p = extras[i];
                    if (p != null && !p.active) {
                        p.originX = x;
                        p.originY = y;
                        p.startTime = legacyNow;
                        p.active = true;
                        return;
                    }
                }
                return;
            }
            if (mFireflies != null && mFireflies.length > 0) {
                Firefly f = mFireflies[mTapEntityIndex % mFireflies.length];
                f.x = x;
                f.y = y;
                mTapEntityIndex++;
            }
        } else {
            if (mLegacyDandelionEnabled) {
                LegacyParticle[] extras = legacyExtras;
                for (int i = 0; i < extras.length; i++) {
                    LegacyParticle p = extras[i];
                    if (p != null && !p.active) {
                        p.originX = x;
                        p.originY = y;
                        p.startTime = legacyNow;
                        p.active = true;
                        return;
                    }
                }
                return;
            }
            if (mDandelions != null && mDandelions.length > 0) {
                Dandelion d = mDandelions[mTapEntityIndex % mDandelions.length];
                d.x = x;
                d.y = y;
                mTapEntityIndex++;
            }
        }
    }

    SceneData getSceneData() {
        return mSceneData;
    }

    boolean consumeBladeIndexRebuildNeeded() {
        boolean v = mBladeIndexRebuildNeeded;
        mBladeIndexRebuildNeeded = false;
        return v;
    }

    boolean isInitialized() {
        return mInitialized;
    }

    void setWeatherState(WeatherState state) {
        if (state == null || state.condition == null) {
            mWeatherCondition = WeatherCondition.D1_CLEAR;
            mHasWeatherNightOverride = false;
            return;
        }
        mWeatherCondition = state.condition;
        // Do not override local day/night from weather payload.
        // RS original uses local time / sun times for sky phase; weather only affects overlays.
        mHasWeatherNightOverride = false;
    }

    // ---- Per-frame master update ----

    void update(long animNowMs) {
        if (!mInitialized) return;

        updateSettingsFromPrefs();

        float dt = advanceClock(animNowMs);
        updateSky();
        applyWeatherToSky();
        boolean bladeAnglesDirty = updateWindAndBlades(dt);
        updateParticleVisibility(dt);
        updateParticlePositions(dt, animNowMs);
        publishSceneData(dt, animNowMs, bladeAnglesDirty);

        // 本帧的脏标记已被渲染器消费，清掉，等下一帧重新置位
        mBladeIndexRebuildNeeded = false;
        mGrassGeometryDirty = false;
    }

    /**
     * 本帧的天文结果：时刻、亮度、昼夜。
     *
     * <p>包成一个类是因为这三个值要穿过下面好几个步骤；散成三个字段会让"它们是一组"这件事
     * 看不出来。复用同一个实例，不产生每帧分配。
     */
    private static final class SkyState {
        float timeFrac;
        float brightness;
        boolean isNight;
    }

    private final SkyState mSky = new SkyState();

    /** 推进时钟，返回本帧秒数。首帧没有上一帧，给个 16ms 的估值；长卡顿夹到 50ms。 */
    private float advanceClock(long animNowMs) {
        float dt = 0.016f;
        if (mLastAnimTimeMs > 0) {
            dt = (animNowMs - mLastAnimTimeMs) / 1000.0f;
            dt = clamp(dt, 0.0f, 0.05f);
        }
        mLastAnimTimeMs = animNowMs;
        return dt;
    }

    /** 预览模式下把一整天压进这么长的真实时间。 */
    private static final long PREVIEW_CYCLE_MS = 30000L;

    /**
     * 场景时钟（毫秒）。天文计算的唯一时间入口。
     *
     * <p>实际壁纸就是真实时间。预览模式把一整天压进 {@link #PREVIEW_CYCLE_MS} ——
     * 走的是**同一套真实算法**（太阳高度角、月亮相位、日食），只是时间轴压缩了，
     * 所以预览里能快速看到日月实际怎么走，而不是另跑一套粗糙渐变。
     *
     * <p>压缩本身在 {@code DayNightResolver.compressedClockMs} 里，ocean/windmill 的
     * 预览共用同一套（它们压到 9 秒）。
     */
    private long sceneClockMs() {
        long real = System.currentTimeMillis();
        if (!mIsPreview) return real;
        return mDayNightSystem.compressedClockMs(real, PREVIEW_CYCLE_MS);
    }

    /** 算本帧的天文状态（时刻 / 亮度 / 昼夜），顺带更新日、月与日食。 */
    private void updateSky() {
        long nowMs = sceneClockMs();
        // 门限按真实时间：nowMs 是模拟时间，预览下 1 小时会被压缩成 1.25 秒
        if (mDayNightSystem.getLastSunUpdateMs() == 0L
                || (System.currentTimeMillis() - mDayNightSystem.getLastSunUpdateMs()) > 3600000L) {
            mDayNightSystem.updateSunTimes(nowMs);
        }

        mSky.timeFrac = mDayNightSystem.timeFraction(nowMs);

        mDayNightSystem.updateAccurateWeights(nowMs);
        float[] accurate = mDayNightSystem.getAccurateWeights();
        mSky.brightness = clamp(accurate[3] + 0.6f * (accurate[1] + accurate[2]), 0.0f, 1.0f);
        mSky.isNight = mDayNightSystem.getLastSunAltitude() < 0.0;

        // Compute moon data once, reuse for sun/moon/eclipse rendering
        mCalendar.setTimeZone(mDayNightSystem.getTimeZone());
        mCalendar.setTimeInMillis(nowMs);
        Calendar now = mCalendar;
        MoonCalculator.MoonData moonData = getCachedMoonData(nowMs, now);
        updateSolarEclipseState(moonData, now);

        if (mSunEnabled) {
            computeSunPosition(now, moonData);
        } else {
            mSceneData.hasSunData = false;
            mSceneData.hasSolarEclipseOcclusion = false;
        }
        if (mMoonEnabled) {
            computeMoonData(now, moonData);
        } else {
            mSceneData.moonVisible = false;
        }
    }

    /** 天气对整体亮度与日月光晕的影响，叠在 {@link #updateSky} 算出的基准亮度上。 */
    private void applyWeatherToSky() {
        mSky.brightness = clamp(
                mSky.brightness * GrassWeatherSystem.brightnessMultiplier(mWeatherCondition), 0.0f, 1.0f);
        if (mSceneData.hasSunData) {
            mSceneData.sunAlpha = clamp(mSceneData.sunAlpha * GrassWeatherSystem.sunAlphaScale(mWeatherCondition),
                0.0f, 1.0f);
        }
        if (mSceneData.moonVisible) {
            mSceneData.moonAlpha = clamp(mSceneData.moonAlpha * GrassWeatherSystem.moonAlphaScale(mWeatherCondition),
                0.0f, 1.0f);
            mSceneData.moonBrightness = clamp(
                mSceneData.moonBrightness * GrassWeatherSystem.moonBrightnessScale(mWeatherCondition),
                0.0f, 1.0f);
            // 辉光开着时把月亮推进 HDR 区间（> 1 = 比白还亮）。
            //
            // **必须放在上面那个 clamp 之后。** 那个 clamp 的上限是 1，早先加在
            // computeMoonData 里的话会被它原样夹回来 —— 实测 moonBrightness 恒为 1.0，
            // 增益等于没加（而画面上就是"开不开辉光月亮都一个样"）。
            if (mGrassGlowEnabled) {
                mSceneData.moonBrightness *= GrassConstants.GLOW_MOON_GAIN;
            }
        }
    }

    /**
     * 推进风相位并重算草叶角度。
     *
     * @return 草叶角度是否变化到需要重建几何
     */
    private boolean updateWindAndBlades(float dt) {
        float dayNightWind = GrassWeatherSystem.windDayNightScale(mWeatherCondition, mSky.isNight);
        float windTimeScale = GrassWeatherSystem.windTimeScale(mWeatherCondition) * dayNightWind;
        /*
         * 相位对时间积分，不是拿开机时间乘倍率。
         *
         * 原式 `SystemClock.uptimeMillis() * 0.00004f * scale` 在 scale 变化的瞬间会让
         * 相位整体瞬移 uptime × 0.00004 × Δscale —— 开机一小时就是 144 × Δscale 个单位
         * （晴朗→雷暴 Δ=0.9，即瞬移 130）。相位一变，每根草采样到的噪声值同时换掉，
         * 整片草闪到另一个姿态：天气每 3 秒切一次时最明显，日夜切换走同一个式子
         * （dayNightWind 1.0 → 0.9~0.94）所以也会跳。
         *
         * 积分式在切换时只有"转速"变化，相位连续，草是平滑地加快/减慢而不是跳。
         */
        mWindPhase = (mWindPhase + dt * WIND_PHASE_PER_SEC * windTimeScale) % WIND_PHASE_PERIOD;
        boolean bladeAnglesDirty = mBladeSystem.updateBladeAngles(mWindPhase,
            GrassWeatherSystem.windAmplitudeScale(mWeatherCondition) * dayNightWind);
        // 上一帧的脏标记已被渲染器消费（几何已重建）：记录当前角度作为
        // 新一轮脏判定基准。避免高 FPS 下每帧增量跌破阈值导致草停止摆动。
        if (mSceneData.grassGeometryDirty) {
            mBladeSystem.markAnglesRendered();
        }
        return bladeAnglesDirty;
    }

    /** 算本帧各类粒子的可见度，并把天气放行做成淡入淡出（不能硬切）。 */
    private void updateParticleVisibility(float dt) {
        boolean allowDandelion = GrassWeatherSystem.allowsDandelion(mWeatherCondition);
        boolean allowFirefly = GrassWeatherSystem.allowsFirefly(mWeatherCondition);
        /*
         * 天气的放行要淡入淡出，不能硬切。
         *
         * 原来是把 allowDandelion/allowFirefly 直接并用在可见度上，于是切到阵雨/雷暴的
         * 那一帧蒲公英与萤火虫凭空消失，切回来又凭空出现。日夜那一层本来就是平滑的
         * （computeStarVisibility 的权重曲线），硬切只来自天气。
         */
        mDandelionWeatherGate = GrassWeatherSystem.fadeGate(
                mDandelionWeatherGate, allowDandelion, dt, WEATHER_PARTICLE_FADE_SEC);
        mFireflyWeatherGate = GrassWeatherSystem.fadeGate(
                mFireflyWeatherGate, allowFirefly, dt, WEATHER_PARTICLE_FADE_SEC);
        mDandelionVisibility = (mDandelionEnabled ? computeDandelionVisibility(mSky.timeFrac) : 0.0f)
                * mDandelionWeatherGate;
        mFireflyVisibility = (mFireflyEnabled ? computeFireflyVisibility(mSky.timeFrac) : 0.0f)
                * mFireflyWeatherGate;
        mNightWeight = computeStarVisibility();
        /*
         * 星空还要再被天气压一道：阴雨夜里不该满天星。
         * 太阳/月亮早有对应的 scale，星星这一层之前漏了，于是任何天气的夜空都长一样。
         */
        mStarVisibility = mNightWeight * GrassWeatherSystem.starVisibilityScale(mWeatherCondition);

        /*
         * 天气色调的开关同样要淡入淡出，不能硬切。
         *
         * 原来这一层写在渲染器里，是一句 `if (sd.isNight) return;` —— 于是日出那一刻天空
         * 从夜色直接跳到"夜色 + 蓝灰色调"，看上去就是夜晚突然变成白天。天气切换时也一样硬。
         * 现在拆成两段平滑的乘积：白天权重（本来就跟着日出日落连续变化）+ 天气开关的淡入淡出。
         */
        mWeatherToneGate = GrassWeatherSystem.fadeGate(mWeatherToneGate,
                GrassWeatherSystem.hasSkyTone(mWeatherCondition), dt, WEATHER_TONE_FADE_SEC);
        mSceneData.dayWeight = 1.0f - mNightWeight;
        mSceneData.weatherToneAlpha = mSceneData.dayWeight * mWeatherToneGate;
    }

    /** 推进粒子位置（传统优先：传统开关开启时现代粒子不再更新）。 */
    private void updateParticlePositions(float dt, long animNowMs) {
        if (!mLegacyDandelionEnabled && mDandelionVisibility > 0.001f && mDandelions != null) {
            GrassParticleSystem.updateDandelionPositions(mRandom, mDandelions, dt,
                    mDandelionSpeedScale, mWidth, mHeight);
        }
        if (!mLegacyFireflyEnabled && mFireflyVisibility > 0.001f && mFireflies != null) {
            GrassParticleSystem.updateFireflyPositions(mFireflies, dt, mWidth, mHeight);
        }
        if (mLegacyDandelionEnabled || mLegacyFireflyEnabled) {
            updateLegacyState(animNowMs);
        }
    }

    /** 把本帧的一切灌进 SceneData，交给渲染层。这一段只有赋值，没有逻辑。 */
    private void publishSceneData(float dt, long animNowMs, boolean bladeAnglesDirty) {
        mSceneData.grassEnabled = mGrassEnabled;
        mSceneData.nightInvert = mNightInvert;
        mSceneData.nightDesaturateGrass = mNightDesaturateGrass;
        mSceneData.sunEnabled = mSunEnabled;
        mSceneData.moonEnabled = mMoonEnabled;
        mSceneData.proceduralSunEnabled = mProceduralSun;
        mSceneData.grassHeightScale = mGrassHeightScale;
        mSceneData.grassWidthScale = mGrassWidthScale;
        mSceneData.grassHardnessScale = mGrassHardnessScale;
        mSceneData.useGrassTint = mUseGrassTint;
        mSceneData.grassTintH = mGrassTintH;
        mSceneData.grassTintS = mGrassTintS;
        mSceneData.grassTintV = mGrassTintV;
        mSceneData.dandelionEnabled = mDandelionVisibility > 0.001f;
        mSceneData.fireflyEnabled = mFireflyVisibility > 0.001f;
        mSceneData.dandelionVisibility = mDandelionVisibility;
        mSceneData.fireflyVisibility = mFireflyVisibility;
        mSceneData.starVisibility = mStarVisibility;
        mSceneData.legacyDandelionEnabled = mLegacyDandelionEnabled;
        mSceneData.legacyFireflyEnabled = mLegacyFireflyEnabled;
        mSceneData.blades = mBladeSystem.getBlades();
        mSceneData.dandelions = mDandelions;
        mSceneData.fireflies = mFireflies;
        mSceneData.legacyNormal = legacyNormal;
        mSceneData.legacyExtras = legacyExtras;
        mSceneData.legacyNormalNight = legacyNormalNight;
        mSceneData.legacyExtrasNight = legacyExtrasNight;
        // 传统粒子（另一套开关）同样叠天气：阵雨/雷暴/雪天不该有蒲公英和萤火虫。
        // 放行系数与现代粒子用的是同一个，两者行为自然一致。
        mSceneData.legacyDandelionVisibility = (1.0f - mNightWeight) * mDandelionWeatherGate;
        mSceneData.legacyFireflyVisibility = mNightWeight * mFireflyWeatherGate;
        mSceneData.legacyNow = legacyNow;
        mSceneData.timeFraction = mSky.timeFrac;
        mSceneData.dawn = mDayNightSystem.getDawn();
        mSceneData.morning = mDayNightSystem.getMorning();
        mSceneData.afternoon = mDayNightSystem.getAfternoon();
        mSceneData.dusk = mDayNightSystem.getDusk();
        mSceneData.newB = mSky.brightness;
        mSceneData.isNight = mSky.isNight;
        mSceneData.weatherCondition = mWeatherCondition;
        System.arraycopy(mDayNightSystem.getAccurateWeights(), 0, mSceneData.accurateWeights, 0, 4);
        mSceneData.solarEclipseWeight = mSolarEclipseWeight;
        mSceneData.lastSunAltitude = mDayNightSystem.getLastSunAltitude();
        // 低空橙红、高空白偏蓝。原来恒定乘 (1.25,1.61,1.84)，所以永远是白偏蓝。
        GrassSunAppearance.fill((float) mSceneData.lastSunAltitude, mSceneData.sunTint);

        // ---- 草叶逆光 ----
        // 总强度里已经含了开关、太阳高度角曲线和天气压制（见 GrassBacklight），
        // 所以这里**不要再套一层开关判断**。它为 0 时着色器提前返回，画面与今天一致。
        mSceneData.lightStrength = GrassBacklight.effectiveStrength(
                mGrassLightEnabled,
                (float) mSceneData.lastSunAltitude,
                mWeatherCondition);
        GrassBacklight.lightPosition(
                (float) mSceneData.lastSunAltitude,
                mSceneData.sunX, mSceneData.sunY,
                mSceneData.moonVisible, mSceneData.moonX, mSceneData.moonY,
                mLightPosScratch);
        mSceneData.lightX = mLightPosScratch[0];
        mSceneData.lightY = mLightPosScratch[1];

        // 辉光只发布用户的开关；设备能力由渲染线程再与一次（见 GrassGL.draw）。
        mSceneData.glowEnabled = mGrassGlowEnabled;

        // 逐叶遮挡**限频**重算。叶片摆动只影响朝向，那个每帧算；遮挡要等草长得够多
        // 或光源明显移动，250ms 一次足够，也把 O(n²) 的成本摊掉。
        int bladeCount = mSceneData.blades != null ? mSceneData.blades.length : 0;
        if (mSceneData.bladeOcclusion == null
                || mSceneData.bladeOcclusion.length != bladeCount) {
            mSceneData.bladeOcclusion = new float[bladeCount];
            mLastOcclusionMs = 0L;
        }
        long occlusionNowMs = SystemClock.uptimeMillis();
        if (occlusionNowMs - mLastOcclusionMs >= GrassBladeLighting.OCCLUSION_INTERVAL_MS) {
            mLastOcclusionMs = occlusionNowMs;
            for (int i = 0; i < bladeCount; i++) {
                mSceneData.bladeOcclusion[i] = GrassBladeLighting.occlusionOf(
                        mSceneData.blades, i, mSceneData.lightX, mSceneData.lightY);
            }
        }

        mSceneData.xDraw = mix(mWidth, 0.0f, mXOffset);
        mSceneData.dt = dt;
        mSceneData.animNowMs = animNowMs;
        mSceneData.bladeIndexRebuildNeeded = mBladeIndexRebuildNeeded;
        mSceneData.grassGeometryDirty = mGrassGeometryDirty || bladeAnglesDirty;

        // 水珠要读 blades / xDraw / dt / 草的缩放，所以放在这一段的最后。
        // 强度为 0 时它自己会停止生成并让已有的珠离场。
        mWaterDroplets.update(dt, GrassWeatherSystem.rainIntensity(mWeatherCondition),
                mSceneData.blades, mSceneData);
        mSceneData.water = mWaterDroplets;
    }

    // ---- Private update helpers ----

    private void syncBladeBuffersFromSystem(boolean rebuildIndices) {
        mVertexCount = mBladeSystem.getVertexCount();
        mIndexCount = mBladeSystem.getIndexCount();
        mRenderDataBuilder.setGeometry(mWidth, mHeight, mVertexCount, mIndexCount, mBladeSystem.getBladeSizes());
        mGrassGeometryDirty = true;
        if (rebuildIndices) {
            mBladeIndexRebuildNeeded = true;
        }
    }

    private void updateLegacyState(long animNowMs) {
        legacyNow = animNowMs;
        if (legacyBlowTime < legacyNow) {
            legacyDirection = (legacyDirection == 0) ? 1 : 0;
            legacyBlowTime = (long) (legacyNow + Math.random() * LEGACY_MAX_BLOW_INTERVAL);
        }
        ensureLegacySet(legacyNormal, legacyExtras, LEGACY_TYPE_DANDELION);
        ensureLegacySet(legacyNormalNight, legacyExtrasNight, LEGACY_TYPE_FIREFLY);
    }

    private void ensureLegacySet(LegacyParticle[] normal, LegacyParticle[] extras, int type) {
        for (int i = 0; i < normal.length; i++) {
            if (normal[i] == null) {
                normal[i] = createLegacyParticle(type);
                normal[i].active = true;
            }
        }
        for (int i = 0; i < extras.length; i++) {
            if (extras[i] == null) {
                extras[i] = createLegacyParticle(type);
                extras[i].active = false; // 空闲，等待点击激活（原版 addTap 语义）
            }
        }
    }

    // ---- Sun / Moon position computation ----

    private void computeSunPosition(Calendar now, MoonCalculator.MoonData moonData) {
        SunCalculator sunCalculator = mDayNightSystem.getSunCalculator();
        if (sunCalculator == null) {
            mSceneData.hasSunData = false;
            mSceneData.hasSolarEclipseOcclusion = false;
            return;
        }
        double hourAngleDeg = sunCalculator.computeHourAngle(now);
        float sunX = celestialXFromHourAngle(hourAngleDeg);
        float clampedAlt = clamp((float) mDayNightSystem.getLastSunAltitude(), 0.0f, 90.0f);
        float sunY = mHeight * (1.0f - clampedAlt / 90.0f);
        float sunSize = mWidth * 0.32f;
        // 地平线以上一律实心，只在地平线以下淡出 —— 见 GrassSunAppearance.opacity。
        // 原来这里是 (alt + 6) / 12，地平线处只有 0.5，低空的太阳于是是半透明的。
        float sunAlpha = GrassSunAppearance.opacity((float) mDayNightSystem.getLastSunAltitude());

        mSceneData.hasSunData = true;
        mSceneData.sunX = sunX;
        mSceneData.sunY = sunY;
        mSceneData.sunAlpha = sunAlpha;
        mSceneData.sunSize = sunSize;

        // Compute solar eclipse occlusion data
        if (moonData != null) {
            SolarEclipse eclipse = computeSolarEclipse(moonData, now);
            if (eclipse.fraction > 0.001f) {
                float moonX = moonXFromHourAngle(moonData.moonHourAngleDeg);
                float moonY = mHeight * (1.0f - clamp((float) moonData.moonAltitudeDeg, 0.0f, 90.0f) / 90.0f);
                mSceneData.hasSolarEclipseOcclusion = true;
                mSceneData.solarEclipseAtSun = eclipse;
                mSceneData.eclipseMoonX = moonX;
                mSceneData.eclipseMoonY = moonY;
                mSceneData.eclipseSunX = sunX;
                mSceneData.eclipseSunY = sunY;
                mSceneData.eclipseSunSize = sunSize;
                mSceneData.eclipseSunAlpha = sunAlpha;
                return;
            }
        }
        mSceneData.hasSolarEclipseOcclusion = false;
    }

    private void computeMoonData(Calendar now, MoonCalculator.MoonData data) {
        if (data == null || data.moonAltitudeDeg <= -2.0) {
            mSceneData.moonVisible = false;
            return;
        }
        float moonX = moonXFromHourAngle(data.moonHourAngleDeg);
        float clampedAlt = clamp((float) data.moonAltitudeDeg, 0.0f, 90.0f);
        float moonY = mHeight * (1.0f - clampedAlt / 90.0f);
        float size = mWidth * 0.24f;
        float baseBrightness = clamp((float) ((data.moonAltitudeDeg + 2.0) / 30.0), 0.0f, 1.0f);
        MoonEclipse eclipse = computeMoonEclipse(data);

        mSceneData.moonVisible = true;
        mSceneData.moonPhaseAngle = (float) data.phaseAngleUtcDeg;
        mSceneData.moonRotationDeg = (float) data.parallacticAngleDeg;
        mSceneData.moonX = moonX;
        mSceneData.moonY = moonY;
        mSceneData.moonSize = size;
        mSceneData.moonBrightness = baseBrightness;
        mSceneData.moonAlpha = 1.0f;
        mSceneData.moonContrast = 1.0f;
        mSceneData.moonSaturation = 1.0f;
        mSceneData.moonBlueTint = 0.0f;
        mSceneData.moonEclipse = eclipse;
    }

    /**
     * 蒲公英可见度 = 白天权重（1 - 夜空权重），与星星/萤火虫/传统粒子同一曲线，
     * 随天空渐变而非 1 分钟阶梯。
     */
    private float computeDandelionVisibility(float now) {
        return 1.0f - computeStarVisibility();
    }

    /**
     * 萤火虫可见度 = 夜空权重，与星星同一曲线。
     */
    private float computeFireflyVisibility(float now) {
        return computeStarVisibility();
    }

    /**
     * 星星可见度与夜空贴图同曲线：跟随 accurateWeights[0]（两者同一来源），
     * 随 night 贴图一起淡入淡出。
     * 之前是独立的 1 分钟阶梯，夜空还在渐变时星星就瞬间全亮。
     */
    private float computeStarVisibility() {
        float[] weights = mDayNightSystem.getAccurateWeights();
        return weights != null ? weights[0] : 0.0f;
    }


    private float celestialXFromHourAngle(double hourAngleDeg) {
        float ratio = (float) ((hourAngleDeg + 180.0) / 360.0);
        float ratioExtended = ratio * 1.4f - 0.1f;
        return mWidth * clamp(ratioExtended, -0.1f, 1.1f);
    }

    private float moonXFromHourAngle(double hourAngleDeg) {
        float ratio = (float) ((hourAngleDeg + 180.0) / 360.0);
        return mWidth * clamp(ratio, -0.1f, 1.1f);
    }

    // ---- Settings management ----

    private void updateSettingsFromPrefs() {
        if (!mPrefsDirty) return;
        mPrefsDirty = false;

        // ---- Plugin-aware settings fallback ----
        SharedPreferences p = mPluginPrefs;

        boolean legacyDandelion = p != null
                ? p.getBoolean(WallpaperSettings.KEY_GRASS_LEGACY_DANDELION, false)
                : WallpaperSettings.isGrassLegacyDandelionEnabled(false);
        if (legacyDandelion != mLegacyDandelionEnabled) {
            mLegacyDandelionEnabled = legacyDandelion;
            if (mLegacyDandelionEnabled) initLegacyParticles();
        }
        boolean legacyFirefly = p != null
                ? p.getBoolean(WallpaperSettings.KEY_GRASS_LEGACY_FIREFLY, false)
                : WallpaperSettings.isGrassLegacyFireflyEnabled(false);
        if (legacyFirefly != mLegacyFireflyEnabled) {
            mLegacyFireflyEnabled = legacyFirefly;
            if (mLegacyFireflyEnabled) initLegacyParticles();
        }
        WallpaperSettings.GrassTint tint = p != null
                ? readGrassTint(p)
                : WallpaperSettings.getGrassTint();
        int newBladeCount = p != null
                ? p.getInt(WallpaperSettings.KEY_GRASS_COUNT, DEFAULT_BLADE_COUNT)
                : WallpaperSettings.getGrassBladeCount(DEFAULT_BLADE_COUNT);
        boolean newEnabled = p != null
                ? p.getBoolean(WallpaperSettings.KEY_GRASS_ENABLED, true)
                : WallpaperSettings.isGrassEnabled(true);
        boolean newNightInvert = p != null
                ? p.getBoolean(WallpaperSettings.KEY_GRASS_NIGHT_INVERT, false)
                : WallpaperSettings.isNightInvert(false);
        boolean newNightDesaturate = p != null
                ? p.getBoolean(WallpaperSettings.KEY_GRASS_NIGHT_DESATURATE, false)
                : WallpaperSettings.isGrassNightDesaturateEnabled(false);
        boolean newSunEnabled = p != null
                ? p.getBoolean(WallpaperSettings.KEY_GRASS_SUN, true)
                : WallpaperSettings.isSunEnabled(true);
        boolean newMoonEnabled = p != null
                ? p.getBoolean(WallpaperSettings.KEY_GRASS_MOON, true)
                : WallpaperSettings.isMoonEnabled(true);
        boolean newProceduralSun = p != null
                ? p.getBoolean(WallpaperSettings.KEY_GRASS_PROCEDURAL_SUN, true)
                : WallpaperSettings.isProceduralSunEnabled(true);
        boolean newGrassLight = p != null
                ? p.getBoolean(WallpaperSettings.KEY_GRASS_LIGHT, false)
                : WallpaperSettings.isGrassLightEnabled(false);
        boolean newGrassGlow = p != null
                ? p.getBoolean(WallpaperSettings.KEY_GRASS_GLOW, false)
                : WallpaperSettings.isGrassGlowEnabled(false);
        float newHeightScale = p != null
                ? clamp(p.getInt(WallpaperSettings.KEY_GRASS_HEIGHT, Math.round(1.0f * 100.0f)) / 100.0f, 0.1f, 10.0f)
                : WallpaperSettings.getGrassHeightScale(1.0f);
        float newWidthScale = p != null
                ? clamp(p.getInt(WallpaperSettings.KEY_GRASS_WIDTH, Math.round(1.0f * 100.0f)) / 100.0f, 0.1f, 10.0f)
                : WallpaperSettings.getGrassWidthScale(1.0f);
        float newHardnessScale = p != null
                ? clamp(p.getInt(WallpaperSettings.KEY_GRASS_HARDNESS, Math.round(1.0f * 100.0f)) / 100.0f, 0.3f, 10.0f)
                : WallpaperSettings.getGrassHardnessScale(1.0f);
        boolean newDandelionEnabled = p != null
                ? p.getBoolean(WallpaperSettings.KEY_GRASS_DANDELION, false)
                : WallpaperSettings.isDandelionEnabled(false);
        boolean newFireflyEnabled = p != null
                ? p.getBoolean(WallpaperSettings.KEY_GRASS_FIREFLY, false)
                : WallpaperSettings.isFireflyEnabled(false);
        int newDandelionCount = p != null
                ? Math.max(1, p.getInt(WallpaperSettings.KEY_GRASS_DANDELION_COUNT, DEFAULT_DANDELION_COUNT))
                : WallpaperSettings.getDandelionCount(DEFAULT_DANDELION_COUNT);
        int newFireflyCount = p != null
                ? Math.max(1, p.getInt(WallpaperSettings.KEY_GRASS_FIREFLY_COUNT, DEFAULT_FIREFLY_COUNT))
                : WallpaperSettings.getFireflyCount(DEFAULT_FIREFLY_COUNT);
        float newDandelionSpeedScale = p != null
                ? clamp(p.getInt(WallpaperSettings.KEY_GRASS_DANDELION_SPEED, Math.round(2.0f * 100.0f)) / 100.0f, 1.0f, 10.0f)
                : WallpaperSettings.getDandelionSpeedScale(2.0f);

        int hash = 17;
        hash = 31 * hash + newBladeCount;
        hash = 31 * hash + (newEnabled ? 1 : 0);
        hash = 31 * hash + (newNightInvert ? 1 : 0);
        hash = 31 * hash + (newNightDesaturate ? 1 : 0);
        hash = 31 * hash + (newSunEnabled ? 1 : 0);
        hash = 31 * hash + (newMoonEnabled ? 1 : 0);
        hash = 31 * hash + (newProceduralSun ? 1 : 0);
        hash = 31 * hash + (newGrassLight ? 1 : 0);
        hash = 31 * hash + (newGrassGlow ? 1 : 0);
        hash = 31 * hash + Float.floatToIntBits(newHeightScale);
        hash = 31 * hash + Float.floatToIntBits(newWidthScale);
        hash = 31 * hash + Float.floatToIntBits(newHardnessScale);
        hash = 31 * hash + (newDandelionEnabled ? 1 : 0);
        hash = 31 * hash + (newFireflyEnabled ? 1 : 0);
        hash = 31 * hash + newDandelionCount;
        hash = 31 * hash + newFireflyCount;
        hash = 31 * hash + Float.floatToIntBits(newDandelionSpeedScale);
        hash = 31 * hash + (tint.enabled ? 1 : 0);
        hash = 31 * hash + tint.color;

        if (hash == mSettingsHash) return;
        mSettingsHash = hash;

        mGrassEnabled = newEnabled;
        mNightInvert = newNightInvert;
        mNightDesaturateGrass = newNightDesaturate;
        mSunEnabled = newSunEnabled;
        mMoonEnabled = newMoonEnabled;
        mProceduralSun = newProceduralSun;
        mGrassLightEnabled = newGrassLight;
        mGrassGlowEnabled = newGrassGlow;
        mGrassHeightScale = newHeightScale;
        mGrassWidthScale = newWidthScale;
        mGrassHardnessScale = newHardnessScale;
        mDandelionEnabled = newDandelionEnabled;
        mFireflyEnabled = newFireflyEnabled;
        mDandelionCount = Math.max(1, newDandelionCount);
        mFireflyCount = Math.max(1, newFireflyCount);
        mDandelionSpeedScale = newDandelionSpeedScale;
        mUseGrassTint = tint.enabled;
        mGrassTintR = ((tint.color >> 16) & 0xFF) / 255.0f;
        mGrassTintG = ((tint.color >> 8) & 0xFF) / 255.0f;
        mGrassTintB = (tint.color & 0xFF) / 255.0f;
        float[] hsv = rgbToHsb(mGrassTintR, mGrassTintG, mGrassTintB);
        mGrassTintH = hsv[0];
        mGrassTintS = hsv[1];
        mGrassTintV = hsv[2];

        if (newBladeCount > 0 && newBladeCount != mBladeCount) {
            mBladeCount = newBladeCount;
            mBladeSystem.setBladeCount(mBladeCount);
            mBladeSystem.initBlades();
            syncBladeBuffersFromSystem(true);
        }
        if (!mLegacyDandelionEnabled) {
            if (mDandelions == null || mDandelions.length != mDandelionCount) initDandelions();
        }
        if (!mLegacyFireflyEnabled) {
            if (mFireflies == null || mFireflies.length != mFireflyCount) initFireflies();
        }
    }

    // ---- Blade methods ----

    private void initBlades() {
        mBladeSystem.setBladeCount(mBladeCount);
        mBladeSystem.initBlades();
        syncBladeBuffersFromSystem(true);
    }

    // ---- Dandelion / Firefly methods ----

    private void initDandelions() {
        mDandelions = GrassParticleSystem.initDandelions(mRandom, mDandelionCount, mWidth, mHeight);
    }

    void resetDandelion(Dandelion d, boolean randomX) {
        GrassParticleSystem.resetDandelion(mRandom, d, randomX, mWidth, mHeight);
    }

    private void initFireflies() {
        mFireflies = GrassParticleSystem.initFireflies(mRandom, mFireflyCount, mWidth, mHeight);
    }

    // ---- Legacy particle methods ----

    private void initLegacyParticles() {
        legacyNow = SystemClock.uptimeMillis();
        legacyDirection = 0;
        legacyBlowTime = (long) (legacyNow + Math.random() * LEGACY_MAX_BLOW_INTERVAL);
        // 双套粒子常驻：蒲公英 + 萤火虫，按夜空权重交叉淡入淡出
        for (int i = 0; i < LEGACY_MAX_NORMAL; i++) {
            legacyNormal[i] = createLegacyParticle(LEGACY_TYPE_DANDELION);
        }
        for (int i = 0; i < LEGACY_MAX_EXTRAS; i++) {
            legacyExtras[i] = createLegacyParticle(LEGACY_TYPE_DANDELION);
            legacyExtras[i].active = false; // 空闲，等待点击激活
        }
        for (int i = 0; i < LEGACY_MAX_NORMAL; i++) {
            legacyNormalNight[i] = createLegacyParticle(LEGACY_TYPE_FIREFLY);
        }
        for (int i = 0; i < LEGACY_MAX_EXTRAS; i++) {
            legacyExtrasNight[i] = createLegacyParticle(LEGACY_TYPE_FIREFLY);
            legacyExtrasNight[i].active = false; // 空闲，等待点击激活
        }
    }

    LegacyParticle createLegacyParticle(int type) {
        return GrassParticleSystem.createLegacyParticle(mRandom, legacyNow, legacyDirection,
                type, mWidth, mHeight);
    }

    void flyLegacyFirefly(LegacyParticle p, boolean isInit) {
        GrassParticleSystem.flyLegacyFirefly(mRandom, p, isInit, legacyNow, legacyDirection, mWidth, mHeight);
    }

    void flyLegacyDandelion(LegacyParticle p, boolean isInit) {
        GrassParticleSystem.flyLegacyDandelion(mRandom, p, isInit, legacyNow, legacyDirection, mWidth, mHeight);
    }

    /**
     * @param now 当前**模拟**时刻的日历，由调用方设好（见 {@link #updateSky}）。
     *            这里不再自己读时钟 —— 否则预览下日食几何会走真实时间，和其他部分脱节。
     */
    private void updateSolarEclipseState(MoonCalculator.MoonData data, Calendar now) {
        // 节流按真实时间；预览下不节流，否则一个 30 秒周期只更新两次，日食会一跳一跳
        long nowMs = System.currentTimeMillis();
        long interval = mIsPreview ? 0L : SOLAR_ECLIPSE_UPDATE_INTERVAL_MS;
        if (mLastSolarEclipseUpdateMs != 0L && (nowMs - mLastSolarEclipseUpdateMs) < interval) return;
        if (data == null) {
            mSolarEclipseWeight = 0.0f;
            mLastSolarEclipseUpdateMs = nowMs;
            return;
        }
        SolarEclipse eclipse = computeSolarEclipse(data, now);
        float[] accurateWeights = mDayNightSystem.getAccurateWeights();
        float dayVisibility = clamp(accurateWeights[3] + 0.45f * (accurateWeights[1] + accurateWeights[2]),
                0.0f, 1.0f);
        float eased = smoothstep(0.0f, 1.0f, eclipse.fraction);
        mSolarEclipseWeight = clamp(eased * dayVisibility, 0.0f, 1.0f);
        mLastSolarEclipseUpdateMs = nowMs;
    }

    private MoonCalculator.MoonData getCachedMoonData(long nowMs, Calendar now) {
        if (mDayNightSystem.getSunCalculator() == null) {
            mCachedMoonData = null;
            return null;
        }

        // 缓存按真实时间衡量 —— nowMs 是模拟时间，预览下 60 秒会被压缩成一帧
        long realNowMs = System.currentTimeMillis();
        if (mCachedMoonData != null && (realNowMs - mLastCelestialComputeMs) < celestialCacheIntervalMs()) {
            return mCachedMoonData;
        }

        mCachedMoonData = MoonCalculator.compute(now,
                mDayNightSystem.getLatitude(), mDayNightSystem.getLongitude());
        mLastCelestialComputeMs = realNowMs;
        return mCachedMoonData;
    }

    /**
     * 天文量的缓存间隔。
     *
     * <p>预览把一天压进 30 秒，实机那 60 秒的缓存会让月亮整个卡住不动；预览下取 0，
     * 也就是逐帧重算 —— {@code MoonCalculator.compute} 只有几十次三角函数，微秒量级。
     */
    private long celestialCacheIntervalMs() {
        return mIsPreview ? PREVIEW_CELESTIAL_CACHE_MS : CELESTIAL_CACHE_INTERVAL_MS;
    }

    // ---- Astronomical computation ----

    private SolarEclipse computeSolarEclipse(MoonCalculator.MoonData data, Calendar now) {
        return GrassAstronomyCalculator.computeSolarEclipse(data, now,
            SOLAR_MEAN_ANGULAR_RADIUS_DEG,
            LUNAR_MEAN_ANGULAR_RADIUS_DEG,
            SOLAR_ECLIPSE_MODEL_TOLERANCE_DEG);
    }

    private MoonEclipse computeMoonEclipse(MoonCalculator.MoonData data) {
        return GrassAstronomyCalculator.computeMoonEclipse(data);
    }

    // ---- Noise ----

    // ---- Math utilities ----

    float clamp(float val, float min, float max) {
        return Math.max(min, Math.min(max, val));
    }

    float mix(float a, float b, float t) {
        return a * (1 - t) + b * t;
    }

    float normf(float start, float stop, float value) {
        return (value - start) / (stop - start);
    }

    float smoothstep(float edge0, float edge1, float x) {
        float t = clamp((x - edge0) / (edge1 - edge0), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }


    int hsbToRgb(float h, float s, float b) {
        float red = 0.0f, green = 0.0f, blue = 0.0f;
        float hf = (h - (int) h) * 6.0f;
        int ihf = (int) hf;
        float f = hf - ihf;
        float pv = b * (1.0f - s);
        float qv = b * (1.0f - s * f);
        float tv = b * (1.0f - s * (1.0f - f));
        switch (ihf) {
            case 0: red = b; green = tv; blue = pv; break;
            case 1: red = qv; green = b; blue = pv; break;
            case 2: red = pv; green = b; blue = tv; break;
            case 3: red = pv; green = qv; blue = b; break;
            case 4: red = tv; green = pv; blue = b; break;
            case 5: red = b; green = pv; blue = qv; break;
        }
        return Color.argb(255, (int) (red * 255), (int) (green * 255), (int) (blue * 255));
    }

    float[] rgbToHsb(float r, float g, float b) {
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float delta = max - min;
        float h;
        if (delta == 0.0f) { h = 0.0f; }
        else if (max == r) { h = ((g - b) / delta) % 6.0f; }
        else if (max == g) { h = ((b - r) / delta) + 2.0f; }
        else { h = ((r - g) / delta) + 4.0f; }
        h /= 6.0f;
        if (h < 0.0f) h += 1.0f;
        float s = max == 0.0f ? 0.0f : (delta / max);
        return new float[]{h, s, max};
    }

    // ---- Random helpers ----

    float random(float range) {
        return mRandom.nextFloat() * range;
    }

    float random(float min, float max) {
        return min + mRandom.nextFloat() * (max - min);
    }

    // ---- Plugin-aware helpers ----

    private static WallpaperSettings.GrassTint readGrassTint(SharedPreferences p) {
        String value = p.getString(WallpaperSettings.KEY_GRASS_COLOR, "default");
        if (value == null || "default".equals(value)) {
            return new WallpaperSettings.GrassTint(false, Color.WHITE);
        }
        try {
            return new WallpaperSettings.GrassTint(true, Color.parseColor(value));
        } catch (IllegalArgumentException ex) {
            return new WallpaperSettings.GrassTint(false, Color.WHITE);
        }
    }
}
