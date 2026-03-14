/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.reandroid.wallpaper.fall;

import android.opengl.Matrix;
import android.util.Log;

import com.reandroid.wallpaper.settings.WallpaperSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

final class FallScene {
    private static final String TAG = "FallScene";

    static final int DEFAULT_LEAVES_COUNT = 14;
    static final int DEFAULT_RANDOM_DROPS = 10;
    static final float LEAF_SIZE = 0.55f;
    private static final int MESH_RESOLUTION = 48;
    private static final int DEFAULT_WATER_MESH_DROPS = 10;
    private static final float PRECISE_RIPPLE_STRENGTH = 0.18f;
    private static final float PRECISE_RIPPLE_SIZE_SCALE = 1.65f;

    static final class Leaf {
        float x;
        float y;
        float scale;
        float angle;
        float spin;
        float altitude;
        float deltaX;
        float deltaY;
        int leafTextureIndex;
        boolean rippled;

        void init(Random random, int leafTexCount, boolean startAboveWater) {
            leafTextureIndex = random.nextInt(Math.max(1, leafTexCount));
            x = (random.nextFloat() - 0.5f) * 4.0f;
            y = (random.nextFloat() - 0.5f) * 3.333f;
            scale = 0.4f + random.nextFloat() * 0.1f;
            angle = random.nextFloat() * 360.0f;
            spin = (random.nextFloat() - 0.5f) * 0.016f;
            altitude = startAboveWater ? 0.7f : -1.0f;
            deltaX = (random.nextFloat() - 0.5f) * 0.02f;
            deltaY = -(0.036f + random.nextFloat() * 0.008f);
            rippled = !startAboveWater;
        }
    }

    static final class Drop {
        float ampS;
        float ampE;
        float spread;
        float x;
        float y;
        float initAmplitude;
        float currentAmplitude;
        float wavelength;
        float frequency;
        float waveNumber;
        float angularFrequency;
        float phase;
        float timeDecay;
        float spatialDecay;
        long birthTimeMs;
        float waveSpeed;

        void init() {
            ampS = 0.0f;
            ampE = 0.0f;
            spread = 1.0f;
            initAmplitude = 0.0f;
            currentAmplitude = 0.0f;
            wavelength = 0.0f;
            frequency = 0.0f;
            waveNumber = 0.0f;
            angularFrequency = 0.0f;
            phase = 0.0f;
            timeDecay = 0.0f;
            spatialDecay = 0.0f;
            birthTimeMs = 0L;
            waveSpeed = 0.0f;
        }

        void updateLegacy(float dt) {
            if (ampS > 0.0f) {
                spread += 30.0f * dt;
                ampE = ampS * (float) Math.exp(-0.02f * spread) / (1.0f + 0.01f * spread);
            }
        }

        void activateLegacy(float meshX, float meshY, float amplitude) {
            x = meshX;
            y = meshY;
            ampS = amplitude;
            spread = 0.0f;
            ampE = amplitude;
            initAmplitude = 0.0f;
            currentAmplitude = 0.0f;
            birthTimeMs = 0L;
        }

        void activatePrecise(float meshX, float meshY, float energy, Random random, long nowMs) {
            x = meshX;
            y = meshY;
            initAmplitude = energy * 1.0f;
            currentAmplitude = initAmplitude;
            wavelength = 4.0f + random.nextFloat() * 2.5f;
            frequency = 2.2f;
            waveNumber = (float) (2.0f * Math.PI / wavelength);
            angularFrequency = (float) (2.0f * Math.PI * frequency);
            phase = 0.0f;
            timeDecay = 0.30f;
            spatialDecay = 0.045f;
            birthTimeMs = nowMs;
            waveSpeed = Math.max(wavelength * frequency, 18.0f);
            ampS = 0.0f;
            ampE = currentAmplitude;
            spread = 1.0f;
        }

        void updatePrecise(long nowMs) {
            if (initAmplitude <= 0.0f || birthTimeMs <= 0L) {
                currentAmplitude = 0.0f;
                return;
            }
            float elapsedTimeSec = Math.max(0.0f, (nowMs - birthTimeMs) / 1000.0f);
            currentAmplitude = (float) (initAmplitude * Math.exp(-timeDecay * elapsedTimeSec));
            ampE = currentAmplitude;
        }

