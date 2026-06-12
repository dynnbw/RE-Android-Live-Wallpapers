package com.reandroid.wallpaper.aurora1;

import android.opengl.Matrix;
import android.view.MotionEvent;

import com.reandroid.utils.MathUtils;
import java.util.Random;

final class Aurora1Scene {
    static final int TEXTURE_BG = 0;
    static final int TEXTURE_BG_LANDSCAPE = 1;
    static final int TEXTURE_AURORA_1 = 2;
    static final int TEXTURE_AURORA_2 = 3;
    static final int TEXTURE_AURORA_3 = 4;
    static final int TEXTURE_STAR = 5;
    static final int TEXTURE_PARTICLE_P1_1 = 6;
    static final int TEXTURE_PARTICLE_P1_2 = 7;
    static final int TEXTURE_PARTICLE_P1_3 = 8;
    static final int TEXTURE_PARTICLE_S1 = 9;

    private static final float DESIGN_WIDTH = 480.0f;
    private static final float DESIGN_HEIGHT = 800.0f;
        private static final int STAR_COUNT = 30;
        private static final int MAX_PARTICLES = 60;

        private static final float[] STAR_SEQ_A = new float[] {0f, 1f, 0.8f, 0f, 0f};
        private static final float[] STAR_SEQ_B = new float[] {0f, 0f, 1f, 0f, 0f, 0f};
        private static final float[] STAR_SEQ_C = new float[] {0f, 0f, 0f, 0f, 1f, 0f};
        private static final float[] STAR_SEQ_D = new float[] {0f, 1f, 1f, 0f, 0f, 0f};
        private static final float[] STAR_SEQ_E = new float[] {0f, 0f, 0f, 1f, 0f, 0f};
        private static final float[] STAR_SEQ_F = new float[] {0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f};
        private static final float[] STAR_SEQ_G = new float[] {0f, 0f, 0f, 0f, 1f, 1f, 0f, 0f};
        private static final float[] STAR_SEQ_H = new float[] {0f, 0f, 1f, 0f, 0f, 0f, 0f};

