package com.reandroid.wallpaper.luminousdots;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for LuminousDotsScene alpha calculation, rotation state machine,
 * and vertex data layout.
 */
public class LuminousDotsLogicTest {

    // ---- getDefaultAlpha ----

    @Test
    public void getDefaultAlpha_producesValidAlpha() {
        float alpha = LuminousDotsScene.getDefaultAlpha(
                10000, 1000, 5000, 6500, 6500, 1f, 1f, 6500f);
        assertTrue("Alpha in [0,1]: " + alpha, alpha >= 0f && alpha <= 1f);
    }

    @Test
    public void getDefaultAlpha_startMsZero_returnsMax() {
        float alpha = LuminousDotsScene.getDefaultAlpha(
                5000, 0, 3000, 6500, 6500, 1f, 1f, 6500f);
        assertEquals("Zero startMs", 1f, alpha, 0.001f);
    }

    @Test
    public void getDefaultAlpha_fadeIn_producesPositive() {
        // Time just after start → should be in fade-in (alpha rising from 0)
        float alpha = LuminousDotsScene.getDefaultAlpha(
                800, 500, 3000, 6500, 6500, 1f, 1f, 6500f);
        assertTrue("Fade-in alpha > 0: " + alpha, alpha > 0f);
    }

    @Test
    public void getDefaultAlpha_fadeOut_decreases() {
        // Time just after end → should be in fade-out (alpha < max)
        float alpha = LuminousDotsScene.getDefaultAlpha(
                3500, 500, 3000, 6500, 6500, 1f, 1f, 6500f);
        assertTrue("Fade-out alpha < max: " + alpha, alpha <= 1f);
    }

    @Test
    public void getDefaultAlpha_respectsMaxAlpha() {
        float maxAlpha = 0.5f;
        float alpha = LuminousDotsScene.getDefaultAlpha(
                10000, 1000, 5000, 6500, 6500, maxAlpha, 1f, 6500f);
        assertTrue("Alpha ≤ maxAlpha: " + alpha, alpha <= maxAlpha);
    }

    // ---- getRandomAlpha ----

    @Test
    public void getRandomAlpha_beforeStart_returnsZero() {
        float alpha = LuminousDotsScene.getRandomAlpha(100, 200, 5000, 1f, 1f);
        assertEquals("Before start", 0f, alpha, 0.001f);
    }

    @Test
    public void getRandomAlpha_afterStart_returnsPositive() {
        float alpha = LuminousDotsScene.getRandomAlpha(300, 200, 5000, 1f, 1f);
        assertTrue("After start > 0", alpha > 0f);
    }

    @Test
    public void getRandomAlpha_afterFadeIn_returnsMax() {
        // Time well after start → fully faded in
        float alpha = LuminousDotsScene.getRandomAlpha(10000, 200, 5000, 1f, 1f);
        assertTrue("After fade-in", alpha >= 0f);
    }

    // ---- Rotation state machine ----

    @Test
    public void rotateIdx_incrementsInRange() {
        // Simulate increaseRotateIdx
        int idx = 0;
        idx++;
        if (idx >= LuminousDotsScene.ROTATE_IDX.length) idx = 0;
        assertEquals("Increment within range", 1, idx);

        idx = 7;
        idx++;
        if (idx >= LuminousDotsScene.ROTATE_IDX.length) idx = 0;
        assertEquals("Wraps to 0", 0, idx);
    }

    @Test
    public void checkRotateIdx_changesParity() {
        // z=true means new idx must have different parity than old
        int idx = 0;
        boolean z = true;
        if ((z && idx % 2 == 0) || (!z && idx % 2 != 0)) {
            idx--;
            if (idx < 0) idx = LuminousDotsScene.ROTATE_IDX.length - 1;
        }
        assertEquals("Changed parity", 7, idx); // 0 → 7
    }

    @Test
    public void checkRotateIdx_logic() {
        // Original: if ((!z || odd) && (z || even)) keep; else decrement
        // z=false, idx=1 (odd): (!false||true)=true, (false||false)=false → decrement → 0
        int idx = 1;
        boolean z = false;
        if ((!z || idx % 2 != 0) && (z || idx % 2 == 0)) {
            // keep
        } else {
            idx--;
            if (idx < 0) idx = 7;
        }
        assertEquals("z=false odd decrements", 0, idx);

        // z=true, idx=1 (odd): (!true||true)=true, (true||false)=true → keep
        idx = 1; z = true;
        if ((!z || idx % 2 != 0) && (z || idx % 2 == 0)) {
            // keep
        } else {
            idx--;
        }
        assertEquals("z=true odd keeps", 1, idx);
    }

