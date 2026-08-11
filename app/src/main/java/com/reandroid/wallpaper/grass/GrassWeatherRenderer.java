package com.reandroid.wallpaper.grass;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.opengl.GLES20;

import com.reandroid.settings.WallpaperSettings;
import com.reandroid.weather.WeatherCondition;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import com.reandroid.utils.MathUtils;

final class GrassWeatherRenderer {

    private SharedPreferences mPluginPrefs;

    void setPluginPrefs(SharedPreferences prefs) {
        mPluginPrefs = prefs;
    }

    private static final int RAIN_BATCH_GROUP_COUNT = 3;
    private static final int SNOW_BATCH_GROUP_COUNT = 4;
    private static final int CLOUD_BATCH_GROUP_COUNT = 4;
    private static final int FLOATS_PER_VERTEX = 4;
    private static final int FLOATS_PER_QUAD = 6 * FLOATS_PER_VERTEX;

    interface TextureLoader {
        int load(String assetPath, boolean repeat, boolean mipmap);
    }

    interface SolidColorTextureFactory {
        int create(byte r, byte g, byte b, byte a);
    }

    interface RenderOps {
        void useBackgroundProgram();

        void setAlphaBlend();
    }

    private static final float[] CLOUD_MDPI_W = {256f, 256f, 256f, 276f};
    private static final float[] CLOUD_MDPI_H = {180f, 163f, 198f, 170f};
    private static final float FOG1_H_OVER_W = 95f / 280f;
    private static final float FOG2_H_OVER_W = 86f / 150f;
    private static final float[] RAIN_MDPI_W = {2f, 2f, 2f};
    private static final float[] RAIN_MDPI_H = {42f, 30f, 49f};

    private int width;
    private int height;
    private float density = 1.0f;
    private int bgMatrixHandle = -1;

    private int texWeatherRain1;
    private int texWeatherRain2;
    private int texWeatherRain3;
    private int texWeatherSnow1;
    private int texWeatherSnow2;
    private int texWeatherSnow3;
    private int texWeatherSnow4;
    private int texWeatherFog1;
    private int texWeatherFog2;
    private int texWeatherCloud1;
    private int texWeatherCloud2;
    private int texWeatherCloud3;
    private int texWeatherCloud4;
    private int texWeatherLightning1;
    private int texWeatherLightning2;
    private int texWeatherLightning3;
    private int texWeatherFlash;
    private int texWeatherTone;

    private long thunderNextStartMs;
    private long thunderActiveStartMs;
    private int thunderTextureIndex;
    private boolean thunderLTR = true;
    private float thunderFlashAlpha;

    private final float[][] rainBatchVertices = new float[RAIN_BATCH_GROUP_COUNT][];
    private final int[] rainBatchFloatCounts = new int[RAIN_BATCH_GROUP_COUNT];
    private final float[][] snowBatchVertices = new float[SNOW_BATCH_GROUP_COUNT][];
    private final int[] snowBatchFloatCounts = new int[SNOW_BATCH_GROUP_COUNT];
    private final float[][] cloudBatchVertices = new float[CLOUD_BATCH_GROUP_COUNT][];
    private final int[] cloudBatchFloatCounts = new int[CLOUD_BATCH_GROUP_COUNT];

    void setViewport(int width, int height) {
        this.width = width;
        this.height = height;
    }

    void setDensity(float density) {
        this.density = density;
    }

    void setBackgroundMatrixHandle(int bgMatrixHandle) {
        this.bgMatrixHandle = bgMatrixHandle;
    }

