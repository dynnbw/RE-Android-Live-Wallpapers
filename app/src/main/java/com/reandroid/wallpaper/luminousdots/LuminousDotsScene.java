package com.reandroid.wallpaper.luminousdots;

import android.content.SharedPreferences;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Pure-logic scene for Samsung LuminousDots live wallpaper.
 * Ported 100% from original Samsung source code.
 * No Android/GL dependencies — only SharedPreferences for settings.
 */
final class LuminousDotsScene {
    static final int GRID = 32;
    static final int QUADS = 1024;
    static final int VERTS = 4096;
    static final int ATTRS = 12;
    static final int STRIDE = 48;

    // Rotation types: {angle, axisX, axisY, axisZ}
    static final float[][] ROTATE_TYPE = {
        {180f, 1f, 0f, 0f},
        {0f,   1f, 0f, 0f},
        {180f, 0f, 1f, 0f},
        {180f, 0f, 0f, 1f}
    };

    // 8 rotate index pairs (rotateArrIdx)
    static final int[][] ROTATE_IDX = {
        {0, 1}, {2, 1}, {2, 3}, {1, 3},
        {1, 0}, {3, 0}, {3, 2}, {0, 2}
    };

    // Camera 8-point cycle path (arrTimeRotateXY)
    static final int[][] CAM_PATH = {
        { 25,  25}, { 50,   0}, { 25, -25}, {  0, -50},
        {-25, -25}, {-50,   0}, {-25,  25}, {  0,  50}
    };

    // 10 glow positions: {gridX, gridY, glowColorIdx, alphaLevel, unused, startMs, endMs}
    static final int[][] GLOW_POS = {
        { 7,  1, 1, 2, 0,  3000, 11000},
        {17,  4, 2, 2, 0,  5200, 13700},
        {22,  3, 0, 2, 0,  3600, 10700},
        { 6, 17, 2, 2, 0,  1800,  9700},
        {12,  9, 0, 2, 0,  3000, 10000},
        {17, 11, 0, 2, 0,  4200, 13700},
        {22, 13, 2, 2, 0,  2200, 11700},
        {23, 18, 1, 2, 0,  1200,  7700},
        {21, 22, 1, 2, 0,  4000, 12000},
        {10, 24, 2, 2, 0,  2000,  8600}
    };

    // --- Color data from ConstGraphic ---

    // objColor[theme][colorIdx] = {r, g, b, a}
    // Theme 0 "Urban" (blue-ish, 16 colors)
    private static final float[][] OBJ_COLOR_0 = {
        {0.408f, 0.753f, 0.858f, 1.0f},
        {0.462f, 0.227f, 0.874f, 1.0f},
        {0.016f, 0.016f, 0.016f, 1.0f},
        {0.141f, 0.224f, 0.926f, 1.0f},
        {0.016f, 0.094f, 0.149f, 1.0f},
        {0.082f, 0.047f, 0.259f, 1.0f},
        {0.2f,   0.2f,   0.2f,   1.0f},
        {0.016f, 0.016f, 0.016f, 1.0f},
        {0.102f, 0.22f,  0.4f,   1.0f},
        {0.133f, 0.067f, 0.196f, 0.5f},
        {0.086f, 0.122f, 0.294f, 1.0f},
        {0.075f, 0.094f, 0.18f,  1.0f},
        {0.102f, 0.18f,  0.376f, 1.0f},
        {0.047f, 0.121f, 0.173f, 1.0f},
    };

    // Theme 1 "Natural" (green-ish, 5 colors)
    private static final float[][] OBJ_COLOR_1 = {
        {0.633f, 0.888f, 0.067f, 1.0f},
        {0.42f,  0.812f, 0.106f, 1.0f},
        {0.811f, 0.809f, 0.007f, 1.0f},
        {0.518f, 0.831f, 0.016f, 1.0f},
        {0.365f, 0.906f, 0.035f, 0.7f},
    };

    // Theme 2 "Luxury" (gold/orange, 5 colors)
    private static final float[][] OBJ_COLOR_2 = {
        {0.882f, 0.419f, 0.125f, 1.0f},
        {0.925f, 0.616f, 0.07f,  1.0f},
        {0.745f, 0.58f,  0.12f,  1.0f},
        {0.914f, 0.663f, 0.063f, 1.0f},
        {0.921f, 0.45f,  0.133f, 1.0f},
    };

    static final float[][][] OBJ_COLOR = {OBJ_COLOR_0, OBJ_COLOR_1, OBJ_COLOR_2};

    // glowColor[theme][colorIdx] = {r, g, b, a}
    // For all themes (3×3)
    private static final float[][][] GLOW_COLOR = {
        { // Theme 0 Urban
            {0.525f, 0.698f, 0.772f, 0.6f},
            {0.643f, 0.659f, 0.0f,   1.0f},
            {0.745f, 0.647f, 0.0f,   1.0f},
        },
        { // Theme 1 Natural
            {0.016f, 0.016f, 0.016f, 1.0f},
            {0.47f,  0.76f,  0.364f, 1.0f},
            {0.327f, 0.431f, 0.086f, 1.0f},
        },
        { // Theme 2 Luxury
            {0.161f, 0.486f, 0.682f, 1.0f},
            {0.694f, 0.631f, 0.003f, 1.0f},
            {0.662f, 0.388f, 0.0f,   1.0f},
        },
    };

    // --- Random pattern shape templates from ConstGraphic ---
    private static final int[][] RANDOM_PATTERN_0 = {
        {50},
        {1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,32,33,34,35,36,37,38,39,40,41,42,43,44,45,46,47,48,49,50}
    };
    private static final int[][] RANDOM_PATTERN_1 = {
        {50},
        {1},{2},{3},{4},{5},{6},{7},{8},{9},{10},{11},{12},{13},{14},{15},{16},{17},{18},{19},{20},
        {21},{22},{23},{24},{25},{26},{27},{28},{29},{30},{31},{32},{33},{34},{35},{36},{37},{38},{39},{40},
        {41},{42},{43},{44},{45},{46},{47},{48},{49},{50}
    };
    private static final int[][] RANDOM_PATTERN_4 = {
        {48},
        {10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,0,0,0,0,0,0,0,0,0,0},
        {9,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,30,0,0,0,0,0,0,0,0,0,0},
        {8,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,31,0,0,0,0,0,0,0,0,0,0},
        {7,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,32,0,0,0,0,0,0,0,0,0,0},
        {6,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,33,0,0,0,0,0,0,0,0,0,0},
        {5,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,34,0,0,0,0,0,0,0,0,0,0},
        {4,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,35,0,0,0,0,0,0,0,0,0,0},
        {3,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,36,0,0,0,0,0,0,0,0,0,0},
        {2,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,37,0,0,0,0,0,0,0,0,0,0},
        {1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,38,39,40,41,42,43,44,45,46,47,48}
    };
    private static final int[][] RANDOM_PATTERN_5 = {
        {48},
        {1,2,3,4,5,6,7,8,9,10,11,12,13,14,15},
        {0,0,0,0,0,0,0,0,0,0,0,0,0,0,16},
        {0,0,0,0,0,0,0,0,0,0,0,0,0,0,17},
        {0,0,0,0,0,0,0,0,0,0,0,0,0,0,18},
        {0,0,0,0,0,0,0,0,0,0,0,0,0,0,19},
        {0,0,0,0,0,0,0,0,0,0,0,0,0,0,20},
        {0,0,0,0,0,0,0,0,0,0,0,0,0,0,21},
        {0,0,0,0,0,0,0,0,0,0,0,0,0,0,22},
        {0,0,0,0,0,0,0,0,0,0,0,0,0,0,23},
        {0,0,0,0,0,0,0,0,0,0,0,0,0,0,24},
        {39,38,37,36,35,34,33,32,31,30,29,28,27,26,25},
        {40,0,0,0,0,0,0,0,0,0,0,0,0,0,0},
        {41,0,0,0,0,0,0,0,0,0,0,0,0,0,0},
        {42,0,0,0,0,0,0,0,0,0,0,0,0,0,0},
        {43,0,0,0,0,0,0,0,0,0,0,0,0,0,0},
        {44,0,0,0,0,0,0,0,0,0,0,0,0,0,0},
        {45,0,0,0,0,0,0,0,0,0,0,0,0,0,0},
        {46,0,0,0,0,0,0,0,0,0,0,0,0,0,0},
        {47,0,0,0,0,0,0,0,0,0,0,0,0,0,0},
        {48,0,0,0,0,0,0,0,0,0,0,0,0,0,0},
    };

    // Obj shape patterns 2-5
    private static final int[][] PATTERN_2 = {{5}, {1,2,3,4,5}};
    private static final int[][] PATTERN_3 = {{5}, {5,4,3,2,1}};
    private static final int[][] PATTERN_4 = {{5}, {1}, {2}, {3}, {4}, {5}};
    private static final int[][] PATTERN_5 = {{5}, {5}, {4}, {3}, {2}, {1}};

    // --- Pattern data from ConstGraphicPattern_UR ---

