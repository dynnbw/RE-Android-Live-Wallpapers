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

    // ---- Entity types (package-private, accessed by GL) ----
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
    int screenWidth, screenHeight;
    int gBgSpeed, gDensity;
    int gAnimation = 1;
    int gDayNight = 1;
    int gDayAndNightSpeed;

    final Layer[] gDay = {new Layer(), new Layer()};
    final Layer[] gNight = {new Layer(), new Layer()};
    final Layer[] gVcnLayer = {new Layer(), new Layer()};
    final Layer[] gLayer4 = {new Layer(), new Layer()};
    final Layer[] gLayer3 = {new Layer(), new Layer()};
    final Layer[] gLayer2 = {new Layer(), new Layer()};
    final Layer[] gLayer1 = {new Layer(), new Layer()};
    int gVcnMouseOffx, gVcnMouseW;

    final Pterosaur gPterosaur = new Pterosaur();
    int gPterosaurW, gPterosaurH, gPterosaurSpeed;

    final Dinosaur[] gDinosaur = {new Dinosaur(), new Dinosaur()};
    int gDinosaurSpeedX, gDinosaurSpeedY;

    final Fireball[] fbs = new Fireball[FIREBALL_COUNT];
    int gFireballBaseSpeed, gFireballW, gFireballH;
    int gFireballsShow;

    int gSunLeft, gSunRight, gSunTop, gSunBottom;
    int gMoonLeft, gMoonRight, gMoonTop, gMoonBottom;

    float gOldTime, gCurTime, gGenTime, gDT;
    int gXOffset;

    final Random mRandom = new Random();

    WildWorldScene() {
        for (int i = 0; i < FIREBALL_COUNT; i++) fbs[i] = new Fireball();
    }

    // ---- init / density (exact RS logic, 4 fixed presets) ----

    void init() {
        gDensity = 0;
        gAnimation = 1;
        gDayNight = 1;
    }

    void setDensity(int den) {
        gDensity = den == 0 ? DENSITY_480x800 : den;
        screenWidth = getScreenWidth();
        screenHeight = getScreenHeight();

        if (gDensity == DENSITY_240x320) {
            gBgSpeed = 24; gDayAndNightSpeed = 16; gFireballBaseSpeed = 80;
            gFireballW = 32; gFireballH = 40;

            gDay[UP].x = 0; gDay[UP].y = 0; gDay[UP].w = screenWidth; gDay[UP].h = 222;
            gDay[DOWN].x = 0; gDay[DOWN].y = 222; gDay[DOWN].w = screenWidth; gDay[DOWN].h = 14;
            gNight[UP].x = 0; gNight[UP].y = -222 - 15; gNight[UP].w = screenWidth; gNight[UP].h = 222;
            gNight[DOWN].x = 0; gNight[DOWN].y = -15; gNight[DOWN].w = screenWidth; gNight[DOWN].h = 15;

            gVcnLayer[UP].x = 0; gVcnLayer[UP].y = 121; gVcnLayer[UP].w = screenWidth; gVcnLayer[UP].h = 79;
            gVcnLayer[DOWN].x = 0; gVcnLayer[DOWN].y = 121 + 79; gVcnLayer[DOWN].w = screenWidth; gVcnLayer[DOWN].h = 7;
            gVcnMouseOffx = 48; gVcnMouseW = 80;

            gLayer4[UP].x = 0; gLayer4[UP].y = 197; gLayer4[UP].w = screenWidth; gLayer4[UP].h = 12;
            gLayer4[DOWN].x = 0; gLayer4[DOWN].y = 197 + 12; gLayer4[DOWN].w = screenWidth; gLayer4[DOWN].h = 6;
            gLayer3[UP].x = 0; gLayer3[UP].y = 206; gLayer3[UP].w = screenWidth; gLayer3[UP].h = 9;
            gLayer3[DOWN].x = 0; gLayer3[DOWN].y = 206 + 9; gLayer3[DOWN].w = screenWidth; gLayer3[DOWN].h = 19;
            gLayer2[UP].x = 0; gLayer2[UP].y = 213; gLayer2[UP].w = screenWidth; gLayer2[UP].h = 17;
            gLayer2[DOWN].x = 0; gLayer2[DOWN].y = 213 + 17; gLayer2[DOWN].w = screenWidth; gLayer2[DOWN].h = 40;
            gLayer1[UP].x = 0; gLayer1[UP].y = 227; gLayer1[UP].w = screenWidth; gLayer1[UP].h = 38;
            gLayer1[DOWN].x = 0; gLayer1[DOWN].y = 227 + 38; gLayer1[DOWN].w = screenWidth; gLayer1[DOWN].h = 55;

            gPterosaur.x = -100; gPterosaur.y = 36; gPterosaurW = 100; gPterosaurH = 72;
            gPterosaur.scale = 1.0f; gPterosaurSpeed = 48;

            gDinosaur[UP].distance = DINOSAUR1_DISTANCE; gDinosaur[UP].x = screenWidth;
            gDinosaur[UP].y = 94 + 178 * 0.2f; gDinosaur[UP].w = 178 * 0.8f; gDinosaur[UP].h = 149 * 0.8f;
            gDinosaur[DOWN].distance = DINOSAUR2_DISTANCE; gDinosaur[DOWN].x = screenWidth * 1.5f;
            gDinosaur[DOWN].y = 102 + 178 * 0.1f; gDinosaur[DOWN].w = 178 * 0.9f; gDinosaur[DOWN].h = 149 * 0.9f;
            gDinosaurSpeedX = 26; gDinosaurSpeedY = 6;

            gSunLeft = 160; gSunRight = 240; gSunTop = 40; gSunBottom = 160;
            gMoonLeft = 20; gMoonRight = 120; gMoonTop = 60; gMoonBottom = 160;
        } else if (gDensity == DENSITY_240x400) {
            gBgSpeed = 24; gDayAndNightSpeed = 16; gFireballBaseSpeed = 80;
            gFireballW = 32; gFireballH = 40;

            gDay[UP].x = 0; gDay[UP].y = 0; gDay[UP].w = screenWidth; gDay[UP].h = 288;
            gDay[DOWN].x = 0; gDay[DOWN].y = 288; gDay[DOWN].w = screenWidth; gDay[DOWN].h = 18;
            gNight[UP].x = 0; gNight[UP].y = -288 - 18; gNight[UP].w = screenWidth; gNight[UP].h = 288;
            gNight[DOWN].x = 0; gNight[DOWN].y = -18; gNight[DOWN].w = screenWidth; gNight[DOWN].h = 18;

            gVcnLayer[UP].x = 0; gVcnLayer[UP].y = 177; gVcnLayer[UP].w = screenWidth; gVcnLayer[UP].h = 81;
            gVcnLayer[DOWN].x = 0; gVcnLayer[DOWN].y = 177 + 81; gVcnLayer[DOWN].w = screenWidth; gVcnLayer[DOWN].h = 14;
            gVcnMouseOffx = 48; gVcnMouseW = 80;

            gLayer4[UP].x = 0; gLayer4[UP].y = 260; gLayer4[UP].w = screenWidth; gLayer4[UP].h = 13;
            gLayer4[DOWN].x = 0; gLayer4[DOWN].y = 260 + 13; gLayer4[DOWN].w = screenWidth; gLayer4[DOWN].h = 15;
            gLayer3[UP].x = 0; gLayer3[UP].y = 270; gLayer3[UP].w = screenWidth; gLayer3[UP].h = 10;
            gLayer3[DOWN].x = 0; gLayer3[DOWN].y = 270 + 10; gLayer3[DOWN].w = screenWidth; gLayer3[DOWN].h = 26;
            gLayer2[UP].x = 0; gLayer2[UP].y = 282; gLayer2[UP].w = screenWidth; gLayer2[UP].h = 16;
            gLayer2[DOWN].x = 0; gLayer2[DOWN].y = 282 + 16; gLayer2[DOWN].w = screenWidth; gLayer2[DOWN].h = 49;
            gLayer1[UP].x = 0; gLayer1[UP].y = 296; gLayer1[UP].w = screenWidth; gLayer1[UP].h = 39;
            gLayer1[DOWN].x = 0; gLayer1[DOWN].y = 296 + 39; gLayer1[DOWN].w = screenWidth; gLayer1[DOWN].h = 65;

            gPterosaur.x = -134; gPterosaur.y = 48; gPterosaurW = 134; gPterosaurH = 96;
            gPterosaur.scale = 1.0f; gPterosaurSpeed = 48;

            gDinosaur[UP].distance = DINOSAUR1_DISTANCE; gDinosaur[UP].x = screenWidth;
            gDinosaur[UP].y = 138 + 213 * 0.2f; gDinosaur[UP].w = 213 * 0.8f; gDinosaur[UP].h = 178 * 0.8f;
            gDinosaur[DOWN].distance = DINOSAUR2_DISTANCE; gDinosaur[DOWN].x = screenWidth * 1.5f;
            gDinosaur[DOWN].y = 145 + 213 * 0.1f; gDinosaur[DOWN].w = 213 * 0.9f; gDinosaur[DOWN].h = 178 * 0.9f;
            gDinosaurSpeedX = 28; gDinosaurSpeedY = 6;

            gSunLeft = 160; gSunRight = 240; gSunTop = 40; gSunBottom = 160;
            gMoonLeft = 20; gMoonRight = 120; gMoonTop = 60; gMoonBottom = 160;
        } else if (gDensity == DENSITY_320x480) {
            gBgSpeed = 30; gDayAndNightSpeed = 20; gFireballBaseSpeed = 90;
            gFireballW = 40; gFireballH = 60;

            gDay[UP].x = 0; gDay[UP].y = 0; gDay[UP].w = screenWidth; gDay[UP].h = 339;
            gDay[DOWN].x = 0; gDay[DOWN].y = 339; gDay[DOWN].w = screenWidth; gDay[DOWN].h = 21;
            gNight[UP].x = 0; gNight[UP].y = -335 - 25; gNight[UP].w = screenWidth; gNight[UP].h = 335;
            gNight[DOWN].x = 0; gNight[DOWN].y = -25; gNight[DOWN].w = screenWidth; gNight[DOWN].h = 25;

            gVcnLayer[UP].x = 0; gVcnLayer[UP].y = 206; gVcnLayer[UP].w = screenWidth; gVcnLayer[UP].h = 104;
            gVcnLayer[DOWN].x = 0; gVcnLayer[DOWN].y = 206 + 104; gVcnLayer[DOWN].w = screenWidth; gVcnLayer[DOWN].h = 11;
            gVcnMouseOffx = 78; gVcnMouseW = 100;

            gLayer4[UP].x = 0; gLayer4[UP].y = 304; gLayer4[UP].w = screenWidth; gLayer4[UP].h = 15;
            gLayer4[DOWN].x = 0; gLayer4[DOWN].y = 304 + 15; gLayer4[DOWN].w = screenWidth; gLayer4[DOWN].h = 9;
            gLayer3[UP].x = 0; gLayer3[UP].y = 315; gLayer3[UP].w = screenWidth; gLayer3[UP].h = 11;
            gLayer3[DOWN].x = 0; gLayer3[DOWN].y = 315 + 11; gLayer3[DOWN].w = screenWidth; gLayer3[DOWN].h = 27;
            gLayer2[UP].x = 0; gLayer2[UP].y = 329; gLayer2[UP].w = screenWidth; gLayer2[UP].h = 19;
            gLayer2[DOWN].x = 0; gLayer2[DOWN].y = 329 + 19; gLayer2[DOWN].w = screenWidth; gLayer2[DOWN].h = 57;
            gLayer1[UP].x = 0; gLayer1[UP].y = 355; gLayer1[UP].w = screenWidth; gLayer1[UP].h = 48;
            gLayer1[DOWN].x = 0; gLayer1[DOWN].y = 355 + 48; gLayer1[DOWN].w = screenWidth; gLayer1[DOWN].h = 76;

            gPterosaur.x = -179; gPterosaur.y = 64; gPterosaurW = 179; gPterosaurH = 128;
            gPterosaur.scale = 1.0f; gPterosaurSpeed = 64;

            gDinosaur[UP].distance = DINOSAUR1_DISTANCE; gDinosaur[UP].x = screenWidth;
            gDinosaur[UP].y = 125 + 284 * 0.2f; gDinosaur[UP].w = 284 * 0.8f; gDinosaur[UP].h = 238 * 0.8f;
            gDinosaur[DOWN].distance = DINOSAUR2_DISTANCE; gDinosaur[DOWN].x = screenWidth * 1.5f;
            gDinosaur[DOWN].y = 145 + 284 * 0.1f; gDinosaur[DOWN].w = 284 * 0.9f; gDinosaur[DOWN].h = 238 * 0.9f;
            gDinosaurSpeedX = 32; gDinosaurSpeedY = 8;

            gSunLeft = 220; gSunRight = 320; gSunTop = 40; gSunBottom = 160;
            gMoonLeft = 30; gMoonRight = 130; gMoonTop = 80; gMoonBottom = 180;
        } else {
            gBgSpeed = 36; gDayAndNightSpeed = 24; gFireballBaseSpeed = 100;
            gFireballW = 60; gFireballH = 100;

            gDay[UP].x = 0; gDay[UP].y = 0; gDay[UP].w = screenWidth; gDay[UP].h = 565;
            gDay[DOWN].x = 0; gDay[DOWN].y = 565; gDay[DOWN].w = screenWidth; gDay[DOWN].h = 35;
            gNight[UP].x = 0; gNight[UP].y = -560 - 40; gNight[UP].w = screenWidth; gNight[UP].h = 560;
            gNight[DOWN].x = 0; gNight[DOWN].y = -40; gNight[DOWN].w = screenWidth; gNight[DOWN].h = 40;

            gVcnLayer[UP].x = 0; gVcnLayer[UP].y = 333; gVcnLayer[UP].w = screenWidth; gVcnLayer[UP].h = 175;
            gVcnLayer[DOWN].x = 0; gVcnLayer[DOWN].y = 333 + 175; gVcnLayer[DOWN].w = screenWidth; gVcnLayer[DOWN].h = 20;
            gVcnMouseOffx = 124; gVcnMouseW = 150;

            gLayer4[UP].x = 0; gLayer4[UP].y = 506; gLayer4[UP].w = screenWidth; gLayer4[UP].h = 25;
            gLayer4[DOWN].x = 0; gLayer4[DOWN].y = 506 + 25; gLayer4[DOWN].w = screenWidth; gLayer4[DOWN].h = 15;
            gLayer3[UP].x = 0; gLayer3[UP].y = 525; gLayer3[UP].w = screenWidth; gLayer3[UP].h = 18;
            gLayer3[DOWN].x = 0; gLayer3[DOWN].y = 525 + 18; gLayer3[DOWN].w = screenWidth; gLayer3[DOWN].h = 45;
            gLayer2[UP].x = 0; gLayer2[UP].y = 550; gLayer2[UP].w = screenWidth; gLayer2[UP].h = 32;
            gLayer2[DOWN].x = 0; gLayer2[DOWN].y = 550 + 32; gLayer2[DOWN].w = screenWidth; gLayer2[DOWN].h = 95;
            gLayer1[UP].x = 0; gLayer1[UP].y = 595; gLayer1[UP].w = screenWidth; gLayer1[UP].h = 80;
            gLayer1[DOWN].x = 0; gLayer1[DOWN].y = 595 + 80; gLayer1[DOWN].w = screenWidth; gLayer1[DOWN].h = 128;

            gPterosaur.x = -270; gPterosaur.y = 107; gPterosaurW = 270; gPterosaurH = 215;
            gPterosaur.scale = 1.0f; gPterosaurSpeed = 64;

            gDinosaur[UP].distance = DINOSAUR1_DISTANCE; gDinosaur[UP].x = screenWidth;
            gDinosaur[UP].y = 225 + 426 * 0.2f; gDinosaur[UP].w = 426 * 0.8f; gDinosaur[UP].h = 390 * 0.8f;
            gDinosaur[DOWN].distance = DINOSAUR2_DISTANCE; gDinosaur[DOWN].x = screenWidth * 1.5f;
            gDinosaur[DOWN].y = 256 + 426 * 0.1f; gDinosaur[DOWN].w = 426 * 0.9f; gDinosaur[DOWN].h = 390 * 0.9f;
            gDinosaurSpeedX = 32; gDinosaurSpeedY = 8;

            gSunLeft = 320; gSunRight = 480; gSunTop = 60; gSunBottom = 240;
            gMoonLeft = 40; gMoonRight = 200; gMoonTop = 100; gMoonBottom = 300;
        }
    }

    // Stored by GL for use in setDensity
    private int mSavedWidth, mSavedHeight;
    int getScreenWidth() { return mSavedWidth; }
    int getScreenHeight() { return mSavedHeight; }
    void storeScreenSize(int w, int h) { mSavedWidth = w; mSavedHeight = h; }

    // ---- update (exact RS logic, frame-scaled via dt) ----

    void update(float frameScale) {
        // Day/night transition
        if (gDayNight > 0) {
            if (gAnimation == 0) {
                gNight[UP].y -= gDayAndNightSpeed * frameScale;
                gNight[DOWN].y -= gDayAndNightSpeed * frameScale;
                if (gNight[DOWN].y + gNight[DOWN].h <= 0) gAnimation = 1;
            }
        } else {
            if (gAnimation == 1) {
                gNight[UP].y += gDayAndNightSpeed * frameScale;
                gNight[DOWN].y += gDayAndNightSpeed * frameScale;
                if (gNight[UP].y >= 0) {
                    gAnimation = 0;
                    gNight[UP].y = 0;
                }
            }
        }

        updateLayers(frameScale);
        updatePterosaur(frameScale);
        updateDinosaur(UP, frameScale);
        updateDinosaur(DOWN, frameScale);
        updateFireballs(frameScale);

        // Random entity generation
        if (gCurTime - gGenTime > GEN_TIME) {
            gGenTime = gCurTime;
            float random = rand(GEN_RANDOM);
            if (gPterosaur.alive == 0 && random > 0.04f && random < 0.14f) {
                gPterosaur.time = SystemClock.uptimeMillis();
                gPterosaur.alive = 1;
                gPterosaur.x = -screenWidth;
                gPterosaur.y = 32 + screenHeight * rand(0.2f);
            }
            if (random < 0.1f) {
                if (gDinosaur[UP].alive == 0) {
                    gDinosaur[UP].time = SystemClock.uptimeMillis();
                    gDinosaur[UP].alive = 1;
                    gDinosaur[UP].x = screenWidth * 3;
                    gDinosaur[UP].stepY = 0;
                }
            } else if (random < 0.18f) {
                if (gDinosaur[DOWN].alive == 0) {
                    gDinosaur[UP].time = SystemClock.uptimeMillis();
                    gDinosaur[DOWN].alive = 1;
                    gDinosaur[DOWN].x = screenWidth * 3;
                    gDinosaur[DOWN].stepY = 0;
                }
            }
        }
    }

    private void updateLayers(float fs) {
        gVcnLayer[UP].x += gBgSpeed * fs * (1 - VCN_DISTANCE);
        if (gVcnLayer[UP].x + gXOffset >= screenWidth) gVcnLayer[UP].x = -gXOffset;
        gLayer4[UP].x += gBgSpeed * fs * (1 - LAYER4_DISTANCE);
        if (gLayer4[UP].x + gXOffset >= screenWidth) gLayer4[UP].x = -gXOffset;
        gLayer3[UP].x += gBgSpeed * fs * (1 - LAYER3_DISTANCE);
        if (gLayer3[UP].x + gXOffset >= screenWidth) gLayer3[UP].x = -gXOffset;
        gLayer2[UP].x += gBgSpeed * fs * (1 - LAYER2_DISTANCE);
        if (gLayer2[UP].x + gXOffset >= screenWidth) gLayer2[UP].x = -gXOffset;
        gLayer1[UP].x += gBgSpeed * fs * (1 - LAYER1_DISTANCE);
        if (gLayer1[UP].x + gXOffset >= screenWidth) gLayer1[UP].x = -gXOffset;
    }

    private void updatePterosaur(float fs) {
        if (gPterosaur.alive == 0) return;
        if (gCurTime - gPterosaur.time > PTERO_DT) {
            gPterosaur.time = gCurTime;
            if (gPterosaur.duration != 0) {
                if (gPterosaur.scale < PTEROSAUR_DISTANCE) gPterosaur.scale = PTEROSAUR_DISTANCE;
                else if (gPterosaur.scale == PTEROSAUR_DISTANCE) gPterosaur.scale = PTEROSAUR_DISTANCE + 0.06f;
                else { gPterosaur.scale = PTEROSAUR_DISTANCE; gPterosaur.duration = 0; }
            } else {
                if (gPterosaur.scale > PTEROSAUR_DISTANCE) gPterosaur.scale = PTEROSAUR_DISTANCE;
                else if (gPterosaur.scale == PTEROSAUR_DISTANCE) gPterosaur.scale = PTEROSAUR_DISTANCE - 0.06f;
                else { gPterosaur.scale = PTEROSAUR_DISTANCE; gPterosaur.duration = 1; }
            }
            gPterosaur.w = gPterosaurW * gPterosaur.scale;
            gPterosaur.h = gPterosaurH * gPterosaur.scale;
        }
        gPterosaur.x += gPterosaurSpeed * fs * gPterosaur.scale;
    }

    private void updateDinosaur(int ud, float fs) {
        Dinosaur d = gDinosaur[ud];
        if (d.alive == 0) return;
        if (gCurTime - d.time > DINO_DT) {
            d.time = gCurTime;
            d.stepY = 0;
        } else {
            d.stepY += gDinosaurSpeedY * (1.7f - d.distance) * fs;
        }
        d.x -= gDinosaurSpeedX * (1.7f - d.distance) * fs;
    }

    private void updateFireballs(float fs) {
        if (gFireballsShow == 0) return;
        int count = 0;
        for (int i = 0; i < FIREBALL_COUNT; i++) {
            Fireball f = fbs[i];
            if (f.steps > 0) {
                if (gCurTime > f.startTime) {
                    f.x += gBgSpeed * fs * (1 - FIREBALL_DISTANCE) + f.dir * f.speed * FIREBALL_DISTANCE * fs;
                    f.y -= f.speed * FIREBALL_DISTANCE * fs;
                    f.steps--;
                    f.angle = 120.0f;
                } else {
                    f.x += gBgSpeed * fs * (1 - FIREBALL_DISTANCE);
                }
                if (f.steps > 0) count++;
            }
        }
        if (count == 0) gFireballsShow = 0;
    }

    // ---- Touch (exact RS onTouchCommand) ----

    void onTouch(float touchX, float touchY) {
        if (touchX > gSunLeft && touchX < gSunRight && touchY > gSunTop && touchY < gSunBottom) {
            if (gDayNight > 0 && gAnimation == 1) gDayNight = 0;
        } else if (touchX > gMoonLeft && touchX < gMoonRight && touchY > gMoonTop && touchY < gMoonBottom) {
            if (gDayNight == 0 && gAnimation == 0) gDayNight = 1;
        } else {
            float xPos = gVcnLayer[UP].x + gXOffset;
            while (xPos < 0) xPos += screenWidth;
            float vcn1L = xPos + gVcnMouseOffx - gVcnMouseW / 2f;
            float vcn1R = vcn1L + gVcnMouseW;
            float vcn2L = vcn1L - screenWidth;
            float vcn2R = vcn2L + gVcnMouseW;
            if (touchX > vcn1L && touchX < vcn1R && touchY > gVcnLayer[UP].y && touchY < gVcnLayer[UP].y + gVcnLayer[UP].h) {
                createFireballs(vcn1L + gVcnMouseW / 2f - gXOffset);
            } else if (touchX > vcn2L && touchX < vcn2R && touchY > gVcnLayer[UP].y && touchY < gVcnLayer[UP].y + gVcnLayer[UP].h) {
                createFireballs(vcn2L + gVcnMouseW / 2f - gXOffset);
            }
        }
    }

    private void createFireballs(float xpos) {
        for (int i = 0; i < FIREBALL_COUNT; i++) {
            if (fbs[i].steps <= 0) {
                float random = rand(0.3f);
                float scale = 0.6f + random;
                fbs[i].w = gFireballW * scale;
                fbs[i].h = gFireballH * scale;
                fbs[i].x = xpos - fbs[i].w / 2f;
                fbs[i].y = gVcnLayer[UP].y + 8;
                fbs[i].speed = gFireballBaseSpeed + gFireballBaseSpeed * random;
                fbs[i].dir = 0.3f - rand(0.6f);
                fbs[i].steps = (int)(30 + random * 40);
                fbs[i].startTime = SystemClock.uptimeMillis() + rand(0.4f) * 10000f;
            }
        }
        gFireballsShow = 1;
    }

    float rand(float max) { return mRandom.nextFloat() * max; }
}
