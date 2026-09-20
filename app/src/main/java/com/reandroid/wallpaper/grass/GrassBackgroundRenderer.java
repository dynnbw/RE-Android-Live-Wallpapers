package com.reandroid.wallpaper.grass;

import android.opengl.GLES30;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import com.reandroid.utils.MathUtils;

final class GrassBackgroundRenderer {
    private int width;
    private int height;

    private int texNight;
    private int texSunrise;
    private int texSunset;
    private int texSky;
    private int texSolarEclipse;

    private int bgPositionHandle = -1;
    private int bgTexHandle = -1;
    private int bgSamplerHandle = -1;
    private int bgAlphaHandle = -1;
    private int bgTintHandle = -1;
    /** 背景程序的逐顶点 alpha 属性——本类画的是整块天空，恒定写 1。 */
    private int bgVertexAlphaHandle = -1;

    private int skyPositionHandle = -1;
    private int skyTexHandle = -1;
    private int skySamplerNightHandle = -1;
    private int skySamplerSunriseHandle = -1;
    private int skySamplerSunsetHandle = -1;
    private int skySamplerSkyHandle = -1;
    private int skySamplerSolarEclipseHandle = -1;
    private int skyWeightNightHandle = -1;
    private int skyWeightSunriseHandle = -1;
    private int skyWeightSunsetHandle = -1;
    private int skyWeightSkyHandle = -1;
    private int skyWeightSolarEclipseHandle = -1;
    private int skyNightInvertHandle = -1;

    private FloatBuffer bgQuadBuffer;
    private FloatBuffer skyQuadBuffer;
    private boolean skyQuadDirty = true;
    private final float[] quadVerts = new float[20];
    /** computeSimpleSkyWeights 的输出缓冲：每帧算一次，别每帧新建。 */
    private final float[] skyWeights = new float[4];

    void setViewport(int width, int height) {
        this.width = width;
        this.height = height;
        skyQuadDirty = true;
    }

    void setBackgroundProgramHandles(int bgPositionHandle, int bgTexHandle, int bgSamplerHandle, int bgAlphaHandle) {
        this.bgPositionHandle = bgPositionHandle;
        this.bgTexHandle = bgTexHandle;
        this.bgSamplerHandle = bgSamplerHandle;
        this.bgAlphaHandle = bgAlphaHandle;
    }

    void setBackgroundTintHandle(int bgTintHandle) {
        this.bgTintHandle = bgTintHandle;
    }

    void setBackgroundVertexAlphaHandle(int bgVertexAlphaHandle) {
        this.bgVertexAlphaHandle = bgVertexAlphaHandle;
    }

    void setSkyProgramHandles(
            int skyPositionHandle,
            int skyTexHandle,
            int skySamplerNightHandle,
            int skySamplerSunriseHandle,
            int skySamplerSunsetHandle,
            int skySamplerSkyHandle,
            int skySamplerSolarEclipseHandle,
            int skyWeightNightHandle,
            int skyWeightSunriseHandle,
            int skyWeightSunsetHandle,
            int skyWeightSkyHandle,
            int skyWeightSolarEclipseHandle,
            int skyNightInvertHandle) {
        this.skyPositionHandle = skyPositionHandle;
        this.skyTexHandle = skyTexHandle;
        this.skySamplerNightHandle = skySamplerNightHandle;
        this.skySamplerSunriseHandle = skySamplerSunriseHandle;
        this.skySamplerSunsetHandle = skySamplerSunsetHandle;
        this.skySamplerSkyHandle = skySamplerSkyHandle;
        this.skySamplerSolarEclipseHandle = skySamplerSolarEclipseHandle;
        this.skyWeightNightHandle = skyWeightNightHandle;
        this.skyWeightSunriseHandle = skyWeightSunriseHandle;
        this.skyWeightSunsetHandle = skyWeightSunsetHandle;
        this.skyWeightSkyHandle = skyWeightSkyHandle;
        this.skyWeightSolarEclipseHandle = skyWeightSolarEclipseHandle;
        this.skyNightInvertHandle = skyNightInvertHandle;
    }

    void setSkyTextures(int texNight, int texSunrise, int texSunset, int texSky, int texSolarEclipse) {
        this.texNight = texNight;
        this.texSunrise = texSunrise;
        this.texSunset = texSunset;
        this.texSky = texSky;
        this.texSolarEclipse = texSolarEclipse;
    }

