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
        final float dinoDownXFactor;  // multiplier on screenWidth
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
        gDensity = den == 0 ? DENSITY_480x800 : den;
        int sw = getScreenWidth();
        int sh = getScreenHeight();
        screenWidth = sw;
        screenHeight = sh;

        DensityPreset p = DENSITY_PRESETS[gDensity - 1];
        gBgSpeed = p.bgSpeed; gDayAndNightSpeed = p.dayNightSpeed; gFireballBaseSpeed = p.fireballBaseSpeed;
        gFireballW = p.fireballW; gFireballH = p.fireballH;

        applyLayer(gDay[UP],   0,                sw, p.dayUpH);
        applyLayer(gDay[DOWN], p.dayUpH,         sw, p.dayDownH);
        applyLayer(gNight[UP], -(p.nightUpH + p.nightDownH), sw, p.nightUpH);
        applyLayer(gNight[DOWN], -p.nightDownH,  sw, p.nightDownH);

        applyLayer(gVcnLayer[UP],   p.vcnUpY,        sw, p.vcnUpH);
        applyLayer(gVcnLayer[DOWN], p.vcnUpY + p.vcnUpH, sw, p.vcnDownH);
        gVcnMouseOffx = p.vcnMouseOffx; gVcnMouseW = p.vcnMouseW;

        applyLayer(gLayer4[UP],   p.layer4UpY,         sw, p.layer4UpH);
        applyLayer(gLayer4[DOWN], p.layer4UpY + p.layer4UpH, sw, p.layer4DownH);
        applyLayer(gLayer3[UP],   p.layer3UpY,         sw, p.layer3UpH);
        applyLayer(gLayer3[DOWN], p.layer3UpY + p.layer3UpH, sw, p.layer3DownH);
        applyLayer(gLayer2[UP],   p.layer2UpY,         sw, p.layer2UpH);
        applyLayer(gLayer2[DOWN], p.layer2UpY + p.layer2UpH, sw, p.layer2DownH);
        applyLayer(gLayer1[UP],   p.layer1UpY,         sw, p.layer1UpH);
        applyLayer(gLayer1[DOWN], p.layer1UpY + p.layer1UpH, sw, p.layer1DownH);

        gPterosaur.x = p.pteroX; gPterosaur.y = p.pteroY;
        gPterosaur.w = p.pteroW; gPterosaur.h = p.pteroH;
        gPterosaur.scale = 1.0f; gPterosaurSpeed = p.pteroSpeed;

        gDinosaur[UP].distance = DINOSAUR1_DISTANCE; gDinosaur[UP].x = sw;
        gDinosaur[UP].y = p.dinoUpY; gDinosaur[UP].w = p.dinoUpW; gDinosaur[UP].h = p.dinoUpH;
        gDinosaur[DOWN].distance = DINOSAUR2_DISTANCE; gDinosaur[DOWN].x = sw * p.dinoDownXFactor;
        gDinosaur[DOWN].y = p.dinoDownY; gDinosaur[DOWN].w = p.dinoDownW; gDinosaur[DOWN].h = p.dinoDownH;
        gDinosaurSpeedX = p.dinoSpeedX; gDinosaurSpeedY = p.dinoSpeedY;

        gSunLeft = p.sunLeft; gSunRight = p.sunRight; gSunTop = p.sunTop; gSunBottom = p.sunBottom;
        gMoonLeft = p.moonLeft; gMoonRight = p.moonRight; gMoonTop = p.moonTop; gMoonBottom = p.moonBottom;
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
