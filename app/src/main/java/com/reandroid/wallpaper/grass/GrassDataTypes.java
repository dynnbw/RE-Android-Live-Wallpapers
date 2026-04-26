package com.reandroid.wallpaper.grass;

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
    boolean useAccurateSun, sunEnabled, moonEnabled;
    float grassHeightScale, grassWidthScale, grassHardnessScale;
    boolean useGrassTint;
    float grassTintH, grassTintS, grassTintV;
    boolean dandelionEnabled, fireflyEnabled, legacyParticles;

    Blade[] blades;
    Dandelion[] dandelions;
    Firefly[] fireflies;
    LegacyParticle[] legacyNormal;
    LegacyParticle[] legacyExtras;
    int legacyType;
    long legacyNow;

    float timeFraction, dawn, morning, afternoon, dusk;
    float newB;
    boolean isNight;
    float dandelionVisibility;
    float fireflyVisibility;
    float starVisibility;
    WeatherCondition weatherCondition;

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
}
