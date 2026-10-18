package com.reandroid.plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * 场景描述：pass 列表 + 缓冲/纹理。
 *
 * <p>由 {@link VkPassRendererDelegate#buildScene} 填写，之后 {@link #commit()} 一次性交给 native。
 */
public final class VkPassScene {

    private final long handle;
    private final List<VkPass> passes = new ArrayList<>();

    VkPassScene(long handle) {
        this.handle = handle;
    }

    public VkPass add(VkPass pass) {
        passes.add(pass);
        return pass;
    }

    public void setClearColor(float r, float g, float b, float a) {
        VkPassNative.nSetClearColor(handle, r, g, b, a);
    }

    public void createBuffer(String name, int strideBytes, int capacityVertices,
                             boolean dynamic) {
        VkPassNative.nCreateBuffer(handle, name, strideBytes, capacityVertices, dynamic);
    }

    public void createTexture(String name) {
        VkPassNative.nCreateTexture(handle, name);
    }

    public void setTexture(String name, int[] argb, int width, int height) {
        VkPassNative.nSetTexture(handle, name, argb, width, height);
    }

    void commit() {
        for (VkPass pass : passes) {
            int[] factors = blendFactors(pass.blendMode);
            pass.index = VkPassNative.nAddPass(
                    handle,
                    pass.vsAsset,
                    pass.fsAsset,
                    pass.attribSizes,
                    pass.strideBytes,
                    pass.topology,
                    pass.blendMode != VkPass.BLEND_NONE,
                    factors[0],
                    factors[1],
                    factors[2],
                    factors[3],
                    pass.pushConstantBytes,
                    pass.bufferName,
                    pass.vertexCount,
                    pass.clear);
            for (VkPass.TextureBinding texture : pass.textures) {
                VkPassNative.nBindTexture(handle, pass.index, texture.binding, texture.name);
            }
        }
    }

    /** GLES 的四种混合模式翻译成 VkBlendFactor 数值。 */
    private static int[] blendFactors(int blendMode) {
        switch (blendMode) {
            case VkPass.BLEND_ALPHA:
                return new int[] {6, 7, 1, 7}; // SRC_ALPHA, ONE_MINUS_SRC_ALPHA
            case VkPass.BLEND_ADDITIVE:
                return new int[] {6, 1, 1, 1}; // SRC_ALPHA, ONE
            case VkPass.BLEND_PREMULTIPLIED:
                return new int[] {1, 7, 1, 7}; // ONE, ONE_MINUS_SRC_ALPHA
            case VkPass.BLEND_NONE:
            default:
                return new int[] {0, 0, 0, 0};
        }
    }
}
