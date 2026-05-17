package com.reandroid.wallpaper.wildworld;

import android.os.SystemClock;

import java.util.Random;

/**
 * WildWorld 场景逻辑 — 100% 移植自 RenderScript wildworld.rs。
 * 所有坐标/速度为固定像素值（4 档密度），无缩放变换。
 */
final class WildWorldScene {

    static final int DENSITY_240x320 = 1;
    static final int DENSITY_240x400 = 2;
    static final int DENSITY_320x480 = 3;
    static final int DENSITY_480x800 = 4;

    static final float MIN_DT = 0.2f;
    static final long DINO_DT = 1000;
    static final long PTERO_DT = 2400;
    static final long GEN_TIME = 4000;
    static final float GEN_RANDOM = 0.4f;

    static final int UP = 0;
    static final int DOWN = 1;

    static final float FIREBALL_DISTANCE = 0.96f;
    static final float VCN_DISTANCE = 0.95f;
    static final float LAYER4_DISTANCE = 0.9f;
    static final float LAYER3_DISTANCE = 0.8f;
    static final float LAYER2_DISTANCE = 0.7f;
    static final float LAYER1_DISTANCE = 0.6f;
    static final float PTEROSAUR_DISTANCE = 0.85f;
    static final float DINOSAUR1_DISTANCE = 0.85f;
    static final float DINOSAUR2_DISTANCE = 0.75f;
    static final int FIREBALL_COUNT = 6;

    // ---- 实体类型（包级私有，GL 访问）----
    static final class Layer {
        float x, y, w, h;
    }
    static final class Pterosaur {
        float x, y, w, h, scale;
        int duration, alive;
        float time;
    }
    static final class Dinosaur {
        float x, y, w, h, stepY, distance;
        int alive;
        float time;
    }
    static final class Fireball {
        float x, y, w, h, dir, speed, angle, startTime;
        int steps;
    }

    // ---- State (exact matches of RS globals) ----
    int mScreenWidth, mScreenHeight;
    int mBgSpeed, mDensity;
    int mAnimation = 1;
    int mDayNight = 1;
    int mDayAndNightSpeed;

    final Layer[] mDay = {new Layer(), new Layer()};
    final Layer[] mNight = {new Layer(), new Layer()};
    final Layer[] mVcnLayer = {new Layer(), new Layer()};
    final Layer[] mLayer4 = {new Layer(), new Layer()};
    final Layer[] mLayer3 = {new Layer(), new Layer()};
    final Layer[] mLayer2 = {new Layer(), new Layer()};
    final Layer[] mLayer1 = {new Layer(), new Layer()};
    int mVcnMouseOffx, mVcnMouseW;

    final Pterosaur mPterosaur = new Pterosaur();
    int mPterosaurW, mPterosaurH, mPterosaurSpeed;

    final Dinosaur[] mDinosaur = {new Dinosaur(), new Dinosaur()};
    int mDinosaurSpeedX, mDinosaurSpeedY;

    final Fireball[] mFireballs = new Fireball[FIREBALL_COUNT];
    int mFireballBaseSpeed, mFireballW, mFireballH;
    int mFireballsShow;

    int mSunLeft, mSunRight, mSunTop, mSunBottom;
    int mMoonLeft, mMoonRight, mMoonTop, mMoonBottom;

    float mOldTime, mCurTime, mGenTime, mDT;
    int mXOffset;

    final Random mRandom = new Random();

    WildWorldScene() {
        for (int i = 0; i < FIREBALL_COUNT; i++) mFireballs[i] = new Fireball();
    }

    // ---- init / density (exact RS logic, 4 fixed presets) ----

    void init() {
        mDensity = 0;
        mAnimation = 1;
        mDayNight = 1;
    }

    // ---- density preset table (eliminates the 133-line copy-paste if/else chain) ----

    private static final class DensityPreset {
        final int densityId;
        final int bgSpeed, dayNightSpeed, fireballBaseSpeed;
        final int fireballW, fireballH;
        // Day/night: upH, downH; stores as ints (same values as original)
        final int dayUpH, dayDownH, nightUpH, nightDownH;
        // VCN
        final int vcnUpY, vcnUpH, vcnDownH, vcnMouseOffx, vcnMouseW;
        // Layers 4-1
        final int layer4UpY, layer4UpH, layer4DownH;
        final int layer3UpY, layer3UpH, layer3DownH;
        final int layer2UpY, layer2UpH, layer2DownH;
        final int layer1UpY, layer1UpH, layer1DownH;
        // Pterosaur
        final int pteroX, pteroY, pteroW, pteroH, pteroSpeed;
        // Dinosaur UP
        final float dinoUpY, dinoUpW, dinoUpH;
        // Dinosaur DOWN
        final float dinoDownXFactor;  // multiplier on mScreenWidth
        final float dinoDownY, dinoDownW, dinoDownH;
        // Dino speeds
        final int dinoSpeedX, dinoSpeedY;
        // Sun/Moon hit regions
        final int sunLeft, sunRight, sunTop, sunBottom;
        final int moonLeft, moonRight, moonTop, moonBottom;