    // OBJ_PATTERN: 255 entries, each {gridX, gridY, colorIdx, alphaLv, shapePatternIdx, startTime, endTime, isReverse}
    static final int[][] OBJ_PATTERN = {
        {1, 1, 0, 0, 4, 0, 0, 0},
        {12, 0, 1, 1, 3, 0, 0, 1},
        {11, 0, 0, 4, 3, 0, 0, 0},
        {21, 3, 1, 1, 4, 0, 0, 0},
        {24, 2, 10, 2, 3, 0, 0, 0},
        {25, 1, 0, 3, 3, 3600, 10100, 0},
        {27, 0, 0, 1, 3, 0, 0, 0},
        {29, 0, 0, 4, 3, 3300, 11100, 0},
        {1, 6, 0, 2, 4, 2100, 9100, 0},
        {6, 7, 7, 5, 4, 0, 0, 1},
        {6, 5, 12, 2, 4, 800, 8800, 1},
        {6, 4, 0, 3, 5, 0, 0, 1},
        {11, 4, 1, 2, 2, 0, 0, 1},
        {13, 4, 0, 2, 3, 0, 0, 0},
        {16, 6, 0, 2, 4, 0, 0, 0},
        {17, 4, 1, 0, 3, 0, 0, 1},
        {18, 4, 1, 1, 2, 0, 0, 0},
        {20, 4, 1, 1, 2, 0, 0, 0},
        {21, 4, 0, 1, 3, 0, 0, 1},
        {28, 4, 0, 1, 2, 0, 0, 0},
        {29, 4, 7, 5, 3, 0, 0, 0},
        {30, 4, 0, 1, 3, 0, 0, 0},
        {2, 10, 12, 4, 2, 0, 0, 0},
        {1, 9, 1, 1, 2, 0, 0, 1},
        {3, 10, 0, 2, 4, 0, 0, 1},
        {6, 13, 0, 0, 2, 0, 0, 1},
        {11, 13, 12, 0, 5, 0, 0, 1},
        {11, 12, 7, 4, 4, 0, 0, 0},
        {16, 12, 14, 1, 5, 0, 0, 0},
        {16, 10, 0, 1, 5, 0, 0, 0},
        {16, 9, 1, 1, 4, 0, 0, 1},
        {19, 11, 0, 1, 5, 0, 0, 0},
        {26, 9, 1, 0, 3, 0, 0, 1},
        {27, 9, 12, 1, 3, 0, 0, 0},
        {28, 9, 14, 2, 3, 0, 0, 0},
        {1, 18, 0, 0, 5, 0, 0, 1},
        {1, 16, 0, 2, 4, 0, 0, 0},
        {6, 14, 1, 1, 5, 0, 0, 1},
        {15, 14, 0, 1, 3, 0, 0, 1},
        {16, 18, 1, 2, 5, 0, 0, 1},
        {16, 17, 0, 1, 4, 0, 0, 0},
        {16, 16, 1, 2, 4, 0, 0, 0},
        {16, 13, 2, 2, 3, 1600, 9600, 0},
        {21, 17, 7, 5, 5, 0, 0, 0},
        {21, 14, 0, 1, 5, 0, 0, 1},
        {26, 18, 1, 0, 5, 0, 0, 1},
        {26, 17, 0, 1, 4, 0, 0, 0},
        {26, 16, 0, 2, 4, 0, 0, 0},
        {27, 14, 0, 2, 4, 0, 0, 0},
        {0, 21, 0, 1, 5, 0, 0, 0},
        {0, 19, 1, 0, 5, 0, 0, 1},
        {6, 19, 0, 1, 2, 0, 0, 0},
        {7, 19, 1, 1, 2, 0, 0, 0},
        {9, 19, 0, 0, 2, 0, 0, 0},
        {15, 22, 0, 1, 5, 0, 0, 0},
        {27, 23, 1, 1, 5, 0, 0, 1},
        {26, 20, 0, 1, 2, 0, 0, 0},
        {31, 15, 0, 1, 3, 0, 0, 0},
        {31, 9, 0, 1, 2, 0, 0, 0},
        {1, 28, 0, 3, 5, 0, 0, 0},
        {1, 25, 1, 1, 4, 0, 0, 0},
        {6, 26, 0, 2, 4, 0, 0, 0},
        {11, 27, 14, 3, 5, 0, 0, 0},
        {10, 22, 0, 0, 5, 0, 0, 0},
        {16, 28, 0, 1, 5, 0, 0, 1},
        {15, 27, 1, 0, 5, 0, 0, 0},
        {15, 25, 7, 5, 5, 0, 0, 0},
        {16, 24, 0, 1, 5, 0, 0, 0},
        {21, 28, 1, 1, 5, 0, 0, 1},
        {21, 26, 12, 2, 4, 0, 0, 0},
        {21, 25, 0, 1, 4, 0, 0, 0},
        {26, 28, 0, 1, 5, 1400, 9900, 1},
        {26, 27, 0, 1, 4, 1300, 9900, 0},
        {0, 30, 0, 1, 2, 2600, 12500, 0},
        {10, 29, 1, 1, 5, 0, 0, 1},
        {15, 31, 7, 3, 4, 0, 0, 1},
        {15, 30, 0, 2, 5, 4600, 10600, 0},
        {20, 30, 0, 1, 4, 0, 0, 0},
        {20, 29, 0, 1, 4, 0, 0, 0},
        {21, 28, 0, 1, 5, 0, 0, 0},
        {25, 28, 0, 2, 5, 2700, 10800, 1},
        {25, 29, 1, 2, 5, 2600, 10800, 0},
        {27, 24, 5, 1, 5, 0, 0, 1},
        {27, 26, 5, 2, 5, 0, 0, 1},
        // Index 4+
        {1, 0, 8, 3, 5, 0, 0, 1},
        {6, 3, 0, 0, 4, 1600, 8100, 0},
        {6, 2, 7, 5, 4, 1500, 8100, 0},
        {6, 1, 1, 0, 4, 0, 0, 0},
        {6, 0, 2, 4, 5, 0, 0, 1},
        {13, 0, 0, 3, 3, 0, 0, 0},
        {12, 0, 1, 1, 3, 0, 0, 1},
        {11, 0, 0, 4, 3, 0, 0, 0},
        {19, 0, 1, 1, 3, 0, 0, 0},
        {18, 0, 10, 2, 3, 0, 0, 0},
        {17, 0, 1, 1, 3, 0, 0, 0},
        {16, 0, 1, 1, 3, 0, 0, 1},
        {21, 3, 1, 1, 4, 0, 0, 0},
        {24, 2, 10, 2, 3, 0, 0, 0},
        {25, 1, 8, 3, 3, 3600, 10100, 0},
        {27, 0, 0, 1, 3, 0, 0, 0},
        {29, 0, 1, 4, 3, 3300, 11100, 0},
        {28, 0, 1, 2, 3, 0, 0, 0},
        {26, 0, 1, 2, 2, 3600, 10100, 0},
        {30, 0, 1, 2, 2, 3400, 11100, 0},
        {31, 0, 1, 2, 3, 2200, 8800, 0},
        {0, 4, 0, 3, 2, 1500, 10500, 0},
        {1, 8, 0, 2, 5, 0, 0, 1},
        {1, 7, 7, 4, 4, 0, 0, 0},
        {1, 6, 0, 2, 4, 2100, 9100, 0},
        {1, 5, 0, 1, 4, 2000, 9100, 0},
        {1, 4, 0, 2, 5, 0, 0, 1},
        {6, 8, 0, 2, 5, 0, 0, 1},
        {6, 7, 7, 5, 4, 0, 0, 1},
        {6, 6, 1, 1, 4, 900, 8800, 1},
        {6, 5, 12, 2, 4, 800, 8800, 1},
        {6, 4, 0, 3, 5, 0, 0, 1},
        {11, 4, 1, 2, 2, 0, 0, 1},
        {13, 4, 0, 2, 3, 0, 0, 0},
        {14, 4, 0, 1, 3, 0, 0, 0},
        {12, 4, 0, 1, 3, 0, 0, 0},
        {11, 4, 0, 1, 3, 0, 0, 1},
        {16, 8, 0, 0, 5, 0, 0, 1},
        {16, 7, 1, 1, 4, 0, 0, 0},
        {16, 6, 0, 2, 4, 0, 0, 0},
        {16, 5, 1, 1, 4, 0, 0, 0},
        {16, 4, 0, 1, 5, 0, 0, 1},
        {17, 4, 1, 0, 3, 0, 0, 1},
        {18, 4, 1, 1, 2, 0, 0, 0},
        {19, 4, 14, 3, 2, 0, 0, 0},
        {20, 4, 1, 1, 2, 0, 0, 0},
        {21, 4, 0, 1, 3, 0, 0, 1},
        {22, 4, 1, 0, 2, 0, 0, 1},
        {23, 4, 0, 1, 2, 0, 0, 0},
        {24, 4, 1, 2, 3, 0, 0, 0},
        {25, 4, 14, 1, 3, 0, 0, 0},
        {26, 4, 0, 2, 2, 0, 0, 1},
        {27, 4, 0, 2, 2, 0, 0, 1},
        {28, 4, 0, 1, 2, 0, 0, 0},
        {29, 4, 7, 5, 3, 0, 0, 0},
        {30, 4, 0, 1, 3, 0, 0, 0},
        {31, 4, 2, 2, 3, 800, 7100, 1},
        {0, 9, 0, 4, 3, 2000, 10500, 0},
        {5, 13, 1, 2, 3, 0, 0, 1},
        {4, 12, 12, 3, 2, 0, 0, 0},
        {3, 11, 0, 2, 2, 0, 0, 0},
        {2, 10, 12, 4, 2, 0, 0, 0},
        {1, 9, 1, 1, 2, 0, 0, 1},
        {2, 9, 0, 2, 4, 0, 0, 1},
        {3, 10, 0, 2, 4, 0, 0, 1},
        {4, 11, 1, 3, 4, 0, 0, 1},
        {5, 12, 0, 0, 4, 0, 0, 1},
        {6, 13, 0, 0, 2, 0, 0, 1},
        {7, 12, 7, 4, 3, 0, 0, 0},
        {8, 11, 1, 2, 3, 0, 0, 0},
        {9, 10, 0, 4, 3, 0, 0, 0},
        {10, 9, 1, 1, 2, 0, 0, 1},
        {11, 13, 12, 0, 5, 0, 0, 1},
        {11, 12, 7, 4, 4, 0, 0, 0},
        {11, 11, 0, 1, 4, 0, 0, 0},
        {11, 10, 8, 3, 4, 0, 0, 0},
        {11, 9, 1, 1, 5, 0, 0, 1},
        {16, 13, 0, 0, 4, 0, 0, 1},
        {16, 12, 14, 1, 5, 0, 0, 0},
        {16, 11, 14, 2, 5, 0, 0, 0},
        {16, 10, 0, 1, 5, 0, 0, 0},
        {16, 9, 1, 1, 4, 0, 0, 1},
        {21, 13, 1, 1, 4, 0, 0, 1},
        {21, 12, 1, 1, 5, 0, 0, 1},
        {19, 11, 0, 1, 5, 0, 0, 0},
        {19, 10, 7, 4, 4, 0, 0, 0},
        {19, 9, 0, 1, 4, 0, 0, 0},
        {26, 9, 1, 0, 3, 0, 0, 1},
        {27, 9, 12, 1, 3, 0, 0, 0},
        {28, 9, 14, 2, 3, 0, 0, 0},
        {29, 9, 0, 2, 3, 0, 0, 1},
        {30, 9, 7, 4, 3, 0, 0, 1},
        {0, 14, 1, 0, 2, 2500, 10500, 1},
        {1, 18, 0, 0, 5, 0, 0, 1},
        {1, 17, 1, 1, 4, 0, 0, 0},
        {1, 16, 0, 2, 4, 0, 0, 0},
        {1, 15, 1, 1, 4, 0, 0, 0},
        {1, 14, 1, 1, 5, 0, 0, 1},
        {6, 18, 0, 0, 5, 0, 0, 1},
        {6, 17, 0, 1, 4, 0, 0, 0},
        {6, 16, 7, 4, 4, 0, 0, 0},
        {6, 15, 0, 1, 4, 0, 0, 0},
        {6, 14, 1, 1, 5, 0, 0, 1},
        {11, 16, 1, 0, 3, 0, 0, 1},
        {12, 15, 8, 2, 2, 0, 0, 1},
        {13, 14, 0, 2, 2, 0, 0, 1},
        {14, 14, 1, 1, 2, 0, 0, 1},
        {15, 14, 0, 1, 3, 0, 0, 1},
        {12, 14, 2, 4, 4, 0, 0, 0},
        {16, 18, 1, 2, 5, 0, 0, 1},
        {16, 17, 1, 1, 4, 0, 0, 0},
        {16, 16, 1, 2, 4, 0, 0, 0},
        {16, 15, 0, 1, 4, 0, 0, 0},
        {16, 14, 1, 2, 5, 0, 0, 1},
        {16, 13, 2, 2, 3, 1600, 9600, 0},
        {13, 13, 0, 1, 4, 1700, 9600, 0},
        {21, 18, 0, 2, 5, 0, 0, 1},
        {21, 17, 7, 5, 5, 0, 0, 0},
        {21, 16, 0, 2, 5, 0, 0, 0},
        {21, 15, 1, 1, 5, 0, 0, 0},
        {21, 14, 0, 1, 5, 0, 0, 1},
        {26, 18, 1, 0, 5, 0, 0, 1},
        {26, 17, 0, 1, 4, 0, 0, 0},
        {26, 16, 0, 2, 4, 0, 0, 0},
        {26, 15, 0, 1, 4, 0, 0, 0},
        {27, 14, 0, 2, 4, 0, 0, 0},
        {26, 13, 1, 2, 4, 0, 0, 0},
        {5, 17, 0, 0, 3, 0, 0, 0},
        {6, 16, 0, 1, 3, 0, 0, 0},
        {7, 15, 0, 1, 3, 0, 0, 0},
        {0, 23, 0, 1, 5, 6600, 15500, 0},
        {0, 22, 0, 1, 5, 6500, 15500, 0},
        {0, 21, 0, 1, 5, 0, 0, 0},
        {0, 20, 0, 2, 5, 0, 0, 1},
        {0, 19, 1, 0, 5, 0, 0, 1},
        {5, 20, 0, 3, 3, 0, 0, 0},
        {6, 19, 0, 1, 2, 0, 0, 0},
        {7, 19, 1, 1, 2, 0, 0, 0},
        {8, 19, 7, 4, 2, 0, 0, 0},
        {9, 19, 0, 0, 2, 0, 0, 0},
        {10, 17, 0, 2, 2, 0, 0, 1},
        {10, 23, 0, 0, 5, 0, 0, 1},
        {10, 22, 1, 1, 4, 0, 0, 1},
        {11, 21, 8, 1, 4, 0, 0, 0},
        {12, 20, 0, 1, 4, 0, 0, 0},
        {13, 19, 1, 1, 5, 0, 0, 0},
        {15, 23, 0, 1, 5, 0, 0, 0},
        {15, 22, 0, 1, 5, 0, 0, 0},
        {16, 21, 1, 1, 5, 0, 0, 0},
        {17, 20, 0, 1, 5, 0, 0, 0},
        {18, 19, 0, 1, 4, 0, 0, 1},
        {20, 23, 0, 1, 5, 0, 0, 1},
        {20, 22, 7, 5, 4, 0, 0, 1},
        {21, 21, 0, 2, 3, 0, 0, 1},
        {22, 20, 0, 1, 3, 0, 0, 1},
        {23, 19, 0, 1, 5, 0, 0, 1},
        {27, 23, 1, 1, 5, 0, 0, 1},
        {27, 22, 0, 2, 4, 0, 0, 1},
        {27, 21, 14, 2, 4, 0, 0, 1},
        {27, 20, 0, 1, 4, 0, 0, 1},
        {27, 19, 12, 1, 5, 0, 0, 1},
        {25, 19, 1, 1, 2, 0, 0, 0},
        {26, 20, 0, 1, 2, 0, 0, 0},
        {23, 18, 0, 2, 2, 1600, 9800, 0},
        {24, 18, 0, 1, 2, 1300, 9800, 0},
        {31, 15, 0, 1, 3, 0, 0, 0},
        {31, 9, 0, 1, 2, 0, 0, 0},
        {0, 24, 0, 3, 2, 0, 0, 0},
        {1, 28, 0, 3, 5, 0, 0, 0},
        {1, 27, 1, 1, 4, 0, 0, 0},
        {1, 26, 7, 3, 4, 0, 0, 0},
        {1, 25, 1, 1, 4, 0, 0, 0},
        {0, 24, 0, 1, 5, 0, 0, 0},
        {6, 28, 0, 1, 5, 0, 0, 1},
        {6, 27, 7, 4, 4, 0, 0, 0},
        {6, 26, 0, 2, 4, 0, 0, 0},
        {6, 25, 1, 1, 4, 0, 0, 0},
        {6, 24, 0, 1, 5, 0, 0, 1},
        {11, 28, 0, 1, 5, 0, 0, 1},
        {11, 27, 14, 3, 5, 0, 0, 0},
        {11, 26, 0, 1, 5, 0, 0, 0},
        {11, 25, 12, 4, 5, 0, 0, 0},
        {10, 22, 0, 0, 5, 0, 0, 0},
        {10, 23, 0, 2, 5, 0, 0, 1},
        {9, 24, 0, 2, 5, 0, 0, 1},
        {16, 28, 0, 1, 5, 0, 0, 1},
        {15, 27, 1, 0, 5, 0, 0, 0},
        {15, 26, 0, 0, 5, 0, 0, 0},
        {15, 25, 7, 5, 5, 0, 0, 0},
        {16, 24, 0, 1, 5, 0, 0, 0},
        {21, 28, 1, 1, 5, 0, 0, 1},
        {21, 27, 0, 1, 4, 0, 0, 0},
        {21, 26, 12, 2, 4, 0, 0, 0},
        {21, 25, 0, 1, 4, 0, 0, 0},
        {21, 24, 1, 1, 5, 0, 0, 1},
        {26, 28, 0, 1, 5, 1400, 9900, 1},
        {26, 27, 0, 1, 4, 1300, 9900, 0},
        {26, 26, 7, 4, 4, 0, 0, 0},
        {26, 25, 1, 1, 4, 0, 0, 0},
        {26, 24, 1, 1, 5, 0, 0, 1},
        {0, 31, 0, 2, 2, 2700, 12500, 0},
        {0, 30, 0, 1, 2, 2600, 12500, 0},
        {0, 29, 0, 2, 3, 0, 0, 1},
        {5, 31, 0, 2, 3, 0, 0, 0, 0},
        {5, 30, 7, 4, 3, 3300, 9500, 0},
        {5, 29, 0, 2, 3, 3200, 9500, 1},
        {10, 31, 0, 1, 4, 0, 0, 0},
        {10, 30, 0, 1, 4, 0, 0, 0},
        {10, 29, 1, 1, 5, 0, 0, 1},
        {15, 31, 7, 3, 4, 0, 0, 1},
        {15, 30, 0, 2, 5, 4600, 10600, 0},
        {15, 29, 0, 1, 5, 4700, 10600, 0},
        {20, 31, 1, 2, 5, 0, 0, 1},
        {20, 30, 0, 1, 4, 0, 0, 0},
        {20, 29, 0, 1, 4, 0, 0, 0},
        {25, 31, 7, 5, 4, 0, 0, 1},
        {25, 30, 0, 3, 4, 0, 0, 1},
        {25, 29, 0, 2, 5, 0, 0, 1},
        {20, 20, 7, 5, 3, 0, 0, 1},
        {21, 24, 0, 1, 3, 4600, 11500, 0},
        {22, 25, 8, 2, 3, 4500, 11500, 0},
        {21, 28, 0, 1, 5, 0, 0, 0},
        {25, 28, 0, 2, 5, 2700, 10800, 1},
        {25, 29, 1, 2, 5, 2600, 10800, 0},
        {19, 29, 0, 2, 5, 2600, 10800, 1},
        {20, 25, 2, 1, 2, 0, 1600, 10100, 1},
        {30, 27, 0, 1, 3, 1500, 9800, 0},
        {31, 27, 2, 5, 3, 1400, 9800, 0},
        {31, 22, 0, 1, 2, 0, 0, 0},
        {6, 2, 0, 2, 4, 0, 0, 1},
        {6, 1, 0, 2, 5, 0, 0, 1},
        {11, 2, 0, 2, 4, 0, 0, 1},
        {11, 1, 0, 2, 5, 0, 0, 1},
        {11, 4, 0, 2, 4, 600, 8800, 0},
        {10, 5, 0, 2, 4, 700, 8800, 0},
        {8, 6, 0, 2, 4, 0, 0, 0},
        {22, 24, 0, 1, 5, 0, 0, 1},
        {27, 24, 5, 1, 5, 0, 0, 1},
        {22, 25, 0, 2, 5, 0, 0, 1},
        {27, 25, 5, 2, 5, 0, 0, 1},
        {22, 26, 0, 2, 5, 0, 0, 1},
        {27, 26, 5, 2, 5, 0, 0, 1},
        {22, 27, 0, 1, 5, 0, 0, 1},
        {27, 27, 5, 1, 5, 0, 0, 1},
    };

