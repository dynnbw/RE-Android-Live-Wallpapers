package com.reandroid.wallpaper;

import java.util.Random;

public final class MathUtils {
    // 使用 Math.PI 精确计算转换常量
    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);
    private static final float RAD_TO_DEG = (float) (180.0 / Math.PI);
    private static final Random sRandom = new Random();

    private MathUtils() {
    }

    // 数学运算
    public static float abs(float value) {
        return value >= 0 ? value : -value;
    }

    // 约束函数
    public static int constrain(int value, int min, int max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    public static long constrain(long value, long min, long max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    public static float constrain(float value, float min, float max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    // 数学函数
    public static float log(float value) {
        return (float) Math.log(value);
    }

    public static float exp(float value) {
        return (float) Math.exp(value);
    }

    public static float pow(float base, float exponent) {
        return (float) Math.pow(base, exponent);
    }

    // 最大值/最小值
    public static float max(float a, float b) {
        return a > b ? a : b;
    }

    public static int max(int a, int b) {
        return a > b ? a : b;
    }

    public static float max(float a, float b, float c) {
        return a > b ? (a > c ? a : c) : (b > c ? b : c);
    }

    public static int max(int a, int b, int c) {
        if (a > b) {
            return a > c ? a : c;
        }
        return b > c ? b : c;
    }

    public static float min(float a, float b) {
        return a < b ? a : b;
    }

    public static int min(int a, int b) {
        return a < b ? a : b;
    }

    public static float min(float a, float b, float c) {
        return a < b ? (a < c ? a : c) : (b < c ? b : c);
    }

    public static int min(int a, int b, int c) {
        if (a < b) {
            return a < c ? a : c;
        }
        return b < c ? b : c;
    }

    // 距离计算
    public static float dist(float x1, float y1, float x2, float y2) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    public static float dist(float x1, float y1, float z1, float x2, float y2, float z2) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float dz = z2 - z1;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // 向量大小（模长）
    public static float magnitude(float x, float y) {
        return (float) Math.sqrt(x * x + y * y);
    }

    public static float magnitude(float x, float y, float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }
    
    // 兼容性方法
    public static float mag(float a, float b) {
        return magnitude(a, b);
    }
    
    public static float mag(float a, float b, float c) {
        return magnitude(a, b, c);
    }

    // 平方
    public static float square(float value) {
        return value * value;
    }
    
    public static float sq(float value) {
        return square(value);
    }

    // 角度/弧度转换
    public static float radians(float degrees) {
        return DEG_TO_RAD * degrees;
    }

    public static float degrees(float radians) {
        return RAD_TO_DEG * radians;
    }

    // 三角函数
    public static float acos(float value) {
        return (float) Math.acos(value);
    }

    public static float asin(float value) {
        return (float) Math.asin(value);
    }

    public static float atan(float value) {
        return (float) Math.atan(value);
    }

    public static float atan2(float y, float x) {
        return (float) Math.atan2(y, x);
    }

    public static float tan(float angle) {
        return (float) Math.tan(angle);
    }

    // 线性插值
    public static float lerp(float start, float end, float t) {
        return start + (end - start) * t;
    }

    // 归一化
    public static float normalize(float value, float start, float stop) {
        if (Math.abs(stop - start) < Float.MIN_VALUE) return 0f;
        return (value - start) / (stop - start);
    }
    
    public static float norm(float start, float stop, float value) {
        return normalize(value, start, stop);
    }

    // 映射函数
    public static float map(float value, float fromStart, float fromEnd, float toStart, float toEnd) {
        float normalized = normalize(value, fromStart, fromEnd);
        return lerp(toStart, toEnd, normalized);
    }

    // 随机数生成
    public static int randomInt(int bound) {
        return bound > 0 ? (int) (sRandom.nextFloat() * bound) : 0;
    }
    
    public static int random(int bound) {
        return randomInt(bound);
    }

    public static int randomInt(int min, int max) {
        if (min >= max) return min;
        return min + (int) (sRandom.nextFloat() * (max - min));
    }
    
    public static int random(int min, int max) {
        return randomInt(min, max);
    }

    public static float randomFloat(float bound) {
        return bound > 0 ? sRandom.nextFloat() * bound : 0f;
    }
    
    public static float random(float bound) {
        return randomFloat(bound);
    }

    public static float randomFloat(float min, float max) {
        if (min >= max) return min;
        return min + sRandom.nextFloat() * (max - min);
    }
    
    public static float random(float min, float max) {
        return randomFloat(min, max);
    }

    public static void setRandomSeed(long seed) {
        sRandom.setSeed(seed);
    }
    
    public static void randomSeed(long seed) {
        setRandomSeed(seed);
    }

    // 实用函数
    public static float clamp(float value, float min, float max) {
        return constrain(value, min, max);
    }
    
    public static int clamp(int value, int min, int max) {
        return constrain(value, min, max);
    }
    
    public static long clamp(long value, long min, long max) {
        return constrain(value, min, max);
    }
    
    public static boolean isInRange(float value, float min, float max) {
        return value >= min && value <= max;
    }
    
    public static boolean isInRange(int value, int min, int max) {
        return value >= min && value <= max;
    }
    
    // 平滑插值函数
    public static float smoothStep(float edge0, float edge1, float x) {
        if (x <= edge0) return 0f;
        if (x >= edge1) return 1f;
        
        x = (x - edge0) / (edge1 - edge0);
        return x * x * (3 - 2 * x);
    }
    
    // 其他实用数学函数
    public static float floor(float value) {
        return (float) Math.floor(value);
    }
    
    public static float ceil(float value) {
        return (float) Math.ceil(value);
    }
    
    public static float round(float value) {
        return (float) Math.round(value);
    }
    
    public static float sqrt(float value) {
        return (float) Math.sqrt(value);
    }
    
    public static float sin(float angle) {
        return (float) Math.sin(angle);
    }
    
    public static float cos(float angle) {
        return (float) Math.cos(angle);
    }
    
    // 计算两点间的角度（弧度）
    public static float angleBetween(float x1, float y1, float x2, float y2) {
        return (float) Math.atan2(y2 - y1, x2 - x1);
    }
    
    // 计算点积（2D）
    public static float dotProduct(float x1, float y1, float x2, float y2) {
        return x1 * x2 + y1 * y2;
    }
    
    // 计算点积（3D）
    public static float dotProduct(float x1, float y1, float z1, float x2, float y2, float z2) {
        return x1 * x2 + y1 * y2 + z1 * z2;
    }
}