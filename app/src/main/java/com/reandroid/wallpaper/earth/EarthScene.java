package com.reandroid.wallpaper.earth;

/**
 * Earth 壁纸场景逻辑层（纯 Java，无 GL/Android 依赖，可 JVM 测试）。
 *
 * <p>对应原版 {@code Model} + {@code actors/*}。相比原版有两处刻意的改动：
 *
 * <ol>
 *   <li><b>时间由外部注入</b>：原版在 {@code Earth.update()} 里直接调
 *       {@code Calendar.getInstance()}，导致这段招牌逻辑无法在 JVM 上验证。
 *       这里改成 {@link #setClock}，由 GL 层传进来。</li>
 *   <li><b>惯性改为 dt 正确</b>，见 {@link EarthCamera}。</li>
 * </ol>
 */
final class EarthScene {

    static final int CAMERA_DISTANCE = 0;
    static final int CAMERA_CLOSEUP = 1;
    static final int CAMERA_CORNER = 2;

    /** 每毫秒转多少度 = 360 / 86400000。 */
    static final float DEGREES_PER_MS = 4.1666667E-6f;

    /**
     * 恒星日相对太阳日多转的角度：360 × 366.2422/365.2422 ≈ 360.9856。
     *
     * <p>恒星日 23h56m04s，比太阳日短约 4 分钟，所以星星每天**提早约 4 分钟**升起。
     * 地球的自转是按太阳日（由时钟锁定的），星空要在这个基础上再快这一点点，
     * 相对地球表面才是"每恒星日转一圈"。
     */
    static final float SIDEREAL_DEG_PER_DAY = 360.9856f;

    /**
     * 网格 UV + 光源位置共同决定的几何常量。
     *
     * <p>关系是 **直射经度 = MESH_LON_OFFSET_DEG − earthAngleY**（斜率 −1 是绕 Y 轴旋转的必然结果，
     * 不是拟合出来的）。这个常量由实机观测标定，两个数据点：
     * <ul>
     *   <li>earthAngleY = 342° → 实测直射经度 145°E（马达加斯加显示凌晨 5~6 点）</li>
     *   <li>earthAngleY = 252° → 实测直射经度 235°E（北美被照亮）</li>
     * </ul>
     * 两点连线的斜率为 −1、截距 127，与纯旋转的几何一致。
     *
     * <p>注意符号：写成 `+` 偏移会整体错 6 小时，必须用 `−`。
     */
    static final float MESH_LON_OFFSET_DEG = 127.0f;

    /** 云层自转：0.008333334 度/秒，一天正好 720°。 */
    static final float CLOUDS_ROTATION_FACTOR = 0.008333334f;
    /** 月球轨道周期 27.3 天 → 每天 13.186813°。 */
    static final float MOON_DEGREES_PER_DAY = 13.186813f;
    /** 月球轨道半径（原版 {@code setAngle} 里写死的 4.0）。 */
    static final float MOON_ORBIT_RADIUS = 4.0f;
    /** 月球相对地球的缩放（原版常量）。 */
    static final float MOON_SCALE = 0.27247787f;

    /** 地球球体相对网格的缩放（原版常量）。 */
    static final float EARTH_SCALE = 0.993f;
    /** 云层球体缩放（原版常量）。 */
    static final float CLOUDS_SCALE = 0.99998397f;
    /** 高光球体缩放（原版常量）。 */
    static final float SPECULAR_SCALE = 1.008f;
    /** 高光层的固定朝向（原版常量）。 */
    static final float SPECULAR_ANGLE_Y = 285.0f;

    private final EarthCamera[] mCameras;
    private int mActiveCamera = CAMERA_DISTANCE;

    /** 空闲自转速度（度/秒）。默认等于原版的 6.0。 */
    private float mSpinDegPerSec = EarthCamera.IDLE_SPIN_DEG_PER_SEC;

    // 地球自转角（由时钟推出）
    private float mEarthAngleY;
    // 太阳直射经度（东经为正），只由 UTC 决定
    private float mSubsolarLon;
    /*
     * 云层自转：累加的是"经过的秒数"而不是角度本身。
     * 每帧 `angle += rate*dt` 会让 float 误差随帧数累积（实测 10 小时偏 0.05°，
     * 原版按帧累加漂得更厉害）；累加时间再乘速率则用 double，漂移可忽略。
     */
    private double mCloudSeconds;
    /** 星空自转角。方向与地球自转同向，速率略快（恒星日），于是星星每天西移约 1°。 */
    private float mSkyAngleY;
    // 月球轨道角与位置
    private float mMoonAngleY;
    private float mMoonX;
    private float mMoonZ;

    EarthScene() {
        // 三个镜头的位置取自原版 Model；注意原版反编译后写成了
        // `new Camera[]{camera, camera, camera}`（三格指向同一个对象），
        // 那是反编译损坏，正确意图是三个独立镜头。
        mCameras = new EarthCamera[] {
                new EarthCamera(CAMERA_DISTANCE, -0.35f, 0.0f, -6.0f),
                new EarthCamera(CAMERA_CLOSEUP, 1.3f, -1.0f, -2.8f),
                new EarthCamera(CAMERA_CORNER, -0.4f, 1.0f, -4.5f),
        };
    }