    void loadTextures(TextureLoader loader, SolidColorTextureFactory solidColorFactory) {
        texWeatherRain1 = loader.load("grass/drawable/grass_weather_rain_01.png", false, false);
        texWeatherRain2 = loader.load("grass/drawable/grass_weather_rain_02.png", false, false);
        texWeatherRain3 = loader.load("grass/drawable/grass_weather_rain_03.png", false, false);
        texWeatherSnow1 = loader.load("grass/drawable/grass_weather_snow_01.png", false, false);
        texWeatherSnow2 = loader.load("grass/drawable/grass_weather_snow_02.png", false, false);
        texWeatherSnow3 = loader.load("grass/drawable/grass_weather_snow_03.png", false, false);
        texWeatherSnow4 = loader.load("grass/drawable/grass_weather_snow_04.png", false, false);
        texWeatherFog1 = loader.load("grass/drawable/grass_weather_fog_01.png", false, false);
        texWeatherFog2 = loader.load("grass/drawable/grass_weather_fog_02.png", false, false);
        texWeatherCloud1 = loader.load("grass/drawable/grass_weather_cloud_01.png", false, false);
        texWeatherCloud2 = loader.load("grass/drawable/grass_weather_cloud_02.png", false, false);
        texWeatherCloud3 = loader.load("grass/drawable/grass_weather_cloud_03.png", false, false);
        texWeatherCloud4 = loader.load("grass/drawable/grass_weather_cloud_04.png", false, false);
        texWeatherLightning1 = loader.load("grass/drawable/grass_weather_lightning_01.png", false, false);
        texWeatherLightning2 = loader.load("grass/drawable/grass_weather_lightning_02.png", false, false);
        texWeatherLightning3 = loader.load("grass/drawable/grass_weather_lightning_03.png", false, false);
        texWeatherFlash = solidColorFactory.create((byte) 255, (byte) 255, (byte) 255, (byte) 255);
        texWeatherTone = createWeatherToneTexture();
    }

    void releaseTextures() {
        int[] tex = new int[]{
                texWeatherRain1, texWeatherRain2, texWeatherRain3,
                texWeatherSnow1, texWeatherSnow2, texWeatherSnow3, texWeatherSnow4,
                texWeatherFog1, texWeatherFog2,
                texWeatherCloud1, texWeatherCloud2, texWeatherCloud3, texWeatherCloud4,
                texWeatherLightning1, texWeatherLightning2, texWeatherLightning3,
                texWeatherFlash, texWeatherTone
        };
        GLES20.glDeleteTextures(tex.length, tex, 0);

        texWeatherRain1 = 0;
        texWeatherRain2 = 0;
        texWeatherRain3 = 0;
        texWeatherSnow1 = 0;
        texWeatherSnow2 = 0;
        texWeatherSnow3 = 0;
        texWeatherSnow4 = 0;
        texWeatherFog1 = 0;
        texWeatherFog2 = 0;
        texWeatherCloud1 = 0;
        texWeatherCloud2 = 0;
        texWeatherCloud3 = 0;
        texWeatherCloud4 = 0;
        texWeatherLightning1 = 0;
        texWeatherLightning2 = 0;
        texWeatherLightning3 = 0;
        texWeatherFlash = 0;
        texWeatherTone = 0;
        resetThunderState();
    }

    void drawWeatherBackground(SceneData sd, boolean weatherEnabled, RenderOps ops, GrassSpriteRenderer spriteRenderer) {
        if (!weatherEnabled || sd.weatherCondition == null) {
            return;
        }
        ops.useBackgroundProgram();
        ops.setAlphaBlend();
        GLES20.glUniformMatrix4fv(bgMatrixHandle, 1, false, sd.projectionMatrix, 0);
        drawWeatherTone(sd, spriteRenderer);
    }