        private static final StarSpec[] STAR_SPECS = new StarSpec[] {
            new StarSpec(50f, 100f, 6.5f, 0.50f, STAR_SEQ_A, 3f, 0.02f),
            new StarSpec(70f, 700f, 9.1f, 0.70f, STAR_SEQ_B, 9f, 0.03f),
            new StarSpec(100f, 400f, 2.6f, 0.20f, STAR_SEQ_C, 3f, 0.04f),
            new StarSpec(200f, 150f, 7.2f, 0.60f, STAR_SEQ_B, 7f, 0.05f),
            new StarSpec(250f, 600f, 7.8f, 0.60f, STAR_SEQ_B, 4f, 0.05f),
            new StarSpec(300f, 200f, 3.9f, 0.30f, STAR_SEQ_D, 5f, 0.06f),
            new StarSpec(450f, 750f, 3.9f, 0.30f, STAR_SEQ_E, 7f, 0.07f),
            new StarSpec(500f, 500f, 3.9f, 0.30f, STAR_SEQ_F, 4f, 0.08f),
            new StarSpec(600f, 300f, 7.8f, 0.60f, STAR_SEQ_D, 5f, 0.09f),
            new StarSpec(680f, 280f, 9.1f, 0.70f, STAR_SEQ_E, 10f, 0.10f),
            new StarSpec(690f, 110f, 1.3f, 0.10f, STAR_SEQ_E, 8f, 0.11f),
            new StarSpec(700f, 700f, 13f, 0.50f, STAR_SEQ_G, 4f, 0.12f),
            new StarSpec(800f, 200f, 6.5f, 0.50f, STAR_SEQ_F, 3f, 0.13f),
            new StarSpec(850f, 150f, 6.5f, 0.50f, STAR_SEQ_H, 9f, 0.14f),
                new StarSpec(900f, 300f, 9.1f, 0.70f, STAR_SEQ_D, 10f, 0.15f),
                new StarSpec(900f, 600f, 9.1f, 0.70f, STAR_SEQ_G, 6f, 0.03f),
                new StarSpec(920f, 350f, 9.1f, 0.20f, STAR_SEQ_G, 6f, 0.04f),
                new StarSpec(1000f, 80f, 7.0f, 0.50f, new float[] {0f, 1f, 0f, 0f}, 7f, 0.05f),
                new StarSpec(1000f, 600f, 9.1f, 0.20f, new float[] {0f, 0f, 1f, 1f, 0f}, 6f, 0.06f),
                new StarSpec(1000f, 700f, 7.0f, 0.70f, new float[] {0f, 1f, 1f, 0f, 0f}, 4f, 0.07f),
                new StarSpec(1100f, 400f, 6.5f, 0.50f, STAR_SEQ_H, 9f, 0.08f),
                new StarSpec(1250f, 100f, 6.5f, 0.50f, STAR_SEQ_A, 3f, 0.09f),
                new StarSpec(1270f, 700f, 9.1f, 0.70f, STAR_SEQ_B, 9f, 0.10f),
                new StarSpec(1300f, 400f, 2.6f, 0.20f, STAR_SEQ_C, 3f, 0.11f),
                new StarSpec(1300f, 150f, 7.2f, 0.60f, STAR_SEQ_B, 7f, 0.12f),
                new StarSpec(1450f, 600f, 7.8f, 0.60f, STAR_SEQ_B, 4f, 0.13f),
                new StarSpec(1500f, 200f, 3.9f, 0.30f, STAR_SEQ_D, 5f, 0.14f),
                new StarSpec(1500f, 750f, 3.9f, 0.30f, STAR_SEQ_E, 7f, 0.15f),
                new StarSpec(1550f, 500f, 3.9f, 0.30f, STAR_SEQ_F, 4f, 0.04f),
                new StarSpec(1600f, 300f, 7.8f, 0.60f, STAR_SEQ_D, 5f, 0.05f),
                new StarSpec(1680f, 280f, 9.1f, 0.70f, STAR_SEQ_E, 10f, 0.06f),
                new StarSpec(1690f, 110f, 2.6f, 0.10f, STAR_SEQ_E, 8f, 0.07f),
                new StarSpec(1700f, 700f, 6.5f, 1.00f, STAR_SEQ_G, 4f, 0.08f),
                new StarSpec(1800f, 300f, 7.2f, 0.50f, STAR_SEQ_F, 3f, 0.09f),
                new StarSpec(1850f, 600f, 6.5f, 0.50f, STAR_SEQ_H, 9f, 0.10f),
                new StarSpec(1900f, 350f, 9.1f, 0.70f, STAR_SEQ_D, 10f, 0.11f),
                new StarSpec(1900f, 150f, 9.1f, 0.70f, STAR_SEQ_G, 6f, 0.12f),
                new StarSpec(1920f, 300f, 9.1f, 0.20f, STAR_SEQ_G, 6f, 0.13f)
        };

