package com.reandroid.wallpaper.grass;

import android.opengl.GLES20;

final class GrassStarRenderer {

    interface SolidColorTextureFactory {
        int create(byte r, byte g, byte b, byte a);
    }

    interface RenderOps {
        void useBackgroundProgram();

        void setAlphaBlend();
    }

    private final NightStarsLayer nightStarsLayer = new NightStarsLayer();

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

    void loadTextures(SolidColorTextureFactory solidColorFactory) {
        texStarWhite = solidColorFactory.create((byte) 255, (byte) 255, (byte) 255, (byte) 255);
        texStarWarm = solidColorFactory.create((byte) 255, (byte) 168, (byte) 152, (byte) 255);
        texStarCool = solidColorFactory.create((byte) 158, (byte) 202, (byte) 255, (byte) 255);
        texStarYellow = solidColorFactory.create((byte) 255, (byte) 238, (byte) 170, (byte) 255);
    }

    void releaseTextures() {
        int[] tex = new int[]{texStarWhite, texStarWarm, texStarCool, texStarYellow};
        GLES20.glDeleteTextures(tex.length, tex, 0);
        texStarWhite = 0;
        texStarWarm = 0;
        texStarCool = 0;
        texStarYellow = 0;
    }

    void drawNightStars(SceneData sd, GrassSpriteRenderer spriteRenderer, RenderOps renderOps) {
        if (sd.starVisibility <= 0.001f || texStarWhite == 0) {
            return;
        }

        renderOps.useBackgroundProgram();
        renderOps.setAlphaBlend();
        GLES20.glUniformMatrix4fv(bgMatrixHandle, 1, false, sd.projectionMatrix, 0);

        nightStarsLayer.draw(sd.animNowMs, width, height, new NightStarsLayer.SpriteDrawer() {
            @Override
            public void draw(int tintType, float cx, float cy, float size, float alpha, float shift) {
                int texture = textureForStarTint(tintType, shift);
                spriteRenderer.drawSprite(texture, cx, cy, size,
                        alpha * sd.starVisibility, false, 0.0f);
            }
        });
    }

    private int textureForStarTint(int tintType, float shift) {
        switch (tintType) {
            case NightStarsLayer.STAR_TINT_RED:
                return shift > 0.6f ? texStarWarm : texStarYellow;
            case NightStarsLayer.STAR_TINT_BLUE:
                return shift > 0.45f ? texStarCool : texStarWhite;
            case NightStarsLayer.STAR_TINT_YELLOW:
                return shift > 0.35f ? texStarYellow : texStarWhite;
            case NightStarsLayer.STAR_TINT_WHITE:
            default:
                return texStarWhite;
        }
    }
}
