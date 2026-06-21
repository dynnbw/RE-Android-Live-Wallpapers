package com.reandroid.wallpaper.magicsmoke;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Magic Smoke 壁纸场景逻辑层（纯 Java，无 GL 调用）。
 * 负责预设管理、动画状态更新、像素处理。
 */
final class MagicSmokeScene {

    static final float REF_ASPECT = 3.0f / 4.0f;

    // Preset definitions (from original RenderScript)
    static class Preset {
        int processTextureMode;
        int backColor;
        int lowColor;
        int highColor;
        float alphaMul;
        int textureMask;
        boolean rotate;
        boolean textureSwap;
        boolean preMul;

        Preset(int mode, int back, int low, int high, float alpha, int mask, boolean rot, boolean swap, boolean pm) {
            processTextureMode = mode;
            backColor = back;
            lowColor = low;
            highColor = high;
            alphaMul = alpha;
            textureMask = mask;
            rotate = rot;
            textureSwap = swap;
            preMul = pm;
        }
    }

    static final int DEFAULT_PRESET = 16;
    static final Preset[] PRESETS = {
        //       mode    back      low       high      alpha mask  rot   swap  premul
        new Preset(1,  0x000000, 0x000000, 0xffffff, 2.0f, 0x0f, true,  false, false),
        new Preset(1,  0x0000ff, 0x000000, 0xffffff, 2.0f, 0x0f, true,  false, false),
        new Preset(1,  0x00ff00, 0x000000, 0xffffff, 2.0f, 0x0f, true,  false, false),
        new Preset(1,  0x00ff00, 0x000000, 0xffffff, 2.0f, 0x0f, true,  false, true),
        new Preset(1,  0x00ff00, 0x00ff00, 0xffffff, 2.5f, 0x1f, true,  true,  true),
        new Preset(1,  0x800000, 0xff0000, 0xffffff, 2.5f, 0x1f, true,  true,  false),
        new Preset(0,  0x000000, 0x000000, 0xffffff, 0.0f, 0x1f, true,  false, false),
        new Preset(1,  0x0000ff, 0x00ff00, 0xffff00, 2.0f, 0x1f, true,  true,  false),
        new Preset(1,  0x008000, 0x00ff00, 0xffffff, 2.5f, 0x1f, true,  true,  false),
        new Preset(1,  0x800000, 0xff0000, 0xffffff, 2.5f, 0x1f, true,  true,  true),
        new Preset(1,  0x808080, 0x000000, 0xffffff, 2.0f, 0x0f, true,  false, true),
        new Preset(1,  0x0000ff, 0x000000, 0xffffff, 2.0f, 0x0f, true,  false, true),
        new Preset(1,  0x0000ff, 0x00ff00, 0xffff00, 1.5f, 0x1f, false, false, true),
        new Preset(1,  0x0000ff, 0x00ff00, 0xffff00, 2.0f, 0x1f, true,  true,  true),
        new Preset(1,  0x0000ff, 0x00ff00, 0xffff00, 1.5f, 0x1f, true,  true,  true),
        new Preset(1,  0x808080, 0x000000, 0xffffff, 2.0f, 0x0f, true,  false, false),
        new Preset(1,  0x000000, 0x000000, 0xffffff, 2.0f, 0x0f, true,  true,  false),
        new Preset(2,  0x000000, 0x000070, 0xff2020, 2.5f, 0x1f, true,  false, false),
        new Preset(2,  0x6060ff, 0x000070, 0xffffff, 2.5f, 0x1f, true,  false, false),
        new Preset(3,  0x0000f0, 0x000000, 0xffffff, 2.0f, 0x0f, true,  true,  false),
    };

    // Animation state (per layer)
    final float[] mXShift = new float[5];
    final float[] mRotation = new float[5];
    final float[] mScale = new float[5];
    float mXOffset;
    float mYOffset;

    // Preset configuration
    int mCurrentPreset = DEFAULT_PRESET;
    final float[] mClearColor = new float[4];

    // Timing
    long mLastTime;

