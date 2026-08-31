package com.reandroid.wallpaper.nightsky;

import android.opengl.GLES20;
import android.opengl.Matrix;

import com.reandroid.utils.MathUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

final class NightSkyTrailRenderer {
    private static final int TRAIL_SEGMENTS = 8;
    private static final float MIN_TRAIL_BRIGHTNESS = 0.0f;
    private static final int MAX_TRAIL_STARS = 5000;
    private static final long TRAIL_GEOMETRY_UPDATE_INTERVAL_ACCEL_MS = 33L;
    private static final long TRAIL_GEOMETRY_UPDATE_INTERVAL_MS = 120L;
    private static final float[] SEG_FADE_START = buildFadeLut(0.82f);
    private static final float[] SEG_FADE_END = buildFadeLut(0.62f);

    private int program;
    private int aPos = -1;
    private int aColor = -1;
    private FloatBuffer linePosBuffer;
    private FloatBuffer lineColorBuffer;
    private int lineVertexCount;
    private float[] posScratch;
    private float[] colorScratch;
    private final float[] lstSamples = new float[TRAIL_SEGMENTS + 1];
    private final float[] sampledX = new float[TRAIL_SEGMENTS + 1];
    private final float[] sampledY = new float[TRAIL_SEGMENTS + 1];
    private final boolean[] sampledValid = new boolean[TRAIL_SEGMENTS + 1];
    private long lastGeometryUpdateMs = Long.MIN_VALUE;
    private int lastViewportWidth = -1;
    private int lastViewportHeight = -1;
    private boolean lastAccelerating;
    private long lastTrailLookbackSimMs = Long.MIN_VALUE;

    private final float[] tmpHorizon = new float[3];
    private final float[] tmpView = new float[4];
    private final float[] tmpViewRotated = new float[4];
    private final float[] tmpClip = new float[4];

    void init(int program) {
        this.program = program;
        if (program != 0) {
            aPos = GLES20.glGetAttribLocation(program, "aPos");
            aColor = GLES20.glGetAttribLocation(program, "aColor");
        } else {
            aPos = -1;
            aColor = -1;
        }
    }

    void draw(
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
            boolean accelerating
    ) {
        if (trailLookbackSimMs <= 0L || catalog == null || catalog.starCount <= 0 || program == 0) {
            return;
        }

        for (int i = 0; i <= TRAIL_SEGMENTS; i++) {
            float frac = i / (float) TRAIL_SEGMENTS;
            long sampleMs = astronomyMs - (long) (trailLookbackSimMs * (1.0f - frac));
            lstSamples[i] = (float) NightSkyMath.computeLocalSiderealTimeRad(sampleMs, lonDeg);
        }

        boolean viewportChanged = viewportWidth != lastViewportWidth || viewportHeight != lastViewportHeight;
        boolean hasGeometry = lineVertexCount > 0 && linePosBuffer != null && lineColorBuffer != null;
        // 加速时逐帧重建(0 节流):星星每帧以最新 LST 绘制,轨迹必须同频更新,
        // 否则高速旋转下轨迹头滞后星星(实测 0.1-0.3s)。静止时天空几乎不动,120ms 足够。
        long updateIntervalMs = accelerating
            ? 0L
            : TRAIL_GEOMETRY_UPDATE_INTERVAL_MS;
        boolean acceleratingStateChanged = accelerating != lastAccelerating;
        long lookbackDelta = Math.abs(trailLookbackSimMs - (lastTrailLookbackSimMs == Long.MIN_VALUE ? 0L : lastTrailLookbackSimMs));
        long lookbackThreshold = Math.max(500L, trailLookbackSimMs / 8L);
        boolean lookbackChanged = lastTrailLookbackSimMs == Long.MIN_VALUE || lookbackDelta >= lookbackThreshold;
        boolean intervalElapsed = lastGeometryUpdateMs == Long.MIN_VALUE
            || (frameTimeMs - lastGeometryUpdateMs) >= updateIntervalMs;

        if (!hasGeometry || viewportChanged || acceleratingStateChanged || lookbackChanged || intervalElapsed) {
            buildTrailLines(catalog, latRad, lstSamples, viewRot, proj, viewportWidth, viewportHeight);
            lastGeometryUpdateMs = frameTimeMs;
            lastViewportWidth = viewportWidth;
            lastViewportHeight = viewportHeight;
            lastAccelerating = accelerating;
            lastTrailLookbackSimMs = trailLookbackSimMs;
        }

        if (lineVertexCount <= 0) {
            return;
        }

        GLES20.glUseProgram(program);
        GLES20.glEnable(GLES20.GL_BLEND);
        // Trail fragment shader outputs premultiplied RGB (rgb * a), so use ONE, ONE.
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE);