    // OBJ_PATTERN_DOWN: 223 entries for the DOWN layer (original uses different pattern set for mMDownPattern)
    static final int[][] OBJ_PATTERN_DOWN = {
        {1, 3, 0, 1, 4, 4600, 13800, 0},
        {6, 3, 0, 1, 4, 0, 0, 0},
        {6, 2, 0, 2, 4, 0, 0, 0},
        {6, 1, 5, 1, 4, 0, 0, 0},
        {6, 0, 5, 1, 5, 0, 0, 1},
        {15, 0, 0, 2, 3, 2400, 10500, 0},
        {14, 0, 0, 3, 3, 1800, 10500, 0},
        {13, 0, 0, 2, 3, 2200, 10500, 0},
        {12, 0, 5, 2, 3, 0, 0, 1},
        {11, 0, 0, 2, 3, 0, 0, 0},
        {19, 0, 5, 1, 3, 0, 0, 0},
        {18, 0, 0, 2, 3, 1300, 10500, 0},
        {17, 0, 0, 1, 3, 1200, 10500, 0},
        {16, 0, 20, 1, 3, 0, 0, 1},
        {21, 3, 5, 1, 4, 0, 0, 0},
        {21, 2, 5, 2, 4, 0, 0, 0},
        {21, 1, 0, 2, 4, 0, 0, 0},
        {21, 0, 0, 2, 5, 0, 0, 1},
        {23, 0, 0, 2, 3, 0, 0, 1},
        {24, 0, 0, 3, 3, 0, 0, 1},
        {25, 0, 1, 2, 3, 0, 0, 1},
        {26, 0, 3, 2, 2, 0, 0, 1},
        {27, 0, 7, 5, 3, 0, 0, 1},
        {28, 0, 7, 4, 3, 0, 0, 1},
        {29, 0, 12, 4, 3, 0, 0, 1},
        {30, 0, 14, 2, 2, 0, 0, 1},
        {31, 0, 8, 2, 3, 0, 0, 1},
        {0, 4, 0, 3, 2, 0, 0, 0},
        {1, 8, 20, 0, 5, 0, 0, 1},
        {1, 7, 0, 1, 4, 0, 0, 0},
        {1, 6, 0, 2, 4, 0, 0, 0},
        {1, 5, 1, 1, 4, 0, 0, 0},
        {1, 4, 20, 1, 5, 0, 0, 1},
        {6, 8, 5, 1, 5, 0, 0, 1},
        {6, 7, 1, 2, 4, 0, 0, 1},
        {6, 6, 1, 3, 4, 0, 0, 1},
        {6, 5, 0, 2, 4, 0, 0, 1},
        {6, 4, 0, 2, 5, 0, 0, 1},
        {11, 4, 0, 2, 2, 0, 0, 1},
        {14, 4, 0, 1, 3, 0, 0, 0},
        {13, 4, 1, 2, 3, 0, 0, 0},
        {12, 4, 5, 1, 3, 0, 0, 0},
        {11, 4, 20, 1, 3, 0, 0, 1},
        {16, 8, 1, 0, 5, 0, 0, 1},
        {16, 7, 1, 1, 4, 0, 0, 0},
        {16, 6, 1, 2, 4, 0, 0, 0},
        {16, 5, 1, 1, 4, 0, 0, 0},
        {16, 4, 5, 1, 5, 0, 0, 1},
        {15, 5, 0, 3, 3, 0, 0, 1},
        {17, 4, 8, 2, 3, 0, 0, 1},
        {18, 4, 8, 2, 2, 0, 0, 0},
        {19, 4, 7, 3, 2, 0, 0, 0},
        {20, 4, 7, 2, 2, 0, 0, 0},
        {21, 4, 7, 2, 3, 0, 0, 1},
        {22, 4, 7, 3, 2, 0, 0, 1},
        {23, 4, 1, 1, 2, 0, 0, 0},
        {24, 4, 0, 2, 3, 0, 0, 0},
        {25, 4, 1, 1, 3, 0, 0, 0},
        {26, 4, 2, 2, 2, 0, 0, 1},
        {27, 4, 7, 3, 2, 0, 0, 1},
        {28, 4, 7, 2, 2, 0, 0, 1},
        {29, 4, 0, 2, 3, 0, 0, 1},
        {30, 4, 1, 2, 3, 0, 0, 1},
        {31, 4, 8, 2, 3, 0, 0, 1},
        {0, 9, 0, 3, 2, 5500, 13500, 0},
        {5, 9, 0, 2, 3, 0, 0, 1},
        {4, 9, 0, 1, 2, 6400, 16000, 0},
        {3, 9, 0, 2, 2, 6300, 16000, 0},
        {2, 9, 7, 1, 2, 0, 0, 0},
        {1, 9, 1, 2, 2, 0, 0, 1},
        {6, 13, 1, 0, 2, 0, 0, 1},
        {7, 12, 1, 1, 3, 0, 0, 0},
        {8, 11, 1, 2, 3, 0, 0, 0},
        {9, 10, 0, 1, 3, 0, 0, 0},
        {10, 9, 0, 2, 2, 0, 0, 1},
        {11, 13, 7, 3, 5, 0, 0, 1},
        {11, 12, 1, 0, 4, 0, 0, 0},
        {11, 11, 1, 1, 4, 0, 0, 0},
        {11, 10, 1, 1, 4, 0, 0, 1},
        {11, 9, 7, 3, 5, 0, 0, 1},
        {16, 13, 8, 0, 4, 0, 0, 1},
        {16, 12, 1, 1, 5, 0, 0, 0},
        {16, 11, 0, 2, 5, 0, 0, 0},
        {16, 10, 7, 1, 5, 0, 0, 0},
        {16, 9, 7, 1, 4, 0, 0, 1},
        {21, 13, 1, 1, 4, 0, 0, 1},
        {21, 12, 1, 1, 5, 0, 0, 1},
        {19, 11, 1, 1, 5, 0, 0, 0},
        {19, 10, 0, 2, 4, 0, 0, 0},
        {19, 9, 5, 1, 4, 0, 0, 0},
        {26, 9, 1, 0, 3, 0, 0, 1},
        {27, 9, 1, 1, 3, 7400, 15500, 0},
        {28, 9, 1, 2, 3, 7300, 15500, 0},
        {29, 9, 0, 2, 3, 4400, 11400, 1},
        {30, 9, 0, 2, 3, 4300, 11400, 1},
        {0, 14, 1, 0, 2, 0, 0, 0},
        {1, 18, 0, 0, 5, 0, 0, 1},
        {1, 17, 1, 1, 4, 0, 0, 0},
        {1, 16, 1, 2, 4, 0, 0, 0},
        {1, 15, 1, 1, 4, 0, 0, 0},
        {1, 14, 0, 1, 5, 0, 0, 1},
        {6, 18, 5, 3, 5, 0, 0, 1},
        {6, 17, 0, 1, 4, 0, 0, 0},
        {6, 16, 0, 2, 4, 0, 0, 0},
        {6, 15, 0, 1, 4, 0, 0, 0},
        {6, 14, 5, 2, 5, 0, 0, 1},
        {11, 16, 5, 0, 3, 0, 0, 1},
        {12, 15, 5, 2, 2, 0, 0, 1},
        {13, 14, 1, 2, 2, 0, 0, 1},
        {14, 14, 0, 1, 2, 0, 0, 1},
        {15, 14, 0, 1, 3, 0, 0, 1},
        {12, 14, 0, 1, 4, 5600, 11700, 0},
        {16, 18, 0, 2, 5, 0, 0, 1},
        {16, 17, 0, 1, 4, 0, 0, 0},
        {16, 16, 1, 2, 4, 0, 0, 0},
        {16, 15, 5, 1, 4, 0, 0, 0},
        {16, 14, 5, 2, 5, 0, 0, 1},
        {16, 13, 2, 2, 3, 9600, 17100, 0},
        {13, 13, 0, 1, 4, 9700, 17100, 0},
        {21, 18, 20, 2, 5, 0, 0, 1},
        {21, 17, 5, 1, 5, 0, 0, 0},
        {21, 16, 0, 2, 5, 0, 0, 0},
        {21, 15, 5, 1, 5, 0, 0, 0},
        {21, 14, 20, 1, 5, 0, 0, 1},
        {26, 18, 20, 0, 5, 0, 0, 1},
        {26, 17, 1, 1, 4, 5400, 12200, 0},
        {26, 16, 1, 2, 4, 5500, 12200, 0},
        {26, 15, 0, 1, 4, 8900, 17700, 0},
        {27, 14, 0, 2, 4, 8800, 17700, 0},
        {26, 13, 5, 2, 4, 0, 0, 0},
        {31, 9, 0, 1, 3, 0, 0, 0},
        {5, 17, 1, 1, 3, 0, 0, 0},
        {6, 16, 1, 2, 3, 3600, 9900, 0},
        {7, 15, 1, 2, 3, 3400, 9900, 0},
        {0, 23, 0, 2, 5, 7700, 17100, 0},
        {0, 22, 0, 2, 5, 7600, 17100, 0},
        {0, 21, 0, 1, 5, 0, 0, 0},
        {0, 20, 0, 3, 5, 0, 0, 1},
        {0, 19, 1, 0, 5, 0, 0, 1},
        {5, 20, 0, 2, 3, 0, 0, 0},
        {6, 19, 0, 1, 2, 0, 0, 0},
        {7, 19, 0, 1, 2, 0, 0, 0},
        {8, 19, 1, 2, 2, 0, 0, 0},
        {9, 19, 5, 1, 2, 0, 0, 0},
        {10, 17, 5, 2, 2, 0, 0, 1},
        {10, 23, 5, 0, 5, 0, 0, 1},
        {10, 22, 5, 1, 4, 0, 0, 1},
        {11, 21, 0, 1, 4, 0, 0, 0},
        {12, 20, 1, 1, 4, 0, 0, 1},
        {13, 19, 1, 1, 5, 0, 0, 1},
        {15, 23, 0, 2, 5, 200, 8300, 0},
        {15, 22, 0, 2, 5, 100, 8300, 0},
        {16, 21, 1, 2, 5, 3400, 12100, 0},
        {17, 20, 1, 2, 5, 3300, 12100, 0},
        {18, 19, 0, 2, 4, 0, 0, 1},
        {20, 23, 1, 1, 5, 0, 0, 1},
        {20, 22, 5, 2, 4, 0, 0, 1},
        {21, 21, 0, 2, 3, 0, 0, 1},
        {22, 20, 5, 1, 3, 0, 0, 1},
        {23, 19, 1, 1, 5, 0, 0, 1},
        {0, 24, 0, 3, 2, 6600, 18100, 0},
        {1, 28, 5, 3, 5, 5600, 15100, 0},
        {1, 27, 5, 1, 4, 0, 0, 0},
        {1, 26, 0, 2, 4, 0, 0, 0},
        {1, 25, 5, 1, 4, 0, 0, 0},
        {0, 24, 5, 1, 5, 0, 0, 0},
        {9, 24, 8, 2, 3, 0, 0, 1},
        {8, 24, 7, 2, 3, 0, 0, 1},
        {7, 24, 7, 2, 3, 0, 0, 1},
        {6, 24, 12, 2, 3, 0, 0, 1},
        {5, 24, 14, 2, 3, 0, 0, 1},
        {14, 24, 14, 2, 3, 0, 0, 1},
        {13, 24, 12, 2, 3, 0, 0, 1},
        {12, 24, 7, 4, 3, 0, 0, 1},
        {11, 24, 7, 5, 3, 0, 0, 1},
        {10, 24, 8, 2, 3, 0, 0, 1},
        {19, 24, 8, 2, 3, 0, 0, 1},
        {18, 24, 3, 2, 3, 0, 0, 1},
        {17, 24, 0, 2, 3, 0, 0, 1},
        {16, 24, 5, 2, 3, 0, 0, 1},
        {15, 24, 1, 2, 3, 0, 0, 1},
        {15, 24, 0, 2, 4, 0, 0, 0},
        {16, 27, 1, 2, 5, 1100, 8500, 0},
        {17, 28, 1, 2, 5, 1000, 8500, 0},
        {21, 28, 1, 1, 5, 0, 0, 1},
        {21, 27, 8, 1, 4, 0, 0, 0},
        {20, 26, 7, 2, 5, 0, 0, 0},
        {20, 25, 7, 1, 5, 0, 0, 0},
        {21, 24, 0, 1, 5, 0, 0, 1},
        {26, 28, 1, 1, 5, 0, 0, 1},
        {26, 27, 5, 1, 5, 0, 0, 0},
        {26, 26, 0, 2, 5, 0, 0, 0},
        {26, 25, 5, 1, 5, 0, 0, 0},
        {26, 24, 1, 1, 5, 0, 0, 1},
        {0, 31, 0, 2, 4, 0, 0, 0},
        {0, 30, 0, 2, 4, 0, 0, 1},
        {0, 29, 0, 1, 5, 0, 0, 1},
        {5, 31, 0, 2, 5, 5700, 14800, 0},
        {5, 30, 0, 1, 5, 5600, 14800, 0},
        {5, 29, 20, 2, 5, 0, 0, 1},
        {10, 31, 0, 2, 4, 3500, 10800, 0},
        {10, 30, 0, 1, 4, 3600, 10800, 0},
        {10, 29, 0, 2, 5, 0, 0, 1},
        {20, 31, 1, 2, 5, 0, 0, 1},
        {20, 30, 0, 2, 4, 3000, 12100, 0},
        {20, 29, 0, 2, 4, 2900, 12100, 0},
        {15, 31, 1, 3, 4, 0, 0, 1},
        {16, 30, 0, 2, 4, 0, 0, 0},
        {16, 29, 1, 2, 5, 0, 0, 1},
        {31, 27, 7, 2, 3, 5600, 13300, 0},
        {31, 22, 7, 2, 2, 5700, 13300, 0},
        {30, 27, 5, 2, 3, 4500, 11100, 0},
        {30, 22, 5, 2, 2, 4400, 11100, 0},
        {25, 29, 3, 2, 4, 0, 0, 0},
        {26, 31, 0, 2, 4, 0, 0, 0},
        {27, 30, 5, 1, 4, 0, 0, 0},
        {25, 30, 7, 2, 4, 1600, 8800, 0},
        {25, 27, 1, 1, 3, 0, 0, 1},
        {20, 25, 1, 1, 2, 0, 0, 0},
    };