    // Preferences
    private final Context mContext;
    private SharedPreferences mPrefs;
    private SharedPreferences mPluginPrefs;

    MagicSmokeScene(Context context) {
        mContext = context;

        // Initialize animation state
        for (int i = 0; i < 5; i++) {
            mXShift[i] = 0.0f;
            mRotation[i] = 360.0f * i / 5.0f;
        }
        mScale[0] = 4.0f;
        mScale[1] = 3.0f;
        mScale[2] = 3.4f;
        mScale[3] = 3.8f;
        mScale[4] = 4.2f;

        mLastTime = 0L;
    }

    private float mSpeedMultiplier = 1.0f;

    /**
     * Plugin path: use host-provided prefs instead of hardcoded "magicsmoke" name.
     */
    void setPluginPrefs(SharedPreferences p) {
        mPluginPrefs = p;
        mSpeedMultiplier = p.getInt("magicsmoke_speed", 100) / 100.0f;
    }

    /**
     * 初始化预设（从 SharedPreferences 加载）
     */
    void init() {
        if (mPluginPrefs != null) {
            mPrefs = mPluginPrefs;
        } else {
            mPrefs = mContext.getSharedPreferences("magicsmoke", Context.MODE_PRIVATE);
        }
        mCurrentPreset = parsePreset(mPrefs.getString("preset", String.valueOf(DEFAULT_PRESET)));
    }

    /**
     * 更新动画状态
     * @param timeMs 当前时间（毫秒）
     */
    void updateAnimation(long timeMs) {
        if (mLastTime == 0L) {
            mLastTime = timeMs;
            return;
        }
        float timeDelta = (timeMs - mLastTime) / 44.0f * mSpeedMultiplier;
        mLastTime = timeMs;

        // Limit time delta to prevent jumps after sleep
        if (timeDelta > 3.0f) timeDelta = 3.0f;

        Preset preset = PRESETS[mCurrentPreset];

        // Update rotation
        if (preset.rotate) {
            mRotation[0] += 0.100f * timeDelta;
            mRotation[1] += 0.102f * timeDelta;
            mRotation[2] += 0.106f * timeDelta;
            mRotation[3] += 0.114f * timeDelta;
            mRotation[4] += 0.123f * timeDelta;
        }

        // Update xshift
        int mask = preset.textureMask;
        if ((mask & 1) != 0) mXShift[0] += 0.00100f * timeDelta;
        if ((mask & 2) != 0) mXShift[1] += 0.00106f * timeDelta;
        if ((mask & 4) != 0) mXShift[2] += 0.00114f * timeDelta;
        if ((mask & 8) != 0) mXShift[3] += 0.00118f * timeDelta;
        if ((mask & 16) != 0) mXShift[4] += 0.00127f * timeDelta;

        // Wrap values to prevent overflow
        for (int i = 0; i < 5; i++) {
            if (mXShift[i] > 1.0f) mXShift[i] -= Math.floor(mXShift[i]);
            if (mRotation[i] > 360.0f) {
                float mult = (float)Math.floor(mRotation[i] / 360.0f);
                mRotation[i] -= 360.0f * mult;
            }
        }

        // Update scale[0] based on textureSwap
        if (preset.textureSwap) {
            mScale[0] = 0.25f;
        } else {
            mScale[0] = 4.0f;
        }
    }

    /**
     * 检查预设是否变化
     * @return true 如果预设已更改
     */
    boolean updatePresetIfNeeded() {
        if (mPrefs == null) {
            return false;
        }
        int preset = parsePreset(mPrefs.getString("preset", String.valueOf(DEFAULT_PRESET)));
        if (preset != mCurrentPreset) {
            mCurrentPreset = preset;
            return true;
        }
        return false;
    }

    /**
     * 解析预设值
     * @param value 预设字符串
     * @return 预设索引
     */
    int parsePreset(String value) {
        int preset = DEFAULT_PRESET;
        if (value != null) {
            try {
                preset = Integer.parseInt(value);
            } catch (NumberFormatException ignored) {
                preset = DEFAULT_PRESET;
            }
        }
        if (preset < 0 || preset >= PRESETS.length) {
            preset = DEFAULT_PRESET;
        }
        return preset;
    }