        DensityPreset(int densityId,
                int bgSpeed, int dayNightSpeed, int fireballBaseSpeed, int fireballW, int fireballH,
                int dayUpH, int dayDownH, int nightUpH, int nightDownH,
                int vcnUpY, int vcnUpH, int vcnDownH, int vcnMouseOffx, int vcnMouseW,
                int l4uY, int l4uH, int l4dH, int l3uY, int l3uH, int l3dH,
                int l2uY, int l2uH, int l2dH, int l1uY, int l1uH, int l1dH,
                int pteroX, int pteroY, int pteroW, int pteroH, int pteroSpeed,
                float dinoUpY, float dinoUpW, float dinoUpH,
                float dinoDownXFactor, float dinoDownY, float dinoDownW, float dinoDownH,
                int dinoSpeedX, int dinoSpeedY,
                int sunL, int sunR, int sunT, int sunB,
                int moonL, int moonR, int moonT, int moonB) {
            this.densityId = densityId;
            this.bgSpeed = bgSpeed; this.dayNightSpeed = dayNightSpeed; this.fireballBaseSpeed = fireballBaseSpeed;
            this.fireballW = fireballW; this.fireballH = fireballH;
            this.dayUpH = dayUpH; this.dayDownH = dayDownH; this.nightUpH = nightUpH; this.nightDownH = nightDownH;
            this.vcnUpY = vcnUpY; this.vcnUpH = vcnUpH; this.vcnDownH = vcnDownH;
            this.vcnMouseOffx = vcnMouseOffx; this.vcnMouseW = vcnMouseW;
            this.layer4UpY = l4uY; this.layer4UpH = l4uH; this.layer4DownH = l4dH;
            this.layer3UpY = l3uY; this.layer3UpH = l3uH; this.layer3DownH = l3dH;
            this.layer2UpY = l2uY; this.layer2UpH = l2uH; this.layer2DownH = l2dH;
            this.layer1UpY = l1uY; this.layer1UpH = l1uH; this.layer1DownH = l1dH;
            this.pteroX = pteroX; this.pteroY = pteroY; this.pteroW = pteroW; this.pteroH = pteroH;
            this.pteroSpeed = pteroSpeed;
            this.dinoUpY = dinoUpY; this.dinoUpW = dinoUpW; this.dinoUpH = dinoUpH;
            this.dinoDownXFactor = dinoDownXFactor; this.dinoDownY = dinoDownY;
            this.dinoDownW = dinoDownW; this.dinoDownH = dinoDownH;
            this.dinoSpeedX = dinoSpeedX; this.dinoSpeedY = dinoSpeedY;
            this.sunLeft = sunL; this.sunRight = sunR; this.sunTop = sunT; this.sunBottom = sunB;
            this.moonLeft = moonL; this.moonRight = moonR; this.moonTop = moonT; this.moonBottom = moonB;
        }
    }