    void drawWeatherOverlays(SceneData sd, boolean frontPass, boolean weatherEnabled,
            RenderOps ops, GrassSpriteRenderer spriteRenderer) {
        if (!weatherEnabled || sd.weatherCondition == null) {
            return;
        }

        ops.useBackgroundProgram();
        ops.setAlphaBlend();
        GLES20.glUniformMatrix4fv(bgMatrixHandle, 1, false, sd.projectionMatrix, 0);

        switch (sd.weatherCondition) {
            case D2_CLOUDY:
                if (!frontPass) {
                    drawCloudLayer(sd.weatherCondition, sd.animNowMs, 8, sd.isNight, spriteRenderer);
                }
                break;
            case D3_DREARY:
                if (!frontPass) {
                    drawCloudLayer(sd.weatherCondition, sd.animNowMs, 12, sd.isNight, spriteRenderer);
                }
                break;
            case D4_FOG:
                if (!frontPass) {
                    drawCloudLayer(sd.weatherCondition, sd.animNowMs, 8, sd.isNight, spriteRenderer);
                } else {
                    drawFogLayer(sd.weatherCondition, spriteRenderer);
                }
                break;
            case D5_RAIN_SHOWERS:
                if (!frontPass) {
                    drawCloudLayer(sd.weatherCondition, sd.animNowMs, 12, sd.isNight, spriteRenderer);
                }
                drawRainLayer(sd.animNowMs, resolveRainCount(false), frontPass, spriteRenderer);
                break;
            case D6_THUNDERSTORMS:
                if (!frontPass) {
                    thunderFlashAlpha = drawLightningSweep(sd.animNowMs, spriteRenderer);
                    drawCloudLayer(sd.weatherCondition, sd.animNowMs, 12, sd.isNight, spriteRenderer);
                }
                drawRainLayer(sd.animNowMs, resolveRainCount(true), frontPass, spriteRenderer);
                if (frontPass && thunderFlashAlpha > 0.0f && texWeatherFlash != 0) {
                    float fullSize = Math.max(width, height) * 2.4f;
                    spriteRenderer.drawSprite(texWeatherFlash, width * 0.5f, height * 0.5f,
                            fullSize, MathUtils.clamp(thunderFlashAlpha, 0.0f, 0.58f), false, 0.0f);
                }
                break;
            case D7_FLURRIES_SNOW:
                if (!frontPass) {
                    drawCloudLayer(sd.weatherCondition, sd.animNowMs, 2, sd.isNight, spriteRenderer);
                }
                drawSnowLayer(sd.animNowMs, resolveSnowCount(false), frontPass, spriteRenderer);
                break;
            case D8_ICE_COLD:
                if (!frontPass) {
                    drawCloudLayer(sd.weatherCondition, sd.animNowMs, 8, sd.isNight, spriteRenderer);
                }
                drawSnowLayer(sd.animNowMs, resolveSnowCount(true), frontPass, spriteRenderer);
                break;
            case D9_SLEET:
                if (!frontPass) {
                    drawCloudLayer(sd.weatherCondition, sd.animNowMs, 12, sd.isNight, spriteRenderer);
                }
                drawRainLayer(sd.animNowMs, resolveRainCount(true), frontPass, spriteRenderer);
                drawSnowLayer(sd.animNowMs, resolveSnowCount(false), frontPass, spriteRenderer);
                break;
            case D1_CLEAR:
            default:
                if (!frontPass) {
                    resetThunderState();
                }
                break;
        }

        if (!frontPass && sd.weatherCondition != WeatherCondition.D6_THUNDERSTORMS) {
            resetThunderState();
        }
    }

    void resetThunderState() {
        thunderNextStartMs = 0L;
        thunderActiveStartMs = 0L;
        thunderTextureIndex = 0;
        thunderLTR = true;
        thunderFlashAlpha = 0.0f;
    }

    private void drawWeatherTone(SceneData sd, GrassSpriteRenderer spriteRenderer) {
        if (sd.isNight) {
            return;
        }
        switch (sd.weatherCondition) {
            case D1_CLEAR:
                return;
            case D2_CLOUDY:
            case D3_DREARY:
            case D4_FOG:
            case D5_RAIN_SHOWERS:
            case D6_THUNDERSTORMS:
            case D7_FLURRIES_SNOW:
            case D8_ICE_COLD:
            case D9_SLEET:
                break;
            default:
                return;
        }

        if (texWeatherTone == 0) {
            return;
        }

        spriteRenderer.drawRectUv(texWeatherTone,
                0.0f, -32.0f, width, height + 32.0f,
                0.0f, 0.0f,
                1.0f, 1.0f,
                1.0f);
    }

    private void drawFogLayer(WeatherCondition condition, GrassSpriteRenderer spriteRenderer) {
        if (texWeatherFog1 == 0 || texWeatherFog2 == 0) return;
        if (condition != WeatherCondition.D4_FOG) return;
        float hazeDrawW = width + 40f;
        float hazeDrawH = FOG1_H_OVER_W * width;
        float hazeTop = height - hazeDrawH;
        spriteRenderer.drawRect(texWeatherFog1, -20f, hazeTop, hazeDrawW, hazeDrawH, 1.0f);
        float fogDrawH = FOG2_H_OVER_W * width * 1.2f;
        float fogTop = hazeTop - (FOG2_H_OVER_W * width / 2.0f);
        spriteRenderer.drawRect(texWeatherFog2, 0f, fogTop, width, fogDrawH, 1.0f);
    }

