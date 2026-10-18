package com.reandroid.plugin;

import android.content.res.AssetManager;
import android.view.Surface;

import java.nio.ByteBuffer;

/** 通用 Vulkan pass 渲染器的 JNI 入口。 */
public final class VkPassNative {

    static {
        System.loadLibrary("rebornvk");
    }

    private VkPassNative() {}

    static native long nCreate(AssetManager assets);

    static native void nDestroy(long handle);

    /**
     * 追加一个 pass。
     *
     * @return pass 下标（写 push constant 时用）
     */
    static native int nAddPass(
            long handle,
            String vsAsset,
            String fsAsset,
            int[] attribSizes,
            int strideBytes,
            int primitive,
            boolean blend,
            int srcColor,
            int dstColor,
            int srcAlpha,
            int dstAlpha,
            int pushConstantBytes,
            String bufferName,
            int vertexCount,
            boolean clear);

    static native void nCreateBuffer(
            long handle, String name, int strideBytes, int capacityVertices, boolean dynamic);

    static native void nCreateTexture(long handle, String name);

    static native void nBindTexture(long handle, int passIndex, int binding, String name);

    /** 像素为 Android 的 ARGB int[]；native 侧转成 RGBA 后上传。 */
    static native void nSetTexture(long handle, String name, int[] argb, int width, int height);

    /** 上传顶点数据；capacity 由 {@link #nCreateBuffer} 决定。 */
    static native void nUploadBuffer(long handle, String name, ByteBuffer vertices);

    static native void nSetPushConstants(long handle, int passIndex, float[] values);

    static native boolean nSurfaceCreated(long handle, Surface surface, int width, int height);

    static native void nSurfaceChanged(long handle, Surface surface, int width, int height);

    static native void nSurfaceDestroyed(long handle);

    static native void nRenderFrame(long handle);

    public static native boolean nIsSupported();
}
