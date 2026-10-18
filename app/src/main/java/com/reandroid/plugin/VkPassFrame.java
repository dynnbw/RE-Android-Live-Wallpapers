package com.reandroid.plugin;

import java.nio.ByteBuffer;

/** 每帧数据入口：动态 buffer 与 push constant。 */
public final class VkPassFrame {

    private final long handle;

    VkPassFrame(long handle) {
        this.handle = handle;
    }

    public void upload(String bufferName, ByteBuffer vertices) {
        VkPassNative.nUploadBuffer(handle, bufferName, vertices);
    }

    public void pushConstants(VkPass pass, float[] values) {
        if (pass.index >= 0) {
            VkPassNative.nSetPushConstants(handle, pass.index, values);
        }
    }
}