    /**
     * 计算纹理遮罩中启用的纹理数量
     * @param mask 纹理遮罩位
     * @return 启用的纹理数量
     */
    int countTextures(int mask) {
        int count = 0;
        for (int i = 0; i < 5; i++) {
            if ((mask & (1 << i)) != 0) count++;
        }
        return count;
    }

    /**
     * 根据预设模式处理像素数组
     * @param pixels 像素数组（ARGB格式）
     * @param preset 预设配置
     * @param alphaFactor Alpha因子
     */
    void processPixels(int[] pixels, Preset preset, float alphaFactor) {
        int mode = preset.processTextureMode;
        int lowCol = preset.lowColor;
        int highCol = preset.highColor;
        boolean preMul = preset.preMul;

        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            int lum = pixel & 0xff;
            int newPixel;

            if (mode == 1) {
                // Dual-color mode
                if (lum < 128) {
                    newPixel = lowCol;
                    int alpha = (int)((255 - lum * 2) / alphaFactor);
                    alpha = Math.max(0, Math.min(255, alpha));
                    if (preMul) newPixel = premultiply(newPixel, alpha);
                    newPixel = (alpha << 24) | (newPixel & 0xffffff);
                } else {
                    newPixel = highCol;
                    int alpha = (int)(((lum - 128) * 2) / alphaFactor);
                    alpha = Math.max(0, Math.min(255, alpha));
                    if (preMul) newPixel = premultiply(newPixel, alpha);
                    newPixel = (alpha << 24) | (newPixel & 0xffffff);
                }
            } else if (mode == 2) {
                // Single-color mode with threshold
                int alpha = lum;
                int threshold = lowCol & 0xff;
                if (alpha < threshold) {
                    alpha = 0;
                } else {
                    float scale = 255.0f / (255.0f - threshold);
                    alpha = (int)(((alpha - threshold) * scale) / alphaFactor);
                }
                alpha = Math.max(0, Math.min(255, alpha));
                newPixel = highCol;
                if (preMul) newPixel = premultiply(newPixel, alpha);
                newPixel = (alpha << 24) | (newPixel & 0xffffff);
            } else if (mode == 3) {
                // Inverted luminance mode
                if (lum < 128) lum *= 2;
                else lum = 255 - (lum - 128) * 2;

                if (lum < 128) {
                    newPixel = lowCol;
                    int alpha = (int)((255 - lum * 2) / alphaFactor);
                    alpha = Math.max(0, Math.min(255, alpha));
                    if (preMul) newPixel = premultiply(newPixel, alpha);
                    newPixel = (alpha << 24) | (newPixel & 0xffffff);
                } else {
                    newPixel = highCol;
                    int alpha = (int)(((lum - 128) * 2) / alphaFactor);
                    alpha = Math.max(0, Math.min(255, alpha));
                    if (preMul) newPixel = premultiply(newPixel, alpha);
                    newPixel = (alpha << 24) | (newPixel & 0xffffff);
                }
            } else {
                // Mode 0: Direct premultiply
                int alpha = (pixel >> 24) & 0xff;
                int rgb = pixel & 0xffffff;
                newPixel = premultiply(rgb, alpha);
                newPixel = (alpha << 24) | (newPixel & 0xffffff);
            }

            pixels[i] = newPixel;
        }
    }

    /**
     * 预乘 Alpha
     * @param rgb RGB颜色值
     * @param alpha Alpha值（0-255）
     * @return 预乘后的RGB值
     */
    static int premultiply(int rgb, int alpha) {
        int r = ((rgb >> 16) & 0xff) * alpha / 255;
        int g = ((rgb >> 8) & 0xff) * alpha / 255;
        int b = (rgb & 0xff) * alpha / 255;
        return (r << 16) | (g << 8) | b;
    }
}
