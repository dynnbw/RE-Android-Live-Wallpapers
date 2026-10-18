package com.reandroid.plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个 pass = 一次 draw。子类在 {@code buildScene} 里声明，之后每帧只更新数据。
 *
 * <p>顶点属性固定为 float（location 0..n-1），与 GLES 侧的 Scene 数据一致。
 */
public final class VkPass {

    public static final int TOPOLOGY_TRIANGLE_STRIP = 0;
    public static final int TOPOLOGY_TRIANGLE_LIST = 1;
    public static final int TOPOLOGY_POINT_LIST = 2;

    public static final int BLEND_NONE = 0;
    public static final int BLEND_ALPHA = 1;
    public static final int BLEND_ADDITIVE = 2;
    public static final int BLEND_PREMULTIPLIED = 3;

    /** VkBlendFactor 数值；非常规组合用 {@link #blendFactors} 直接指定。 */
    public static final int FACTOR_ZERO = 0;
    public static final int FACTOR_ONE = 1;
    public static final int FACTOR_SRC_COLOR = 2;
    public static final int FACTOR_ONE_MINUS_SRC_COLOR = 3;
    public static final int FACTOR_DST_COLOR = 4;
    public static final int FACTOR_ONE_MINUS_DST_COLOR = 5;
    public static final int FACTOR_SRC_ALPHA = 6;
    public static final int FACTOR_ONE_MINUS_SRC_ALPHA = 7;
    public static final int FACTOR_DST_ALPHA = 8;
    public static final int FACTOR_ONE_MINUS_DST_ALPHA = 9;

    static final class TextureBinding {
        final int binding;
        final String name;

        TextureBinding(int binding, String name) {
            this.binding = binding;
            this.name = name;
        }
    }

    final String vsAsset;
    final String fsAsset;
    final int[] attribSizes;
    final int strideBytes;
    final int topology;
    final int blendMode;

    String bufferName = "";
    int vertexCount;
    boolean clear;
    int pushConstantBytes;
    /** 非空时覆盖 {@link #blendMode} 的默认因子。 */
    private int[] blendOverride;
    /** commit 之后由 {@link VkPassScene} 填上。 */
    int index = -1;
    final List<TextureBinding> textures = new ArrayList<>();

    public VkPass(String vsAsset, String fsAsset, int[] attribSizes,
                  int strideBytes, int topology, int blendMode) {
        this.vsAsset = vsAsset;
        this.fsAsset = fsAsset;
        this.attribSizes = attribSizes;
        this.strideBytes = strideBytes;
        this.topology = topology;
        this.blendMode = blendMode;
    }

    public VkPass buffer(String name, int vertexCount) {
        this.bufferName = name;
        this.vertexCount = vertexCount;
        return this;
    }

    public VkPass clear(boolean clear) {
        this.clear = clear;
        return this;
    }

    public VkPass pushConstants(int bytes) {
        this.pushConstantBytes = bytes;
        return this;
    }

    public VkPass texture(int binding, String name) {
        textures.add(new TextureBinding(binding, name));
        return this;
    }

    /** 直接指定 src/dst 因子（VkBlendFactor 数值），覆盖 blendMode 的默认组合。 */
    public VkPass blendFactors(int srcColor, int dstColor, int srcAlpha, int dstAlpha) {
        blendOverride = new int[] {srcColor, dstColor, srcAlpha, dstAlpha};
        return this;
    }

    int[] resolveBlendFactors() {
        return blendOverride != null
                ? blendOverride
                : VkPassScene.blendFactors(blendMode);
    }
}
