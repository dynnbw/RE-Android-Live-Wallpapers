package com.reandroid.wallpaper.grass;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.opengl.GLES30;

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

    /** 雨丝现在只有一张贴图、一个批次（原来三张固定尺寸的精灵已删除）。 */
    private static final int RAIN_BATCH_GROUP_COUNT = 1;
    private static final int SNOW_BATCH_GROUP_COUNT = 4;
    private static final int CLOUD_BATCH_GROUP_COUNT = 4;
    /**
     * 顶点格式：x, y, u, v, a —— 与 GrassRenderDataBuilder 产出的一致（两个渲染器共用）。
     *
     * <p>天气这几层整批透明度统一，所以逐顶点 a 一律写 1，透明度走 drawBatch 的 uniform。
     * 逐顶点那份留给粒子/星空。
     */
    private static final int FLOATS_PER_VERTEX = 5;
    private static final int FLOATS_PER_QUAD = 6 * FLOATS_PER_VERTEX;

    interface TextureLoader {
        int load(String assetPath, boolean repeat, boolean mipmap);
    }

    private static final float[] CLOUD_MDPI_W = {256f, 256f, 256f, 276f};
    private static final float[] CLOUD_MDPI_H = {180f, 163f, 198f, 170f};
    private static final float FOG1_H_OVER_W = 95f / 280f;
    private static final float FOG2_H_OVER_W = 86f / 150f;

    private int width;
    private int height;
    private float density = 1.0f;
    private int bgMatrixHandle = -1;

    /**
     * 原版的目标屏宽（dp）。云贴图来自 {@code drawable-mdpi}，原版按 320dp 宽的机型设计
     * —— 不论是 320×480 的 mdpi 机还是 480×800 的 hdpi 机，dp 宽都是 320。
     */
    private static final float REFERENCE_WIDTH_DP = 320.0f;

    /**
     * 云的缩放系数：复刻原版的**构图比例**，而不是物理尺寸。
     *
     * <p>原版是 {@code BitmapFactory.decodeResource}，它内部会设 {@code inDensity=160}、
     * {@code inTargetDensity=设备 dpi}，所以云在原版里是 256dp。在**同一台机器上**按
     * {@code density} 缩放是对的 —— 但资源是照 320dp 屏宽设计的：256dp 的云在原版屏上占
     * 80% 屏宽，到 393dp 的现代屏上只剩 65%，整个构图等比缩水（与烟花那次同一个根因）。
     * 这里改成按屏宽还原原版比例。
     *
     * <p>不按屏高：云是横向铺开的，用屏高当基准会让云比屏幕还宽。
     */
    private float cloudScale() {
        return width > 0 ? width / REFERENCE_WIDTH_DP : 1.0f;
    }

    private int texRainStreak;

    /**
     * 雨粒子。原来的实现里每条雨丝速度都在 300-350、三张贴图尺寸与速度毫无关系，
     * 也就是一堵平墙；这里换成参考实现的粒子模型：**下落速度、尺寸、透明度由同一个
     * "深度"值决定**，那才是雨看起来有纵深的原因。
     */
    private GrassRainParticleSystem mRainParticles;
    /** drawRainLayer 前后两遍都会调，用它挡住第二次推进（否则 dt 翻倍、雨速是两倍）。 */
    private long mRainSteppedAtMs = -1L;
    /** 上一帧是否在降雨；用来检测"刚切进降雨"并重置粒子。 */
    private boolean mRainActive;
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

    /** 本帧的天气精灵染色，由 {@link #updateWeatherTint} 从 {@code sd.dayWeight} 算出。 */
    private float weatherTintR = 1.0f;
    private float weatherTintG = 1.0f;
    private float weatherTintB = 1.0f;

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
        // 雨丝条纹：从天气应用的 rain.png 解出来的原像素（64x64 白竖条）
        texRainStreak = loader.load("grass/drawable/grass_rain_streak.png", false, false);
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
                texRainStreak,
                texWeatherSnow1, texWeatherSnow2, texWeatherSnow3, texWeatherSnow4,
                texWeatherFog1, texWeatherFog2,
                texWeatherCloud1, texWeatherCloud2, texWeatherCloud3, texWeatherCloud4,
                texWeatherLightning1, texWeatherLightning2, texWeatherLightning3,
                texWeatherFlash, texWeatherTone
        };
        GLES30.glDeleteTextures(tex.length, tex, 0);

        texRainStreak = 0;
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
        GLES30.glUniformMatrix4fv(bgMatrixHandle, 1, false, sd.projectionMatrix, 0);
        drawWeatherTone(sd, spriteRenderer);
    }

    void drawWeatherOverlays(SceneData sd, boolean frontPass, boolean weatherEnabled,
            RenderOps ops, GrassSpriteRenderer spriteRenderer) {
        if (!weatherEnabled || sd.weatherCondition == null) {
            return;
        }

        ops.useBackgroundProgram();
        ops.setAlphaBlend();
        GLES30.glUniformMatrix4fv(bgMatrixHandle, 1, false, sd.projectionMatrix, 0);

        /*
         * 整个天气图层按昼夜染色：白天是白的，夜里压暗。
         *
         * 判据是"这东西反不反光"：云、雾、雨、雪都只是散射阳光，夜里没有光源就该是暗的；
         * 闪电（含那层白色闪光）是自发光，所以中间要单独恢复成白色，不参与压暗。
         */
        updateWeatherTint(sd);
        spriteRenderer.setTint(weatherTintR, weatherTintG, weatherTintB);

        // 切出降雨：清标志，下次切回来会重新起雨。
        // 放在这里是有意的 —— 每个非降雨 case 各写一遍必然会漏掉某一个。
        if (GrassWeatherSystem.rainIntensity(sd.weatherCondition) <= 0.0f) {
            mRainActive = false;
        }

        switch (sd.weatherCondition) {
            case D2_CLOUDY:
                if (!frontPass) {
                    drawCloudLayer(sd.weatherCondition, sd.animNowMs, 8, spriteRenderer);
                }
                break;
            case D3_DREARY:
                if (!frontPass) {
                    drawCloudLayer(sd.weatherCondition, sd.animNowMs, 12, spriteRenderer);
                }
                break;
            case D4_FOG:
                if (!frontPass) {
                    drawCloudLayer(sd.weatherCondition, sd.animNowMs, 8, spriteRenderer);
                } else {
                    drawFogLayer(sd.weatherCondition, spriteRenderer);
                }
                break;
            case D5_RAIN_SHOWERS:
                if (!frontPass) {
                    drawCloudLayer(sd.weatherCondition, sd.animNowMs, 12, spriteRenderer);
                }
                drawRainLayer(sd, resolveRainCount(false), frontPass, spriteRenderer);
                break;
            case D6_THUNDERSTORMS:
                if (!frontPass) {
                    // 闪电自发光，单独用白色画，画完立刻回到天气染色
                    spriteRenderer.setTintWhite();
                    thunderFlashAlpha = drawLightningSweep(sd.animNowMs, spriteRenderer);
                    restoreWeatherTint(spriteRenderer);
                    drawCloudLayer(sd.weatherCondition, sd.animNowMs, 12, spriteRenderer);
                }
                drawRainLayer(sd, resolveRainCount(true), frontPass, spriteRenderer);
                if (frontPass && thunderFlashAlpha > 0.0f && texWeatherFlash != 0) {
                    float fullSize = Math.max(width, height) * 2.4f;
                    spriteRenderer.setTintWhite();
                    spriteRenderer.drawSprite(texWeatherFlash, width * 0.5f, height * 0.5f,
                            fullSize, MathUtils.clamp(thunderFlashAlpha, 0.0f, 0.58f), false, 0.0f);
                    restoreWeatherTint(spriteRenderer);
                }
                break;
            case D7_FLURRIES_SNOW:
                if (!frontPass) {
                    drawCloudLayer(sd.weatherCondition, sd.animNowMs, 2, spriteRenderer);
                }
                drawSnowLayer(sd.animNowMs, resolveSnowCount(false), frontPass, spriteRenderer);
                break;
            case D8_ICE_COLD:
                if (!frontPass) {
                    drawCloudLayer(sd.weatherCondition, sd.animNowMs, 8, spriteRenderer);
                }
                drawSnowLayer(sd.animNowMs, resolveSnowCount(true), frontPass, spriteRenderer);
                break;
            case D9_SLEET:
                if (!frontPass) {
                    drawCloudLayer(sd.weatherCondition, sd.animNowMs, 12, spriteRenderer);
                }
                drawRainLayer(sd, resolveRainCount(true), frontPass, spriteRenderer);
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

        // uTint 是 program 级状态：退出前必须还原，否则后续绘制（草、粒子）会被一起染黑。
        spriteRenderer.setTintWhite();
    }

    void resetThunderState() {
        thunderNextStartMs = 0L;
        thunderActiveStartMs = 0L;
        thunderTextureIndex = 0;
        thunderLTR = true;
        thunderFlashAlpha = 0.0f;
    }

    private void drawWeatherTone(SceneData sd, GrassSpriteRenderer spriteRenderer) {
        /*
         * 透明度由场景算好（白天权重 × 天气开关的淡入淡出），这里不再自己判断昼夜 ——
         * 原先那句 `if (sd.isNight) return;` 会让日出那一刻天空瞬间换色。
         */
        float alpha = sd.weatherToneAlpha;
        if (alpha <= 0.001f || !GrassWeatherSystem.hasSkyTone(sd.weatherCondition)) {
            return;
        }
        if (texWeatherTone == 0) {
            return;
        }

        spriteRenderer.drawRectUv(texWeatherTone,
                0.0f, -32.0f, width, height + 32.0f,
                0.0f, 0.0f,
                1.0f, 1.0f,
                alpha);
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

    /** 按昼夜权重算出本帧的天气精灵染色。 */
    private void updateWeatherTint(SceneData sd) {
        float day = sd.dayWeight;
        weatherTintR = GrassWeatherSystem.weatherSpriteTint(GrassWeatherSystem.NIGHT_WEATHER_R, day);
        weatherTintG = GrassWeatherSystem.weatherSpriteTint(GrassWeatherSystem.NIGHT_WEATHER_G, day);
        weatherTintB = GrassWeatherSystem.weatherSpriteTint(GrassWeatherSystem.NIGHT_WEATHER_B, day);
    }

    private void restoreWeatherTint(GrassSpriteRenderer spriteRenderer) {
        spriteRenderer.setTint(weatherTintR, weatherTintG, weatherTintB);
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
            GrassSpriteRenderer spriteRenderer) {
        int cond = condition.ordinal();
        float tSec = animNowMs / 1000.0f;
        clearBatchCounts(cloudBatchFloatCounts);
        float k = cloudScale();
        for (int i = 0; i < cloudCount; i++) {
            int texIdx = cloudTexIndexForWeather(condition, i);
            int texture = cloudTexForIndex(texIdx);
            if (texture == 0) continue;
            float cloudW = CLOUD_MDPI_W[texIdx] * k;
            float cloudH = CLOUD_MDPI_H[texIdx] * k;
            /*
             * 速度跟着长度一起缩（原版的速度是写死的 8.0 px/s，不随屏幕走）。
             * 两者同比例 → 距离等比、横穿一次的时间不变，保持原版"多少秒飘过一朵"的节奏。
             * 烟花那次也是同一个做法：只缩长度量和速度，耗时不变。
             */
            float speed = 8.0f * (1.0f + hash01((long) (i * 7 + cond * 31))) * k;
            /*
             * 位置用 double 算。animNowMs 是开机以来的毫秒（开机一小时就是 3.6e6），
             * 乘上速度后是 1e8 量级，float 在这个量级只剩十几的精度，取模后会抖。
             * 原版用的就是 double（mSpeed 是 double，(j - mStartTime) 是 long）。
             */
            double cycleLen = cloudW + width;
            double phase = hash01((long) (i * 13 + cond * 17 + 1000)) * cycleLen;
            double xPos = (phase + (double) speed * tSec) % cycleLen - cloudW;
            float yOff = hash01((long) (i * 11 + cond * 7 + 2000)) * (cloudH / 2.0f) - cloudH / 4.0f;
            appendQuadToGroup(cloudBatchVertices, cloudBatchFloatCounts, texIdx,
                    (float) xPos, yOff, (float) xPos + cloudW, yOff + cloudH,
                    0.0f, 0.0f, 1.0f, 1.0f);
        }
        /*
         * 云不按昼夜调透明度 —— 原版 {@code Cloud.draw} 是
         * {@code canvas.drawBitmap(mBitmap, null, mRect, null)}，paint 传 null，就是不额外
         * 改透明度。贴图自己平均 alpha 只有 78~119/255，本来就是柔云；夜里再乘 0.30 会让它
         * 几乎看不见，星空直接透出来。夜色改用染色表达（见 weatherTint*）。
         */
        for (int i = 0; i < CLOUD_BATCH_GROUP_COUNT; i++) {
            int floatCount = cloudBatchFloatCounts[i];
            if (floatCount <= 0) {
                continue;
            }
            int texture = cloudTexForIndex(i);
            if (texture == 0) {
                continue;
            }
            spriteRenderer.drawBatch(texture, cloudBatchVertices[i], floatCount, 1.0f);
        }
    }

    /**
     * 画雨丝。前后两遍各调一次，但**粒子系统每帧只推进一次** ——
     * 否则 dt 翻倍、雨速是标称的两倍，而且看起来还挺正常，不容易发现。
     */
    private void drawRainLayer(SceneData sd, int count, boolean frontPass,
                               GrassSpriteRenderer spriteRenderer) {
        if (texRainStreak == 0) return;

        if (mRainParticles == null || mRainParticles.count() != count) {
            GrassRainParticleSystem.Config cfg = new GrassRainParticleSystem.Config();
            cfg.count = count;
            // 线段发射器：屏幕上方一条斜线，粒子沿它撒开再各自往下掉
            cfg.startX = -width * 0.2f;
            cfg.endX = width * 1.2f;
            cfg.startY = -height * 0.45f;
            cfg.endY = height * 0.10f;
            cfg.baseWidth = 8.0f * density;
            cfg.baseHeight = 96.0f * density;
            mRainParticles = new GrassRainParticleSystem(cfg);
            mRainSteppedAtMs = -1L;
            mRainActive = false;
        }

        // 刚切进降雨：重置一次，让雨有起点（预热，避免开局从一条干净的线开始）
        if (!mRainActive) {
            mRainParticles.reset(true);
            mRainActive = true;
            mRainSteppedAtMs = -1L;
        }
        if (mRainSteppedAtMs != sd.animNowMs) {
            mRainParticles.advance(sd.dt, sd.animNowMs / 1000.0f);
            mRainSteppedAtMs = sd.animNowMs;
        }

        clearBatchCounts(rainBatchFloatCounts);
        for (int i = 0; i < mRainParticles.count(); i++) {
            if (mRainParticles.state(i) != GrassRainParticleSystem.STATE_RUN) continue;
            // 前后分层沿用原逻辑：一半在草叶后、一半在草叶前
            boolean front = hash01(i * 37L + 991L) > 0.5f;
            if (front != frontPass) continue;
            float alpha = mRainParticles.renderAlpha(i);
            if (alpha <= 0.001f) continue;

            float w = mRainParticles.quadWidthPx(i);
            float h = mRainParticles.quadHeightPx(i);
            float x0 = mRainParticles.x(i) - w * 0.5f;
            float y0 = mRainParticles.y(i) - h * 0.5f;
            appendQuadToGroup(rainBatchVertices, rainBatchFloatCounts, 0,
                    x0, y0, x0 + w, y0 + h, 0.0f, 0.0f, 1.0f, 1.0f, alpha);
        }
        if (rainBatchFloatCounts[0] > 0) {
            spriteRenderer.drawBatch(texRainStreak, rainBatchVertices[0],
                    rainBatchFloatCounts[0], 1.0f);
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
        appendQuadToGroup(groups, counts, group, left, top, right, bottom,
                uLeft, vTop, uRight, vBottom, 1.0f);
    }

    /** 带逐四边形透明度的版本 —— 雨丝按深度给浓淡要它。 */
    private void appendQuadToGroup(float[][] groups, int[] counts, int group,
            float left, float top, float right, float bottom,
            float uLeft, float vTop, float uRight, float vBottom, float alpha) {
        ensureGroupCapacity(groups, counts, group, FLOATS_PER_QUAD);
        float[] out = groups[group];
        int cursor = counts[group];

        cursor = putVertex(out, cursor, left, top, uLeft, vTop, alpha);
        cursor = putVertex(out, cursor, left, bottom, uLeft, vBottom, alpha);
        cursor = putVertex(out, cursor, right, bottom, uRight, vBottom, alpha);

        cursor = putVertex(out, cursor, left, top, uLeft, vTop, alpha);
        cursor = putVertex(out, cursor, right, bottom, uRight, vBottom, alpha);
        cursor = putVertex(out, cursor, right, top, uRight, vTop, alpha);

        counts[group] = cursor;
    }

    private int putVertex(float[] out, int cursor, float x, float y, float u, float v) {
        out[cursor++] = x;
        out[cursor++] = y;
        out[cursor++] = u;
        out[cursor++] = v;
        out[cursor++] = 1.0f;   // 逐顶点 alpha：本层不用，整批透明度走 uniform
        return cursor;
    }

    /** 逐顶点 alpha 版本。雨丝要按深度给不同的浓淡，整批一个 uniform 表达不了。 */
    private int putVertex(float[] out, int cursor, float x, float y, float u, float v, float a) {
        out[cursor++] = x;
        out[cursor++] = y;
        out[cursor++] = u;
        out[cursor++] = v;
        out[cursor++] = a;
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
        GLES30.glGenTextures(1, tex, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0]);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);

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
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, texWidth, texHeight, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf);
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
