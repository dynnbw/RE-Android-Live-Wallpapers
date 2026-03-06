package com.reandroid.wallpaper.grass;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * 月球天文计算工具类
 * 核心修复：
 * 1. 本地时间计算月球可见性（高度/方位）→ 保证月亮本地时间正常出现
 * 2. UTC时间计算月食判定条件（黄经差+黄纬食限）→ 保证月食无8小时偏移
 */
final class MoonCalculator {
    // 月相微调参数（单位：天，如需校准仅改此值，建议范围±0.1内）
    // 负数=提前月相，正数=延后月相，0=无微调
    private static final double PHASE_OFFSET_DAYS = 0.0;

    /**
     * 月球数据封装类
     * 分两类参数：
     * - Local后缀：本地时间基准 → 仅用于月球可见性（高度/方位）
     * - Utc后缀：UTC时间基准 → 仅用于月食判定（黄经差+黄纬食限）
     */
    static class MoonData {
        // 本地时间基准 - 月球可见性相关
        final double moonAltitudeDeg;      // 月球高度（度）
        final double moonAzimuthDeg;       // 月球方位角（度）
        final double moonHourAngleDeg;     // 月球时角（度）
        final double moonLongitudeLocalDeg;// 本地时间月球黄经（度）
        final double moonLatitudeLocalDeg; // 本地时间月球黄纬（度）
        final double sunAltitudeDeg;       // 太阳高度（度，用于日夜判定）

        // UTC时间基准 - 月食判定核心参数
        final double phaseAngleUtcDeg;     // UTC日月黄经差（满月/月相判定）
        final double moonLongitudeUtcDeg;  // UTC月球黄经（度）
        final double moonLatitudeUtcDeg;   // UTC月球黄纬（度，月食食限判定核心）

        /**
         * 构造方法：初始化所有月球数据
         * @param moonAltitudeDeg 本地月球高度
         * @param moonAzimuthDeg 本地月球方位角
         * @param moonHourAngleDeg 本地月球时角
         * @param moonLongitudeLocalDeg 本地月球黄经
         * @param moonLatitudeLocalDeg 本地月球黄纬
         * @param sunAltitudeDeg 本地太阳高度
         * @param phaseAngleUtcDeg UTC日月黄经差
         * @param moonLongitudeUtcDeg UTC月球黄经
         * @param moonLatitudeUtcDeg UTC月球黄纬
         */
        MoonData(double moonAltitudeDeg, double moonAzimuthDeg, double moonHourAngleDeg,
                 double moonLongitudeLocalDeg, double moonLatitudeLocalDeg, double sunAltitudeDeg,
                 double phaseAngleUtcDeg, double moonLongitudeUtcDeg, double moonLatitudeUtcDeg) {
            this.moonAltitudeDeg = moonAltitudeDeg;
            this.moonAzimuthDeg = moonAzimuthDeg;
            this.moonHourAngleDeg = moonHourAngleDeg;
            this.moonLongitudeLocalDeg = moonLongitudeLocalDeg;
            this.moonLatitudeLocalDeg = moonLatitudeLocalDeg;
            this.sunAltitudeDeg = sunAltitudeDeg;
            this.phaseAngleUtcDeg = phaseAngleUtcDeg;
            this.moonLongitudeUtcDeg = moonLongitudeUtcDeg;
            this.moonLatitudeUtcDeg = moonLatitudeUtcDeg;
        }
    }

    // 私有构造方法：禁止实例化
    private MoonCalculator() {
    }

