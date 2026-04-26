package com.reandroid.wallpaper.nightsky;

import android.content.Context;
import android.content.res.Resources;

import com.reandroid.wallpaper.R;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

final class NightSkyCatalogLoader {
    private static final int MAX_STAR_COUNT = 200_000;
    private static final float MAG_LIMIT = 6.5f;

    private NightSkyCatalogLoader() {
    }

    static NightSkyCatalog load(Context context) {
        if (context == null) {
            return emptyCatalog();
        }

        NightSkyCatalog compact = tryLoadCompact(context);
        if (compact != null) {
            return compact;
        }
        return emptyCatalog();
    }

    private static NightSkyCatalog tryLoadCompact(Context context) {
        Resources resources = context.getResources();
        if (resources == null) {
            return null;
        }

        try (InputStream input = resources.openRawResource(R.raw.hip_v65);
             DataInputStream din = new DataInputStream(input)) {
            int starCount = readIntLE(din);
            if (starCount < 0 || starCount > MAX_STAR_COUNT) {
                return null;
            }
            float[] starParams = new float[starCount * 4];
            float[] starColors = new float[starCount * 3];
            for (int i = 0; i < starCount; i++) {
                int p = i * 4;
                int c = i * 3;
                starParams[p] = readFloatLE(din);
                starParams[p + 1] = readFloatLE(din);
                starParams[p + 2] = readFloatLE(din);
                starParams[p + 3] = readFloatLE(din);
                starColors[c] = readFloatLE(din);
                starColors[c + 1] = readFloatLE(din);
                starColors[c + 2] = readFloatLE(din);
            }

            int brightCount = readIntLE(din);
            if (brightCount < 0 || brightCount > starCount) {
                return null;
            }
            float[] brightParams = new float[brightCount * 4];
            float[] brightColors = new float[brightCount * 3];
            for (int i = 0; i < brightCount; i++) {
                int p = i * 4;
                int c = i * 3;
                brightParams[p] = readFloatLE(din);
                brightParams[p + 1] = readFloatLE(din);
                brightParams[p + 2] = readFloatLE(din);
                brightParams[p + 3] = readFloatLE(din);
                brightColors[c] = readFloatLE(din);
                brightColors[c + 1] = readFloatLE(din);
                brightColors[c + 2] = readFloatLE(din);
            }

            float[] starBaseAlpha = new float[starCount];
            float[] brightBaseAlpha = new float[brightCount];
            precomputePhotometryChannels(starParams, starBaseAlpha, brightParams, brightBaseAlpha);
            return new NightSkyCatalog(
                    starParams,
                    starColors,
                    starBaseAlpha,
                    brightParams,
                    brightColors,
                    brightBaseAlpha
            );
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static int readIntLE(DataInputStream din) throws IOException {
        int b0 = din.readUnsignedByte();
        int b1 = din.readUnsignedByte();
        int b2 = din.readUnsignedByte();
        int b3 = din.readUnsignedByte();
        return (b0 & 0xFF)
                | ((b1 & 0xFF) << 8)
                | ((b2 & 0xFF) << 16)
                | ((b3 & 0xFF) << 24);
    }

    private static float readFloatLE(DataInputStream din) throws IOException {
        return Float.intBitsToFloat(readIntLE(din));
    }

    private static void precomputePhotometryChannels(
            float[] allParams,
            float[] allBaseAlpha,
            float[] brightParams,
            float[] brightBaseAlpha
    ) {
        float maxBrightness = computeMaxBrightness(allParams);
        if (maxBrightness <= 0.0f) {
            fillPrecomputed(allParams, allBaseAlpha, 0.0f, 0.0f);
            fillPrecomputed(brightParams, brightBaseAlpha, 0.0f, 0.0f);
            return;
        }
        fillPrecomputedByMax(allParams, allBaseAlpha, maxBrightness);
        fillPrecomputedByMax(brightParams, brightBaseAlpha, maxBrightness);
    }

    private static float computeMaxBrightness(float[] params) {
        if (params == null || params.length < 4) {
            return 0.0f;
        }
        float max = 0.0f;
        int starCount = params.length / 4;
        for (int i = 0; i < starCount; i++) {
            int p = i * 4;
            float vmag = params[p + 2];
            float brightness = (float) Math.pow(2.512, MAG_LIMIT - vmag);
            if (brightness > max) {
                max = brightness;
            }
        }
        return max;
    }

    private static void fillPrecomputedByMax(float[] params, float[] baseAlpha, float maxBrightness) {
        if (params == null || params.length < 4) {
            return;
        }
        int starCount = params.length / 4;
        for (int i = 0; i < starCount; i++) {
            int p = i * 4;
            float vmag = params[p + 2];
            float brightness = (float) Math.pow(2.512, MAG_LIMIT - vmag);
            float normBrightness = NightSkyMath.clamp(brightness / maxBrightness, 0.0f, 1.0f);
            params[p + 2] = brightness;
            params[p + 3] = normBrightness;
            baseAlpha[i] = NightSkyMath.clamp(
                    0.08f + 0.92f * (float) Math.pow(normBrightness, 0.42f),
                    0.08f,
                    1.0f
            );
        }
    }

    private static void fillPrecomputed(float[] params, float[] baseAlpha, float brightness, float normBrightness) {
        if (params == null || params.length < 4) {
            return;
        }
        int starCount = params.length / 4;
        for (int i = 0; i < starCount; i++) {
            int p = i * 4;
            params[p + 2] = brightness;
            params[p + 3] = normBrightness;
            baseAlpha[i] = 0.0f;
        }
    }

    private static NightSkyCatalog emptyCatalog() {
        return new NightSkyCatalog(
                new float[0],
                new float[0],
                new float[0],
                new float[0],
                new float[0],
                new float[0]
        );
    }
}
