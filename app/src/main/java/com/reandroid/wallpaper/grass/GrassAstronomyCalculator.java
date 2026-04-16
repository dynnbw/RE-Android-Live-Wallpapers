package com.reandroid.wallpaper.grass;

import java.util.Calendar;

final class GrassAstronomyCalculator {
    private GrassAstronomyCalculator() {
    }

    static SolarEclipse computeSolarEclipse(MoonCalculator.MoonData data, Calendar now,
            float solarRadiusMeanDeg, float lunarRadiusMeanDeg, float modelToleranceDeg) {
        float defaultRatio = lunarRadiusMeanDeg / solarRadiusMeanDeg;
        if (data.sunAltitudeDeg <= -0.8 || data.moonAltitudeDeg <= -2.0) {
            return new SolarEclipse(0.0f, 0.0f, defaultRatio);
        }
        float phase = (float) data.phaseAngleUtcDeg;
        float phaseDelta = Math.min(Math.abs(phase), Math.abs(360.0f - phase));
        float nodeLatitude = Math.abs((float) data.moonLatitudeLocalDeg);
        if (phaseDelta > 20.0f || nodeLatitude > 7.0f) {
            return new SolarEclipse(0.0f, 0.0f, defaultRatio);
        }
        float[] angularRadii = computeApparentAngularRadii(now, solarRadiusMeanDeg, lunarRadiusMeanDeg);
        float sunRadiusDeg = angularRadii[0];
        float moonRadiusDeg = angularRadii[1];
        double separationDeg = angularSeparationDeg(
                data.sunAltitudeDeg, data.sunAzimuthDeg,
                data.moonAltitudeDeg, data.moonAzimuthDeg);
        float orbitalSeparationDeg = (float) Math.hypot(phaseDelta, nodeLatitude);
        float separationCandidate = Math.min((float) separationDeg, orbitalSeparationDeg);
        float moonAltRad = (float) Math.toRadians(clamp((float) data.moonAltitudeDeg, 0.0f, 90.0f));
        float parallaxCorrection = 0.65f * (float) Math.cos(moonAltRad);
        float effectiveSeparationDeg = Math.max(0.0f,
                separationCandidate - modelToleranceDeg - 0.35f * parallaxCorrection);
        float overlapObscuration = computeDiscOverlapFraction(sunRadiusDeg, moonRadiusDeg, effectiveSeparationDeg);
        float solarVisible = smoothstep(-0.5f, 5.0f, (float) data.sunAltitudeDeg);
        float lunarVisible = smoothstep(-2.0f, 4.0f, (float) data.moonAltitudeDeg);
        float horizonAttenuation = smoothstep(1.5f, 12.0f,
                (float) Math.min(data.sunAltitudeDeg, data.moonAltitudeDeg));
        float conjunctionAttenuation = 1.0f - smoothstep(8.0f, 22.0f, phaseDelta);
        float nodeAttenuation = 1.0f - smoothstep(3.0f, 9.0f, nodeLatitude);
        float fraction = clamp(
                overlapObscuration * solarVisible * lunarVisible * horizonAttenuation
                        * conjunctionAttenuation * nodeAttenuation,
                0.0f, 1.0f);
        float signedAzDelta = normalizeSignedDegrees((float) (data.moonAzimuthDeg - data.sunAzimuthDeg));
        float phaseProgress = clamp((signedAzDelta + 12.0f) / 24.0f, 0.0f, 1.0f);
        float moonRadiusRatio = moonRadiusDeg / sunRadiusDeg;
        return new SolarEclipse(fraction, phaseProgress, moonRadiusRatio);
    }

    static MoonEclipse computeMoonEclipse(MoonCalculator.MoonData data) {
        double delta = Math.abs(data.phaseAngleUtcDeg - 180.0);
        if (delta > 10.0) return new MoonEclipse(0, 0.0f, 0.0f, 0.0f, 0.0f);
        double beta = Math.abs(data.moonLatitudeUtcDeg);
        if (beta > 10.2) return new MoonEclipse(0, 0.0f, 0.0f, 0.0f, 0.0f);
        double umbra = (1.0 - beta) / 0.27;
        double penumbra = (1.5 - beta) / 0.27;
        float pen = clamp((float) penumbra, 0.0f, 1.0f);
        float umb = clamp((float) umbra, 0.0f, 1.0f);
        float total = clamp((float) (umbra - 1.0), 0.0f, 1.0f);
        double phaseRad = Math.toRadians(data.phaseAngleUtcDeg);
        float basePhase;
        if (umbra <= 0.0) { basePhase = pen * 0.25f; }
        else if (umbra < 1.0) { basePhase = 0.25f + umb * 0.35f; }
        else { basePhase = 0.60f + total * 0.20f; }
        float phase = basePhase;
        float easedPhase = smoothstep(0.0f, 1.0f, phase);
        float offsetMag = mix(1.6f, -1.6f, easedPhase);
        float dirX = Math.sin(phaseRad) >= 0.0 ? 1.0f : -1.0f;
        float offX = dirX * offsetMag;
        if (umbra > 1.0) return new MoonEclipse(3, (float) umbra, phase, offX, 0.0f);
        if (umbra > 0.0) return new MoonEclipse(2, (float) umbra, phase, offX, 0.0f);
        if (penumbra > 0.0) return new MoonEclipse(1, clamp((float) penumbra, 0.0f, 1.0f), phase, offX, 0.0f);
        return new MoonEclipse(0, 0.0f, 0.0f, 0.0f, 0.0f);
    }