        float getDisplacementAt(float px, float py, long nowMs) {
            if (currentAmplitude <= 0.1f || birthTimeMs <= 0L) {
                return 0.0f;
            }

            float dx = px - x;
            float dy = py - y;
            float radius = (float) Math.sqrt(dx * dx + dy * dy);
            float scaledRadius = radius / PRECISE_RIPPLE_SIZE_SCALE;
            float timeSinceBirth = (nowMs - birthTimeMs) / 1000.0f;
            if (timeSinceBirth <= 0.0f) {
                return 0.0f;
            }

            float waveFrontRadius = waveSpeed * timeSinceBirth;
            if (scaledRadius > waveFrontRadius + 1.0f) {
                return 0.0f;
            }

            float decay = (float) Math.exp(-timeDecay * timeSinceBirth - spatialDecay * scaledRadius);
            float oscillation = (float) Math.cos(waveNumber * scaledRadius - angularFrequency * timeSinceBirth + phase);
            float geometricAttenuation = 1.0f / (float) Math.sqrt(scaledRadius + 1.0f);
            return currentAmplitude * decay * oscillation * geometricAttenuation;
        }
    }

    static final class SceneData {
        private final float[] projectionMatrix = new float[16];
        private final float[] viewMatrix = new float[16];
        private Leaf[] leaves;
        private float[] waterMeshVertices;
        private float[] waterMeshTexCoords;
        private short[] waterMeshIndices;
        private int waterMeshVertexCount;
        private int waterMeshIndexCount;
        private float xOffset = 0.5f;

        float[] getProjectionMatrix() {
            return projectionMatrix;
        }

        float[] getViewMatrix() {
            return viewMatrix;
        }

        Leaf[] getLeaves() {
            return leaves;
        }

        float[] getWaterMeshVertices() {
            return waterMeshVertices;
        }

        float[] getWaterMeshTexCoords() {
            return waterMeshTexCoords;
        }

        short[] getWaterMeshIndices() {
            return waterMeshIndices;
        }

        int getWaterMeshVertexCount() {
            return waterMeshVertexCount;
        }

        int getWaterMeshIndexCount() {
            return waterMeshIndexCount;
        }

        float getXOffset() {
            return xOffset;
        }
    }

    private final Random mRandom = new Random();
    private final SceneData mSceneData = new SceneData();

    private int mWidth;
    private int mHeight;
    private long mLastTimeMs;
    private float mDeltaTime;
    private int mLeafCount = DEFAULT_LEAVES_COUNT;
    private int mLastLeafCount = DEFAULT_LEAVES_COUNT;
    private int mLeafTextureCount = DEFAULT_LEAVES_COUNT;
    private int mRotate = 0;
    private int mMeshWidth;
    private int mMeshHeight;
    private float mGlHeight = 3.333f;
    private float mBackgroundScale = 0.75f;
    private Drop[] mDrops;
    private Drop[] mWaterDrops;
    private int mWaterDropCount = DEFAULT_WATER_MESH_DROPS;
    private int mLastWaterDropCount = DEFAULT_WATER_MESH_DROPS;
    private boolean mUsePreciseRippleCalc = false;
    private boolean mLastUsePreciseRippleCalc = false;
    private boolean mInitialized = false;
    private boolean mMeshBuffersDirty = true;
    private boolean mWaterTexCoordsDirty = true;

    FallScene(int width, int height) {
        mWidth = width;
        mHeight = height;
        mLastTimeMs = System.currentTimeMillis();
        prepareNonGLResources();
    }

    void setLeafTextureCount(int leafTextureCount) {
        if (leafTextureCount > 0) {
            mLeafTextureCount = leafTextureCount;
        }
    }

    void setOffset(float xOffset) {
        mSceneData.xOffset = xOffset;
    }

    void resize(int width, int height) {
        mWidth = width;
        mHeight = height;
        mRotate = width > height ? 1 : 0;
        mGlHeight = 2.0f * (float) height / (float) width;
        updateProjectionMatrix();
        createWaterMesh();
        mMeshBuffersDirty = true;
        mWaterTexCoordsDirty = true;
    }

    void update(long timeMs) {
        if (!mInitialized) {
            prepareNonGLResources();
        }

        long nowMs = System.currentTimeMillis();
        mDeltaTime = Math.min((nowMs - mLastTimeMs) * 0.001f, 0.2f);
        mLastTimeMs = nowMs;

        ensureWaterDropCount();
        updateDrops();
        updateLeaves();
        updateWaterMesh(nowMs);
    }

