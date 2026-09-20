package com.reandroid.wallpaper.grass;

import android.content.SharedPreferences;
import android.opengl.GLES30;

/**
 * 夜空星星的 GLES 渲染。
 *
 * <p>批次构建不在这里 —— 它和 Vulkan 共用 {@link GrassRenderDataBuilder#buildStarBatches}。
 * 本类只负责挑贴图和发起绘制。原先这里另有一份完整实现，还额外按 alpha 分了 8 个桶
 * （每次绘制只能有一个统一 alpha），于是 4 组贴图要画 32 次。顶点格式支持逐顶点 alpha 之后，
 * 那份分桶既没必要也不如逐顶点精确，已经删掉，现在是 4 次绘制。
 */
final class GrassStarRenderer {

    void setPluginPrefs(SharedPreferences prefs) {
        nightStarsLayer.setPluginPrefs(prefs);
    }

    private final NightStarsLayer nightStarsLayer = new NightStarsLayer();

    private GrassRenderDataBuilder renderDataBuilder;
    private final GrassRenderDataBuilder.StarBatches starBatches =
            new GrassRenderDataBuilder.StarBatches();

    private int width;
    private int height;
    private int bgMatrixHandle = -1;

    private int texStarWhite;
    private int texStarWarm;
    private int texStarCool;
    private int texStarYellow;

    void setViewport(int width, int height) {
        this.width = width;
        this.height = height;
    }

    void setBackgroundMatrixHandle(int bgMatrixHandle) {
        this.bgMatrixHandle = bgMatrixHandle;
    }

    void setRenderDataBuilder(GrassRenderDataBuilder renderDataBuilder) {
        this.renderDataBuilder = renderDataBuilder;
    }

    void loadTextures(SolidColorTextureFactory solidColorFactory) {
        texStarWhite = solidColorFactory.create((byte) 255, (byte) 255, (byte) 255, (byte) 255);
        texStarWarm = solidColorFactory.create((byte) 255, (byte) 168, (byte) 152, (byte) 255);
        texStarCool = solidColorFactory.create((byte) 158, (byte) 202, (byte) 255, (byte) 255);
        texStarYellow = solidColorFactory.create((byte) 255, (byte) 238, (byte) 170, (byte) 255);
    }

    void releaseTextures() {
        int[] tex = new int[]{texStarWhite, texStarWarm, texStarCool, texStarYellow};
        GLES30.glDeleteTextures(tex.length, tex, 0);
        texStarWhite = 0;
        texStarWarm = 0;
        texStarCool = 0;
        texStarYellow = 0;
    }

    void drawNightStars(SceneData sd, GrassSpriteRenderer spriteRenderer, RenderOps renderOps) {
        if (sd.starVisibility <= 0.001f || texStarWhite == 0 || renderDataBuilder == null) {
            return;
        }

        renderOps.useBackgroundProgram();
        renderOps.setAlphaBlend();
        GLES30.glUniformMatrix4fv(bgMatrixHandle, 1, false, sd.projectionMatrix, 0);

        GrassRenderDataBuilder.StarBatches stars = renderDataBuilder.buildStarBatches(
                nightStarsLayer, sd, width, height, starBatches);

        drawStarGroup(spriteRenderer, texStarWhite, stars.white, stars.whiteCount);
        drawStarGroup(spriteRenderer, texStarWarm, stars.warm, stars.warmCount);
        drawStarGroup(spriteRenderer, texStarCool, stars.cool, stars.coolCount);
        drawStarGroup(spriteRenderer, texStarYellow, stars.yellow, stars.yellowCount);
    }

    private static void drawStarGroup(GrassSpriteRenderer spriteRenderer,
            int texture, float[] vertices, int vertexCount) {
        if (texture == 0 || vertexCount <= 0) {
            return;
        }
        // uniform alpha 传 1：透明度已经逐顶点烘好了
        spriteRenderer.drawBatch(texture, vertices,
                vertexCount * GrassRenderDataBuilder.FLOATS_PER_SPRITE_VERTEX, 1.0f);
    }
}
