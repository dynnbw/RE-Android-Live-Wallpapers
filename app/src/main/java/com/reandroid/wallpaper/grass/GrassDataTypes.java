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
    boolean sunEnabled, moonEnabled, proceduralSunEnabled;
    float grassHeightScale, grassWidthScale, grassHardnessScale;
    boolean useGrassTint;
    float grassTintH, grassTintS, grassTintV;
    boolean dandelionEnabled, fireflyEnabled;
    boolean legacyDandelionEnabled, legacyFireflyEnabled;

    Blade[] blades;

    /**
     * 挂在草叶上的水珠与水花。
     *
     * <p>这里放的是**系统本身**而不是摊平的数组 —— 它的活跃数是内部维护的，
     * 摊出来就得每帧同步两份状态，容易不一致。
     */
    GrassWaterDroplets water;
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

    /**
     * 草叶逆光：选好的光源屏幕位置与总强度。
     *
     * <p>位置由 {@link GrassBacklight#lightPosition} 选（太阳 / 月亮 / 兜底），
     * 强度由 {@link GrassBacklight#effectiveStrength} 算（开关 × 高度角 × 天气）。
     * <p>强度为 0 时着色器提前返回，画面与加特效之前逐像素一致。
     */
    float lightX, lightY, lightStrength;
    /** 逐叶遮挡，与 {@link #blades} 一一对应；由 GrassScene 限频重算。null = 还没算过。 */
    float[] bladeOcclusion;

    /** 取第 {@code index} 片叶的遮挡，越界或没算过时返回 1（完全受光）。 */
    float bladeOcclusion(int index) {
        if (bladeOcclusion == null || index < 0 || index >= bladeOcclusion.length) {
            return 1.0f;
        }
        return bladeOcclusion[index];
    }

    /** 太阳本体的 RGB 增益，随高度角变化 —— 低空橙红、高空白偏蓝。见 {@link GrassSunAppearance}。 */
    final float[] sunTint = new float[]{1.0f, 1.0f, 1.0f};

    boolean hasSolarEclipseOcclusion;
    SolarEclipse solarEclipseAtSun;
    float eclipseMoonX, eclipseMoonY, eclipseSunX, eclipseSunY, eclipseSunSize, eclipseSunAlpha;

    boolean moonVisible;
    float moonPhaseAngle, moonX, moonY, moonSize;
    /**
     * 月面相对屏幕的旋转角（度）。来自地平纬角 —— 月亮的朝向在天空里是固定的，
     * 观察者的"上"随纬度与时刻变，两者之差就是要转的角度。
     *
     * <p>注意是**整体**旋转：月面纹理和明暗终止线一起转，不是只转终止线。
     */
    float moonRotationDeg;
    float moonBrightness, moonAlpha, moonContrast, moonSaturation, moonBlueTint;
    MoonEclipse moonEclipse;

    float xDraw, dt;
    long animNowMs;

    boolean bladeIndexRebuildNeeded;
    boolean grassGeometryDirty;
}