    private static final DensityPreset[] DENSITY_PRESETS = {
        //  idx  bgSpd dnSpd fbSpd fbW fbH  dayUp dayDn ngtUp ngtDn  vcnY vcnH vcnDH vcnOx vcnW   l4Y l4H l4DH l3Y l3H l3DH l2Y l2H l2DH l1Y l1H l1DH  pteroX pteroY pteroW pteroH pteroSpd  dUpY     dUpW   dUpH   dDnXF  dDnY    dDnW   dDnH   dSpdX dSpdY  sunL sunR sunT sunB  moonL moonR moonT moonB
        new DensityPreset(1,  24,16, 80,  32,40,  222,14, 222,15,  121,79, 7, 48, 80,   197,12, 6, 206, 9,19, 213,17,40, 227,38,55,  -100,36, 100,72, 48,   129.6f, 142.4f,119.2f,1.5f, 119.8f, 160.2f,134.1f,26,6,   160,240,40,160,  20,120,60,160),
        new DensityPreset(2,  24,16, 80,  32,40,  288,18, 288,18,  177,81,14, 48, 80,   260,13,15, 270,10,26, 282,16,49, 296,39,65,  -134,48, 134,96, 48,   180.6f, 170.4f,142.4f,1.5f, 166.3f, 191.7f,160.2f,28,6,   160,240,40,160,  20,120,60,160),
        new DensityPreset(3,  30,20, 90,  40,60,  339,21, 335,25,  206,104,11,78,100,  304,15, 9, 315,11,27, 329,19,57, 355,48,76,  -179,64, 179,128,64, 181.8f, 227.2f,190.4f,1.5f, 173.4f, 255.6f,214.2f,32,8,   220,320,40,160,  30,130,80,180),
        new DensityPreset(4,  36,24,100, 60,100, 565,35, 560,40,  333,175,20,124,150, 506,25,15, 525,18,45, 550,32,95, 595,80,128, -270,107,270,215,64, 310.2f, 340.8f,312.0f,1.5f, 298.6f, 383.4f,351.0f,32,8,   320,480,60,240, 40,200,100,300),
    };

    private void applyLayer(Layer l, float y, float w, float h) { l.x = 0; l.y = y; l.w = w; l.h = h; }

    void setDensity(int den) {
        mDensity = den == 0 ? DENSITY_480x800 : den;
        int sw = getScreenWidth();
        int sh = getScreenHeight();
        mScreenWidth = sw;
        mScreenHeight = sh;

        DensityPreset p = DENSITY_PRESETS[mDensity - 1];
        mBgSpeed = p.bgSpeed; mDayAndNightSpeed = p.dayNightSpeed; mFireballBaseSpeed = p.fireballBaseSpeed;
        mFireballW = p.fireballW; mFireballH = p.fireballH;

        applyLayer(mDay[UP],   0,                sw, p.dayUpH);
        applyLayer(mDay[DOWN], p.dayUpH,         sw, p.dayDownH);
        applyLayer(mNight[UP], -(p.nightUpH + p.nightDownH), sw, p.nightUpH);
        applyLayer(mNight[DOWN], -p.nightDownH,  sw, p.nightDownH);

        applyLayer(mVcnLayer[UP],   p.vcnUpY,        sw, p.vcnUpH);
        applyLayer(mVcnLayer[DOWN], p.vcnUpY + p.vcnUpH, sw, p.vcnDownH);
        mVcnMouseOffx = p.vcnMouseOffx; mVcnMouseW = p.vcnMouseW;

        applyLayer(mLayer4[UP],   p.layer4UpY,         sw, p.layer4UpH);
        applyLayer(mLayer4[DOWN], p.layer4UpY + p.layer4UpH, sw, p.layer4DownH);
        applyLayer(mLayer3[UP],   p.layer3UpY,         sw, p.layer3UpH);
        applyLayer(mLayer3[DOWN], p.layer3UpY + p.layer3UpH, sw, p.layer3DownH);
        applyLayer(mLayer2[UP],   p.layer2UpY,         sw, p.layer2UpH);
        applyLayer(mLayer2[DOWN], p.layer2UpY + p.layer2UpH, sw, p.layer2DownH);
        applyLayer(mLayer1[UP],   p.layer1UpY,         sw, p.layer1UpH);
        applyLayer(mLayer1[DOWN], p.layer1UpY + p.layer1UpH, sw, p.layer1DownH);

        mPterosaur.x = p.pteroX; mPterosaur.y = p.pteroY;
        mPterosaur.w = p.pteroW; mPterosaur.h = p.pteroH;
        mPterosaur.scale = 1.0f; mPterosaurSpeed = p.pteroSpeed;

        mDinosaur[UP].distance = DINOSAUR1_DISTANCE; mDinosaur[UP].x = sw;
        mDinosaur[UP].y = p.dinoUpY; mDinosaur[UP].w = p.dinoUpW; mDinosaur[UP].h = p.dinoUpH;
        mDinosaur[DOWN].distance = DINOSAUR2_DISTANCE; mDinosaur[DOWN].x = sw * p.dinoDownXFactor;
        mDinosaur[DOWN].y = p.dinoDownY; mDinosaur[DOWN].w = p.dinoDownW; mDinosaur[DOWN].h = p.dinoDownH;
        mDinosaurSpeedX = p.dinoSpeedX; mDinosaurSpeedY = p.dinoSpeedY;

        mSunLeft = p.sunLeft; mSunRight = p.sunRight; mSunTop = p.sunTop; mSunBottom = p.sunBottom;
        mMoonLeft = p.moonLeft; mMoonRight = p.moonRight; mMoonTop = p.moonTop; mMoonBottom = p.moonBottom;
    }

