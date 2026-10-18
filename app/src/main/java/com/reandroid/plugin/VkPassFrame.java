package com.reandroid.plugin;

import java.nio.ByteBuffer;

/** 每帧数据入口：动态 buffer 与 push constant。 */
public final class VkPassFrame {

    private final long handle;

    VkPassFrame(long handle) {
        this.handle = handle;
    }

    public void upload(String bufferName, ByteBuffer vertices) {
        upload(bufferName, vertices, vertices.capacity());
    }

    public void upload(String bufferName, ByteBuffer vertices, int bytes) {
        VkPassNative.nUploadBuffer(handle, bufferName, vertices, bytes);
    }

    public void pushConstants(VkPass pass, float[] values) {
        if (pass.index >= 0) {
            VkPassNative.nSetPushConstants(handle, pass.index, values);
        }
    }

    public void setVertexCount(VkPass pass, int vertexCount) {
        if (pass.index >= 0) {
            VkPassNative.nSetVertexCount(handle, pass.index, vertexCount);
        }
    }
}
