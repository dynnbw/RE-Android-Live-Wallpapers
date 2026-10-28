package com.reandroid.plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * 场景描述：pass 列表 + 缓冲/纹理。
 *
 * <p>由 {@link VkPassRendererDelegate#buildScene} 填写，之后 {@link #commit()} 一次性交给 native。
 */
public final class VkPassScene {

    /** 采样器过滤方式，数值同 Vulkan 的 VkFilter。 */
    public static final int FILTER_NEAREST = 0;

    public static final int FILTER_LINEAR = 1;

    /** 采样器寻址方式，数值同 Vulkan 的 VkSamplerAddressMode。 */
    public static final int ADDRESS_REPEAT = 0;

    public static final int ADDRESS_MIRRORED_REPEAT = 1;
    public static final int ADDRESS_CLAMP_TO_EDGE = 2;

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

    public void createBuffer(String name, int strideBytes, int capacityVertices, boolean dynamic) {
        VkPassNative.nCreateBuffer(handle, name, strideBytes, capacityVertices, dynamic);
    }

    public void createTexture(String name) {
        VkPassNative.nCreateTexture(handle, name);
    }

    /**
     * 建纹理并指定采样方式。默认是 {@link #FILTER_LINEAR} + {@link #ADDRESS_CLAMP_TO_EDGE}。
     *
     * <p>纹理图集和不希望被平滑的近景精灵用 {@code NEAREST}；需要平铺的用 {@code REPEAT}
     * （GLES 侧对应 {@code GL_REPEAT}）。
     */
    public void createTexture(String name, int filter, int addressMode) {
        createTexture(name, filter, filter, addressMode);
    }

    /** 最小/放大用不同的过滤方式（GLES 的 {@code GL_TEXTURE_MIN_FILTER} / {@code MAG_FILTER}）。 */
    public void createTexture(String name, int minFilter, int magFilter, int addressMode) {
        VkPassNative.nCreateTexture(handle, name);
        VkPassNative.nSetTextureSampler(handle, name, minFilter, magFilter, addressMode);
    }

    /** 之后改采样方式：只换 sampler，已上传的像素不动。 */
    public void setTextureSampler(String name, int filter, int addressMode) {
        setTextureSampler(name, filter, filter, addressMode);
    }

    public void setTextureSampler(String name, int minFilter, int magFilter, int addressMode) {
        VkPassNative.nSetTextureSampler(handle, name, minFilter, magFilter, addressMode);
    }

    public void setTexture(String name, int[] argb, int width, int height) {
        VkPassNative.nSetTexture(handle, name, argb, width, height);
    }

    void commit() {
        for (VkPass pass : passes) {
            int[] factors = pass.resolveBlendFactors();
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
    static int[] blendFactors(int blendMode) {
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