    private int cloudTexForIndex(int idx) {
        switch (idx) {
            case 0:
                return texWeatherCloud1;
            case 1:
                return texWeatherCloud2;
            case 2:
                return texWeatherCloud3;
            case 3:
                return texWeatherCloud4;
            default:
                return texWeatherCloud1;
        }
    }

    private int cloudTexIndexForWeather(WeatherCondition condition, int i) {
        switch (condition) {
            case D7_FLURRIES_SNOW:
                return 0;
            case D2_CLOUDY:
            case D4_FOG:
            case D8_ICE_COLD:
                return (i & 1) == 0 ? 2 : 0;
            case D3_DREARY:
            case D5_RAIN_SHOWERS:
            case D6_THUNDERSTORMS:
            case D9_SLEET:
                return (i & 1) == 0 ? 3 : 2;
            default:
                return 0;
        }
    }

    private void drawCloudLayer(WeatherCondition condition, long animNowMs, int cloudCount,
            boolean isNight, GrassSpriteRenderer spriteRenderer) {
        int cond = condition.ordinal();
        float tSec = animNowMs / 1000.0f;
        float cloudAlpha = isNight ? 0.30f : 1.0f;
        clearBatchCounts(cloudBatchFloatCounts);
        for (int i = 0; i < cloudCount; i++) {
            int texIdx = cloudTexIndexForWeather(condition, i);
            int texture = cloudTexForIndex(texIdx);
            if (texture == 0) continue;
            float cloudW = CLOUD_MDPI_W[texIdx] * density;
            float cloudH = CLOUD_MDPI_H[texIdx] * density;
            float speed = 8.0f * (1.0f + hash01((long) (i * 7 + cond * 31)));
            float cycleLen = cloudW + width;
            float phase = hash01((long) (i * 13 + cond * 17 + 1000)) * cycleLen;
            float xPos = (phase + speed * tSec) % cycleLen - cloudW;
            float yOff = hash01((long) (i * 11 + cond * 7 + 2000)) * (cloudH / 2.0f) - cloudH / 4.0f;
            appendQuadToGroup(cloudBatchVertices, cloudBatchFloatCounts, texIdx,
                    xPos, yOff, xPos + cloudW, yOff + cloudH,
                    0.0f, 0.0f, 1.0f, 1.0f);
        }
        for (int i = 0; i < CLOUD_BATCH_GROUP_COUNT; i++) {
            int floatCount = cloudBatchFloatCounts[i];
            if (floatCount <= 0) {
                continue;
            }
            int texture = cloudTexForIndex(i);
            if (texture == 0) {
                continue;
            }
            spriteRenderer.drawBatch(texture, cloudBatchVertices[i], floatCount, cloudAlpha);
        }
    }

    private void drawRainLayer(long animNowMs, int count, boolean frontPass, GrassSpriteRenderer spriteRenderer) {
        if (texWeatherRain1 == 0 || texWeatherRain2 == 0 || texWeatherRain3 == 0) return;
        float tSec = animNowMs / 1000.0f;
        clearBatchCounts(rainBatchFloatCounts);
        for (int i = 0; i < count; i++) {
            boolean front = hash01(i * 37L + 991L) > 0.5f;
            if (front != frontPass) continue;
            int texIdx = i % 3;
            float rainW = RAIN_MDPI_W[texIdx] * density;
            float rainH = RAIN_MDPI_H[texIdx] * density;
            float xPos = hash01((long) (i * 17 + 503)) * width;
            float speed = 300.0f + hash01((long) (i * 31 + 271)) * 50.0f;
            float cycleLen = rainH + height;
            float phase = hash01((long) (i * 23 + 713)) * cycleLen;
            float yPos = (phase + speed * tSec) % cycleLen - rainH;
            appendQuadToGroup(rainBatchVertices, rainBatchFloatCounts, texIdx,
                    xPos, yPos, xPos + rainW, yPos + rainH,
                    0.0f, 0.0f, 1.0f, 1.0f);
        }
        for (int i = 0; i < RAIN_BATCH_GROUP_COUNT; i++) {
            int floatCount = rainBatchFloatCounts[i];
            if (floatCount <= 0) {
                continue;
            }
            int texture = rainTextureForIndex(i);
            if (texture == 0) {
                continue;
            }
            spriteRenderer.drawBatch(texture, rainBatchVertices[i], floatCount, 1.0f);
        }
    }