    void addDrop(int x, int y) {
        if (mWaterDrops == null || mWaterDropCount <= 0) {
            return;
        }

        int minIndex = 0;
        float minAmp = Float.MAX_VALUE;
        for (int i = 0; i < mWaterDropCount; i++) {
            float score = mUsePreciseRippleCalc ? mWaterDrops[i].currentAmplitude : mWaterDrops[i].ampE;
            if (score < minAmp) {
                minAmp = score;
                minIndex = i;
            }
        }

        float posX = ((float) x / (float) mWidth) * 2.0f - 1.0f;
        float posY = (1.0f - (float) y / (float) mHeight) * mGlHeight - (mGlHeight * 0.5f);
        if (mRotate == 0) {
            posX += mSceneData.xOffset * 2.0f;
        }

        float scaleX = (mMeshWidth - 1) * 0.5f;
        float scaleY = (mMeshHeight - 1) * 0.5f;
        float dropX = (posX + 1.0f) * scaleX;
        float dropY = ((posY / (mGlHeight * 0.5f)) + 1.0f) * scaleY;

        Drop drop = mWaterDrops[minIndex];
        if (mUsePreciseRippleCalc) {
            drop.activatePrecise(dropX, dropY, 1.2f, mRandom, System.currentTimeMillis());
        } else {
            drop.activateLegacy(dropX, dropY, 1.2f);
        }
        mWaterTexCoordsDirty = true;
    }

    SceneData getSceneData() {
        return mSceneData;
    }

    boolean consumeMeshBufferRebuildRequested() {
        boolean value = mMeshBuffersDirty;
        mMeshBuffersDirty = false;
        return value;
    }

    boolean consumeWaterTexCoordsDirty() {
        boolean value = mWaterTexCoordsDirty;
        mWaterTexCoordsDirty = false;
        return value;
    }

    private void prepareNonGLResources() {
        if (mInitialized) {
            return;
        }
        mInitialized = true;

        mLeafTextureCount = WallpaperSettings.isGreenLeavesEnabled(false) ? 20 : 14;
        mLeafCount = WallpaperSettings.getFallLeafCount(DEFAULT_LEAVES_COUNT);
        mLastLeafCount = mLeafCount;
        mSceneData.leaves = new Leaf[mLeafCount];
        for (int i = 0; i < mLeafCount; i++) {
            mSceneData.leaves[i] = new Leaf();
            mSceneData.leaves[i].init(mRandom, mLeafTextureCount, false);
        }

        mDrops = new Drop[DEFAULT_RANDOM_DROPS];
        for (int i = 0; i < DEFAULT_RANDOM_DROPS; i++) {
            mDrops[i] = new Drop();
            mDrops[i].init();
        }

        mRotate = mWidth > mHeight ? 1 : 0;
        float width = mWidth > mHeight ? mHeight : mWidth;
        float height = mWidth > mHeight ? mWidth : mHeight;
        mGlHeight = 2.0f * height / width;

        mWaterDropCount = Math.max(1, WallpaperSettings.getFallMaxDrops(DEFAULT_WATER_MESH_DROPS));
        mLastWaterDropCount = mWaterDropCount;
        mUsePreciseRippleCalc = WallpaperSettings.isFallPreciseCalcEnabled(false);
        mLastUsePreciseRippleCalc = mUsePreciseRippleCalc;
        mWaterDrops = new Drop[mWaterDropCount];
        for (int i = 0; i < mWaterDropCount; i++) {
            mWaterDrops[i] = new Drop();
            mWaterDrops[i].init();
        }

        createWaterMesh();
        Matrix.setLookAtM(mSceneData.viewMatrix, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0);
        updateProjectionMatrix();
        Log.d(TAG, "FallScene 初始化完成");
    }

    private void updateProjectionMatrix() {
        float yScale = 0.9f;
        Matrix.orthoM(mSceneData.projectionMatrix, 0, -1, 1,
                -mGlHeight / 2.0f * yScale, mGlHeight / 2.0f * yScale, 0.1f, 10.0f);
    }