        private static final AnimatedLayerSpec[] LAYER_SPECS = new AnimatedLayerSpec[] {
            new AnimatedLayerSpec(TEXTURE_AURORA_1, 548f, 779f, 1.00f, 1300f, -500f, 0f, 500f, 60f, 0f, 90f, 60f, new float[] {1f, 0.95f, 0.98f, 1f}, 5f, new float[] {0f, 0.5f, 0.4f, 0.45f, 0.5f, 0f}, 30f, 0.020f),
            new AnimatedLayerSpec(TEXTURE_AURORA_1, 548f, 779f, 0.80f, 0f, 600f, 400f, 1100f, 30f, 305f, 350f, 30f, null, 0f, new float[] {0f, 0.6f, 0.55f, 0.6f, 0f}, 10f, 0.018f),
            new AnimatedLayerSpec(TEXTURE_AURORA_1, 548f, 779f, 1.00f, 0f, 1000f, -800f, 1500f, 48f, 50f, -180f, 48f, null, 0f, new float[] {0f, 0.65f, 0f}, 12f, 0.022f),
            new AnimatedLayerSpec(TEXTURE_AURORA_1, 548f, 779f, 1.00f, 1500f, 2500f, 800f, -150f, 48f, -50f, 180f, 48f, null, 0f, new float[] {0f, 0.65f, 0f}, 12f, 0.022f),
            new AnimatedLayerSpec(TEXTURE_AURORA_2, 527f, 776f, 0.50f, 0f, -400f, 200f, 800f, 50f, 0f, -30f, 50f, null, 0f, new float[] {0f, 0.5f, 0f, 0.4f, 0.25f, 0.5f, 0f}, 10f, 0.014f),
            new AnimatedLayerSpec(TEXTURE_AURORA_2, 527f, 776f, 0.70f, 800f, -400f, 500f, 300f, 20f, -90f, -180f, 20f, null, 0f, new float[] {0f, 0.35f, 0f, 0.4f, 0.3f, 0.4f, 0.35f, 0f}, 20f, 0.015f),
            new AnimatedLayerSpec(TEXTURE_AURORA_2, 527f, 776f, 0.70f, -250f, 1200f, 1200f, 800f, 15f, 270f, 220f, 15f, null, 0f, new float[] {0f, 0.55f, 0f, 0.4f, 0.25f, 0.45f, 0f}, 15f, 0.016f),
            new AnimatedLayerSpec(TEXTURE_AURORA_2, 527f, 776f, 0.50f, 800f, 1200f, 0f, -400f, 28f, 90f, 90f, 28f, new float[] {0.5f, 1.5f}, 28f, new float[] {0f, 0.5f, 0f}, 14f, 0.016f),
            new AnimatedLayerSpec(TEXTURE_AURORA_2, 527f, 776f, 0.70f, 600f, 1200f, 800f, 1200f, 20f, 180f, 180f, 20f, new float[] {0.7f, 1.7f}, 20f, new float[] {0f, 0.5f, 0f}, 10f, 0.015f),
            new AnimatedLayerSpec(TEXTURE_AURORA_2, 527f, 776f, 0.70f, 1800f, 2400f, 800f, 400f, 20f, 180f, 180f, 20f, new float[] {1.7f, 0.7f}, 20f, new float[] {0f, 0.5f, 0f}, 10f, 0.015f),
            new AnimatedLayerSpec(TEXTURE_AURORA_2, 527f, 776f, 0.50f, 3000f, 2400f, 400f, -100f, 22f, 90f, 90f, 22f, new float[] {1.7f, 0.7f}, 33f, new float[] {0f, 0.5f, 0f}, 11f, 0.015f),
            new AnimatedLayerSpec(TEXTURE_AURORA_3, 1178f, 779f, 0.80f, 1000f, -700f, 0f, 300f, 24f, 0f, -120f, 24f, new float[] {0.9f, 0.7f, 0.8f, 0.9f}, 12f, new float[] {0f, 0.65f, 0.4f, 0.5f, 0f}, 12f, 0.010f),
            new AnimatedLayerSpec(TEXTURE_AURORA_3, 1178f, 779f, 1.00f, 0f, 1700f, 0f, 500f, 38f, 65f, 180f, 38f, null, 0f, new float[] {0f, 0.45f, 0.5f, 0.3f, 0.45f, 0f}, 38f, 0.010f),
            new AnimatedLayerSpec(TEXTURE_AURORA_3, 1178f, 779f, 1.00f, 0f, 700f, 2000f, 2500f, 38f, 165f, 80f, 38f, null, 0f, new float[] {0f, 0.45f, 0.5f, 0.3f, 0.45f, 0f}, 38f, 0.010f)
        };

    static final class Sprite {
        int textureId;
        boolean additive;
        float x;
        float y;
        float width;
        float height;
        float rotationDeg;
        float alpha;
        float red = 1.0f;
        float green = 1.0f;
        float blue = 1.0f;
        float flowX;
        float flowY;
        float distort;
    }

