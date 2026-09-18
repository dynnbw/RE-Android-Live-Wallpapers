package com.reandroid.wallpaper.holospiral;

import android.content.SharedPreferences;

/**
 * Holo Spiral 场景逻辑层（纯逻辑，不接触 GL）。
 *
 * <p>参数表、配色方案、几何数据生成、旋转角推进都在这里，GL 类只负责建缓冲和下发绘制。
 * 这些都不依赖 Android 的图形栈，可以在 JVM 上直接跑。
 */
final class HoloSpiralScene {

    /** 顶点格式：x, y, z, r, g, b, a。GL 侧建缓冲时要按它算 stride。 */
    static final int FLOATS_PER_VERTEX = 7;

    // ---- 可配置参数（由 setPluginPrefs 从注入的设置里读）----
    int numInnerPoints = 100;
    int numOuterPoints = 50;
    float maxPointSize = 75.0f;
    float innerRadius = 5.0f;
    float outerRadius = 10.0f;
    float fov = 60.0f;
    float spiralRotateSpeed = 15.0f;
    int innerColorPrimary = 0xB30000FF;
    int innerColorSecondary = 0xD2A633FF;
    int outerColor = 0xDC267894;
    int bgColorTop = 0xFF08001A;
    int bgColorBottom = 0xFF1A1A53;

    // ---- 固定常量（不对外暴露设置项）----
    static final float INNER_SPIRAL_DEPTH = 50.0f;
    static final float OUTER_SPIRAL_DEPTH = 30.0f;
    static final float SEPARATION_DEG = 23.0f;
    static final float INNER_ROTATE_SPEED = 1.5f;
    static final float OUTER_ROTATE_SPEED = 0.5f;

    private SharedPreferences mPluginPrefs;
    private boolean mGeometryDirty;

    private long mLastTimeMs;
    private float mInnerRotateAngle;
    private float mOuterRotateAngle;

    void setPluginPrefs(SharedPreferences prefs) {
        mPluginPrefs = prefs;
        readPrefs();
    }

    /** 读取全部参数；设置变更后重读会把几何置脏，由 GL 侧重建缓冲。 */
    private void readPrefs() {
        if (mPluginPrefs == null) return;
        SharedPreferences p = mPluginPrefs;
        String scheme = p.getString("holospiral_color_scheme", "default");

        numInnerPoints = p.getInt("holospiral_inner_points", 100);
        numOuterPoints = p.getInt("holospiral_outer_points", 50);
        maxPointSize = p.getInt("holospiral_point_size", 75);
        innerRadius = p.getInt("holospiral_inner_radius", 5);
        outerRadius = p.getInt("holospiral_outer_radius", 10);
        fov = p.getInt("holospiral_fov", 60);
        spiralRotateSpeed = p.getInt("holospiral_rotate_speed", 15);

        applyColorScheme(scheme);
        mGeometryDirty = true;
    }

    /** 配色方案只是一张表，没有设置项能单独改其中某个颜色。 */
    private void applyColorScheme(String scheme) {
        switch (scheme) {
            case "purple":
                innerColorPrimary = 0xB36600FF; innerColorSecondary = 0xD2CC33FF;
                outerColor = 0xDC8B00FF; bgColorTop = 0xFF0D001A; bgColorBottom = 0xFF1A0A3A;
                break;
            case "red":
                innerColorPrimary = 0xB3FF3300; innerColorSecondary = 0xD2FFAA33;
                outerColor = 0xDCFF6633; bgColorTop = 0xFF1A0008; bgColorBottom = 0xFF3A0A1A;
                break;
            case "green":
                innerColorPrimary = 0xB300FF44; innerColorSecondary = 0xD233FFAA;
                outerColor = 0xDC26FF78; bgColorTop = 0xFF001A08; bgColorBottom = 0xFF0A3A1A;
                break;
            case "gold":
                innerColorPrimary = 0xB3FFAA00; innerColorSecondary = 0xD2FFDD66;
                outerColor = 0xDCFFCC33; bgColorTop = 0xFF1A1000; bgColorBottom = 0xFF3A2A0A;
                break;
            case "ice":
                innerColorPrimary = 0xB300CCFF; innerColorSecondary = 0xD266EEFF;
                outerColor = 0xDC44CCFF; bgColorTop = 0xFF00101A; bgColorBottom = 0xFF0A2030;
                break;
            default: // "default"
                innerColorPrimary = 0xB30000FF; innerColorSecondary = 0xD2A633FF;
                outerColor = 0xDC267894; bgColorTop = 0xFF08001A; bgColorBottom = 0xFF1A1A53;
                break;
        }
    }

