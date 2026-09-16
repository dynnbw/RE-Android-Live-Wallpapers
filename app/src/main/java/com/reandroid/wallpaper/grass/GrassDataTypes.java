package com.reandroid.wallpaper.grass;

import com.reandroid.utils.MathUtils;
import com.reandroid.weather.WeatherCondition;

final class Blade {
    float angle;
    int size;
    float xPos, yPos, offset, scale, lengthX, lengthY, hardness;
    float h, s, b;
    float turbulencex;
}

final class Dandelion {
    float x, y, speed, size, swayPhase, swaySpeed, rotationDeg;
}

final class Firefly {
    float x, y, vx, vy, size, phase, flickerSpeed;
}

final class LegacyParticle {
    int type;
    boolean active;
    float angle;
    int bladeNum, sizeNum, texture;
    long startTime, stayEndTime, silentEndTime, flareEndTime;
    long velocityRetargetTime;
    float originX, originY, dx, dy;

    LegacyParticle() {
    }
}

final class MoonEclipse {
    final int type;
    final float fraction, phase, shadowOffsetX, shadowOffsetY;

    MoonEclipse(int t, float fr, float ph, float sox, float soy) {
        type = t;
        fraction = fr;
        phase = ph;
        shadowOffsetX = sox;
        shadowOffsetY = soy;
    }
}

final class SolarEclipse {
    final float fraction, phase, moonRadiusRatio;

    SolarEclipse(float fr, float ph, float mrr) {
        fraction = fr;
        phase = ph;
        moonRadiusRatio = mrr;
    }
}

final class SceneData {
    final float[] projectionMatrix = new float[16];

    boolean grassEnabled, nightInvert, nightDesaturateGrass;
    boolean useAccurateSun, sunEnabled, moonEnabled, proceduralSunEnabled;
    float grassHeightScale, grassWidthScale, grassHardnessScale;
    boolean useGrassTint;
    float grassTintH, grassTintS, grassTintV;
    boolean dandelionEnabled, fireflyEnabled;
    boolean legacyDandelionEnabled, legacyFireflyEnabled;

    Blade[] blades;
    Dandelion[] dandelions;
    Firefly[] fireflies;
    LegacyParticle[] legacyNormal;
    LegacyParticle[] legacyExtras;
    LegacyParticle[] legacyNormalNight;
    LegacyParticle[] legacyExtrasNight;
    /**
     * 传统粒子的可见度。蒲公英白天出、萤火虫夜里出，各自再乘一个天气放行系数。
     *
     * <p>拆成两个而不是留一个 {@code legacyTransition} 单值：那个是双向交叉淡入
     * （蒲公英 = 1 − t、萤火虫 = t），只乘一个系数的话，压掉萤火虫会把蒲公英反推亮。
     */
    float legacyDandelionVisibility;
    float legacyFireflyVisibility;
    long legacyNow;

    float timeFraction, dawn, morning, afternoon, dusk;
    float newB;
    boolean isNight;
    float dandelionVisibility;
    float fireflyVisibility;
    float starVisibility;
    WeatherCondition weatherCondition;

    /** 白天权重 = 1 − 夜空权重。跟着日出日落平滑变化，0 是深夜、1 是白天。 */
    float dayWeight;
    /**
     * 天气色调（{@code texWeatherTone}）的透明度。
     *
     * <p>= 白天权重 × 天气开关的淡入淡出。晴天为 0。**不要**改成"夜里直接不画"的硬判断
     * —— 那样日出那一刻整片天空会瞬间换色。
     */
    float weatherToneAlpha;

    final float[] accurateWeights = new float[4];
    float solarEclipseWeight;
    double lastSunAltitude;

    boolean hasSunData;
    float sunX, sunY, sunAlpha, sunSize;

    boolean hasSolarEclipseOcclusion;
    SolarEclipse solarEclipseAtSun;
    float eclipseMoonX, eclipseMoonY, eclipseSunX, eclipseSunY, eclipseSunSize, eclipseSunAlpha;

    boolean moonVisible;
    float moonPhaseAngle, moonX, moonY, moonSize;
    boolean moonIsDaytime;
    float moonBrightness, moonAlpha, moonContrast, moonSaturation, moonBlueTint;
    MoonEclipse moonEclipse;

    float xDraw, dt;
    long animNowMs;

    boolean bladeIndexRebuildNeeded;
    boolean grassGeometryDirty;

    /** Compute simple-sun sky blend weights [wNight,wSunrise,wSunset,wSky]. Shared by GLES and Vulkan paths. */
    static void computeSimpleSkyWeights(float now, float dawn, float morning, float afternoon, float dusk, float[] out) {
        out[0] = 0.0f; out[1] = 0.0f; out[2] = 0.0f; out[3] = 0.0f;
        if (now >= 0.0f && now < dawn) {
            out[0] = 1.0f;
        } else if (now >= dawn && now <= morning) {
            float half = dawn + (morning - dawn) * 0.5f;
            if (now <= half) {
                float t = MathUtils.norm(dawn, half, now);
                out[0] = 1.0f - t;
                out[1] = t;
            } else {
                float t = MathUtils.norm(half, morning, now);
                out[1] = 1.0f - t;
                out[3] = t;
            }
        } else if (now > morning && now < afternoon) {
            out[3] = 1.0f;
        } else if (now >= afternoon && now <= dusk) {
            float half = afternoon + (dusk - afternoon) * 0.5f;
            if (now <= half) {
                float t = MathUtils.norm(afternoon, half, now);
                out[3] = 1.0f - t;
                out[2] = t;
            } else {
                float t = MathUtils.norm(half, dusk, now);
                out[2] = 1.0f - t;
                out[0] = t;
            }
        } else {
            out[0] = 1.0f;
        }
    }
}
