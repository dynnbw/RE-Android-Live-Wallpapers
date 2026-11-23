package com.reandroid.wallpaper.nightsky;

import android.opengl.Matrix;

import com.reandroid.utils.MathUtils;

/**
 * 星轨几何生成器 —— GLES 与 VK 共用。
 *
 * <p>输出是交错的「位置 + 颜色」顶点：{@code x, y, r, g, b, a}，每段轨迹 6 个顶点
 * （两个三角形）。几何只在时间加速时出现；采样时刻、分段淡出、宽度公式与原来的
 * GL 实现逐行一致。
 *
 * <p>位置已经是 NDC（调用方传入的投影矩阵做完除法后的结果），所以两条渲染路径都不需要
 * 再做变换：GLES 直接喂 {@code vec4(aPos,0,1)}，VK 侧把 y 取反、z 放到 0.5。
 *
 * <p>是否重算沿用原来的节流规则：视口、加速状态、回溯时长变化或间隔到期才重建，
 * 加速时逐帧重建（0 节流）。
 */
final class NightSkyTrailBuilder {

    static final int TRAIL_SEGMENTS = 8;
    static final int MAX_TRAIL_STARS = 5000;
    static final int MAX_VERTICES_PER_SEGMENT = 6;
    /** x, y, r, g, b, a */
    static final int FLOATS_PER_VERTEX = 6;

    static final int STRIDE_BYTES = FLOATS_PER_VERTEX * 4;

    private static final float MIN_TRAIL_BRIGHTNESS = 0.0f;
    private static final long TRAIL_GEOMETRY_UPDATE_INTERVAL_MS = 120L;
    private static final float[] SEG_FADE_START = buildFadeLut(0.82f);
    private static final float[] SEG_FADE_END = buildFadeLut(0.62f);

    /** 交错顶点数组；容量按 {@link #maxVertexCount(int)} 预留。 */
    float[] data;

    /** 当前有效顶点数。 */
    int vertexCount;

    private final float[] lstSamples = new float[TRAIL_SEGMENTS + 1];
    private final float[] sampledX = new float[TRAIL_SEGMENTS + 1];
    private final float[] sampledY = new float[TRAIL_SEGMENTS + 1];
    private final boolean[] sampledValid = new boolean[TRAIL_SEGMENTS + 1];
    private long lastGeometryUpdateMs = Long.MIN_VALUE;
    private int lastViewportWidth = -1;
    private int lastViewportHeight = -1;
    private boolean lastAccelerating;
    private long lastTrailLookbackSimMs = Long.MIN_VALUE;

    private final float[] tmpView = new float[4];
    private final float[] tmpViewRotated = new float[4];
    private final float[] tmpClip = new float[4];

    /** 新渲染器刚建好时用：清掉缓存，下一帧一定重算并上传。 */
    void reset() {
        lastGeometryUpdateMs = Long.MIN_VALUE;
        lastViewportWidth = -1;
        lastViewportHeight = -1;
        lastAccelerating = false;
        lastTrailLookbackSimMs = Long.MIN_VALUE;
        vertexCount = 0;
    }

    /** 给定星表的理论最大顶点数（与 {@code stride} 的取整方式一致）。 */
    static int maxVertexCount(int starCount) {
        if (starCount <= 0) {
            return 0;
        }
        int stride = Math.max(1, starCount / MAX_TRAIL_STARS);
        int sampledStarCount = (starCount + stride - 1) / stride;
        return sampledStarCount * TRAIL_SEGMENTS * MAX_VERTICES_PER_SEGMENT;
    }

