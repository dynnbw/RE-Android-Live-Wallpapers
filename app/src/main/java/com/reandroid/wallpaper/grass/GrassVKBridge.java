package com.reandroid.wallpaper.grass;

/**
 * VK data bridge — forwards scene data from GrassRenderDataBuilder to Vulkan JNI.
 * Extracted from GrassScene to reduce God Class bloat.
 */
final class GrassVKBridge {

    private final GrassRenderDataBuilder mBuilder;

    GrassVKBridge(GrassRenderDataBuilder builder) {
        mBuilder = builder;
    }

    float[] computeSkyParams(SceneData sd) {
        return mBuilder.computeSkyParams(sd);
    }

    float[] buildMoonParams(SceneData sd) {
        return mBuilder.buildMoonParams(sd);
    }

    float[] buildGrassVertexArray(SceneData sd) {
        return mBuilder.buildGrassVertexArray(sd);
    }

    boolean wasGrassVertexArrayUpdated() {
        return mBuilder.wasGrassVertexArrayUpdated();
    }

    int getGrassVertexCount() {
        return mBuilder.getGrassVertexCount();
    }

    int getSunVertexCount() {
        return mBuilder.getSunVertexCount();
    }

    int getDandelionVertexCount() {
        return mBuilder.getDandelionVertexCount();
    }

    int getFireflyVertexCount() {
        return mBuilder.getFireflyVertexCount();
    }

    int getFireflyFlareVertexCount() {
        return mBuilder.getFireflyFlareVertexCount();
    }

    int getMoonVertexCount() {
        return mBuilder.getMoonVertexCount();
    }

    short[] buildGrassIndexArray() {
        return mBuilder.buildGrassIndexArray();
    }

    float[] buildSunSpriteVertices(SceneData sd) {
        return mBuilder.buildSunSpriteVertices(sd);
    }

    float[] buildMoonSpriteVertices(SceneData sd) {
        return mBuilder.buildMoonSpriteVertices(sd);
    }

    float[] buildDandelionSpriteVertices(SceneData sd) {
        return mBuilder.buildDandelionSpriteVertices(sd);
    }

    float[] buildFireflySpriteVertices(SceneData sd) {
        return mBuilder.buildFireflySpriteVertices(sd);
    }

    float[] buildFireflyFlareSpriteVertices(SceneData sd) {
        return mBuilder.buildFireflyFlareSpriteVertices(sd);
    }
}