    // --- State ---
    private final Random mRandom = new Random();

    // Scene data output
    SceneData mData;

    // Animation state
    long mSystemTimer;
    long mAlphaEventTimer;
    long mNoViewedTimerGap;
    long mEventTimer;       // pattern commit time (for alpha pulsing)
    float mSpeed = 0.6f;
    float mSpeedTimer = 6500f;
    float mLayoutSpeedTimer = 5000f;
    float mLayoutMiddleTimer = 5500f;
    float mLayoutEndTimer = 11000f;

    float mUAlpha = 1f;
    float mScale = 1.6f;
    float mDiagonalXY;
    int mHalfLayoutSize;
    int mWidth, mHeight;
    int mMaxOffset;  // max(xMaxOffset, yMaxOffset)

    // Rotation state
    int mRotateLayerIdx = 4;
    int mRotateIdxLeftUp = 1;
    int mRotateIdxRightUp = 1;
    int mRotateIdxLeftDown = 1;
    int mRotateIdxRightDown = 1;
    int mRotateIdxGlow = 1;

    // Camera state
    int mRotateTimerIdx = 0;
    boolean mIsStartRotate = true;
    float mCameraPx = 0f;
    float mCameraPy = 50f;
    float mSpeedTimeRotate = 0.15f;
    int mCurrentHour = 0;