    void drawBackground(SceneData sd) {
        // Compute sky blend weights once, shared with Vulkan path via SceneData
        float[] w = skyWeights;
        SceneData.computeSimpleSkyWeights(sd.timeFraction, sd.dawn, sd.morning, sd.afternoon, sd.dusk, w);
        float wNight = w[0], wSunrise = w[1], wSunset = w[2], wSky = w[3];

        // Two-weight transition phases: draw first layer at full alpha, second at blend alpha
        if (wNight > 0.0f && wSunrise > 0.0f) {
            setAlpha(1.0f);
            drawNight(sd.nightInvert);
            setAlpha(wSunrise);
            drawSunrise();
        } else if (wSunrise > 0.0f && wSky > 0.0f) {
            setAlpha(1.0f);
            drawSunrise();
            setAlpha(wSky);
            drawNoon();
        } else if (wSky > 0.0f && wSunset > 0.0f) {
            setAlpha(1.0f);
            drawNoon();
            setAlpha(wSunset);
            drawSunset();
        } else if (wSunset > 0.0f && wNight > 0.0f) {
            setAlpha(1.0f);
            drawSunset();
            setAlpha(wNight);
            drawNight(sd.nightInvert);
        } else if (wNight >= 1.0f) {
            setAlpha(1.0f);
            drawNight(sd.nightInvert);
        } else if (wSky >= 1.0f) {
            setAlpha(1.0f);
            drawNoon();
        }
    }

