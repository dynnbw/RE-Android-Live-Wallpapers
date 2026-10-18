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

    public static final int BLEND_NONE = 0;
    public static final int BLEND_ALPHA = 1;
    public static final int BLEND_ADDITIVE = 2;
    public static final int BLEND_PREMULTIPLIED = 3;

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
}