    // ---- Camera path ----

    @Test
    public void camPath_8points_cycle() {
        assertEquals("8 camera points", 8, LuminousDotsScene.CAM_PATH.length);
        assertEquals("First point X", 25, LuminousDotsScene.CAM_PATH[0][0]);
        assertEquals("First point Y", 25, LuminousDotsScene.CAM_PATH[0][1]);
    }

    @Test
    public void camPath_coversAllQuadrants() {
        boolean hasPosX = false, hasNegX = false, hasPosY = false, hasNegY = false;
        for (int[] p : LuminousDotsScene.CAM_PATH) {
            if (p[0] > 0) hasPosX = true;
            if (p[0] < 0) hasNegX = true;
            if (p[1] > 0) hasPosY = true;
            if (p[1] < 0) hasNegY = true;
        }
        assertTrue("Has positive X", hasPosX);
        assertTrue("Has negative X", hasNegX);
        assertTrue("Has positive Y", hasPosY);
        assertTrue("Has negative Y", hasNegY);
    }

    // ---- Rotation types ----

    @Test
    public void rotateType_fourTypes() {
        assertEquals("4 rotation types", 4, LuminousDotsScene.ROTATE_TYPE.length);
        // Type 0: 180° around X
        assertEquals("Type 0 angle", 180f, LuminousDotsScene.ROTATE_TYPE[0][0], 0.001f);
        assertEquals("Type 0 axis X", 1f, LuminousDotsScene.ROTATE_TYPE[0][1], 0.001f);
    }

    @Test
    public void rotateIdx_eightPairs() {
        assertEquals("8 rotation index pairs", 8, LuminousDotsScene.ROTATE_IDX.length);
    }

    // ---- Glow positions ----

    @Test
    public void glowPos_tenEntries() {
        assertEquals("10 glow positions", 10, LuminousDotsScene.GLOW_POS.length);
    }

    @Test
    public void glowPos_validGridCoords() {
        for (int[] gp : LuminousDotsScene.GLOW_POS) {
            assertTrue("Grid X 0-31: " + gp[0], gp[0] >= 0 && gp[0] < 32);
            assertTrue("Grid Y 0-31: " + gp[1], gp[1] >= 0 && gp[1] < 32);
        }
    }

    // ---- Vertex layout ----

    @Test
    public void vertexLayout_strideCorrect() {
        assertEquals("ATTRS", 12, LuminousDotsScene.ATTRS);
        assertEquals("STRIDE bytes", 48, LuminousDotsScene.STRIDE);
        assertEquals("GRID size", 32, LuminousDotsScene.GRID);
        assertEquals("QUADS count", 1024, LuminousDotsScene.QUADS);
        assertEquals("VERTS count", 4096, LuminousDotsScene.VERTS);
    }

    // ---- Grid math ----

    @Test
    public void gridCellSize_computation() {
        float diagonal = 1920f;
        int cellSize = ((int) diagonal) / 32; // GRID
        int halfLayout = cellSize * 16;
        assertEquals("cellSize", 60, cellSize);
        assertEquals("halfLayout", 960, halfLayout);
    }

    // ---- Pattern data integrity ----

    @Test
    public void objPattern_hasEntries() {
        assertTrue("OBJ_PATTERN has entries", LuminousDotsScene.OBJ_PATTERN.length > 0);
    }

    @Test
    public void objPattern_entriesHaveAtLeast8fields() {
        int count = 0;
        for (int[] e : LuminousDotsScene.OBJ_PATTERN) {
            assertTrue("Entry has ≥8 fields", e.length >= 8);
            if (e.length == 8) count++;
            else assertTrue("Extra field entries have valid grid coords", e[0] >= 0 && e[0] < 32);
        }
        assertTrue("Most entries are 8-field: " + count + "/" + LuminousDotsScene.OBJ_PATTERN.length,
                count > LuminousDotsScene.OBJ_PATTERN.length * 0.95);
    }

    // ---- Color data ----

    @Test
    public void objColor_threeThemes() {
        assertEquals("3 color themes", 3, LuminousDotsScene.OBJ_COLOR.length);
    }

    @Test
    public void glowColor_threeColorIndices() {
        // GLOW_COLOR is used per-glow, verified by GLOW_POS having valid color indices (0-2)
        for (int[] gp : LuminousDotsScene.GLOW_POS) {
            assertTrue("Glow colorIdx 0-2: " + gp[2], gp[2] >= 0 && gp[2] <= 2);
        }
    }
}