    private void drawSnowLayer(long animNowMs, int count, boolean frontPass, GrassSpriteRenderer spriteRenderer) {
        if (texWeatherSnow1 == 0 || texWeatherSnow2 == 0 || texWeatherSnow3 == 0 || texWeatherSnow4 == 0) {
            return;
        }
        float tSec = animNowMs / 1000.0f;
        clearBatchCounts(snowBatchFloatCounts);
        for (int i = 0; i < count; i++) {
            boolean front = hash01(i * 41L + 577L) > 0.5f;
            if (front != frontPass) continue;
            float radiusOffset = hash01((long) (i * 7 + 101)) * 4.0f;
            float size = (2.0f + radiusOffset) * 2.0f;
            float speed = 40.0f + hash01((long) (i * 11 + 137)) * (6.0f - (2.0f + radiusOffset)) * 5.0f;
            float xPos = hash01((long) (i * 13 + 199)) * width;
            float cycleLen = size + height;
            float phase = hash01((long) (i * 19 + 317)) * cycleLen;
            float yPos = (phase + speed * tSec) % cycleLen - size;
            int texIdx = i & 3;
            float cy = yPos + size * 0.5f;
            float half = size * 0.5f;
            appendQuadToGroup(snowBatchVertices, snowBatchFloatCounts, texIdx,
                    xPos - half, cy - half, xPos + half, cy + half,
                    0.0f, 1.0f, 1.0f, 0.0f);
        }
        for (int i = 0; i < SNOW_BATCH_GROUP_COUNT; i++) {
            int floatCount = snowBatchFloatCounts[i];
            if (floatCount <= 0) {
                continue;
            }
            int texture = snowTextureForIndex(i);
            if (texture == 0) {
                continue;
            }
            spriteRenderer.drawBatch(texture, snowBatchVertices[i], floatCount, 1.0f);
        }
    }

    private int resolveRainCount(boolean intense) {
        int base = mPluginPrefs != null
                ? mPluginPrefs.getInt(WallpaperSettings.KEY_GRASS_WEATHER_RAIN_COUNT, 25)
                : WallpaperSettings.getGrassWeatherRainCount(25);
        if (!intense) {
            return Math.max(10, base / 2);
        }
        return base;
    }

    private int resolveSnowCount(boolean intense) {
        int base = mPluginPrefs != null
                ? mPluginPrefs.getInt(WallpaperSettings.KEY_GRASS_WEATHER_SNOW_COUNT, 25)
                : WallpaperSettings.getGrassWeatherSnowCount(25);
        if (!intense) {
            return Math.max(10, base / 2);
        }
        return base;
    }

    private int rainTextureForIndex(int idx) {
        switch (idx) {
            case 0:
                return texWeatherRain1;
            case 1:
                return texWeatherRain2;
            default:
                return texWeatherRain3;
        }
    }

    private int snowTextureForIndex(int idx) {
        switch (idx) {
            case 0:
                return texWeatherSnow1;
            case 1:
                return texWeatherSnow2;
            case 2:
                return texWeatherSnow3;
            default:
                return texWeatherSnow4;
        }
    }

    private void clearBatchCounts(int[] counts) {
        for (int i = 0; i < counts.length; i++) {
            counts[i] = 0;
        }
    }

    private void appendQuadToGroup(float[][] groups, int[] counts, int group,
            float left, float top, float right, float bottom,
            float uLeft, float vTop, float uRight, float vBottom) {
        ensureGroupCapacity(groups, counts, group, FLOATS_PER_QUAD);
        float[] out = groups[group];
        int cursor = counts[group];

        cursor = putVertex(out, cursor, left, top, uLeft, vTop);
        cursor = putVertex(out, cursor, left, bottom, uLeft, vBottom);
        cursor = putVertex(out, cursor, right, bottom, uRight, vBottom);

        cursor = putVertex(out, cursor, left, top, uLeft, vTop);
        cursor = putVertex(out, cursor, right, bottom, uRight, vBottom);
        cursor = putVertex(out, cursor, right, top, uRight, vTop);

        counts[group] = cursor;
    }

    private int putVertex(float[] out, int cursor, float x, float y, float u, float v) {
        out[cursor++] = x;
        out[cursor++] = y;
        out[cursor++] = u;
        out[cursor++] = v;
        return cursor;
    }