    private void createWaterMesh() {
        float height = mGlHeight;
        int wResolution = MESH_RESOLUTION + 2;
        int hResolution = (int) (MESH_RESOLUTION * height / 2.0f) + 2;

        List<Float> vertices = new ArrayList<>();
        List<Float> texCoords = new ArrayList<>();
        for (int y = 0; y <= hResolution; y++) {
            float yOffset = (((float) y / hResolution) * 2.0f - 1.0f) * height / 2.0f;
            for (int x = 0; x <= wResolution; x++) {
                float xPos = ((float) x / wResolution) * 2.0f - 1.0f;
                vertices.add(xPos);
                vertices.add(yOffset);
                vertices.add(0.0f);
                texCoords.add((float) x / wResolution);
                texCoords.add((float) y / hResolution);
            }
        }

        List<Integer> indices = new ArrayList<>();
        for (int y = 0; y < hResolution; y++) {
            int yOffset = y * (wResolution + 1);
            for (int x = 0; x < wResolution; x++) {
                int index = yOffset + x;
                int nextRow = index + wResolution + 1;
                indices.add(index);
                indices.add(index + 1);
                indices.add(nextRow);
                indices.add(index + 1);
                indices.add(nextRow + 1);
                indices.add(nextRow);
            }
        }

        mSceneData.waterMeshVertexCount = vertices.size() / 3;
        mSceneData.waterMeshIndexCount = indices.size();
        mMeshWidth = wResolution + 1;
        mMeshHeight = hResolution + 1;

        mSceneData.waterMeshVertices = new float[vertices.size()];
        for (int i = 0; i < vertices.size(); i++) {
            mSceneData.waterMeshVertices[i] = vertices.get(i);
        }

        mSceneData.waterMeshTexCoords = new float[texCoords.size()];
        for (int i = 0; i < texCoords.size(); i++) {
            mSceneData.waterMeshTexCoords[i] = texCoords.get(i);
        }

        mSceneData.waterMeshIndices = new short[indices.size()];
        for (int i = 0; i < indices.size(); i++) {
            mSceneData.waterMeshIndices[i] = (short) (int) indices.get(i);
        }
    }

    private void ensureWaterDropCount() {
        int desired = Math.max(1, WallpaperSettings.getFallMaxDrops(DEFAULT_WATER_MESH_DROPS));
        boolean desiredPrecise = WallpaperSettings.isFallPreciseCalcEnabled(false);
        if (desired == mLastWaterDropCount && desiredPrecise == mLastUsePreciseRippleCalc && mWaterDrops != null) {
            return;
        }

        mWaterDropCount = desired;
        mLastWaterDropCount = desired;
        mUsePreciseRippleCalc = desiredPrecise;
        mLastUsePreciseRippleCalc = desiredPrecise;
        mWaterDrops = new Drop[mWaterDropCount];
        for (int i = 0; i < mWaterDropCount; i++) {
            mWaterDrops[i] = new Drop();
            mWaterDrops[i].init();
        }
        mWaterTexCoordsDirty = true;
    }

    private void updateDrops() {
        for (Drop drop : mDrops) {
            drop.updateLegacy(mDeltaTime);
        }
        if (mRandom.nextFloat() < 0.3f) {
            int index = mRandom.nextInt(DEFAULT_RANDOM_DROPS);
            Drop drop = mDrops[index];
            drop.ampS = 1.0f;
            drop.spread = 0.0f;
            drop.x = (mRandom.nextFloat() - 0.5f) * 2.0f;
            drop.y = (mRandom.nextFloat() - 0.5f) * 3.0f;
        }
    }

    private void updateLeaves() {
        int desiredCount = WallpaperSettings.getFallLeafCount(DEFAULT_LEAVES_COUNT);
        if (desiredCount != mLastLeafCount && desiredCount > 0) {
            mLeafCount = desiredCount;
            mLastLeafCount = desiredCount;
            mSceneData.leaves = new Leaf[mLeafCount];
            for (int i = 0; i < mLeafCount; i++) {
                mSceneData.leaves[i] = new Leaf();
                mSceneData.leaves[i].init(mRandom, mLeafTextureCount, false);
            }
        }

        for (Leaf leaf : mSceneData.leaves) {
            if (leaf.altitude <= 0.0f) {
                if (!leaf.rippled) {
                    genLeafDrop(leaf, 1.5f);
                    leaf.rippled = true;
                    leaf.spin *= 0.25f;
                }

                leaf.x += leaf.deltaX * mDeltaTime;
                leaf.y += leaf.deltaY * mDeltaTime;
                leaf.angle += leaf.spin;

                float margin = LEAF_SIZE * 0.6f;
                float screenBottom = -mGlHeight / 2.0f - margin;
                float screenTop = mGlHeight / 2.0f + margin;
                if (leaf.y < screenBottom || leaf.y > screenTop) {
                    leaf.init(mRandom, mLeafTextureCount, true);
                }
            } else {
                leaf.altitude -= 0.15f * mDeltaTime;
                leaf.angle += leaf.spin * 2.0f;
            }
        }
    }