    /**
     * 按需重算几何。
     *
     * @return 这一帧是否重建了 {@link #data}（只有重建时才需要重新上传）
     */
    boolean update(
            NightSkyCatalog catalog,
            float latRad,
            float lonDeg,
            long astronomyMs,
            long frameTimeMs,
            float[] viewRot,
            float[] proj,
            int viewportWidth,
            int viewportHeight,
            long trailLookbackSimMs,
            boolean accelerating) {
        if (trailLookbackSimMs <= 0L || catalog == null || catalog.starCount <= 0) {
            vertexCount = 0;
            return false;
        }

        for (int i = 0; i <= TRAIL_SEGMENTS; i++) {
            float frac = i / (float) TRAIL_SEGMENTS;
            long sampleMs = astronomyMs - (long) (trailLookbackSimMs * (1.0f - frac));
            lstSamples[i] = (float) NightSkyMath.computeLocalSiderealTimeRad(sampleMs, lonDeg);
        }

        boolean viewportChanged =
                viewportWidth != lastViewportWidth || viewportHeight != lastViewportHeight;
        boolean hasGeometry = vertexCount > 0 && data != null;
        // 加速时逐帧重建：星星每帧以最新 LST 绘制，轨迹必须同频更新，
        // 否则高速旋转下轨迹头滞后星星。静止时天空几乎不动，120ms 足够。
        long updateIntervalMs = accelerating ? 0L : TRAIL_GEOMETRY_UPDATE_INTERVAL_MS;
        boolean acceleratingStateChanged = accelerating != lastAccelerating;
        long lookbackDelta = Math.abs(trailLookbackSimMs
                - (lastTrailLookbackSimMs == Long.MIN_VALUE ? 0L : lastTrailLookbackSimMs));
        long lookbackThreshold = Math.max(500L, trailLookbackSimMs / 8L);
        boolean lookbackChanged =
                lastTrailLookbackSimMs == Long.MIN_VALUE || lookbackDelta >= lookbackThreshold;
        boolean intervalElapsed = lastGeometryUpdateMs == Long.MIN_VALUE
                || (frameTimeMs - lastGeometryUpdateMs) >= updateIntervalMs;

        if (!hasGeometry
                || viewportChanged
                || acceleratingStateChanged
                || lookbackChanged
                || intervalElapsed) {
            buildTrailLines(
                    catalog, latRad, lstSamples, viewRot, proj, viewportWidth, viewportHeight);
            lastGeometryUpdateMs = frameTimeMs;
            lastViewportWidth = viewportWidth;
            lastViewportHeight = viewportHeight;
            lastAccelerating = accelerating;
            lastTrailLookbackSimMs = trailLookbackSimMs;
            return true;
        }
        return false;
    }