        linePosBuffer.position(0);
        lineColorBuffer.position(0);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 2 * 4, linePosBuffer);
        GLES20.glEnableVertexAttribArray(aColor);
        GLES20.glVertexAttribPointer(aColor, 4, GLES20.GL_FLOAT, false, 4 * 4, lineColorBuffer);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, lineVertexCount);

        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisableVertexAttribArray(aColor);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    private void buildTrailLines(
            NightSkyCatalog catalog,
            float latRad,
            float[] lstSamples,
            float[] viewRot,
            float[] proj,
            int viewportWidth,
            int viewportHeight
    ) {
        int stride = Math.max(1, catalog.starCount / MAX_TRAIL_STARS);
        int sampledStarCount = (catalog.starCount + stride - 1) / stride;
        int maxVerticesPerSegment = 6;
        int maxVertices = sampledStarCount * TRAIL_SEGMENTS * maxVerticesPerSegment;
        ensureScratchCapacity(maxVertices * 2, maxVertices * 4);
        int p = 0;
        int c = 0;
        int v = 0;
        float minDim = Math.max(1.0f, Math.min(viewportWidth, viewportHeight));

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

            // 优化:每星仅 lat/dec 的 sin/cos 预计算一次(原实现每采样 6 trig,现 2 trig)
            float sinDec = (float) Math.sin(decRad);
            float cosDec = (float) Math.cos(decRad);
            float sinLat = (float) Math.sin(latRad);
            float cosLat = (float) Math.cos(latRad);
            for (int sample = 0; sample <= TRAIL_SEGMENTS; sample++) {
                if (projectEquatorialToNdcFast(raRad, sinDec, cosDec, sinLat, cosLat,
                        lstSamples[sample], viewRot, proj)) {
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

                // One ribbon per segment: two triangles, visually thick but still a single trail.
                p = appendVertex(posScratch, p, sx0, sy0);
                c = appendVertexColor(colorScratch, c, r, g, b, alpha0);
                p = appendVertex(posScratch, p, tx0, ty0);
                c = appendVertexColor(colorScratch, c, r, g, b, alpha0);
                p = appendVertex(posScratch, p, sx1, sy1);
                c = appendVertexColor(colorScratch, c, r, g, b, alpha1);

                p = appendVertex(posScratch, p, sx1, sy1);
                c = appendVertexColor(colorScratch, c, r, g, b, alpha1);
                p = appendVertex(posScratch, p, tx0, ty0);
                c = appendVertexColor(colorScratch, c, r, g, b, alpha0);
                p = appendVertex(posScratch, p, tx1, ty1);
                c = appendVertexColor(colorScratch, c, r, g, b, alpha1);
                v += 6;
            }
        }

        lineVertexCount = v;
        uploadBuffers(p, c);
    }

    /** 快速投影:sinDec/cosDec/sinLat/cosLat 已由调用方预计算(每采样仅 2 trig) */
    private boolean projectEquatorialToNdcFast(
            float raRad,
            float sinDec,
            float cosDec,
            float sinLat,
            float cosLat,
            float lstRad,
            float[] viewRot,
            float[] proj
    ) {
        float h = lstRad - raRad;
        float sinH = (float) Math.sin(h);
        float cosH = (float) Math.cos(h);

        float east = -cosDec * sinH;
        float north = (sinDec * cosLat) - (cosDec * sinLat * cosH);
        float up = (sinDec * sinLat) + (cosDec * cosLat * cosH);

        tmpHorizon[0] = east;
        tmpHorizon[1] = north;
        tmpHorizon[2] = up;

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

    private void ensureScratchCapacity(int posFloatCount, int colorFloatCount) {
        if (posScratch == null || posScratch.length < posFloatCount) {
            posScratch = new float[posFloatCount];
        }
        if (colorScratch == null || colorScratch.length < colorFloatCount) {
            colorScratch = new float[colorFloatCount];
        }
    }

    private void uploadBuffers(int usedPosFloats, int usedColorFloats) {
        if (linePosBuffer == null || linePosBuffer.capacity() < usedPosFloats) {
            linePosBuffer = ByteBuffer.allocateDirect(usedPosFloats * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer();
        }
        if (lineColorBuffer == null || lineColorBuffer.capacity() < usedColorFloats) {
            lineColorBuffer = ByteBuffer.allocateDirect(usedColorFloats * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer();
        }

        linePosBuffer.clear();
        linePosBuffer.put(posScratch, 0, usedPosFloats);
        linePosBuffer.position(0);

        lineColorBuffer.clear();
        lineColorBuffer.put(colorScratch, 0, usedColorFloats);
        lineColorBuffer.position(0);
    }

    private boolean isFinite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    private int appendVertex(float[] pos, int p, float x, float y) {
        pos[p++] = x;
        pos[p++] = y;
        return p;
    }

    private int appendVertexColor(float[] color, int c, float r, float g, float b, float a) {
        color[c++] = r;
        color[c++] = g;
        color[c++] = b;
        color[c++] = a;
        return c;
    }
}
