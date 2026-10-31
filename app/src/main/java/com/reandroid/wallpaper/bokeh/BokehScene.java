package com.reandroid.wallpaper.bokeh;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 散景彩虹的场景逻辑：光斑的生成、漂移、消亡，以及随屏滚动。
 *
 * <p>原版把像素常量写死（半径 40、漂移 ±20、描边 3），那是给当年 480–640p 的屏写的；
 * 这里按屏幕短边等比放大，密度按面积折算，否则在同一块现代高分屏上会缩成一点点。
 */
public final class BokehScene {

    /** 原版参考屏的短边（480p）。 */
    private static final float BASE_SHORT_SIDE = 480f;

    /** 原版参考屏的面积，用来把初始光斑数量按面积折算。 */
    private static final float BASE_AREA = 480f * 800f;

    private static final int BASE_INITIAL_CIRCLES = 20;
    private static final int MAX_CIRCLES = 360;
    private static final int RING_PX = 3;

    private final Random mRandom = new Random();
    private final List<BokehCircle> mCircles = new ArrayList<>();

    private int mWidth;
    private int mHeight;
    private int mOffsetX;
    private int mOffsetY;
    private boolean mPreview;
    private int mIteration;
    private boolean mSeeded;

    /** 短边相对原版的放大倍数。 */
    private float mScale = 1f;

    public void resize(int width, int height, boolean preview) {
        if (width <= 0 || height <= 0) {
            return;
        }
        mWidth = width;
        mHeight = height;
        mPreview = preview;
        mScale = Math.min(4f, Math.max(1f, Math.min(width, height) / BASE_SHORT_SIDE));
        if (!mSeeded) {
            mSeeded = true;
            int initial = Math.round(BASE_INITIAL_CIRCLES * areaScale());
            for (int i = 0; i < initial; i++) {
                spawnRandom();
            }
        }
    }

    public void setOffset(int offsetX, int offsetY) {
        mOffsetX = offsetX;
        mOffsetY = offsetY;
    }

    public void update() {
        if (mWidth <= 0 || mHeight <= 0) {
            return;
        }
        for (int i = mCircles.size() - 1; i >= 0; i--) {
            BokehCircle circle = mCircles.get(i);
            circle.tick();
            if (circle.isDone()) {
                mCircles.remove(i);
            }
        }
        mIteration++;
        // 原版：预览每帧生成，壁纸路径每两帧生成一个
        if (mPreview || mIteration % 2 == 0) {
            spawnRandom();
        }
    }

    /** 点击处生成一个光斑（屏幕坐标，需要扣掉滚动偏移）。 */
    public void addCircleAt(float screenX, float screenY) {
        createCircle(screenX - mOffsetX, screenY - mOffsetY);
    }

    public List<BokehCircle> circles() {
        return mCircles;
    }

    public int offsetX() {
        return mOffsetX;
    }

    public int offsetY() {
        return mOffsetY;
    }

    /** 描边宽度，与原版同样按屏等比放大。 */
    public float ringPx() {
        return RING_PX * mScale;
    }

    private float areaScale() {
        return Math.max(1f, (float) (mWidth * (long) mHeight) / BASE_AREA);
    }

    private void spawnRandom() {
        if (mWidth <= 0 || mHeight <= 0) {
            return;
        }
        createCircle(
                (float) (mWidth * mRandom.nextDouble()), (float) (mHeight * mRandom.nextDouble()));
    }

    private void createCircle(float x, float y) {
        if (mCircles.size() >= MAX_CIRCLES) {
            return;
        }
        float radius = 40f * mScale;
        // 原版的色相按 y 位置取彩虹，并带一点随机扰动
        float yFraction = 0.05f + y / mHeight - 0.2f * mRandom.nextFloat();
        if (yFraction < 0f) {
            yFraction += 1f;
        }
        if (yFraction > 1f) {
            yFraction -= 1f;
        }
        int color = rainbowColor(yFraction);
        int steps = 40 + mRandom.nextInt(20);
        float deltaX = (float) (mRandom.nextDouble() * 40.0 - 20.0) * mScale;
        float deltaY = (float) (mRandom.nextDouble() * 40.0 - 20.0) * mScale;
        mCircles.add(new BokehCircle(x, y, radius, color, steps, deltaX, deltaY));
    }

    /** HSV(hue, 1, 1) 转 0xRRGGBB。纯 Java，单测能跑。 */
    static int rainbowColor(float fraction) {
        float hue = (fraction - (float) Math.floor(fraction)) * 6f;
        int sector = (int) hue;
        float f = hue - sector;
        int up = Math.round(255f * f);
        int down = Math.round(255f * (1f - f));
        int r;
        int g;
        int b;
        switch (sector) {
            case 0:
                r = 255;
                g = up;
                b = 0;
                break;
            case 1:
                r = down;
                g = 255;
                b = 0;
                break;
            case 2:
                r = 0;
                g = 255;
                b = up;
                break;
            case 3:
                r = 0;
                g = down;
                b = 255;
                break;
            case 4:
                r = up;
                g = 0;
                b = 255;
                break;
            default:
                r = 255;
                g = 0;
                b = down;
                break;
        }
        return (r << 16) | (g << 8) | b;
    }
}