    /** 参数变过、需要重建几何缓冲。读一次就清掉。 */
    boolean consumeGeometryDirty() {
        boolean dirty = mGeometryDirty;
        mGeometryDirty = false;
        return dirty;
    }

    /** 重置时间基准与旋转角（初始化时用）。 */
    void resetAnimation() {
        mLastTimeMs = 0L;
        mInnerRotateAngle = 0.0f;
        mOuterRotateAngle = 0.0f;
    }

    /** 返回距上一帧的秒数；第一帧返回 0。 */
    float tickTime(long timeMs) {
        if (mLastTimeMs == 0L) {
            mLastTimeMs = timeMs;
            return 0.0f;
        }
        float dt = (timeMs - mLastTimeMs) * 0.001f;
        mLastTimeMs = timeMs;
        return dt;
    }

    float getInnerRotateAngle() {
        return mInnerRotateAngle;
    }

    float getOuterRotateAngle() {
        return mOuterRotateAngle;
    }

    /**
     * 推进旋转角。
     *
     * <p><b>调用方必须在绘制之后调用</b>：原实现是「按当前角度画，画完再推进」，
     * 提前推进会让相位整体差一帧。
     */
    void advanceRotateAngles(float dt) {
        mOuterRotateAngle = modulo360(mOuterRotateAngle + (dt * OUTER_ROTATE_SPEED));
        mInnerRotateAngle = modulo360(mInnerRotateAngle + (dt * INNER_ROTATE_SPEED));
    }

    /** 全屏背景的顶点数据（上下两个颜色）。 */
    float[] buildBackgroundData() {
        float[] top = convertColor(bgColorTop);
        float[] bottom = convertColor(bgColorBottom);

        return new float[] {
            -1.0f,  1.0f, 0.0f, top[0],    top[1],    top[2],    top[3],
            -1.0f, -1.0f, 0.0f, bottom[0], bottom[1], bottom[2], bottom[3],
             1.0f,  1.0f, 0.0f, top[0],    top[1],    top[2],    top[3],
             1.0f, -1.0f, 0.0f, bottom[0], bottom[1], bottom[2], bottom[3]
        };
    }

    /**
     * 螺旋点阵的顶点数据：位置沿螺旋线展开，颜色在两个端点色之间插值。
     *
     * <p>注意插值因子是 {@code sin(radians / 2)}，值域是 <b>[-1, 1]</b> 而不是 [0, 1]，
     * 所以颜色会**越过**两个端点色（某些通道为负，交给帧缓冲截断）。
     * 这是原实现的行为，逐字保留 —— 看着像 bug，但改了颜色就变，别顺手"修"。
     */
    float[] buildSpiralData(int count, float depth, float radius,
            float separationDegrees, int primaryColor, int secondaryColor) {
        float[] primary = convertColor(primaryColor);
        float[] secondary = convertColor(secondaryColor);

        float separationRads = (separationDegrees / 360.0f) * 2.0f * (float) Math.PI;
        float halfDepth = depth / 2.0f;
        float radians = 0.0f;

        float[] data = new float[count * FLOATS_PER_VERTEX];
        int idx = 0;

        for (int i = 0; i < count; i++) {
            float percentage = (float) i / (float) count;
            float x = radius * (float) Math.cos(radians);
            float y = radius * (float) Math.sin(radians);
            float z = (percentage * depth) - halfDepth;

            float r = (float) Math.sin(radians / 2.0f);
            float colorR = primary[0] + ((secondary[0] - primary[0]) * r);
            float colorG = primary[1] + ((secondary[1] - primary[1]) * r);
            float colorB = primary[2] + ((secondary[2] - primary[2]) * r);
            float colorA = primary[3] + ((secondary[3] - primary[3]) * r);

            data[idx++] = x;
            data[idx++] = y;
            data[idx++] = z;
            data[idx++] = colorR;
            data[idx++] = colorG;
            data[idx++] = colorB;
            data[idx++] = colorA;

            radians += separationRads;
        }

        return data;
    }

    /** ARGB → 归一化 RGBA。 */
    static float[] convertColor(int argb) {
        float a = ((argb >> 24) & 0xff) / 255.0f;
        float r = ((argb >> 16) & 0xff) / 255.0f;
        float g = ((argb >> 8) & 0xff) / 255.0f;
        float b = (argb & 0xff) / 255.0f;
        return new float[] {r, g, b, a};
    }

    /** 折到 [0, 360)。用乘法倒数估算圈数，与原实现一致。 */
    static float modulo360(float value) {
        int multiplier = (int) (value * (1.0f / 360.0f));
        return value - (multiplier * 360.0f);
    }
}