    private void buildTrailLines(
            NightSkyCatalog catalog,
            float latRad,
            float[] lstSamples,
            float[] viewRot,
            float[] proj,
            int viewportWidth,
            int viewportHeight) {
        int stride = Math.max(1, catalog.starCount / MAX_TRAIL_STARS);
        int maxVertices = maxVertexCount(catalog.starCount);
        int needed = maxVertices * FLOATS_PER_VERTEX;
        if (data == null || data.length < needed) {
            data = new float[needed];
        }

        int v = 0;
        float minDim = Math.max(1.0f, Math.min(viewportWidth, viewportHeight));
        float sinLat = (float) Math.sin(latRad);
        float cosLat = (float) Math.cos(latRad);

        for (int i = 0; i < catalog.starCount; i += stride) {
            int pp = i * 4;
            int cc = i * 3;

            float raRad = (float) (catalog.starParams[pp] * NightSkyMath.DEG_TO_RAD);
            float decRad = (float) (catalog.starParams[pp + 1] * NightSkyMath.DEG_TO_RAD);
            float r = catalog.starColors[cc];
            float g = catalog.starColors[cc + 1];
            float b = catalog.starColors[cc + 2];

            float normBrightness = MathUtils.clamp(catalog.starParams[pp + 3], 0.0f, 1.0f);
            if (normBrightness < MIN_TRAIL_BRIGHTNESS) {
                continue;
            }
            float baseAlpha = catalog.starBaseAlpha[i];

            // 每星仅 lat/dec 的 sin/cos 预计算一次。
            float sinDec = (float) Math.sin(decRad);
            float cosDec = (float) Math.cos(decRad);
            for (int sample = 0; sample <= TRAIL_SEGMENTS; sample++) {
                if (projectEquatorialToNdcFast(
                        raRad, sinDec, cosDec, sinLat, cosLat, lstSamples[sample], viewRot, proj)) {
                    sampledX[sample] = tmpClip[0] / tmpClip[3];
                    sampledY[sample] = tmpClip[1] / tmpClip[3];
                    sampledValid[sample] = isFinite(sampledX[sample]) && isFinite(sampledY[sample]);
                } else {
                    sampledValid[sample] = false;
                }
            }

            for (int seg = 0; seg < TRAIL_SEGMENTS; seg++) {
                if (!sampledValid[seg] || !sampledValid[seg + 1]) {
                    continue;
                }
                float x0 = sampledX[seg];
                float y0 = sampledY[seg];
                float x1 = sampledX[seg + 1];
                float y1 = sampledY[seg + 1];

                float alpha0 = baseAlpha * SEG_FADE_START[seg];
                float alpha1 = baseAlpha * SEG_FADE_END[seg + 1];

                float dx = x1 - x0;
                float dy = y1 - y0;
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len < 1e-6f) {
                    continue;
                }

                float nx = -dy / len;
                float ny = dx / len;
                float trailWidthPx = 2.0f + 7.0f * (float) Math.pow(normBrightness, 0.62f);
                float halfWidthNdc = (trailWidthPx / minDim);

                float sx0 = x0 + nx * halfWidthNdc;
                float sy0 = y0 + ny * halfWidthNdc;
                float tx0 = x0 - nx * halfWidthNdc;
                float ty0 = y0 - ny * halfWidthNdc;

                float sx1 = x1 + nx * halfWidthNdc;
                float sy1 = y1 + ny * halfWidthNdc;
                float tx1 = x1 - nx * halfWidthNdc;
                float ty1 = y1 - ny * halfWidthNdc;

                // 每段一条带：两个三角形。
                v = putVertex(v, sx0, sy0, r, g, b, alpha0);
                v = putVertex(v, tx0, ty0, r, g, b, alpha0);
                v = putVertex(v, sx1, sy1, r, g, b, alpha1);

                v = putVertex(v, sx1, sy1, r, g, b, alpha1);
                v = putVertex(v, tx0, ty0, r, g, b, alpha0);
                v = putVertex(v, tx1, ty1, r, g, b, alpha1);
            }
        }

        vertexCount = v / FLOATS_PER_VERTEX;
    }

    /** 快速投影：sinDec/cosDec/sinLat/cosLat 已由调用方预计算。 */
    private boolean projectEquatorialToNdcFast(
            float raRad,
            float sinDec,
            float cosDec,
            float sinLat,
            float cosLat,
            float lstRad,
            float[] viewRot,
            float[] proj) {
        float h = lstRad - raRad;
        float sinH = (float) Math.sin(h);
        float cosH = (float) Math.cos(h);

        float east = -cosDec * sinH;
        float north = (sinDec * cosLat) - (cosDec * sinLat * cosH);
        float up = (sinDec * sinLat) + (cosDec * cosLat * cosH);

        tmpView[0] = east * 5.0f;
        tmpView[1] = north * 5.0f;
        tmpView[2] = up * 5.0f;
        tmpView[3] = 1.0f;

        Matrix.multiplyMV(tmpViewRotated, 0, viewRot, 0, tmpView, 0);
        Matrix.multiplyMV(tmpClip, 0, proj, 0, tmpViewRotated, 0);

        return tmpClip[3] > 0.0001f;
    }

    private static float[] buildFadeLut(float exponent) {
        float[] lut = new float[TRAIL_SEGMENTS + 1];
        for (int i = 0; i <= TRAIL_SEGMENTS; i++) {
            float t = i / (float) TRAIL_SEGMENTS;
            lut[i] = 0.30f + 0.70f * (float) Math.pow(t, exponent);
        }
        return lut;
    }

    private static boolean isFinite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    private int putVertex(int v, float x, float y, float r, float g, float b, float a) {
        data[v++] = x;
        data[v++] = y;
        data[v++] = r;
        data[v++] = g;
        data[v++] = b;
        data[v++] = a;
        return v;
    }
}