    /**
     * 核心计算方法：获取月球数据
     * @param calendar 本地时间Calendar（无需手动转UTC，内部自动处理）
     * @param latDeg 观测点纬度（度）
     * @param lonDeg 观测点经度（度）
     * @return 包含本地/UTC双基准的月球数据
     */
    static MoonData compute(Calendar calendar, double latDeg, double lonDeg) {
        // ========== 1. 本地时间基准计算：月球可见性（高度/方位） ==========
        double jdLocal = julianDateLocal(calendar); // 本地儒略日
        double TLocal = (jdLocal - 2451545.0) / 36525.0; // 儒略世纪数（本地）
        double eps = computeObliquity(TLocal); // 黄赤交角（度）

        // 本地太阳位置计算
        double sunLonLocal = computeSunLongitude(TLocal);
        double[] sunEqLocal = eclipticToEquatorial(sunLonLocal, 0.0, eps);
        double sunRaLocal = sunEqLocal[0];
        double sunDecLocal = sunEqLocal[1];

        // 本地月球位置计算
        double[] moonLonLatLocal = computeMoonLongitudeLatitude(TLocal);
        double moonLonLocal = moonLonLatLocal[0];
        double moonLatLocal = moonLonLatLocal[1];
        double[] moonEqLocal = eclipticToEquatorial(moonLonLocal, moonLatLocal, eps);
        double moonRaLocal = moonEqLocal[0];
        double moonDecLocal = moonEqLocal[1];

        // 本地恒星时 + 月球/太阳高度/方位（决定月亮是否在本地天空出现）
        double lstLocal = localSiderealTime(jdLocal, lonDeg);
        double moonHourAngle = normalizeDegrees180(lstLocal - moonRaLocal);
        double moonAlt = altitudeDeg(latDeg, moonDecLocal, moonHourAngle);
        double moonAz = azimuthDeg(latDeg, moonDecLocal, moonHourAngle);
        double sunHourAngle = normalizeDegrees180(lstLocal - sunRaLocal);
        double sunAlt = altitudeDeg(latDeg, sunDecLocal, sunHourAngle);

        // ========== 2. UTC时间基准计算：月食判定核心参数 ==========
        double jdUtc = julianDateUtc(calendar); // UTC儒略日（核心修复：正确转换）
        double TUtc = (jdUtc - 2451545.0) / 36525.0; // 儒略世纪数（UTC）

        // UTC太阳黄经
        double sunLonUtc = computeSunLongitude(TUtc);
        // UTC月球黄经+黄纬（月食食限判定核心）
        double[] moonLonLatUtc = computeMoonLongitudeLatitude(TUtc);
        double moonLonUtc = moonLonLatUtc[0];
        double moonLatUtc = moonLonLatUtc[1];

        // UTC日月黄经差（满月判定）+ 相位微调（无叠加偏移）
        double phaseAngleUtc = normalizeDegrees(
                moonLonUtc - sunLonUtc + (PHASE_OFFSET_DAYS * 360.0 / 29.5306)
        );

        // ========== 3. 返回双基准月球数据 ==========
        return new MoonData(
                moonAlt, moonAz, moonHourAngle,
                moonLonLocal, moonLatLocal, sunAlt,
                phaseAngleUtc, moonLonUtc, moonLatUtc
        );
    }

    // ========== 核心天文计算辅助方法 ==========

    /**
     * 计算黄赤交角（度）
     * @param T 儒略世纪数
     * @return 黄赤交角（度）
     */
    private static double computeObliquity(double T) {
        return 23.43929111 - 0.0130042 * T - 1.64e-7 * T * T + 5.04e-7 * T * T * T;
    }

    /**
     * 计算太阳黄经（度）
     * @param T 儒略世纪数
     * @return 太阳黄经（度，归一化到0-360°）
     */
    private static double computeSunLongitude(double T) {
        double L0 = 280.46646 + 36000.76983 * T + 0.0003032 * T * T;
        double M = 357.52911 + 35999.05029 * T - 0.0001537 * T * T;
        double C = (1.914602 - 0.004817 * T - 0.000014 * T * T) * sinDeg(M)
                + (0.019993 - 0.000101 * T) * sinDeg(2.0 * M)
                + 0.000289 * sinDeg(3.0 * M);
        double omega = 125.04 - 1934.136 * T;
        double lambda = L0 + C - 0.00569 - 0.00478 * sinDeg(omega);
        return normalizeDegrees(lambda);
    }

    /**
     * 计算月球黄经+黄纬（度）
     * @param T 儒略世纪数
     * @return double[0]=月球黄经，double[1]=月球黄纬（均归一化到0-360°）
     */
    private static double[] computeMoonLongitudeLatitude(double T) {
        double Lp = 218.3164477 + 481267.88123421 * T - 0.0015786 * T * T
                + (T * T * T) / 538841.0 - (T * T * T * T) / 65194000.0;

        double D = 297.8501921 + 445267.1114034 * T - 0.0018819 * T * T
                + (T * T * T) / 545868.0 - (T * T * T * T) / 113065000.0;
        double M = 357.5291092 + 35999.0502909 * T - 0.0001536 * T * T
                + (T * T * T) / 24490000.0;
        double Mp = 134.9633964 + 477198.8675055 * T + 0.0087414 * T * T
                + (T * T * T) / 69699.0 - (T * T * T * T) / 14712000.0;
        double F = 93.2720950 + 483202.0175233 * T - 0.0036539 * T * T
                - (T * T * T) / 3526000.0 + (T * T * T * T) / 863310000.0;

        double dL = 6.289 * sinDeg(Mp)
                + 1.274 * sinDeg(2.0 * D - Mp)
                + 0.658 * sinDeg(2.0 * D)
                + 0.214 * sinDeg(2.0 * Mp)
                + 0.110 * sinDeg(D);
        double lon = normalizeDegrees(Lp + dL);

        double dB = 5.128 * sinDeg(F)
                + 0.280 * sinDeg(Mp + F)
                + 0.277 * sinDeg(Mp - F)
                + 0.173 * sinDeg(2.0 * D - F)
                + 0.055 * sinDeg(2.0 * D - Mp - F)
                + 0.046 * sinDeg(2.0 * D + F);
        double lat = dB;

        return new double[]{lon, lat};
    }