    // Stored by GL for use in setDensity
    private int mSavedWidth, mSavedHeight;
    int getScreenWidth() { return mSavedWidth; }
    int getScreenHeight() { return mSavedHeight; }
    void storeScreenSize(int w, int h) { mSavedWidth = w; mSavedHeight = h; }

    // ---- update (exact RS logic, frame-scaled via dt) ----

    void update(float frameScale) {
        // Day/night transition
        if (mDayNight > 0) {
            if (mAnimation == 0) {
                mNight[UP].y -= mDayAndNightSpeed * frameScale;
                mNight[DOWN].y -= mDayAndNightSpeed * frameScale;
                if (mNight[DOWN].y + mNight[DOWN].h <= 0) mAnimation = 1;
            }
        } else {
            if (mAnimation == 1) {
                mNight[UP].y += mDayAndNightSpeed * frameScale;
                mNight[DOWN].y += mDayAndNightSpeed * frameScale;
                if (mNight[UP].y >= 0) {
                    mAnimation = 0;
                    mNight[UP].y = 0;
                }
            }
        }

        updateLayers(frameScale);
        updatePterosaur(frameScale);
        updateDinosaur(UP, frameScale);
        updateDinosaur(DOWN, frameScale);
        updateFireballs(frameScale);

        // Random entity generation
        if (mCurTime - mGenTime > GEN_TIME) {
            mGenTime = mCurTime;
            float random = rand(GEN_RANDOM);
            if (mPterosaur.alive == 0 && random > 0.04f && random < 0.14f) {
                mPterosaur.time = SystemClock.uptimeMillis();
                mPterosaur.alive = 1;
                mPterosaur.x = -mScreenWidth;
                mPterosaur.y = 32 + mScreenHeight * rand(0.2f);
            }
            if (random < 0.1f) {
                if (mDinosaur[UP].alive == 0) {
                    mDinosaur[UP].time = SystemClock.uptimeMillis();
                    mDinosaur[UP].alive = 1;
                    mDinosaur[UP].x = mScreenWidth * 3;
                    mDinosaur[UP].stepY = 0;
                }
            } else if (random < 0.18f) {
                if (mDinosaur[DOWN].alive == 0) {
                    mDinosaur[UP].time = SystemClock.uptimeMillis();
                    mDinosaur[DOWN].alive = 1;
                    mDinosaur[DOWN].x = mScreenWidth * 3;
                    mDinosaur[DOWN].stepY = 0;
                }
            }
        }
    }

    private void updateLayers(float fs) {
        mVcnLayer[UP].x += mBgSpeed * fs * (1 - VCN_DISTANCE);
        if (mVcnLayer[UP].x + mXOffset >= mScreenWidth) mVcnLayer[UP].x = -mXOffset;
        mLayer4[UP].x += mBgSpeed * fs * (1 - LAYER4_DISTANCE);
        if (mLayer4[UP].x + mXOffset >= mScreenWidth) mLayer4[UP].x = -mXOffset;
        mLayer3[UP].x += mBgSpeed * fs * (1 - LAYER3_DISTANCE);
        if (mLayer3[UP].x + mXOffset >= mScreenWidth) mLayer3[UP].x = -mXOffset;
        mLayer2[UP].x += mBgSpeed * fs * (1 - LAYER2_DISTANCE);
        if (mLayer2[UP].x + mXOffset >= mScreenWidth) mLayer2[UP].x = -mXOffset;
        mLayer1[UP].x += mBgSpeed * fs * (1 - LAYER1_DISTANCE);
        if (mLayer1[UP].x + mXOffset >= mScreenWidth) mLayer1[UP].x = -mXOffset;
    }