    EarthCamera[] cameras() {
        return mCameras;
    }

    EarthCamera activeCamera() {
        return mCameras[mActiveCamera];
    }

    int activeCameraId() {
        return mActiveCamera;
    }

    /** 空闲自转速度（度/秒）。 */
    void setSpinDegPerSec(float degPerSec) {
        mSpinDegPerSec = Math.max(0.0f, degPerSec);
    }

    float spinDegPerSec() {
        return mSpinDegPerSec;
    }

    /**
     * 注入时钟。由 GL 层按分钟级从 {@code Calendar} 取出后调用。
     *
     * @param msSinceNoon   当天**本地**相对 12:00 的毫秒数
     * @param tzRawOffsetMs 时区原始偏移（毫秒）
     * @param dayOfYear     一年中的第几天（1~366），用于月球轨道角
     * @param daysSinceEpoch 自纪元起的连续天数（用于恒星日漂移）
     */
    void setClock(long msSinceNoon, int tzRawOffsetMs, int dayOfYear, double daysSinceEpoch) {
        /*
         * 先算"太阳直射经度"，它**只由 UTC 时间决定**：
         *   UTC 正午 → 0°（格林尼治），之后每小时西移 15°，正午之前在东经。
         *
         * 原版写的是 `-75 - (tz.getRawOffset() + 本地距正午毫秒) * degPerMs`：
         * msSinceNoon 取自本地时间却又加了一次时区偏移，等于 `utcMs + 2*tz`，
         * **时区被算了两遍**（UTC+8 就多算 16 小时）。这里改成只用 UTC。
         */
        long utcMsSinceNoon = msSinceNoon - tzRawOffsetMs;
        mSubsolarLon = wrapSigned(-(utcMsSinceNoon / 3600000.0f) * 15.0f);

        // 再由直射经度反推地球自转角。符号必须是减：绕 Y 轴旋转下，角度与经度是反向的
        // （写成加会整体差 6 小时，实测踩过）。
        mEarthAngleY = wrap(MESH_LON_OFFSET_DEG - mSubsolarLon);

        setMoonAngle(dayOfYear * MOON_DEGREES_PER_DAY);

        // 星空：与地球同向，但按恒星日（略快），于是每天西移约 1°（≈4 分钟）
        /*
         * 先在 double 里取模、再转 float。
         *
         * daysSinceEpoch≈2e4，乘上 360.9856 ≈ 7.2e6 —— float 在这个量级的 ULP 约 0.5°，
         * 直接转等于把星空量化成半度一跳（长跑时肉眼可见地"顿"）。
         */
        mSkyAngleY = (float) wrap(-daysSinceEpoch * SIDEREAL_DEG_PER_DAY);
    }

    /** 太阳直射经度（东经为正）。只由 UTC 时间决定，与时区无关。 */
    float subsolarLongitude() {
        return mSubsolarLon;
    }

    /** 星空自转角（含恒星日漂移）。 */
    float skyAngleY() {
        return mSkyAngleY;
    }

    float earthAngleY() {
        return mEarthAngleY;
    }

    float cloudAngleY() {
        return wrap((float) (mCloudSeconds * CLOUDS_ROTATION_FACTOR));
    }

    float moonAngleY() {
        return mMoonAngleY;
    }

    float moonX() {
        return mMoonX;
    }

    float moonZ() {
        return mMoonZ;
    }

    /** 原版 {@code Moon.setAngle}：角度决定轨道上的位置。 */
    private void setMoonAngle(float deg) {
        mMoonAngleY = deg % 360.0f;
        double rad = Math.toRadians(deg);
        mMoonZ = (float) (Math.cos(rad) * MOON_ORBIT_RADIUS);
        mMoonX = (float) (Math.sin(rad) * MOON_ORBIT_RADIUS);
    }

    /**
     * 推进一帧。
     *
     * @param dt 本帧秒数
     */
    void update(float dt) {
        if (dt <= 0.0f) return;
        // 云层自转（不受镜头影响，原版就是独立累积的）
        mCloudSeconds += dt;
        for (EarthCamera c : mCameras) {
            c.update(dt, mSpinDegPerSec);
        }
    }

    /** 拖拽：转动当前镜头（原版对**所有**镜头都转发，因为它们共享同一份角度来源）。 */
    void drag(float dxPixels) {
        for (EarthCamera c : mCameras) {
            c.drag(dxPixels);
        }
    }

    /** 点击：切到下一个镜头。 */
    void onTap() {
        mActiveCamera = (mActiveCamera + 1) % mCameras.length;
    }

    private static float wrap(float deg) {
        return (float) wrap((double) deg);
    }

    /** 归一到 [0, 360)。角度量大时（如恒星日累计）要在 double 里取模，见 setClock。 */
    private static double wrap(double deg) {
        deg %= 360.0;
        if (deg < 0.0) deg += 360.0;
        return deg;
    }

    /** 归一到 (-180, 180]，用于经度。 */
    private static float wrapSigned(float deg) {
        deg %= 360.0f;
        if (deg > 180.0f) deg -= 360.0f;
        if (deg <= -180.0f) deg += 360.0f;
        return deg;
    }
}