    /**
     * 黄道坐标转赤道坐标
     * @param lonDeg 黄道经度（度）
     * @param latDeg 黄道纬度（度）
     * @param epsDeg 黄赤交角（度）
     * @return double[0]=赤经，double[1]=赤纬（均归一化到0-360°）
     */
    private static double[] eclipticToEquatorial(double lonDeg, double latDeg, double epsDeg) {
        double lon = Math.toRadians(lonDeg);
        double lat = Math.toRadians(latDeg);
        double eps = Math.toRadians(epsDeg);

        double sinDec = Math.sin(lat) * Math.cos(eps)
                + Math.cos(lat) * Math.sin(eps) * Math.sin(lon);
        double dec = Math.asin(sinDec);

        double y = Math.sin(lon) * Math.cos(eps) - Math.tan(lat) * Math.sin(eps);
        double x = Math.cos(lon);
        double ra = Math.atan2(y, x);

        return new double[]{normalizeDegrees(Math.toDegrees(ra)), Math.toDegrees(dec)};
    }

    /**
     * 计算地方恒星时（度）
     * @param jd 儒略日
     * @param lonDeg 观测点经度（度）
     * @return 地方恒星时（度，归一化到0-360°）
     */
    private static double localSiderealTime(double jd, double lonDeg) {
        double T = (jd - 2451545.0) / 36525.0;
        double gmst = 280.46061837 + 360.98564736629 * (jd - 2451545.0)
                + 0.000387933 * T * T - (T * T * T) / 38710000.0;
        return normalizeDegrees(gmst + lonDeg);
    }

    /**
     * 计算天体高度角（度）
     * @param latDeg 观测点纬度（度）
     * @param decDeg 天体赤纬（度）
     * @param hourAngleDeg 天体时角（度）
     * @return 高度角（度）
     */
    private static double altitudeDeg(double latDeg, double decDeg, double hourAngleDeg) {
        double lat = Math.toRadians(latDeg);
        double dec = Math.toRadians(decDeg);
        double ha = Math.toRadians(hourAngleDeg);

        double sinAlt = Math.sin(lat) * Math.sin(dec)
                + Math.cos(lat) * Math.cos(dec) * Math.cos(ha);
        return Math.toDegrees(Math.asin(sinAlt));
    }

    /**
     * 计算天体方位角（度）
     * @param latDeg 观测点纬度（度）
     * @param decDeg 天体赤纬（度）
     * @param hourAngleDeg 天体时角（度）
     * @return 方位角（度，归一化到0-360°）
     */
    private static double azimuthDeg(double latDeg, double decDeg, double hourAngleDeg) {
        double lat = Math.toRadians(latDeg);
        double dec = Math.toRadians(decDeg);
        double ha = Math.toRadians(hourAngleDeg);

        double y = Math.sin(ha);
        double x = Math.cos(ha) * Math.sin(lat) - Math.tan(dec) * Math.cos(lat);
        return normalizeDegrees(Math.toDegrees(Math.atan2(y, x)) + 180.0);
    }

    // ========== 时间转换辅助方法 ==========

    /**
     * 计算本地时间儒略日
     * @param calendar 本地时间Calendar
     * @return 本地儒略日
     */
    private static double julianDateLocal(Calendar calendar) {
        long millis = calendar.getTimeInMillis();
        return millis / 86400000.0 + 2440587.5;
    }

    /**
     * 计算UTC时间儒略日（核心修复：本地时间→UTC时间正确转换）
     * @param calendar 本地时间Calendar
     * @return UTC儒略日
     */
    private static double julianDateUtc(Calendar calendar) {
        long millisLocal = calendar.getTimeInMillis();
        int offsetMs = calendar.getTimeZone().getOffset(millisLocal);
        long millisUtc = millisLocal + offsetMs;
        return millisUtc / 86400000.0 + 2440587.5;
    }

    // ========== 通用工具方法 ==========

    /**
     * 角度正弦值（输入角度，自动转弧度）
     * @param deg 角度（度）
     * @return 正弦值
     */
    private static double sinDeg(double deg) {
        return Math.sin(Math.toRadians(deg));
    }

    /**
     * 角度归一化到0-360°
     * @param deg 原始角度（度）
     * @return 归一化后角度（0-360°）
     */
    private static double normalizeDegrees(double deg) {
        double v = deg % 360.0;
        if (v < 0.0) v += 360.0;
        return v;
    }

    /**
     * 角度归一化到-180-180°
     * @param deg 原始角度（度）
     * @return 归一化后角度（-180-180°）
     */
    private static double normalizeDegrees180(double deg) {
        double v = normalizeDegrees(deg);
        if (v > 180.0) v -= 360.0;
        return v;
    }
}