    void drawAccurateBackground(SceneData sd) {
        if (skyQuadBuffer == null) {
            skyQuadBuffer = ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            skyQuadDirty = true;
        }
        if (skyQuadDirty) {
            skyQuadBuffer.clear();
            skyQuadBuffer.put(0.0f).put(0.0f).put(0.0f).put(0.0f);
            skyQuadBuffer.put(0.0f).put(height).put(0.0f).put(1.0f);
            skyQuadBuffer.put(width).put(height).put(1.0f).put(1.0f);
            skyQuadBuffer.put(width).put(0.0f).put(1.0f).put(0.0f);
            skyQuadBuffer.position(0);
            skyQuadDirty = false;
        }

        skyQuadBuffer.position(0);
        GLES30.glEnableVertexAttribArray(skyPositionHandle);
        GLES30.glVertexAttribPointer(skyPositionHandle, 2, GLES30.GL_FLOAT, false, 16, skyQuadBuffer);
        skyQuadBuffer.position(2);
        GLES30.glEnableVertexAttribArray(skyTexHandle);
        GLES30.glVertexAttribPointer(skyTexHandle, 2, GLES30.GL_FLOAT, false, 16, skyQuadBuffer);

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texNight);
        GLES30.glUniform1i(skySamplerNightHandle, 0);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texSunrise);
        GLES30.glUniform1i(skySamplerSunriseHandle, 1);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texSunset);
        GLES30.glUniform1i(skySamplerSunsetHandle, 2);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texSky);
        GLES30.glUniform1i(skySamplerSkyHandle, 3);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE4);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texSolarEclipse);
        GLES30.glUniform1i(skySamplerSolarEclipseHandle, 4);

        GLES30.glUniform1f(skyWeightNightHandle, sd.accurateWeights[0]);
        GLES30.glUniform1f(skyWeightSunriseHandle, sd.accurateWeights[1]);
        GLES30.glUniform1f(skyWeightSunsetHandle, sd.accurateWeights[2]);
        GLES30.glUniform1f(skyWeightSkyHandle, sd.accurateWeights[3]);
        GLES30.glUniform1f(skyWeightSolarEclipseHandle, sd.solarEclipseWeight);
        GLES30.glUniform1f(skyNightInvertHandle, sd.nightInvert ? 1.0f : 0.0f);

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_FAN, 0, 4);
        GLES30.glDisableVertexAttribArray(skyPositionHandle);
        GLES30.glDisableVertexAttribArray(skyTexHandle);
    }

    /**
     * 背景的每层天空都走这里。
     *
     * <p>顺带把 uTint 设回白色：本类是唯一不经过 {@link GrassSpriteRenderer}、直接用背景
     * 程序画的地方，而 uTint 是 program 级状态 —— 不设回白色就会继承上一帧云留下的暗色。
     * 只在 {@link #drawBackground}（背景程序）里调用；{@link #drawAccurateBackground}
     * 用的是天空程序，那个着色器没有 uTint，也就不会被染色影响。
     */
    private void setAlpha(float alpha) {
        GLES30.glUniform1f(bgAlphaHandle, alpha);
        if (bgTintHandle >= 0) {
            GLES30.glUniform3f(bgTintHandle, 1.0f, 1.0f, 1.0f);
        }
    }

    private void drawNight(boolean nightInvert) {
        if (nightInvert) {
            drawBackgroundQuad(texNight, 0.0f, -32.0f, 0.0f, 1.0f,
                    0.0f, height, 0.0f, 0.0f,
                    width, height, 2.0f, 0.0f,
                    width, -32.0f, 2.0f, 1.0f);
        } else {
            drawBackgroundQuad(texNight, 0.0f, -32.0f, 0.0f, 0.0f,
                    0.0f, height, 0.0f, 1.0f,
                    width, height, 2.0f, 1.0f,
                    width, -32.0f, 2.0f, 0.0f);
        }
    }

    private void drawSunrise() {
        drawRect(texSunrise);
    }

    private void drawNoon() {
        drawRect(texSky);
    }

    private void drawSunset() {
        drawRect(texSunset);
    }

    private void drawRect(int texture) {
        drawBackgroundQuad(texture, 0.0f, 0.0f, 0.0f, 0.0f,
                0.0f, height, 0.0f, 1.0f,
                width, height, 1.0f, 1.0f,
                width, 0.0f, 1.0f, 0.0f);
    }

    private void drawBackgroundQuad(int texture,
            float x0, float y0, float u0, float v0,
            float x1, float y1, float u1, float v1,
            float x2, float y2, float u2, float v2,
            float x3, float y3, float u3, float v3) {
        if (bgQuadBuffer == null) {
            bgQuadBuffer = ByteBuffer.allocateDirect(4 * 5 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        }

        /*
         * 顶点是 x,y,u,v,a 五个 float。这里的 a 必须显式写 1：
         * 着色器里 float 属性取的是通用属性的 x 分量，而数组没启用时通用默认值是
         * (0,0,0,1) —— 读出来是 0 不是 1，天空会整块变透明。
         */
        int c = 0;
        quadVerts[c++] = x0; quadVerts[c++] = y0; quadVerts[c++] = u0; quadVerts[c++] = v0; quadVerts[c++] = 1.0f;
        quadVerts[c++] = x1; quadVerts[c++] = y1; quadVerts[c++] = u1; quadVerts[c++] = v1; quadVerts[c++] = 1.0f;
        quadVerts[c++] = x2; quadVerts[c++] = y2; quadVerts[c++] = u2; quadVerts[c++] = v2; quadVerts[c++] = 1.0f;
        quadVerts[c++] = x3; quadVerts[c++] = y3; quadVerts[c++] = u3; quadVerts[c++] = v3; quadVerts[c] = 1.0f;

        bgQuadBuffer.clear();
        bgQuadBuffer.put(quadVerts).position(0);
        GLES30.glEnableVertexAttribArray(bgPositionHandle);
        GLES30.glVertexAttribPointer(bgPositionHandle, 2, GLES30.GL_FLOAT, false, 20, bgQuadBuffer);
        bgQuadBuffer.position(2);
        GLES30.glEnableVertexAttribArray(bgTexHandle);
        GLES30.glVertexAttribPointer(bgTexHandle, 2, GLES30.GL_FLOAT, false, 20, bgQuadBuffer);
        if (bgVertexAlphaHandle >= 0) {
            bgQuadBuffer.position(4);
            GLES30.glEnableVertexAttribArray(bgVertexAlphaHandle);
            GLES30.glVertexAttribPointer(bgVertexAlphaHandle, 1, GLES30.GL_FLOAT, false, 20, bgQuadBuffer);
        }

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture);
        GLES30.glUniform1i(bgSamplerHandle, 0);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_FAN, 0, 4);

        GLES30.glDisableVertexAttribArray(bgPositionHandle);
        GLES30.glDisableVertexAttribArray(bgTexHandle);
        if (bgVertexAlphaHandle >= 0) {
            GLES30.glDisableVertexAttribArray(bgVertexAlphaHandle);
        }
    }
}