    static final class SceneData {
        final float[] projectionMatrix = new float[16];
        final Sprite background = new Sprite();
        final Sprite[] auroraSprites = createSprites(LAYER_SPECS.length);
        final Sprite[] starSprites = createSprites(STAR_COUNT);
        final Sprite[] particleSprites = createSprites(MAX_PARTICLES);
        boolean useLandscape;
        int activeParticleCount;

        private static Sprite[] createSprites(int count) {
            Sprite[] sprites = new Sprite[count];
            for (int i = 0; i < count; i++) {
                sprites[i] = new Sprite();
            }
            return sprites;
        }
    }

    private static final class AnimatedLayerSpec {
        final int textureId;
        final float textureWidth;
        final float textureHeight;
        final float baseScale;
        final float txStart;
        final float txEnd;
        final float tyStart;
        final float tyEnd;
        final float translationDuration;
        final float rzStart;
        final float rzEnd;
        final float rotationDuration;
        final float[] scaleSequence;
        final float scaleDuration;
        final float[] opacitySequence;
        final float opacityDuration;
        final float distort;

        AnimatedLayerSpec(int textureId, float textureWidth, float textureHeight, float baseScale,
                float txStart, float txEnd, float tyStart, float tyEnd, float translationDuration,
                float rzStart, float rzEnd, float rotationDuration,
                float[] scaleSequence, float scaleDuration,
                float[] opacitySequence, float opacityDuration,
                float distort) {
            this.textureId = textureId;
            this.textureWidth = textureWidth;
            this.textureHeight = textureHeight;
            this.baseScale = baseScale;
            this.txStart = txStart;
            this.txEnd = txEnd;
            this.tyStart = tyStart;
            this.tyEnd = tyEnd;
            this.translationDuration = translationDuration;
            this.rzStart = rzStart;
            this.rzEnd = rzEnd;
            this.rotationDuration = rotationDuration;
            this.scaleSequence = scaleSequence;
            this.scaleDuration = scaleDuration;
            this.opacitySequence = opacitySequence;
            this.opacityDuration = opacityDuration;
            this.distort = distort;
        }
    }

    private static final class StarSpec {
        final float x;
        final float y;
        final float size;
        final float alpha;
        final float[] pulseSequence;
        final float pulseDuration;
        final float parallax;

        StarSpec(float x, float y, float size, float alpha, float[] pulseSequence, float pulseDuration, float parallax) {
            this.x = x;
            this.y = y;
            this.size = size;
            this.alpha = alpha;
            this.pulseSequence = pulseSequence;
            this.pulseDuration = pulseDuration;
            this.parallax = parallax;
        }
    }

    private static final class ParticleState {
        boolean active;
        float x;
        float y;
        float vx;
        float vy;
        float size;
        float life;
        float age;
        float spin;
        int textureId;
    }

    private final SceneData mSceneData = new SceneData();
    private final Random mRandom = new Random(0xA071A1);
    private final ParticleState[] mParticles = new ParticleState[MAX_PARTICLES];

    private int mWidth;
    private int mHeight;
    private float mXOffset = 0.5f;
    private float mTouchX = -1000.0f;
    private float mTouchY = -1000.0f;
    private boolean mTouchActive;
    private float mEmitterAccumulator;
    private long mLastUpdateMs;
    private float mElapsedSec;

    Aurora1Scene(int width, int height) {
        for (int i = 0; i < MAX_PARTICLES; i++) {
            mParticles[i] = new ParticleState();
        }
        // 初始化所有极光精灵，确保即使还未调用update也有有效的默认值
        for (int i = 0; i < mSceneData.auroraSprites.length; i++) {
            Sprite sprite = mSceneData.auroraSprites[i];
            sprite.textureId = LAYER_SPECS[i].textureId;  // 使用spec中的正确纹理ID
            sprite.width = 2560.0f;
            sprite.height = 2560.0f;
            sprite.alpha = 0.5f;
            sprite.red = 1.0f;
            sprite.green = 1.0f;
            sprite.blue = 1.0f;
            sprite.additive = true;
        }
        // 也初始化背景精灵
        mSceneData.background.alpha = 1.0f;
        mSceneData.background.red = 1.0f;
        mSceneData.background.green = 1.0f;
        mSceneData.background.blue = 1.0f;
        resize(width, height);
    }