    // Cross-fade alpha per corner
    float mBgAlphaLeftUp = 1f;
    float mBgAlphaRightUp = 0f;
    float mBgAlphaLeftDown = 0f;
    float mBgAlphaRightDown = 1f;
    float mGlowAlpha = 0f;

    // Screen-off state
    boolean mIsScreenOff = true;
    float mScreenLockGap = 0f;

    // Battery state (matching original thresholds)
    private int mBatteryLevel = 100;
    private float mUBatteryAlpha = 1f;     // current battery alpha (animated)
    private float mBatteryLv2Max = 0.5f;   // alpha cap at ≤15%
    private float mBatteryLv1Max = 0.7f;   // alpha cap at ≤30%
    private boolean mBatteryChanged;
    private long mBatteryUpdateTimer;

    // Settings
    private SharedPreferences mPrefs;
    int mShape;       // 0=box, 1=round, 2=dot
    int mColorState;  // 0=Urban, 1=Natural, 2=Luxury
    int mDotAmount;   // 2-14
    int mUnitPattern; // 0/1/2

    // Built vertex data
    FloatBuffer mVerticesUp;
    FloatBuffer mVerticesDown;
    ShortBuffer mIndices;
    // Glow data
    final List<GlowData> mGlowList = new ArrayList<>();
    boolean mGridBuilt;