    private void ensureGroupCapacity(float[][] groups, int[] counts, int group, int appendFloats) {
        int required = counts[group] + appendFloats;
        float[] current = groups[group];
        if (current != null && current.length >= required) {
            return;
        }

        int newSize = current == null ? 2048 : current.length;
        while (newSize < required) {
            newSize *= 2;
        }

        float[] expanded = new float[newSize];
        if (current != null && counts[group] > 0) {
            System.arraycopy(current, 0, expanded, 0, counts[group]);
        }
        groups[group] = expanded;
    }

    private float drawLightningSweep(long animNowMs, GrassSpriteRenderer spriteRenderer) {
        if (texWeatherLightning1 == 0 || texWeatherLightning2 == 0 || texWeatherLightning3 == 0) {
            return 0.0f;
        }

        if (thunderActiveStartMs == 0L && thunderNextStartMs == 0L) {
            thunderNextStartMs = animNowMs + 2000L + (long) (hash01(animNowMs * 31L + 7L) * 6000.0f);
        }

        if (thunderActiveStartMs == 0L && animNowMs >= thunderNextStartMs) {
            thunderActiveStartMs = animNowMs;
            thunderTextureIndex = (int) (hash01(animNowMs * 13L + 17L) * 3.0f) % 3;
            thunderLTR = hash01(animNowMs * 19L + 23L) > 0.5f;
        }

        if (thunderActiveStartMs == 0L) {
            return 0.0f;
        }

        long elapsed = animNowMs - thunderActiveStartMs;
        if (elapsed >= 200L) {
            thunderActiveStartMs = 0L;
            thunderNextStartMs = animNowMs + 2000L + (long) (hash01(animNowMs * 29L + 31L) * 6000.0f);
            return 0.0f;
        }

        float progress = MathUtils.clamp(elapsed / 200.0f, 0.0f, 1.0f);
        float clipW = width * progress;
        float clipH = height * progress;
        float left = thunderLTR ? 0.0f : (width - clipW);
        float right = left + clipW;
        float top = 0.0f;
        float bottom = clipH;
        int texture;
        switch (thunderTextureIndex) {
            case 0:
                texture = texWeatherLightning1;
                break;
            case 1:
                texture = texWeatherLightning2;
                break;
            default:
                texture = texWeatherLightning3;
                break;
        }
        spriteRenderer.drawRectUv(texture,
                left, top, right, bottom,
                left / width, 1.0f - top / height,
                right / width, 1.0f - bottom / height,
                1.0f);

        return MathUtils.clamp(((elapsed / 200.0f) + 0.2f) * 0.5f, 0.0f, 1.0f);
    }

    private int createWeatherToneTexture() {
        final int topColor = 0xFF517398;
        final int bottomColor = 0xFF6B9EBF;
        final int texWidth = 1;
        final int texHeight = 64;
        final float gradientEnd = 0.75f;

        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        byte[] rgba = new byte[texWidth * texHeight * 4];
        for (int y = 0; y < texHeight; y++) {
            float v = y / (float) (texHeight - 1);
            float t = MathUtils.clamp(v / gradientEnd, 0.0f, 1.0f);
            int color = Color.argb(
                    Math.round(MathUtils.lerp(Color.alpha(topColor), Color.alpha(bottomColor), t)),
                    Math.round(MathUtils.lerp(Color.red(topColor), Color.red(bottomColor), t)),
                    Math.round(MathUtils.lerp(Color.green(topColor), Color.green(bottomColor), t)),
                    Math.round(MathUtils.lerp(Color.blue(topColor), Color.blue(bottomColor), t)));
            int idx = y * 4;
            rgba[idx] = (byte) Color.red(color);
            rgba[idx + 1] = (byte) Color.green(color);
            rgba[idx + 2] = (byte) Color.blue(color);
            rgba[idx + 3] = (byte) Color.alpha(color);
        }

        ByteBuffer buf = ByteBuffer.allocateDirect(rgba.length).order(ByteOrder.nativeOrder());
        buf.put(rgba).position(0);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, texWidth, texHeight, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf);
        return tex[0];
    }

    private static float hash01(long v) {
        long x = v;
        x ^= (x << 13);
        x ^= (x >>> 7);
        x ^= (x << 17);
        long masked = x & 0x7fffffffL;
        return masked / 2147483647.0f;
    }

}
