package com.reandroid.wallpaper.dandelion;

/**
 * 一颗孢子或一粒浮尘。
 *
 * <p>两者生命周期同构（出生 → 匀速漂移 + 风加速 → 淡出），差别只在贴图、尺寸和淡入淡出时长，
 * 所以共用这一个结构。坐标是 480×800 的设计坐标，渲染时整体等比放大。
 */
public final class DandelionParticle {

    /** 贴图序号：浮尘 0..1 对应小/大两颗，孢子 0..6 对应七张孢子图。 */
    public final int sprite;

    public final float size;

    final long bornMs;
    final long lifeMs;
    final float fadeInMs;
    final float fadeOutMs;
    final float sizeInMs;
    final float sizeOutMs;

    public float x;
    public float y;
    public float velocityX;
    public float velocityY;

    DandelionParticle(
            int sprite,
            float x,
            float y,
            float velocityX,
            float velocityY,
            float size,
            long bornMs,
            long lifeMs,
            float fadeInMs,
            float fadeOutMs,
            float sizeInMs,
            float sizeOutMs) {
        this.sprite = sprite;
        this.x = x;
        this.y = y;
        this.velocityX = velocityX;
        this.velocityY = velocityY;
        this.size = size;
        this.bornMs = bornMs;
        this.lifeMs = lifeMs;
        this.fadeInMs = fadeInMs;
        this.fadeOutMs = fadeOutMs;
        this.sizeInMs = sizeInMs;
        this.sizeOutMs = sizeOutMs;
    }

    float ageMs(long nowMs) {
        return nowMs - bornMs;
    }

    boolean isDead(long nowMs) {
        return ageMs(nowMs) >= lifeMs;
    }

    /** 当前位置的透明度：淡入淡出都是线性的，两端可以都为 0。 */
    float alpha(long nowMs) {
        float age = ageMs(nowMs);
        float a = 1f;
        if (fadeInMs > 0f && age < fadeInMs) {
            a = age / fadeInMs;
        }
        float remaining = lifeMs - age;
        if (fadeOutMs > 0f && remaining < fadeOutMs) {
            a = Math.min(a, remaining / fadeOutMs);
        }
        return Math.max(0f, Math.min(1f, a));
    }

    /** 尺寸系数：sizein / sizeout 描述的是"长到这么大/缩到没有"的时长。 */
    float sizeFactor(long nowMs) {
        float age = ageMs(nowMs);
        float f = 1f;
        if (sizeInMs > 0f && age < sizeInMs) {
            f = age / sizeInMs;
        }
        float remaining = lifeMs - age;
        if (sizeOutMs > 0f && remaining < sizeOutMs) {
            f = Math.min(f, remaining / sizeOutMs);
        }
        return Math.max(0f, Math.min(1f, f));
    }
}