    LuminousDotsScene(int width, int height) {
        mWidth = width;
        mHeight = height;
        mCurrentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR);
        mSystemTimer = System.currentTimeMillis();
    }

    void setPluginPrefs(SharedPreferences prefs) {
        mPrefs = prefs;
    }

    private SharedPreferences getPrefs() {
        if (mPrefs != null) return mPrefs;
        return null;
    }

    // --- Settings parsing (matching original visible() calculations) ---

    /** Called by Engine to set battery level (0-100). Mimics original battery broadcast receiver. */
    void setBatteryLevel(int level) {
        if (level == mBatteryLevel) return;
        mBatteryLevel = level;
        mBatteryChanged = true;

        // Set thresholds based on theme + dot amount (matching original setbatteryLvMax)
        switch (mColorState) {
            case 0:
                if (mDotAmount >= 10)      { mBatteryLv2Max = 0.3f; mBatteryLv1Max = 0.6f; }
                else if (mDotAmount >= 5)  { mBatteryLv2Max = 0.4f; mBatteryLv1Max = 0.6f; }
                else                        { mBatteryLv2Max = 0.5f; mBatteryLv1Max = 0.7f; }
                break;
            default: // 1, 2
                if (mDotAmount >= 5)       { mBatteryLv2Max = 0.4f; mBatteryLv1Max = 0.6f; }
                else                        { mBatteryLv2Max = 0.6f; mBatteryLv1Max = 0.7f; }
                break;
        }
    }

    void readSettings() {
        SharedPreferences p = getPrefs();
        if (p == null) return;
        mShape = Integer.parseInt(p.getString("shape", "0"));
        mColorState = Integer.parseInt(p.getString("color", "0"));
        int scaleRate = p.getInt("scale", 0);
        mScale = ((scaleRate + 5) * 17 + 85) / 88f;
        mDotAmount = p.getInt("amount", 0) + 5;
        mUnitPattern = Integer.parseInt(p.getString("pattern", "0"));
        if (mUnitPattern > 2) mUnitPattern = 0;
        int speedRate = p.getInt("speed", 0); // 0-10
        mSpeed = ((speedRate + 5) * 13 + 60) / 100f;
        mSpeedTimer = (10 - (speedRate + 5)) * 650f;
        float f3 = ((speedRate + 5) * 4 + 60) / 100f;
        mLayoutSpeedTimer = 5000f / f3;
        mLayoutMiddleTimer = 5500f / f3;
        mLayoutEndTimer = 11000f / f3;
    }

    void resize(int width, int height, int xMaxOffset, int yMaxOffset) {
        mWidth = width;
        mHeight = height;
        int xOff = Math.max(xMaxOffset, width);
        int yOff = Math.max(yMaxOffset, height);
        mMaxOffset = Math.max(xOff, yOff);
        mDiagonalXY = mMaxOffset;
        mHalfLayoutSize = ((int) mDiagonalXY) / 32 * 16;
        mScreenLockGap = mDiagonalXY * 0.1f;
    }

    // --- SceneData output ---
    static final class SceneData {
        FloatBuffer verticesUp, verticesDown;
        ShortBuffer indices;
        float mScale;
        int halfLayoutSize;
        // Per-corner alphas
        float alphaLU, alphaRU, alphaLD, alphaRD, glowAlpha, uAlpha;
        // Gradient uniforms
        float gradDownStart, gradUpStart, gradGapUp, gradGapDown;
        // Camera
        float camPx, camPy;
        // Rotation indices
        int rotIdxLU0, rotIdxLU1, rotIdxRU0, rotIdxRU1;
        int rotIdxLD0, rotIdxLD1, rotIdxRD0, rotIdxRD1;
        int rotIdxGlow;
        int shape;
        int colorState;
        float batteryAlpha;
        int width, height;
        int xMaxOffset, yMaxOffset;
        boolean isPreview;
        // Glow list reference
        List<GlowData> glowList;
    }

    static final class GlowData {
        FloatBuffer vertices;
        int glowColorIdx;  // index into GLOW_COLOR (0-2)
        float alpha;       // mGlowAlpha
    }

    void update(long nowMs) {
        if (!mGridBuilt) return;

        // Battery alpha fade — matching original batteryUpdate()
        // Animate uBatteryAlpha toward target based on battery level
        float targetAlpha;
        if (mBatteryLevel <= 15)       targetAlpha = mBatteryLv2Max;
        else if (mBatteryLevel <= 30)  targetAlpha = mBatteryLv1Max;
        else                           targetAlpha = 1f;

        if (mBatteryChanged) {
            if (targetAlpha < mUBatteryAlpha) {
                mUBatteryAlpha -= 0.01f;
                if (mUBatteryAlpha < targetAlpha) mUBatteryAlpha = targetAlpha;
            } else if (targetAlpha > mUBatteryAlpha) {
                mUBatteryAlpha += 0.01f;
                if (mUBatteryAlpha > targetAlpha) mUBatteryAlpha = targetAlpha;
            }
            if (mUBatteryAlpha == targetAlpha) {
                mBatteryChanged = false;
                mNoViewedTimerGap += nowMs - mBatteryUpdateTimer;
            }
        } else if (mBatteryLevel > 30 && mUBatteryAlpha < 1f) {
            mUBatteryAlpha += 0.01f;
            mBatteryChanged = true;
            if (mUBatteryAlpha > 1f) mUBatteryAlpha = 1f;
        }
        if (mBatteryChanged && mBatteryUpdateTimer == 0) {
            mBatteryUpdateTimer = nowMs;
        }

        // Screen-off / visible state logic
        // In original: mIsScreenOff is managed by broadcast receivers
        if (mIsScreenOff) {
            // Fade from screen-off
            if (mGlowAlpha <= 1f) mGlowAlpha += 0.01f;
            if (mGlowAlpha > 0.5f && mUAlpha > 0f) mUAlpha -= 0.012f;
            mBgAlphaLeftDown = mUAlpha;
            mBgAlphaRightUp = mUAlpha;
            mBgAlphaRightDown = 1f - mUAlpha;
            mBgAlphaLeftUp = 1f - mUAlpha;
            if (mUAlpha < 0f) {
                mIsScreenOff = false;
                mNoViewedTimerGap = nowMs - mSystemTimer;
                mAlphaEventTimer = nowMs - mNoViewedTimerGap;
            }
        }

        // Main update logic (from updateAction)
        mSystemTimer = nowMs - mNoViewedTimerGap;

        // Alpha cross-fade calculation
        float elapsed = (mSystemTimer - mAlphaEventTimer);
        if (elapsed > mLayoutMiddleTimer) {
            if (mUAlpha <= 0f) {
                increaseRotateIdx(true);
                mRotateLayerIdx++;
                if (mRotateLayerIdx > 3) mRotateLayerIdx = 0;
            }
            mUAlpha = (elapsed - mLayoutMiddleTimer) / mLayoutSpeedTimer;
            if (mUAlpha >= 1f) {
                mUAlpha = 1f;
                if (elapsed > mLayoutEndTimer) {
                    mAlphaEventTimer = mSystemTimer;
                    increaseRotateIdx(false);
                }
            }
        } else if (elapsed > 0f) {
            mUAlpha = (mLayoutSpeedTimer - elapsed) / mLayoutSpeedTimer;
            if (mUAlpha < 0f) mUAlpha = 0f;
        }

        // Set bg alphas based on rotateLayerIdx parity
        if (mRotateLayerIdx % 2 == 0) {
            mBgAlphaLeftUp = mUAlpha;
            mBgAlphaRightDown = mUAlpha;
        } else {
            mBgAlphaRightUp = mUAlpha;
            mBgAlphaLeftDown = mUAlpha;
        }
        mGlowAlpha = 1f;

        // Camera motion
        if (!mIsStartRotate) {
            mCameraPx += (CAM_PATH[mRotateTimerIdx][0] - mCameraPx) * mSpeedTimeRotate;
            mCameraPy += (CAM_PATH[mRotateTimerIdx][1] - mCameraPy) * mSpeedTimeRotate;
        }
        if (Math.abs(CAM_PATH[mRotateTimerIdx][0] - mCameraPx) <= 0.01f &&
            Math.abs(CAM_PATH[mRotateTimerIdx][1] - mCameraPy) <= 0.01f) {
            mIsStartRotate = true;
            mCameraPx = CAM_PATH[mRotateTimerIdx][0];
            mCameraPy = CAM_PATH[mRotateTimerIdx][1];
            mRotateTimerIdx++;
            mSpeedTimeRotate = 0.15f;
            if (mRotateTimerIdx >= CAM_PATH.length) mRotateTimerIdx = 0;
            mCurrentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR);
        } else if (mIsStartRotate) {
            int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR);
            if (hour != mCurrentHour) mIsStartRotate = false;
        }

        // Update motion thread alpha (in original: MotionThreadDefault threads do this)
        updateMotionAlpha(mVerticesUp, nowMs, 1024);
        updateMotionAlpha(mVerticesDown, nowMs, 1024);
        for (GlowData g : mGlowList) {
            updateMotionAlpha(g.vertices, nowMs, 1);
        }

        // Pack data for GL
        mData = new SceneData();
        mData.verticesUp = mVerticesUp;
        mData.verticesDown = mVerticesDown;
        mData.indices = mIndices;
        mData.mScale = mScale;
        mData.halfLayoutSize = mHalfLayoutSize;
        mData.alphaLU = mBgAlphaLeftUp;
        mData.alphaRU = mBgAlphaRightUp;
        mData.alphaLD = mBgAlphaLeftDown;
        mData.alphaRD = mBgAlphaRightDown;
        mData.glowAlpha = mGlowAlpha;
        mData.uAlpha = mUAlpha;
        mData.camPx = mCameraPx;
        mData.camPy = mCameraPy;
        mData.rotIdxLU0 = ROTATE_IDX[mRotateIdxLeftUp][0];
        mData.rotIdxLU1 = ROTATE_IDX[mRotateIdxLeftUp][1];
        mData.rotIdxRU0 = ROTATE_IDX[mRotateIdxRightUp][0];
        mData.rotIdxRU1 = ROTATE_IDX[mRotateIdxRightUp][1];
        mData.rotIdxLD0 = ROTATE_IDX[mRotateIdxLeftDown][0];
        mData.rotIdxLD1 = ROTATE_IDX[mRotateIdxLeftDown][1];
        mData.rotIdxRD0 = ROTATE_IDX[mRotateIdxRightDown][0];
        mData.rotIdxRD1 = ROTATE_IDX[mRotateIdxRightDown][1];
        mData.rotIdxGlow = mRotateIdxGlow;
        mData.shape = mShape;
        mData.colorState = mColorState;
        mData.batteryAlpha = mUBatteryAlpha;
        mData.width = mWidth;
        mData.height = mHeight;
        mData.xMaxOffset = mMaxOffset;
        mData.yMaxOffset = mMaxOffset;
        mData.glowList = mGlowList;

        // Gradient parameters (matching original onDrawFrame calculations)
        int yMaxOffset = mMaxOffset;
        float i, i2, i3, i4;
        switch (mColorState) {
            case 1:
                i  = yMaxOffset * 0.7f;
                i2 = -yMaxOffset * 0.55f;
                i3 = yMaxOffset / 5f;
                i4 = yMaxOffset / 4f;
                break;
            case 2:
                i  = yMaxOffset * 0.7f;
                i2 = -yMaxOffset * 0.55f;
                i3 = yMaxOffset / 5f;
                i4 = yMaxOffset / 4.5f;
                break;
            default: // 0
                i2 = -yMaxOffset * 0.3f;
                i  = yMaxOffset * 0.5f;
                i3 = yMaxOffset / 2.5f;
                i4 = yMaxOffset / 2f;
                break;
        }

        if (mHeight >= mWidth) {
            mData.gradDownStart = i2 - mScreenLockGap;
            mData.gradUpStart = i;
            mData.gradGapUp = i3;
            mData.gradGapDown = i4;
        } else {
            mData.gradDownStart = (-yMaxOffset) / 3.25f;
            mData.gradUpStart = yMaxOffset * 0.35f;
            mData.gradGapUp = yMaxOffset / ((yMaxOffset - 240) / 120f);
            mData.gradGapDown = yMaxOffset / ((yMaxOffset - 240) / 120f);
        }
        if (mData.isPreview) {
            mData.gradDownStart = -yMaxOffset * 2;
            mData.gradUpStart = yMaxOffset * 2;
            mData.gradGapUp = 0;
            mData.gradGapDown = 0;
        }
    }

    SceneData getSceneData() {
        return mData;
    }

    // --- Motion alpha update (in place of MotionThreadDefault) ---
    private void updateMotionAlpha(FloatBuffer buf, long nowMs, int cnt) {
        if (buf == null) return;
        // Original: (SystemTimer - eventTimer) + 40000
        long adjustedTime = (mSystemTimer - mEventTimer) + 40000;
        for (int idx = 0; idx < cnt; idx++) {
            int base = idx * 4 * ATTRS;  // 4 verts per quad, 12 attrs per vert
            long startMs = (long) buf.get(base + 10); // attr offset 10 (startTime)
            if (startMs > 0) {
                long endMs = (long) buf.get(base + 11);   // attr offset 11 (endTime)
                float baseAlpha = buf.get(base + 5);       // attr offset 5 (alpha)
                float f = getDefaultAlpha(adjustedTime, startMs, endMs, 6500, 6500, baseAlpha, mSpeed, mSpeedTimer);
                // Write alpha to all 4 vertices of this quad (offset 9 = alpha in color)
                buf.put(base + 9, f);           // vert 0
                buf.put(base + 12 + 9, f);      // vert 1
                buf.put(base + 24 + 9, f);      // vert 2
                buf.put(base + 36 + 9, f);      // vert 3
            }
        }
    }

    // --- Grid building ---

    void buildGrid() {
        readSettings();
        if (mPrefs == null) {
            mGridBuilt = false;
            return;
        }

        int cellSize = ((int) mDiagonalXY) / GRID;
        mHalfLayoutSize = cellSize * (GRID / 2);

        // Create vertex buffers (index 0)
        mVerticesUp = ByteBuffer.allocateDirect(VERTS * 4 * ATTRS)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
        mVerticesDown = ByteBuffer.allocateDirect(VERTS * 4 * ATTRS)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();

        // Build indices (6144 shorts = 1024 quads × 6 indices)
        short[] indices = new short[QUADS * 6];
        int idx = 0, vertIdx = 0;
        for (int i = 0; i < QUADS; i++) {
            short base = (short) (vertIdx);
            indices[idx++] = (short) (base + 0);
            indices[idx++] = (short) (base + 1);
            indices[idx++] = (short) (base + 2);
            indices[idx++] = (short) (base + 2);
            indices[idx++] = (short) (base + 1);
            indices[idx++] = (short) (base + 3);
            vertIdx += 4;
        }
        mIndices = ByteBuffer.allocateDirect(indices.length * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer();
        mIndices.put(indices).position(0);

        // Fill grid
        int vp = 0; // vertex position in buffer (in float offset units)
        int quadIdx = 0;
        mGlowList.clear();

        for (int gx = -mHalfLayoutSize; gx < mHalfLayoutSize; gx += cellSize) {
            for (int gy = mHalfLayoutSize; gy > -mHalfLayoutSize; gy -= cellSize) {
                if (quadIdx >= QUADS) break;

                boolean isRound = false;
                if (quadIdx % 3 == 0 && mShape == 1) isRound = true;

                int gridX = quadIdx / GRID;
                int gridY = quadIdx % GRID;

                float[] init = getInitObjAttr(gx, gy, 0f, 0f);
                // Set default color from theme's first color
                float[] color = OBJ_COLOR[mColorState][0];
                init[4] = color[0];
                init[5] = color[1];
                init[6] = color[2];
                init[7] = color[3] + 0.2f; // matches original: fArr[mColorState][3] + 0.2f

                oneDotSetting(mVerticesUp, vp, 0, init, cellSize, cellSize, isRound);
                oneDotSetting(mVerticesDown, vp, 0, init, cellSize, cellSize, isRound);

                // Check glow positions: GLOW_POS = {gridX, gridY, glowColorIdx, alphaLevel, unused, startMs, endMs}
                for (int gi = 0; gi < GLOW_POS.length; gi++) {
                    int[] gp = GLOW_POS[gi];
                    if (gp[0] == gridX && gp[1] == gridY) {
                        GlowData glow = new GlowData();
                        int glowColorIdx = gp[2];   // index into GLOW_COLOR
                        int alphaLevel = gp[3];      // 0-5 for getLevAlpha
                        int startMs = gp[5];
                        int endMs = gp[6];
                        glow.glowColorIdx = glowColorIdx;
                        float levAlpha = getLevAlpha(alphaLevel);
                        float[] gInit = new float[] {
                            gx - (int)(cellSize * 1.15f),
                            (int)(cellSize * 1.15f) + gy,
                            0f, levAlpha,
                            0.016f, 0.016f, 0.016f, 1f,
                            (float) startMs,
                            (float) (endMs + randomInt(1000, 3000))
                        };
                        float[] glowC = GLOW_COLOR[glowColorIdx][mColorState];
                        gInit[4] = glowC[0];
                        gInit[5] = glowC[1];
                        gInit[6] = glowC[2];
                        float fa = getDefaultAlpha(40000, startMs, (long) gInit[9], 6500, 6500, levAlpha, mSpeed, mSpeedTimer);
                        gInit[7] = fa;
                        FloatBuffer gv = ByteBuffer.allocateDirect(4 * 4 * ATTRS)
                            .order(ByteOrder.nativeOrder()).asFloatBuffer();
                        oneDotSetting(gv, 0, 0, gInit, (int)(cellSize * 1.3f), (int)(cellSize * 1.3f), false);
                        glow.vertices = gv;
                        mGlowList.add(glow);
                    }
                }
                vp += ATTRS * 4;
                quadIdx++;
            }
        }

        // Apply pattern data (UP uses OBJ_PATTERN, DOWN uses OBJ_PATTERN_DOWN — matching original)
        applyPattern(mVerticesUp, OBJ_PATTERN, mColorState);
        applyPattern(mVerticesDown, OBJ_PATTERN_DOWN, mColorState);
        mEventTimer = System.currentTimeMillis(); // commit pattern time for alpha pulsing

        mGridBuilt = true;
    }

    // --- Pattern application ---

    private void applyPattern(FloatBuffer buf, int[][] pattern, int themeIdx) {
        if (buf == null || pattern == null) return;
        for (int[] e : pattern) {
            if (e.length < 8) continue;
            int gx = e[0], gy = e[1];
            int colorIdx = e[2];
            int alphaLv = e[3];
            int shapePatIdx = e[4];
            int startTime = e[5];
            int endTime = e[6];
            int isReverse = e[7];

            int[][] shape = getObjShape(shapePatIdx);
            if (shape == null) continue;

            float amountAlpha;
            int dotAmt = (mDotAmount > 0) ? mDotAmount : 5;
            if (dotAmt <= 7) amountAlpha = 0.8f - (0.05f * (8 - dotAmt));
            else amountAlpha = dotAmt / 10f;

            float alpha = 1f;
            if (dotAmt <= 5) alpha = 1f - (0.05f * (5 - dotAmt));

            float f = ((gx >= 10 || gy >= 10) && ((gx <= 20 || gy <= 20) && ((gx >= 10 || gy <= 20) && (gx <= 20 || gy >= 10))))
                ? amountAlpha : alpha;

            int ci = colorIdx;
            if (ci >= OBJ_COLOR[themeIdx].length) {
                do { ci = randomInt(0, OBJ_COLOR[themeIdx].length); } while (ci == 9);
            }

            int i10 = 10; // non-random pattern: i10=10 (original: z ? 181 : 10)
            int randomBase = randomInt(1000, 3000);

            for (int r = 1; r < shape.length; r++) {
                for (int c = 0; c < shape[r].length; c++) {
                    int val = shape[r][c];
                    if (val == 0) continue;

                    int cellIdx = isReverse != 0
                        ? (((shape.length - 2 - (r - 1) + gx) * GRID) + ((shape[r].length - 1 - c) + gy))
                        : (((r - 1 + gx) * GRID) + (c + gy));
                    if (cellIdx < 0 || cellIdx >= QUADS) continue;

                    int base = cellIdx * 4 * ATTRS;

                    float levAlpha = getLevAlpha(alphaLv);
                    int nVal = val >= 99 ? randomInt(0, shape[0][0]) : val;
                    if (nVal == 0) break;

                    for (int v = 0; v < 4; v++) {
                        int off = base + v * ATTRS + 2;
                        float[] col = OBJ_COLOR[themeIdx][ci];
                        buf.put(off + 4, col[0]);
                        buf.put(off + 5, col[1]);
                        buf.put(off + 6, col[2]);
                        buf.put(off + 7, col[3] * levAlpha * f);
                        if (isReverse == 0)
                            buf.put(off + 7, buf.get(off + 7) * (nVal / (shape[0][0] / 3f)));
                        buf.put(off + 3, buf.get(off + 7));
                        buf.put(off + 8, (float) startTime);
                        buf.put(off + 9, (float) (endTime + 2000 + nVal * i10 + randomBase));
                    }
                }
            }
        }
    }

    // --- Shape pattern lookup ---

    private int[][] getObjShape(int idx) {
        switch (idx) {
            case 2: return PATTERN_2;
            case 3: return PATTERN_3;
            case 4: return PATTERN_4;
            case 5: return PATTERN_5;
            default: return null;
        }
    }

    // --- Rotation state helpers ---

    private void increaseRotateIdx(boolean z) {
        if (mRotateLayerIdx < 4) {
            if (mRotateLayerIdx % 2 == 0) {
                mRotateIdxLeftUp++;
                if (mRotateIdxLeftUp >= ROTATE_IDX.length) mRotateIdxLeftUp = 0;
                mRotateIdxRightDown++;
                if (mRotateIdxRightDown >= ROTATE_IDX.length) mRotateIdxRightDown = 0;
                mRotateIdxLeftUp = checkRotateIdx(mRotateIdxLeftUp, z);
                mRotateIdxRightDown = checkRotateIdx(mRotateIdxRightDown, z);
            } else {
                mRotateIdxRightUp++;
                if (mRotateIdxRightUp >= ROTATE_IDX.length) mRotateIdxRightUp = 0;
                mRotateIdxLeftDown++;
                if (mRotateIdxLeftDown >= ROTATE_IDX.length) mRotateIdxLeftDown = 0;
                mRotateIdxRightUp = checkRotateIdx(mRotateIdxRightUp, z);
                mRotateIdxLeftDown = checkRotateIdx(mRotateIdxLeftDown, z);
            }
            mRotateIdxGlow = 1;
        }
    }

    private static int checkRotateIdx(int idx, boolean z) {
        if ((!z || idx % 2 != 0) && (z || idx % 2 == 0)) return idx;
        idx--;
        return idx < 0 ? ROTATE_IDX.length - 1 : idx;
    }

    // --- Alpha functions from SmallGLUT ---

    static float getDefaultAlpha(long currentTime, long startMs, long endMs,
                                  long fadeIn, long fadeOut, float maxAlpha,
                                  float speed, float speedTimer) {
        long cycle = (long)((endMs + (endMs + speedTimer - startMs)) + speedTimer);
        float t = (currentTime - (cycle * (currentTime / cycle))) * speed;
        long t2 = (long)(endMs * speed);
        long t1 = (long)(startMs * speed);
        if (t2 > 0 && t2 + speedTimer < t) {
            float a = ((t - (t2 + speedTimer)) / fadeOut) * maxAlpha;
            return a > maxAlpha ? maxAlpha : a;
        } else if (t1 > 0 && t1 < t) {
            float a = maxAlpha * (fadeIn - (t - t1)) / fadeIn;
            return a <= 0 ? 0 : a;
        }
        return maxAlpha;
    }

    static float getRandomAlpha(long currentTime, long startMs, long fadeIn,
                                 float maxAlpha, float speed) {
        float t = currentTime * speed;
        float t3 = fadeIn * speed;
        if (startMs <= 0 || startMs >= t) {
            return 0;
        } else {
            float a = ((t3 - (t - startMs)) / t3) * maxAlpha;
            return a < 0 ? 0 : a;
        }
    }

    // --- Lev alpha (glow brightness levels) ---

    private float getLevAlpha(int level) {
        switch (level) {
            case 0: return randomFloat(0.4f, 0.8f);
            case 1: return randomFloat(0.5f, 1.0f);
            case 2: return randomFloat(1.0f, 1.2f);
            case 3: return randomFloat(1.2f, 1.5f);
            case 4: return randomFloat(1.5f, 1.8f);
            case 5: return 4.5f;
            default: return 0.5f;
        }
    }

    // --- Vertex helpers (from GraphicCell) ---

    private static float[] getInitObjAttr(float x, float y, float z, float alpha) {
        return new float[]{x, y, z, alpha, 0.016f, 0.016f, 0.016f, 1f, 0f, 0f};
    }

    private void oneDotSetting(FloatBuffer buf, int offset, int layer, float[] attr,
                                int w, int h, boolean random) {
        int rnd = random ? randomInt(0, 11) : 0;
        for (int v = 0; v < 4; v++) {
            int i = offset + v * ATTRS;
            buf.put(i + 0, v % 2 == 0 ? (int) attr[0] : (int) (attr[0] + w));
            buf.put(i + 1, v < 2 ? (int) attr[1] : (int) (attr[1] - h));
            buf.put(i + 2, (int) attr[2]);
            buf.put(i + 3, v % 2 == 0 ? 1f : 0f);   // tu
            buf.put(i + 4, v < 2 ? 0f : 1f);         // tv
            buf.put(i + 5, attr[3]);                   // alpha
            buf.put(i + 6, attr[4] * attr[3]);        // r*alpha
            buf.put(i + 7, attr[5] * attr[3]);        // g*alpha
            buf.put(i + 8, attr[6] * attr[3]);        // b*alpha
            buf.put(i + 9, attr[7] * attr[3]);        // a*alpha
            buf.put(i + 10, attr[8]);                  // startMs
            buf.put(i + 11, attr[9]);                  // endMs
            // Random jitter
            buf.put(i + 0, (v % 2 == 0 ? rnd : -rnd) + buf.get(i + 0));
            buf.put(i + 1, (v < 2 ? -rnd : rnd) + buf.get(i + 1));
        }
    }

    // --- MVP matrix builder (from Model.draw) ---
    // Matches original chain: lookAt → rotateY180 → project → translate → scale → rotate

    static void buildMVP(float[] out, float[] proj, int height,
                          float tx, float ty, float tz, float scale,
                          float[] rot, float camX, float camY) {
        // Step 1: setLookAtM(vMatrix, 0, 0, 0, -height, 0, 0, 0, camX, camY, 0)
        float[] vMatrix = new float[16];
        setLookAtM(vMatrix, 0, 0, 0, -height, 0, 0, 0, camX, camY, 0);

        // Step 2: setRotateM(mMatrix, 0, 180, 0, 1, 0) — Y-axis flip
        float[] mMatrix = new float[16];
        setRotateM(mMatrix, 180, 0, 1, 0);

        // Step 3: multiplyMM(mvp, vMatrix, mMatrix)
        float[] mvp = new float[16];
        multiplyMM(mvp, vMatrix, mMatrix);

        // Step 4: multiplyMM(mvp, proj, mvp)
        float[] tmp = new float[16];
        multiplyMM(tmp, proj, mvp);
        System.arraycopy(tmp, 0, mvp, 0, 16);

        // Step 5: translateM(mvp, tx, ty, tz)
        translateM(mvp, tx, ty, tz);

        // Step 6: scaleM(mvp, scale, scale, 1)
        scaleM(mvp, scale, scale, 1);

        // Step 7: rotateM(mvp, rot[0], rot[1], rot[2], rot[3])
        rotateM(mvp, rot[0], rot[1], rot[2], rot[3]);

        System.arraycopy(mvp, 0, out, 0, 16);
    }

    /** Glow MVP: lookAt → rotateY180 → project → rotate → scale (NO translate, rotate before scale).
     *  Matches original Glow.draw() MVP chain. */
    static void buildMVPGlow(float[] out, float[] proj, int height,
                              float scale, float[] rot, float camX, float camY) {
        float[] vMatrix = new float[16];
        setLookAtM(vMatrix, 0, 0, 0, -height, 0, 0, 0, camX, camY, 0);

        float[] mMatrix = new float[16];
        setRotateM(mMatrix, 180, 0, 1, 0);

        float[] mvp = new float[16];
        multiplyMM(mvp, vMatrix, mMatrix);

        float[] tmp = new float[16];
        multiplyMM(tmp, proj, mvp);
        System.arraycopy(tmp, 0, mvp, 0, 16);

        // Rotate BEFORE scale (no translate)
        rotateM(mvp, rot[0], rot[1], rot[2], rot[3]);
        scaleM(mvp, scale, scale, 1);

        System.arraycopy(mvp, 0, out, 0, 16);
    }

    // --- Pure Java matrix math (subset of Mat4) ---

    static void setLookAtM(float[] m, int offset,
                            float eyeX, float eyeY, float eyeZ,
                            float centerX, float centerY, float centerZ,
                            float upX, float upY, float upZ) {
        float fx = centerX - eyeX, fy = centerY - eyeY, fz = centerZ - eyeZ;
        float rlf = 1f / (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        fx *= rlf; fy *= rlf; fz *= rlf;
        float sx = fy * upZ - fz * upY, sy = fz * upX - fx * upZ, sz = fx * upY - fy * upX;
        float rls = 1f / (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
        sx *= rls; sy *= rls; sz *= rls;
        float ux = sy * fz - sz * fy, uy = sz * fx - sx * fz, uz = sx * fy - sy * fx;
        m[offset + 0] = sx; m[offset + 4] = sy; m[offset + 8] = sz;  m[offset + 12] = 0;
        m[offset + 1] = ux; m[offset + 5] = uy; m[offset + 9] = uz;  m[offset + 13] = 0;
        m[offset + 2] = -fx; m[offset + 6] = -fy; m[offset + 10] = -fz; m[offset + 14] = 0;
        m[offset + 3] = 0; m[offset + 7] = 0; m[offset + 11] = 0; m[offset + 15] = 1;
        translateM(m, -eyeX, -eyeY, -eyeZ);
    }

    private static void setRotateM(float[] m, float angle, float ax, float ay, float az) {
        for (int i = 0; i < 16; i++) m[i] = 0f;
        float rad = (float) Math.toRadians(angle);
        float c = (float) Math.cos(rad), s = (float) Math.sin(rad), t = 1f - c;
        float len = (float) Math.sqrt(ax * ax + ay * ay + az * az);
        if (len == 0f) { m[0] = m[5] = m[10] = m[15] = 1f; return; }
        float x = ax / len, y = ay / len, z = az / len;
        m[0] = t * x * x + c;       m[4] = t * x * y - s * z; m[8]  = t * x * z + s * y; m[12] = 0;
        m[1] = t * x * y + s * z;   m[5] = t * y * y + c;     m[9]  = t * y * z - s * x; m[13] = 0;
        m[2] = t * x * z - s * y;   m[6] = t * y * z + s * x; m[10] = t * z * z + c;     m[14] = 0;
        m[3] = 0; m[7] = 0; m[11] = 0; m[15] = 1;
    }

    static void multiplyMM(float[] result, float[] a, float[] b) {
        float[] tmp = new float[16];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                int idx = j * 4 + i;
                tmp[idx] = 0;
                for (int k = 0; k < 4; k++) tmp[idx] += a[k * 4 + i] * b[j * 4 + k];
            }
        }
        System.arraycopy(tmp, 0, result, 0, 16);
    }

    static void translateM(float[] m, float x, float y, float z) {
        for (int i = 0; i < 4; i++) m[12 + i] += m[i] * x + m[4 + i] * y + m[8 + i] * z;
    }

    static void scaleM(float[] m, float x, float y, float z) {
        for (int i = 0; i < 4; i++) { m[i] *= x; m[4 + i] *= y; m[8 + i] *= z; }
    }

    static void rotateM(float[] m, float angle, float ax, float ay, float az) {
        float rad = (float) Math.toRadians(angle);
        float c = (float) Math.cos(rad), s = (float) Math.sin(rad), t = 1f - c;
        float len = (float) Math.sqrt(ax * ax + ay * ay + az * az);
        if (len == 0f) return;
        float nx = ax / len, ny = ay / len, nz = az / len;
        float[] r = new float[16];
        r[0] = t * nx * nx + c;       r[4] = t * nx * ny - s * nz; r[8]  = t * nx * nz + s * ny; r[12] = 0;
        r[1] = t * nx * ny + s * nz;  r[5] = t * ny * ny + c;      r[9]  = t * ny * nz - s * nx; r[13] = 0;
        r[2] = t * nx * nz - s * ny;  r[6] = t * ny * nz + s * nx; r[10] = t * nz * nz + c;      r[14] = 0;
        r[3] = 0; r[7] = 0; r[11] = 0; r[15] = 1;
        float[] tmp = new float[16];
        multiplyMM(tmp, m, r);
        System.arraycopy(tmp, 0, m, 0, 16);
    }

    static void frustumM(float[] m, float left, float right, float bottom, float top, float near, float far) {
        for (int i = 0; i < 16; i++) m[i] = 0;
        m[0] = 2 * near / (right - left);
        m[5] = 2 * near / (top - bottom);
        m[8] = (right + left) / (right - left);
        m[9] = (top + bottom) / (top - bottom);
        m[10] = -(far + near) / (far - near);
        m[11] = -1;
        m[14] = -2 * far * near / (far - near);
    }

    // --- Random number helpers ---

    private float randomFloat(float min, float max) {
        return min + mRandom.nextFloat() * (max - min);
    }

    private int randomInt(int min, int max) {
        if (max <= min) return min;
        return min + mRandom.nextInt(max - min);
    }
}
