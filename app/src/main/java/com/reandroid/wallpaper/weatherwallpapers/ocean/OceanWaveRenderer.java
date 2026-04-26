package com.reandroid.wallpaper.weatherwallpapers.ocean;

class OceanWaveRenderer {
    interface Drawer {
        void drawSprite(int texture, float x, float y, float z,
                        float scaleX, float scaleY, float rotation, float alpha);
    }

    void drawWaves(Drawer drawer,
                   int frameCnt,
                   float offset,
                   float landscape,
                   int waveBack,
                   int[] wave) {
        drawer.drawSprite(waveBack, (1.25f - offset) * 5.0f, -6.2f, -22.0f,
                1.6f * landscape, 0.6f, 0.0f, 1.0f);

        float baseX = (-5.0f) + ((1.25f - (offset / 5.0f)) * 5.0f);
        int waveCnt = frameCnt % 200;
        if (waveCnt < 54) {
            drawer.drawSprite(wave[(waveCnt / 3) + 4], baseX, -4.3f, -21.5f,
                    0.825f * landscape, 0.37f, 0.0f, 1.0f);
        } else if (waveCnt < 84) {
            float alpha1 = (waveCnt - 54) / 30.0f;
            drawer.drawSprite(wave[(waveCnt / 3) + 4], baseX, -4.3f, -21.5f,
                    0.825f * landscape, 0.37f, 0.0f, 1.0f);
            drawer.drawSprite(wave[((waveCnt - 54) / 3) + 10], baseX, -4.3f, -21.5f,
                    0.825f * landscape, 0.37f, 0.0f, alpha1);
        } else if (waveCnt < 108) {
            drawer.drawSprite(wave[((waveCnt - 54) / 3) + 10], baseX, -4.3f, -21.5f,
                    0.825f * landscape, 0.37f, 0.0f, 1.0f);
        } else if (waveCnt < 120) {
            float alpha2 = (waveCnt - 108) / 12.0f;
            drawer.drawSprite(wave[((waveCnt - 54) / 3) + 10], baseX, -4.3f, -21.5f,
                    0.825f * landscape, 0.37f, 0.0f, 1.0f);
            drawer.drawSprite(wave[(waveCnt - 108) / 3], baseX, -4.3f, -21.5f,
                    0.825f * landscape, 0.37f, 0.0f, alpha2);
        } else if (waveCnt < 188) {
            drawer.drawSprite(wave[(waveCnt - 108) / 3], baseX, -4.3f, -21.5f,
                    0.825f * landscape, 0.37f, 0.0f, 1.0f);
        } else {
            float alpha3 = (waveCnt - 188) / 12.0f;
            drawer.drawSprite(wave[(waveCnt - 108) / 3], baseX, -4.3f, -21.5f,
                    0.825f * landscape, 0.37f, 0.0f, 1.0f);
            drawer.drawSprite(wave[(waveCnt - 188) / 3], baseX, -4.3f, -21.5f,
                    0.825f * landscape, 0.37f, 0.0f, alpha3);
        }
    }
}