    private void updatePterosaur(float fs) {
        if (mPterosaur.alive == 0) return;
        if (mCurTime - mPterosaur.time > PTERO_DT) {
            mPterosaur.time = mCurTime;
            if (mPterosaur.duration != 0) {
                if (mPterosaur.scale < PTEROSAUR_DISTANCE) mPterosaur.scale = PTEROSAUR_DISTANCE;
                else if (mPterosaur.scale == PTEROSAUR_DISTANCE) mPterosaur.scale = PTEROSAUR_DISTANCE + 0.06f;
                else { mPterosaur.scale = PTEROSAUR_DISTANCE; mPterosaur.duration = 0; }
            } else {
                if (mPterosaur.scale > PTEROSAUR_DISTANCE) mPterosaur.scale = PTEROSAUR_DISTANCE;
                else if (mPterosaur.scale == PTEROSAUR_DISTANCE) mPterosaur.scale = PTEROSAUR_DISTANCE - 0.06f;
                else { mPterosaur.scale = PTEROSAUR_DISTANCE; mPterosaur.duration = 1; }
            }
            mPterosaur.w = mPterosaurW * mPterosaur.scale;
            mPterosaur.h = mPterosaurH * mPterosaur.scale;
        }
        mPterosaur.x += mPterosaurSpeed * fs * mPterosaur.scale;
    }

    private void updateDinosaur(int ud, float fs) {
        Dinosaur d = mDinosaur[ud];
        if (d.alive == 0) return;
        if (mCurTime - d.time > DINO_DT) {
            d.time = mCurTime;
            d.stepY = 0;
        } else {
            d.stepY += mDinosaurSpeedY * (1.7f - d.distance) * fs;
        }
        d.x -= mDinosaurSpeedX * (1.7f - d.distance) * fs;
    }

    private void updateFireballs(float fs) {
        if (mFireballsShow == 0) return;
        int count = 0;
        for (int i = 0; i < FIREBALL_COUNT; i++) {
            Fireball f = mFireballs[i];
            if (f.steps > 0) {
                if (mCurTime > f.startTime) {
                    f.x += mBgSpeed * fs * (1 - FIREBALL_DISTANCE) + f.dir * f.speed * FIREBALL_DISTANCE * fs;
                    f.y -= f.speed * FIREBALL_DISTANCE * fs;
                    f.steps--;
                    f.angle = 120.0f;
                } else {
                    f.x += mBgSpeed * fs * (1 - FIREBALL_DISTANCE);
                }
                if (f.steps > 0) count++;
            }
        }
        if (count == 0) mFireballsShow = 0;
    }

    // ---- Touch (exact RS onTouchCommand) ----

    void onTouch(float touchX, float touchY) {
        if (touchX > mSunLeft && touchX < mSunRight && touchY > mSunTop && touchY < mSunBottom) {
            if (mDayNight > 0 && mAnimation == 1) mDayNight = 0;
        } else if (touchX > mMoonLeft && touchX < mMoonRight && touchY > mMoonTop && touchY < mMoonBottom) {
            if (mDayNight == 0 && mAnimation == 0) mDayNight = 1;
        } else {
            float xPos = mVcnLayer[UP].x + mXOffset;
            while (xPos < 0) xPos += mScreenWidth;
            float vcn1L = xPos + mVcnMouseOffx - mVcnMouseW / 2f;
            float vcn1R = vcn1L + mVcnMouseW;
            float vcn2L = vcn1L - mScreenWidth;
            float vcn2R = vcn2L + mVcnMouseW;
            if (touchX > vcn1L && touchX < vcn1R && touchY > mVcnLayer[UP].y && touchY < mVcnLayer[UP].y + mVcnLayer[UP].h) {
                createFireballs(vcn1L + mVcnMouseW / 2f - mXOffset);
            } else if (touchX > vcn2L && touchX < vcn2R && touchY > mVcnLayer[UP].y && touchY < mVcnLayer[UP].y + mVcnLayer[UP].h) {
                createFireballs(vcn2L + mVcnMouseW / 2f - mXOffset);
            }
        }
    }

    private void createFireballs(float xpos) {
        for (int i = 0; i < FIREBALL_COUNT; i++) {
            if (mFireballs[i].steps <= 0) {
                float random = rand(0.3f);
                float scale = 0.6f + random;
                mFireballs[i].w = mFireballW * scale;
                mFireballs[i].h = mFireballH * scale;
                mFireballs[i].x = xpos - mFireballs[i].w / 2f;
                mFireballs[i].y = mVcnLayer[UP].y + 8;
                mFireballs[i].speed = mFireballBaseSpeed + mFireballBaseSpeed * random;
                mFireballs[i].dir = 0.3f - rand(0.6f);
                mFireballs[i].steps = (int)(30 + random * 40);
                mFireballs[i].startTime = SystemClock.uptimeMillis() + rand(0.4f) * 10000f;
            }
        }
        mFireballsShow = 1;
    }

    float rand(float max) { return mRandom.nextFloat() * max; }
}
