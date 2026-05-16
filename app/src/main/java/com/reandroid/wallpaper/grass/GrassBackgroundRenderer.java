package com.reandroid.wallpaper.grass;

import android.opengl.GLES20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import com.reandroid.wallpaper.MathUtils;

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
    private final float[] quadVerts = new float[16];

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
        float[] w = new float[4];
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
        GLES20.glEnableVertexAttribArray(skyPositionHandle);
        GLES20.glVertexAttribPointer(skyPositionHandle, 2, GLES20.GL_FLOAT, false, 16, skyQuadBuffer);
        skyQuadBuffer.position(2);
        GLES20.glEnableVertexAttribArray(skyTexHandle);
        GLES20.glVertexAttribPointer(skyTexHandle, 2, GLES20.GL_FLOAT, false, 16, skyQuadBuffer);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texNight);
        GLES20.glUniform1i(skySamplerNightHandle, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texSunrise);
        GLES20.glUniform1i(skySamplerSunriseHandle, 1);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texSunset);
        GLES20.glUniform1i(skySamplerSunsetHandle, 2);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texSky);
        GLES20.glUniform1i(skySamplerSkyHandle, 3);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE4);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texSolarEclipse);
        GLES20.glUniform1i(skySamplerSolarEclipseHandle, 4);

        GLES20.glUniform1f(skyWeightNightHandle, sd.accurateWeights[0]);
        GLES20.glUniform1f(skyWeightSunriseHandle, sd.accurateWeights[1]);
        GLES20.glUniform1f(skyWeightSunsetHandle, sd.accurateWeights[2]);
        GLES20.glUniform1f(skyWeightSkyHandle, sd.accurateWeights[3]);
        GLES20.glUniform1f(skyWeightSolarEclipseHandle, sd.solarEclipseWeight);
        GLES20.glUniform1f(skyNightInvertHandle, sd.nightInvert ? 1.0f : 0.0f);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4);
        GLES20.glDisableVertexAttribArray(skyPositionHandle);
        GLES20.glDisableVertexAttribArray(skyTexHandle);
    }

    private void setAlpha(float alpha) {
        GLES20.glUniform1f(bgAlphaHandle, alpha);
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
            bgQuadBuffer = ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        }

        quadVerts[0] = x0;
        quadVerts[1] = y0;
        quadVerts[2] = u0;
        quadVerts[3] = v0;
        quadVerts[4] = x1;
        quadVerts[5] = y1;
        quadVerts[6] = u1;
        quadVerts[7] = v1;
        quadVerts[8] = x2;
        quadVerts[9] = y2;
        quadVerts[10] = u2;
        quadVerts[11] = v2;
        quadVerts[12] = x3;
        quadVerts[13] = y3;
        quadVerts[14] = u3;
        quadVerts[15] = v3;

        bgQuadBuffer.clear();
        bgQuadBuffer.put(quadVerts).position(0);
        GLES20.glEnableVertexAttribArray(bgPositionHandle);
        GLES20.glVertexAttribPointer(bgPositionHandle, 2, GLES20.GL_FLOAT, false, 16, bgQuadBuffer);
        bgQuadBuffer.position(2);
        GLES20.glEnableVertexAttribArray(bgTexHandle);
        GLES20.glVertexAttribPointer(bgTexHandle, 2, GLES20.GL_FLOAT, false, 16, bgQuadBuffer);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glUniform1i(bgSamplerHandle, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4);

        GLES20.glDisableVertexAttribArray(bgPositionHandle);
        GLES20.glDisableVertexAttribArray(bgTexHandle);
    }
}