    void resize(int width, int height) {
        mWidth = Math.max(1, width);
        mHeight = Math.max(1, height);
        Matrix.orthoM(mSceneData.projectionMatrix, 0, 0.0f, mWidth, 0.0f, mHeight, -1.0f, 1.0f);
    }

    void setOffset(float xOffset) {
        mXOffset = xOffset;
    }

    void onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                mTouchActive = true;
                mTouchX = event.getX();
                mTouchY = mHeight - event.getY();
                emitBurst(mTouchX, mTouchY, mWidth < 490 ? 2 : 8);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mTouchActive = false;
                emitBurst(mTouchX, mTouchY, mWidth < 490 ? 1 : 4);
                mTouchX = -1000.0f;
                mTouchY = -1000.0f;
                break;
            default:
                break;
        }
    }

    void update(long timeMs) {
        if (mLastUpdateMs == 0L) {
            mLastUpdateMs = timeMs;
        }
        float dt = Math.min(0.05f, Math.max(0.0f, (timeMs - mLastUpdateMs) / 1000.0f));
        mLastUpdateMs = timeMs;
        mElapsedSec += dt;

        updateBackground();
        updateStars();
        updateAuroraLayers();
        updateParticles(dt);
    }

    SceneData getSceneData() {
        return mSceneData;
    }

    private void updateBackground() {
        SceneData data = mSceneData;
        Sprite bg = data.background;
        data.useLandscape = mWidth > mHeight;

        bg.textureId = data.useLandscape ? TEXTURE_BG_LANDSCAPE : TEXTURE_BG;
        bg.additive = false;
        bg.alpha = 1.0f;
        bg.red = 1.0f;
        bg.green = 1.0f;
        bg.blue = 1.0f;
        bg.rotationDeg = 0.0f;
        bg.flowX = 0.0f;
        bg.flowY = 0.0f;
        bg.distort = 0.0f;
        
        // 为背景计算宽度，确保覆盖整个屏幕且不会过度拉伸
        if (data.useLandscape) {
            // 横屏：bg_landscape (1024x300) 宽高比约3.41
            // 使用能覆盖屏幕且考虑滚动的宽度
            float screenAspect = (float) mWidth / mHeight;      // 2400/1080 ≈ 2.22
            float textureAspect = 1024.0f / 300.0f;              // ≈ 3.41
            
            if (screenAspect > textureAspect) {
                // 屏幕宽高比比纹理大，需要拉宽纹理来填充
                // 确保背景至少占屏幕宽度的1.5倍（用于滚动）
                bg.width = Math.max(mWidth * 1.5f, mWidth * textureAspect / screenAspect * 2.0f);
            } else {
                // 屏幕宽高比比纹理小，直接使用1.5倍宽度覆盖
                bg.width = mWidth * 1.5f;
            }
            bg.height = mHeight;
        } else {
            // 竖屏：bg (512x512) 是方形
            bg.width = mHeight * 1.5f;
            bg.height = mHeight;
        }
        
        // 根据 xOffset 计算背景位置，支持水平滚动
        // 滚动范围 = 背景宽度 - 屏幕宽度，确保任意 xOffset 均无黑边
        float scrollRange = Math.max(0, bg.width - mWidth);
        bg.x = bg.width * 0.5f - mXOffset * scrollRange;
        bg.y = mHeight * 0.5f;
    }

    private void updateStars() {
        float scaleX = mWidth / DESIGN_WIDTH;
        float scaleY = mHeight / DESIGN_HEIGHT;
        float parallax = (0.5f - mXOffset) * mWidth;
        for (int i = 0; i < STAR_COUNT; i++) {
            StarSpec state = STAR_SPECS[i];
            Sprite sprite = mSceneData.starSprites[i];
            sprite.textureId = TEXTURE_STAR;
            sprite.additive = true;
            float pulse = sampleSequence(state.pulseSequence, state.pulseDuration, mElapsedSec + i * 0.17f);
            float size = state.size * scaleX * (0.82f + 0.36f * pulse);
            sprite.width = size;
            sprite.height = size;
            sprite.x = state.x * scaleX + parallax * state.parallax;
            sprite.y = mHeight - state.y * scaleY;
            sprite.rotationDeg = 0.0f;
            sprite.red = 0.94f;
            sprite.green = 0.88f;
            sprite.blue = 1.0f;
            sprite.alpha = MathUtils.clamp(state.alpha * (0.28f + 0.72f * pulse), 0.0f, 1.0f);
            sprite.flowX = 0.0f;
            sprite.flowY = 0.0f;
            sprite.distort = 0.0f;
        }
    }

    private void updateAuroraLayers() {
        float scaleX = Math.max(0.001f, mWidth / DESIGN_WIDTH);
        float scaleY = Math.max(0.001f, mHeight / DESIGN_HEIGHT);
        float auroraParallax = (0.5f - mXOffset) * mWidth * 1.5f;
        float touchInfluenceX = mTouchActive ? (mTouchX / Math.max(1.0f, mWidth) - 0.5f) * 36.0f : 0.0f;
        float touchInfluenceY = mTouchActive ? ((mTouchY / Math.max(1.0f, mHeight)) - 0.4f) * 24.0f : 0.0f;

        for (int i = 0; i < LAYER_SPECS.length; i++) {
            AnimatedLayerSpec spec = LAYER_SPECS[i];
            Sprite sprite = mSceneData.auroraSprites[i];
            float tx = MathUtils.lerp(spec.txStart, spec.txEnd, cycle01(mElapsedSec, spec.translationDuration));
            float ty = MathUtils.lerp(spec.tyStart, spec.tyEnd, cycle01(mElapsedSec, spec.translationDuration));
            float rotation = MathUtils.lerp(spec.rzStart, spec.rzEnd, cycle01(mElapsedSec, spec.rotationDuration));
            float scale = spec.baseScale;
            if (spec.scaleSequence != null && spec.scaleDuration > 0.0f) {
                scale = sampleSequence(spec.scaleSequence, spec.scaleDuration, mElapsedSec);
            }
            float opacity = sampleSequence(spec.opacitySequence, spec.opacityDuration, mElapsedSec);

            sprite.textureId = spec.textureId;
            sprite.additive = true;
            sprite.width = Math.max(1.0f, spec.textureWidth * scaleX * scale);
            sprite.height = Math.max(1.0f, spec.textureHeight * scaleY * scale);
            sprite.x = tx * scaleX + sprite.width * 0.5f + auroraParallax + touchInfluenceX * (0.22f + i * 0.015f);
            sprite.y = mHeight - ty * scaleY - sprite.height * 0.5f + touchInfluenceY * 0.10f;
            sprite.rotationDeg = rotation;
            sprite.alpha = Math.max(0.35f, opacity);
            if (spec.textureId == TEXTURE_AURORA_1) {
                sprite.red = 0.90f;
                sprite.green = 0.94f;
                sprite.blue = 1.0f;
            } else if (spec.textureId == TEXTURE_AURORA_2) {
                sprite.red = 1.0f;
                sprite.green = 0.88f;
                sprite.blue = 1.0f;
            } else {
                sprite.red = 0.92f;
                sprite.green = 0.90f;
                sprite.blue = 1.0f;
            }
            sprite.flowX = i * 0.31f;
            sprite.flowY = 0.16f + i * 0.011f;
            sprite.distort = spec.distort;
        }
    }

    private void updateParticles(float dt) {
        if (mTouchActive) {
            mEmitterAccumulator += dt;
            float interval = mWidth < 490 ? 0.10f : 0.10f;
            while (mEmitterAccumulator >= interval) {
                mEmitterAccumulator -= interval;
                emitBurst(mTouchX, mTouchY, mWidth < 490 ? 1 : 3);
            }
        } else {
            mEmitterAccumulator = 0.0f;
        }

        int activeCount = 0;
        for (int i = 0; i < MAX_PARTICLES; i++) {
            ParticleState state = mParticles[i];
            if (!state.active) {
                continue;
            }
            state.age += dt;
            if (state.age >= state.life) {
                state.active = false;
                continue;
            }
            state.x += state.vx * dt;
            state.y += state.vy * dt;
            state.vx *= 0.986f;
            state.vy = state.vy * 0.989f + 10.0f * dt;

            Sprite sprite = mSceneData.particleSprites[activeCount++];
            float t = state.age / state.life;
            sprite.textureId = state.textureId;
            sprite.additive = true;
            sprite.x = state.x;
            sprite.y = state.y;
            sprite.width = state.size * (0.78f + 0.46f * t);
            sprite.height = sprite.width;
            sprite.rotationDeg = state.spin * t;
            sprite.alpha = (1.0f - t) * (1.0f - t);
            sprite.red = 1.0f;
            sprite.green = 0.86f + 0.10f * (1.0f - t);
            sprite.blue = 1.0f;
            sprite.flowX = 0.0f;
            sprite.flowY = 0.0f;
            sprite.distort = 0.0f;
        }
        mSceneData.activeParticleCount = activeCount;
    }

    private void emitBurst(float x, float y, int count) {
        if (x < -100.0f || y < -100.0f) {
            return;
        }
        boolean portraitTouch = mWidth < 490;
        for (int i = 0; i < count; i++) {
            ParticleState state = obtainParticle();
            float angle = (float) (mRandom.nextFloat() * Math.PI * 2.0);
            float speed = portraitTouch ? 120.0f + mRandom.nextFloat() * 300.0f : 80.0f + mRandom.nextFloat() * 150.0f;
            state.active = true;
            state.x = x + randomRange(-10.0f, 10.0f);
            state.y = y + randomRange(-10.0f, 10.0f);
            state.vx = (float) Math.cos(angle) * speed;
            state.vy = (float) Math.sin(angle) * speed - (portraitTouch ? 140.0f : 45.0f);
            state.size = portraitTouch ? (8.0f + mRandom.nextFloat() * 8.0f) : (14.0f + mRandom.nextFloat() * 18.0f);
            state.life = portraitTouch ? (0.18f + mRandom.nextFloat() * 0.10f) : (0.7f + mRandom.nextFloat() * 0.35f);
            state.age = 0.0f;
            state.spin = randomRange(-55.0f, 55.0f);
            if (portraitTouch) {
                state.textureId = TEXTURE_PARTICLE_S1;
            } else {
                int texturePick = mRandom.nextInt(3);
                state.textureId = texturePick == 0 ? TEXTURE_PARTICLE_P1_1
                        : texturePick == 1 ? TEXTURE_PARTICLE_P1_2
                        : TEXTURE_PARTICLE_P1_3;
            }
        }
    }

    private ParticleState obtainParticle() {
        for (int i = 0; i < MAX_PARTICLES; i++) {
            if (!mParticles[i].active) {
                return mParticles[i];
            }
        }
        ParticleState oldest = mParticles[0];
        for (int i = 1; i < MAX_PARTICLES; i++) {
            if (mParticles[i].age > oldest.age) {
                oldest = mParticles[i];
            }
        }
        return oldest;
    }

    private float cycle01(float time, float duration) {
        if (duration <= 0.0f) {
            return 0.0f;
        }
        float wrapped = time % duration;
        if (wrapped < 0.0f) {
            wrapped += duration;
        }
        return wrapped / duration;
    }

    private float sampleSequence(float[] values, float duration, float time) {
        if (values == null || values.length == 0) {
            return 1.0f;
        }
        if (values.length == 1 || duration <= 0.0f) {
            return values[0];
        }
        float progress = cycle01(time, duration) * (values.length - 1);
        int index = (int) Math.floor(progress);
        int nextIndex = Math.min(values.length - 1, index + 1);
        float fraction = progress - index;
        return MathUtils.lerp(values[index], values[nextIndex], fraction);
    }

    private float randomRange(float min, float max) {
        return min + mRandom.nextFloat() * (max - min);
    }
}