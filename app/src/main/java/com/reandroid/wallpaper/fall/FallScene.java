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

import android.content.SharedPreferences;
import android.opengl.Matrix;
import android.util.Log;

import com.reandroid.settings.WallpaperSettings;

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

        void init() {
            ampS = 0.0f;
            ampE = 0.0f;
            spread = 1.0f;
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
    private boolean mInitialized = false;
    private boolean mMeshBuffersDirty = true;
    private boolean mWaterTexCoordsDirty = true;
    private float[] mVkLeafData = new float[0];
    private int mVkLeafFloatCount = 0;
    private volatile SharedPreferences mPrefs;

    FallScene(int width, int height) {
        mWidth = width;
        mHeight = height;
        mLastTimeMs = System.currentTimeMillis();
        // Defer prepareNonGLResources() to first update() so setPluginPrefs() is available
    }

    /** Called by FallGL.start() or first update() to initialize non-GL resources. */
    void ensureResources() {
        prepareNonGLResources();
    }

    /** Plugin path: use host-provided prefs instead of WallpaperSettings. */
    void setPluginPrefs(SharedPreferences prefs) {
        mPrefs = prefs;
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
            float score = mWaterDrops[i].ampE;
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
        drop.activateLegacy(dropX, dropY, 1.2f);
        mWaterTexCoordsDirty = true;
    }

    SceneData getSceneData() {
        return mSceneData;
    }

    float[] buildLeafDataForVK() {
        Leaf[] leaves = mSceneData.leaves;
        int leafCount = leaves != null ? leaves.length : 0;
        int required = leafCount * 6;
        if (required <= 0) {
            mVkLeafFloatCount = 0;
            return mVkLeafData;
        }
        if (mVkLeafData.length < required) {
            mVkLeafData = new float[required];
        }
        for (int i = 0; i < leafCount; i++) {
            Leaf leaf = leaves[i];
            int base = i * 6;
            mVkLeafData[base] = leaf.x;
            mVkLeafData[base + 1] = leaf.y;
            mVkLeafData[base + 2] = leaf.scale;
            mVkLeafData[base + 3] = leaf.angle;
            mVkLeafData[base + 4] = leaf.altitude;
            mVkLeafData[base + 5] = leaf.leafTextureIndex;
        }
        mVkLeafFloatCount = required;
        return mVkLeafData;
    }

    int getVKLeafCount() {
        return mVkLeafFloatCount / 6;
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

        mLeafTextureCount = isGreenLeaves() ? 20 : 14;
        mLeafCount = getLeafCount();
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

        mWaterDropCount = Math.max(1, getMaxDrops());
        mLastWaterDropCount = mWaterDropCount;
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
        int desired = Math.max(1, getMaxDrops());
        if (desired == mLastWaterDropCount && mWaterDrops != null) {
            return;
        }

        mWaterDropCount = desired;
        mLastWaterDropCount = desired;
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
        int desiredCount = getLeafCount();
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
            float score = mWaterDrops[i].ampE;
            if (score < minAmp) {
                minIndex = i;
                minAmp = score;
            }
        }

        Drop drop = mWaterDrops[minIndex];
        drop.activateLegacy(meshX, meshY, amplitude);
    }

    private void updateWaterMesh(long nowMs) {
        if (mSceneData.waterMeshVertices == null || mWaterDrops == null || mWaterDropCount <= 0) {
            return;
        }

        for (Drop drop : mWaterDrops) {
            drop.spread += 30.0f * mDeltaTime;
            drop.ampE = drop.ampS / drop.spread;
        }

        float height = mGlHeight;
        int wResolution = MESH_RESOLUTION + 2;
        int hResolution = (int) (MESH_RESOLUTION * height / 2.0f) + 2;
        float[] deformedTex = mSceneData.waterMeshTexCoords;
        if (deformedTex == null || deformedTex.length < (wResolution * hResolution * 2)) {
            deformedTex = new float[Math.max(0, wResolution * hResolution * 2)];
        }

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

                int texIndex = (y * wResolution + x) * 2;
                deformedTex[texIndex] = varU;
                deformedTex[texIndex + 1] = varV;
            }
        }

        mSceneData.waterMeshTexCoords = deformedTex;
        mWaterTexCoordsDirty = true;
    }

    // ---- Plugin-aware settings fallback ----

    private boolean isGreenLeaves() {
        if (mPrefs != null) return mPrefs.getBoolean(WallpaperSettings.KEY_FALL_GREEN_LEAVES, false);
        return WallpaperSettings.isGreenLeavesEnabled(false);
    }

    private int getLeafCount() {
        if (mPrefs != null) return mPrefs.getInt(WallpaperSettings.KEY_FALL_LEAF_COUNT, DEFAULT_LEAVES_COUNT);
        return WallpaperSettings.getFallLeafCount(DEFAULT_LEAVES_COUNT);
    }

    private int getMaxDrops() {
        if (mPrefs != null) return mPrefs.getInt(WallpaperSettings.KEY_FALL_MAX_DROPS, DEFAULT_WATER_MESH_DROPS);
        return WallpaperSettings.getFallMaxDrops(DEFAULT_WATER_MESH_DROPS);
    }
}