    static float clockProgress(float now, float start, float end) {
        float duration;
        float elapsed;
        if (start <= end) {
            duration = end - start;
            elapsed = now - start;
        } else {
            duration = (1440.0f - start) + end;
            elapsed = now >= start ? (now - start) : (1440.0f - start + now);
        }
        if (duration <= 0.0f) return 0.0f;
        return clamp(elapsed / duration, 0.0f, 1.0f);
    }

    static boolean isBetweenClock(float now, float start, float end) {
        if (start <= end) return now >= start && now < end;
        return now >= start || now < end;
    }

    private static float[] computeApparentAngularRadii(Calendar now, float solarRadiusMeanDeg,
            float lunarRadiusMeanDeg) {
        float d = (float) ((now.getTimeInMillis() / 86400000.0) - 10957.5);
        float sunMeanAnomaly = (float) Math.toRadians(normalizeDegreesFloat(357.5291f + 0.98560028f * d));
        float moonMeanAnomaly = (float) Math.toRadians(normalizeDegreesFloat(134.9634f + 13.064993f * d));
        float sunRadiusDeg = solarRadiusMeanDeg * (1.0f + 0.0167f * (float) Math.cos(sunMeanAnomaly));
        float moonRadiusDeg = lunarRadiusMeanDeg * (1.0f + 0.0549f * (float) Math.cos(moonMeanAnomaly));
        sunRadiusDeg = clamp(sunRadiusDeg, 0.258f, 0.275f);
        moonRadiusDeg = clamp(moonRadiusDeg, 0.245f, 0.285f);
        return new float[]{sunRadiusDeg, moonRadiusDeg};
    }

    private static double angularSeparationDeg(double alt1Deg, double az1Deg, double alt2Deg, double az2Deg) {
        double alt1 = Math.toRadians(alt1Deg);
        double alt2 = Math.toRadians(alt2Deg);
        double deltaAz = Math.toRadians(az1Deg - az2Deg);
        double cosD = Math.sin(alt1) * Math.sin(alt2)
                + Math.cos(alt1) * Math.cos(alt2) * Math.cos(deltaAz);
        cosD = Math.max(-1.0, Math.min(1.0, cosD));
        return Math.toDegrees(Math.acos(cosD));
    }

    private static float computeDiscOverlapFraction(float sunRadiusDeg, float moonRadiusDeg, float centerDistanceDeg) {
        if (sunRadiusDeg <= 0.0f || moonRadiusDeg <= 0.0f) return 0.0f;
        double r1 = sunRadiusDeg, r2 = moonRadiusDeg, d = centerDistanceDeg;
        if (d >= r1 + r2) return 0.0f;
        double overlapArea;
        if (d <= Math.abs(r1 - r2)) {
            double minR = Math.min(r1, r2);
            overlapArea = Math.PI * minR * minR;
        } else {
            overlapArea = circleIntersectionArea(r1, r2, d);
        }
        double sunArea = Math.PI * r1 * r1;
        if (sunArea <= 0.0) return 0.0f;
        return clamp((float) (overlapArea / sunArea), 0.0f, 1.0f);
    }

    private static double circleIntersectionArea(double r1, double r2, double d) {
        double r1Sq = r1 * r1, r2Sq = r2 * r2;
        double alpha = Math.acos(clampDouble((d * d + r1Sq - r2Sq) / (2.0 * d * r1), -1.0, 1.0));
        double beta = Math.acos(clampDouble((d * d + r2Sq - r1Sq) / (2.0 * d * r2), -1.0, 1.0));
        double part1 = r1Sq * alpha;
        double part2 = r2Sq * beta;
        double part3 = 0.5 * Math.sqrt(clampDouble(
                (-d + r1 + r2) * (d + r1 - r2) * (d - r1 + r2) * (d + r1 + r2), 0.0, Double.MAX_VALUE));
        return part1 + part2 - part3;
    }

    private static float normalizeSignedDegrees(float deg) {
        float n = deg % 360.0f;
        if (n > 180.0f) n -= 360.0f;
        else if (n < -180.0f) n += 360.0f;
        return n;
    }

    private static float normalizeDegreesFloat(float deg) {
        float n = deg % 360.0f;
        if (n < 0.0f) n += 360.0f;
        return n;
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = clamp((x - edge0) / (edge1 - edge0), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    private static float mix(float a, float b, float t) {
        return a * (1.0f - t) + b * t;
    }

    private static float clamp(float val, float min, float max) {
        return Math.max(min, Math.min(max, val));
    }

    private static double clampDouble(double val, double min, double max) {
        return Math.max(min, Math.min(max, val));
    }
}
