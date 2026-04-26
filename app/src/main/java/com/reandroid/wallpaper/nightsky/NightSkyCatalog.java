package com.reandroid.wallpaper.nightsky;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

final class NightSkyCatalog {
    final float[] starParams;
    final float[] starColors;
    final float[] starBaseAlpha;
    final int starCount;

    final float[] brightParams;
    final float[] brightColors;
    final float[] brightBaseAlpha;
    final int brightCount;

    NightSkyCatalog(
            float[] starParams,
            float[] starColors,
            float[] starBaseAlpha,
            float[] brightParams,
            float[] brightColors,
            float[] brightBaseAlpha
    ) {
        this.starParams = starParams;
        this.starColors = starColors;
        this.starBaseAlpha = starBaseAlpha;
        this.brightParams = brightParams;
        this.brightColors = brightColors;
        this.brightBaseAlpha = brightBaseAlpha;
        this.starCount = starParams.length / 4;
        this.brightCount = brightParams.length / 4;
    }

    FloatBuffer newStarParamBuffer() {
        return toBuffer(starParams);
    }

    FloatBuffer newStarColorBuffer() {
        return toBuffer(starColors);
    }

    private FloatBuffer toBuffer(float[] src) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(src.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        buffer.put(src).position(0);
        return buffer;
    }
}
