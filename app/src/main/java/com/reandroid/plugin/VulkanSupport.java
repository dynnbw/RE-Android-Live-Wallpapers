package com.reandroid.plugin;

import android.util.Log;

/**
 * 设备能不能真的用 Vulkan 出画面。
 *
 * <p>能加载 libvulkan.so 不等于能画：Android 9 的 Stack G11（STA 固件）建得起
 * instance，但 {@code vkEnumeratePhysicalDevices} 返回 0，渲染器直到第一次
 * {@code vkCreateBuffer} 才碰到空 device。native 探针按渲染器真正的启动前提
 * 检查（物理设备、swapchain 扩展、图形队列），结果与渲染器初始化一致。
 *
 * <p>结果在进程内缓存：驱动能力不会在运行中变化，而建 instance 有开销。
 */
final class VulkanSupport {

    private static final String TAG = "VulkanSupport";

    private static Boolean sSupported;

    private VulkanSupport() {}

    static synchronized boolean isSupported() {
        if (sSupported == null) {
            try {
                sSupported = VkPassNative.nIsSupported();
            } catch (Throwable t) {
                // 没有 libvulkan.so 的设备连 librebornvk 都加载不了
                Log.w(TAG, "Vulkan probe unavailable", t);
                sSupported = false;
            }
        }
        return sSupported;
    }
}
