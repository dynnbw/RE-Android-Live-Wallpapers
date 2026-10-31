package com.reandroid.wallpaper.bokeh;

/**
 * 一个散景光斑的生命周期。纯逻辑，单测直接跑。
 *
 * <p>对应原版的 {@code BokehRainbowCircle}：半径按 {@code 1 + 0.5·f} 长大，位置线性漂移，
 * 透明度先升后降（四分之一处最亮）。
 */
final class BokehCircle {

    private static final float RADIUS_GROWTH = 0.5f;

    final float originX;
    final float originY;
    final float originRadius;
    final float deltaX;
    final float deltaY;
    final int color;
    final int steps;

    float x;
    float y;
    float radius;
    int alpha;
    int step;

    BokehCircle(float x, float y, float radius, int color, int steps,
            float deltaX, float deltaY) {
        this.x = x;
        this.originX = x;
        this.y = y;
        this.originY = y;
        this.radius = radius;
        this.originRadius = radius;
        this.color = color;
        this.steps = steps;
        this.deltaX = deltaX;
        this.deltaY = deltaY;
    }

    void tick() {
        step++;
        float fraction = (float) step / (float) steps;
        radius = originRadius + (originRadius * RADIUS_GROWTH * fraction);
        x = originX + deltaX * fraction;
        y = originY + deltaY * fraction;
        // 原版：前 1/4 线性升到 128，之后降到 0
        alpha = fraction <= 0.25f
                ? (int) (512.0f * fraction)
                : (int) (-128.0f * (fraction - 1.0f) / 0.75f);
    }

    boolean isDone() {
        return step > steps;
    }
}