    private void genLeafDrop(Leaf leaf, float amplitude) {
        float posX = leaf.x;
        float posY = leaf.y;
        if (mRotate < 1) {
            posX += mSceneData.xOffset * 2.0f;
        }

        float scaleX = (mMeshWidth - 1) * 0.5f;
        float scaleY = (mMeshHeight - 1) * 0.5f;
        float meshX = (posX + 1.0f) * scaleX;
        float meshY = ((posY / (mGlHeight * 0.5f)) + 1.0f) * scaleY;

        int minIndex = 0;
        float minAmp = Float.MAX_VALUE;
        if (mWaterDrops == null || mWaterDropCount <= 0) {
            return;
        }
        for (int i = 0; i < mWaterDropCount; i++) {
            float score = mUsePreciseRippleCalc ? mWaterDrops[i].currentAmplitude : mWaterDrops[i].ampE;
            if (score < minAmp) {
                minIndex = i;
                minAmp = score;
            }
        }

        Drop drop = mWaterDrops[minIndex];
        if (mUsePreciseRippleCalc) {
            drop.activatePrecise(meshX, meshY, amplitude, mRandom, System.currentTimeMillis());
        } else {
            drop.activateLegacy(meshX, meshY, amplitude);
        }
    }

    private void updateWaterMesh(long nowMs) {
        if (mSceneData.waterMeshVertices == null || mWaterDrops == null || mWaterDropCount <= 0) {
            return;
        }

        if (mUsePreciseRippleCalc) {
            for (Drop drop : mWaterDrops) {
                drop.updatePrecise(nowMs);
            }
        } else {
            for (Drop drop : mWaterDrops) {
                drop.spread += 30.0f * mDeltaTime;
                drop.ampE = drop.ampS / drop.spread;
            }
        }

        float height = mGlHeight;
        int wResolution = MESH_RESOLUTION + 2;
        int hResolution = (int) (MESH_RESOLUTION * height / 2.0f) + 2;
        float[] deformedTex = new float[mSceneData.waterMeshTexCoords.length];

        for (int y = 0; y < hResolution; y++) {
            for (int x = 0; x < wResolution; x++) {
                int vertexIndex = (y * wResolution + x) * 3;
                float xPos = mSceneData.waterMeshVertices[vertexIndex];
                float yPos = mSceneData.waterMeshVertices[vertexIndex + 1];

                float posX = xPos;
                float posY = yPos;
                float varU = posX + 1.0f;
                float varV = posY + (mGlHeight * 0.5f);
                float dxMul = 1.0f;

                if (mRotate < 1) {
                    varU *= 0.25f;
                    float vScale = 0.33f * (3.333f / mGlHeight);
                    varV *= vScale;
                    varU += mSceneData.xOffset * 0.5f;
                    posX += mSceneData.xOffset * 2.0f;
                } else {
                    varU *= 0.5f;
                    float vScale = 0.3125f * (3.333f / mGlHeight);
                    varV *= vScale;
                    dxMul = 2.5f;
                }

                varU = 0.5f + (varU - 0.5f) * mBackgroundScale;
                varV = 0.5f + (varV - 0.5f) * mBackgroundScale;

                float scaleX = (mMeshWidth - 1) * 0.5f;
                float scaleY = (mMeshHeight - 1) * 0.5f;
                float posScaledX = (posX + 1.0f) * scaleX;
                float posScaledY = ((posY / (mGlHeight * 0.5f)) + 1.0f) * scaleY;

                if (mUsePreciseRippleCalc) {
                    float uOffset = 0.0f;
                    float vOffset = 0.0f;
                    for (Drop drop : mWaterDrops) {
                        float displacement = drop.getDisplacementAt(posScaledX, posScaledY, nowMs);
                        if (Math.abs(displacement) < 0.00001f) {
                            continue;
                        }
                        float ddx = drop.x - posScaledX;
                        float ddy = drop.y - posScaledY;
                        float radius = (float) Math.sqrt(ddx * ddx + ddy * ddy);
                        if (radius < 0.0001f) {
                            continue;
                        }
                        float invR = 1.0f / radius;
                        float waveOffset = displacement * PRECISE_RIPPLE_STRENGTH;
                        uOffset += (ddx * dxMul) * invR * waveOffset * 0.35f;
                        vOffset += ddy * invR * waveOffset;
                    }
                    varU += uOffset;
                    varV += vOffset;
                } else {
                    for (Drop drop : mWaterDrops) {
                        float dx = drop.x - posScaledX;
                        float dy = drop.y - posScaledY;
                        dx *= dxMul;
                        float dist = (float) Math.sqrt(dx * dx + dy * dy);
                        if (dist < drop.spread) {
                            float amp = drop.ampE * 0.12f * dist;
                            amp /= (drop.spread * drop.spread);
                            amp *= (float) Math.sin(drop.spread - dist);
                            varU += dx * amp;
                            varV += dy * amp;
                        }
                    }
                }

                int texIndex = (y * wResolution + x) * 2;
                deformedTex[texIndex] = varU;
                deformedTex[texIndex + 1] = varV;
            }
        }

        mSceneData.waterMeshTexCoords = deformedTex;
        mWaterTexCoordsDirty = true;
    }
}
