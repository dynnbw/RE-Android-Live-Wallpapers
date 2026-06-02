#pragma once

#define VK_USE_PLATFORM_ANDROID_KHR

#include <jni.h>

#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#include <vulkan/vulkan.h>

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>
#include <mutex>
#include <string>
#include <vector>

// ─── log macros (expect LOG_TAG defined before include) ───
#ifndef LOG_TAG
#define LOG_TAG "VkCommon"
#endif
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ─── shared free functions ───

inline uint32_t vkFindMemoryType(VkPhysicalDevice physicalDevice, uint32_t typeFilter,
                                  VkMemoryPropertyFlags properties) {
    VkPhysicalDeviceMemoryProperties memProperties{};
    vkGetPhysicalDeviceMemoryProperties(physicalDevice, &memProperties);
    for (uint32_t i = 0; i < memProperties.memoryTypeCount; ++i) {
        if ((typeFilter & (1u << i)) &&
            (memProperties.memoryTypes[i].propertyFlags & properties) == properties) {
            return i;
        }
    }
    return 0;
}

inline bool vkIsVulkanSupported() {
    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "VkCheck";
    appInfo.apiVersion = VK_API_VERSION_1_0;

    std::array<const char*, 2> extensions = {
        VK_KHR_SURFACE_EXTENSION_NAME,
        VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};

    VkInstanceCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    createInfo.pApplicationInfo = &appInfo;
    createInfo.enabledExtensionCount = static_cast<uint32_t>(extensions.size());
    createInfo.ppEnabledExtensionNames = extensions.data();

    VkInstance probe = VK_NULL_HANDLE;
    VkResult result = vkCreateInstance(&createInfo, nullptr, &probe);
    bool supported = (result == VK_SUCCESS && probe != VK_NULL_HANDLE);
    if (probe != VK_NULL_HANDLE) vkDestroyInstance(probe, nullptr);
    return supported;
}

inline void vkArgbToRgba(int32_t* pixels, size_t count) {
    for (size_t i = 0; i < count; ++i) {
        uint8_t* p = reinterpret_cast<uint8_t*>(&pixels[i]);
        uint8_t a = p[3], r = p[2], g = p[1], b = p[0];
        // Android ARGB (0xAARRGGBB) → Vulkan RGBA (bytes: [R,G,B,A], little-endian 0xAABBGGRR)
        pixels[i] = static_cast<int32_t>(
            (static_cast<uint32_t>(a) << 24) |
            (static_cast<uint32_t>(b) << 16) |
            (static_cast<uint32_t>(g) << 8) |
            static_cast<uint32_t>(r));
    }
}

// ─── CRTP base class for VK wallpaper renderers ───

template <typename Derived>
class VkRendererBase {
public:
    virtual ~VkRendererBase() = default;

    // ─── instance / device lifecycle ───

    bool createInstanceLocked(const char* appName) {
        if (instance_ != VK_NULL_HANDLE) return true;

        VkApplicationInfo appInfo{};
        appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
        appInfo.pApplicationName = appName;
        appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
        appInfo.pEngineName = appName;
        appInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
        appInfo.apiVersion = VK_API_VERSION_1_0;

        std::array<const char*, 2> extensions = {
            VK_KHR_SURFACE_EXTENSION_NAME,
            VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};

        VkInstanceCreateInfo createInfo{};
        createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
        createInfo.pApplicationInfo = &appInfo;
        createInfo.enabledExtensionCount = static_cast<uint32_t>(extensions.size());
        createInfo.ppEnabledExtensionNames = extensions.data();

        VkResult result = vkCreateInstance(&createInfo, nullptr, &instance_);
        if (result != VK_SUCCESS) {
            LOGE("vkCreateInstance failed: %d", result);
            instance_ = VK_NULL_HANDLE;
            return false;
        }
        return true;
    }

    bool createDeviceLocked() {
        if (device_ != VK_NULL_HANDLE) return true;

        uint32_t deviceCount = 0;
        vkEnumeratePhysicalDevices(instance_, &deviceCount, nullptr);
        if (deviceCount == 0) { LOGE("No Vulkan physical devices"); return false; }

        std::vector<VkPhysicalDevice> devices(deviceCount);
        vkEnumeratePhysicalDevices(instance_, &deviceCount, devices.data());
        for (VkPhysicalDevice candidate : devices) {
            uint32_t qfc = 0;
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, &qfc, nullptr);
            std::vector<VkQueueFamilyProperties> qfs(qfc);
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, &qfc, qfs.data());
            for (uint32_t i = 0; i < qfc; ++i) {
                VkBool32 present = VK_FALSE;
                vkGetPhysicalDeviceSurfaceSupportKHR(candidate, i, surface_, &present);
                if ((qfs[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) && present) {
                    physicalDevice_ = candidate;
                    queueFamilyIndex_ = i;
                    break;
                }
            }
            if (physicalDevice_ != VK_NULL_HANDLE) break;
        }
        if (physicalDevice_ == VK_NULL_HANDLE) {
            LOGE("No suitable queue family"); return false;
        }

        float priority = 1.0f;
        VkDeviceQueueCreateInfo qci{};
        qci.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
        qci.queueFamilyIndex = queueFamilyIndex_;
        qci.queueCount = 1;
        qci.pQueuePriorities = &priority;

        VkPhysicalDeviceFeatures features{};
        const char* devExts[] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};

        VkDeviceCreateInfo dci{};
        dci.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
        dci.queueCreateInfoCount = 1;
        dci.pQueueCreateInfos = &qci;
        dci.enabledExtensionCount = 1;
        dci.ppEnabledExtensionNames = devExts;
        dci.pEnabledFeatures = &features;

        VkResult r = vkCreateDevice(physicalDevice_, &dci, nullptr, &device_);
        if (r != VK_SUCCESS) { LOGE("vkCreateDevice failed: %d", r); return false; }

        vkGetDeviceQueue(device_, queueFamilyIndex_, 0, &graphicsQueue_);

        VkCommandPoolCreateInfo pi{};
        pi.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
        pi.queueFamilyIndex = queueFamilyIndex_;
        pi.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        r = vkCreateCommandPool(device_, &pi, nullptr, &commandPool_);
        if (r != VK_SUCCESS) { LOGE("vkCreateCommandPool failed: %d", r); return false; }

        VkSemaphoreCreateInfo si{};
        si.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
        vkCreateSemaphore(device_, &si, nullptr, &imageAvailableSemaphore_);
        vkCreateSemaphore(device_, &si, nullptr, &renderFinishedSemaphore_);

        VkFenceCreateInfo fi{};
        fi.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        fi.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        vkCreateFence(device_, &fi, nullptr, &inFlightFence_);

        return static_cast<Derived*>(this)->onDeviceCreated();
    }

    // ─── render pass ───

    bool createRenderPassLocked() {
        if (renderPass_ != VK_NULL_HANDLE) return true;

        VkAttachmentDescription ca{};
        ca.format = swapchainFormat_;
        ca.samples = VK_SAMPLE_COUNT_1_BIT;
        ca.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
        ca.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        ca.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        ca.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        ca.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        ca.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;

        VkAttachmentReference ar{};
        ar.attachment = 0;
        ar.layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;

        VkSubpassDescription sp{};
        sp.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        sp.colorAttachmentCount = 1;
        sp.pColorAttachments = &ar;

        VkSubpassDependency dep{};
        dep.srcSubpass = VK_SUBPASS_EXTERNAL;
        dep.dstSubpass = 0;
        dep.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dep.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dep.srcAccessMask = 0;
        dep.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;

        VkRenderPassCreateInfo rpi{};
        rpi.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
        rpi.attachmentCount = 1;
        rpi.pAttachments = &ca;
        rpi.subpassCount = 1;
        rpi.pSubpasses = &sp;
        rpi.dependencyCount = 1;
        rpi.pDependencies = &dep;

        VkResult r = vkCreateRenderPass(device_, &rpi, nullptr, &renderPass_);
        if (r != VK_SUCCESS) { LOGE("vkCreateRenderPass failed: %d", r); return false; }
        return true;
    }

    // ─── swapchain lifecycle ───

    bool createSwapchainResourcesLocked() {
        destroySwapchainResourcesLocked();

        VkSurfaceCapabilitiesKHR caps{};
        vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice_, surface_, &caps);
        uint32_t fmtCount = 0;
        vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice_, surface_, &fmtCount, nullptr);
        std::vector<VkSurfaceFormatKHR> formats(fmtCount);
        vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice_, surface_, &fmtCount, formats.data());

        VkSurfaceFormatKHR selectedFormat = formats[0];
        for (const auto& f : formats) {
            if (f.format == VK_FORMAT_B8G8R8A8_UNORM || f.format == VK_FORMAT_R8G8B8A8_UNORM) {
                selectedFormat = f; break;
            }
        }
        swapchainFormat_ = selectedFormat.format;

        VkExtent2D extent = caps.currentExtent;
        if (extent.width == 0 || extent.height == 0) { LOGE("zero surface extent"); return false; }
        extent.width = std::clamp(extent.width, caps.minImageExtent.width, caps.maxImageExtent.width);
        extent.height = std::clamp(extent.height, caps.minImageExtent.height, caps.maxImageExtent.height);
        swapchainExtent_ = extent;

        uint32_t imageCount = caps.minImageCount + 1;
        if (caps.maxImageCount > 0 && imageCount > caps.maxImageCount) imageCount = caps.maxImageCount;

        VkSwapchainCreateInfoKHR sci{};
        sci.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
        sci.surface = surface_;
        sci.minImageCount = imageCount;
        sci.imageFormat = swapchainFormat_;
        sci.imageColorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
        sci.imageExtent = extent;
        sci.imageArrayLayers = 1;
        sci.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
        sci.preTransform = caps.currentTransform;
        sci.compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
        sci.presentMode = VK_PRESENT_MODE_FIFO_KHR;
        sci.clipped = VK_TRUE;
        sci.oldSwapchain = VK_NULL_HANDLE;

        VkResult r = vkCreateSwapchainKHR(device_, &sci, nullptr, &swapchain_);
        if (r != VK_SUCCESS) { LOGE("vkCreateSwapchainKHR failed: %d", r); return false; }

        vkGetSwapchainImagesKHR(device_, swapchain_, &imageCount, nullptr);
        swapchainImages_.resize(imageCount);
        vkGetSwapchainImagesKHR(device_, swapchain_, &imageCount, swapchainImages_.data());
        swapchainImageViews_.resize(imageCount);
        for (uint32_t i = 0; i < imageCount; ++i) {
            VkImageViewCreateInfo vi{};
            vi.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
            vi.image = swapchainImages_[i];
            vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
            vi.format = swapchainFormat_;
            vi.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
            vi.subresourceRange.baseMipLevel = 0;
            vi.subresourceRange.levelCount = 1;
            vi.subresourceRange.baseArrayLayer = 0;
            vi.subresourceRange.layerCount = 1;
            r = vkCreateImageView(device_, &vi, nullptr, &swapchainImageViews_[i]);
            if (r != VK_SUCCESS) { LOGE("vkCreateImageView[%u] failed: %d", i, r); return false; }
        }

        static_cast<Derived*>(this)->createRenderPassLocked();

        swapchainFramebuffers_.resize(imageCount);
        for (uint32_t i = 0; i < imageCount; ++i) {
            VkFramebufferCreateInfo fi{};
            fi.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
            fi.renderPass = renderPass_;
            fi.attachmentCount = 1;
            fi.pAttachments = &swapchainImageViews_[i];
            fi.width = extent.width;
            fi.height = extent.height;
            fi.layers = 1;
            r = vkCreateFramebuffer(device_, &fi, nullptr, &swapchainFramebuffers_[i]);
            if (r != VK_SUCCESS) { LOGE("vkCreateFramebuffer[%u] failed: %d", i, r); return false; }
        }

        static_cast<Derived*>(this)->createPipelinesLocked();

        swapchainCommandBuffers_.resize(imageCount);
        VkCommandBufferAllocateInfo ai{};
        ai.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        ai.commandPool = commandPool_;
        ai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        ai.commandBufferCount = imageCount;
        r = vkAllocateCommandBuffers(device_, &ai, swapchainCommandBuffers_.data());
        if (r != VK_SUCCESS) { LOGE("vkAllocateCommandBuffers(swapchain) failed: %d", r); return false; }

        return static_cast<Derived*>(this)->onSwapchainCreated();
    }

    void destroySwapchainResourcesLocked() {
        if (device_ == VK_NULL_HANDLE) return;
        vkDeviceWaitIdle(device_);

        if (!swapchainCommandBuffers_.empty()) {
            vkFreeCommandBuffers(device_, commandPool_,
                static_cast<uint32_t>(swapchainCommandBuffers_.size()), swapchainCommandBuffers_.data());
            swapchainCommandBuffers_.clear();
        }
        for (auto& fb : swapchainFramebuffers_) { if (fb) vkDestroyFramebuffer(device_, fb, nullptr); }
        swapchainFramebuffers_.clear();
        static_cast<Derived*>(this)->destroyPipelinesLocked();
        for (auto& iv : swapchainImageViews_) { if (iv) vkDestroyImageView(device_, iv, nullptr); }
        swapchainImageViews_.clear();
        swapchainImages_.clear();
        if (swapchain_ != VK_NULL_HANDLE) { vkDestroySwapchainKHR(device_, swapchain_, nullptr); swapchain_ = VK_NULL_HANDLE; }
    }

    void destroySurfaceLocked() {
        if (device_ != VK_NULL_HANDLE) vkDeviceWaitIdle(device_);
        destroySwapchainResourcesLocked();
        static_cast<Derived*>(this)->destroyTexturesLocked();
        if (renderPass_ != VK_NULL_HANDLE) { vkDestroyRenderPass(device_, renderPass_, nullptr); renderPass_ = VK_NULL_HANDLE; }
        if (surface_ != VK_NULL_HANDLE) { vkDestroySurfaceKHR(instance_, surface_, nullptr); surface_ = VK_NULL_HANDLE; }
    }

    void destroyDeviceLocked() {
        if (device_ == VK_NULL_HANDLE) return;
        vkDeviceWaitIdle(device_);
        destroySurfaceLocked();
        if (commandPool_ != VK_NULL_HANDLE) { vkDestroyCommandPool(device_, commandPool_, nullptr); commandPool_ = VK_NULL_HANDLE; }
        if (imageAvailableSemaphore_ != VK_NULL_HANDLE) { vkDestroySemaphore(device_, imageAvailableSemaphore_, nullptr); imageAvailableSemaphore_ = VK_NULL_HANDLE; }
        if (renderFinishedSemaphore_ != VK_NULL_HANDLE) { vkDestroySemaphore(device_, renderFinishedSemaphore_, nullptr); renderFinishedSemaphore_ = VK_NULL_HANDLE; }
        if (inFlightFence_ != VK_NULL_HANDLE) { vkDestroyFence(device_, inFlightFence_, nullptr); inFlightFence_ = VK_NULL_HANDLE; }
        if (device_ != VK_NULL_HANDLE) { vkDestroyDevice(device_, nullptr); device_ = VK_NULL_HANDLE; }
        physicalDevice_ = VK_NULL_HANDLE;
        queueFamilyIndex_ = 0;
        graphicsQueue_ = VK_NULL_HANDLE;
        if (instance_ != VK_NULL_HANDLE) { vkDestroyInstance(instance_, nullptr); instance_ = VK_NULL_HANDLE; }
    }

    // ─── surface / swapchain helpers ───

    bool createOrUpdateSurfaceLocked(ANativeWindow* window, int w, int h) {
        if (window == nullptr) return false;
        if (surface_ != VK_NULL_HANDLE) destroySurfaceLocked();

        VkAndroidSurfaceCreateInfoKHR si{};
        si.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
        si.window = window;
        VkResult r = vkCreateAndroidSurfaceKHR(instance_, &si, nullptr, &surface_);
        if (r != VK_SUCCESS) { LOGE("vkCreateAndroidSurfaceKHR failed: %d", r); return false; }
        return recreateSurfaceAndSwapchainLocked(w, h);
    }

    void recreateSwapchainLocked() {
        if (device_ != VK_NULL_HANDLE) vkDeviceWaitIdle(device_);
        destroySwapchainResourcesLocked();
        createSwapchainResourcesLocked();
    }

    bool recreateSurfaceAndSwapchainLocked(int w, int h) {
        if (!static_cast<Derived*>(this)->createInstanceLocked(nullptr)) return false;
        if (!createDeviceLocked()) return false;
        if (!createSwapchainResourcesLocked()) return false;
        static_cast<Derived*>(this)->recordCommandBuffersLocked(w, h);
        return true;
    }

    void recreateInFlightFenceLocked() {
        if (inFlightFence_ != VK_NULL_HANDLE) { vkDestroyFence(device_, inFlightFence_, nullptr); inFlightFence_ = VK_NULL_HANDLE; }
        VkFenceCreateInfo fi{};
        fi.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        fi.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        vkCreateFence(device_, &fi, nullptr, &inFlightFence_);
    }

    void recoverRenderStateLocked(int w, int h) {
        if (!static_cast<Derived*>(this)->isReadyLocked()) {
            if (device_ != VK_NULL_HANDLE) vkDeviceWaitIdle(device_);
            destroySurfaceLocked();
            createSwapchainResourcesLocked();
            static_cast<Derived*>(this)->recordCommandBuffersLocked(w, h);
        }
    }

    // ─── command buffer helpers ───

    VkCommandBuffer beginOneTimeCommandsLocked() {
        VkCommandBufferAllocateInfo ai{};
        ai.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        ai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        ai.commandPool = commandPool_;
        ai.commandBufferCount = 1;

        VkCommandBuffer cb = VK_NULL_HANDLE;
        VkResult r = vkAllocateCommandBuffers(device_, &ai, &cb);
        if (r != VK_SUCCESS) { LOGE("vkAllocateCommandBuffers(one-time) failed: %d", r); return VK_NULL_HANDLE; }

        VkCommandBufferBeginInfo bi{};
        bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        r = vkBeginCommandBuffer(cb, &bi);
        if (r != VK_SUCCESS) {
            LOGE("vkBeginCommandBuffer(one-time) failed: %d", r);
            vkFreeCommandBuffers(device_, commandPool_, 1, &cb);
            return VK_NULL_HANDLE;
        }
        return cb;
    }

    bool endOneTimeCommandsLocked(VkCommandBuffer cb) {
        VkResult r = vkEndCommandBuffer(cb);
        if (r != VK_SUCCESS) { LOGE("vkEndCommandBuffer(one-time) failed: %d", r); vkFreeCommandBuffers(device_, commandPool_, 1, &cb); return false; }
        VkSubmitInfo si{};
        si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        si.commandBufferCount = 1;
        si.pCommandBuffers = &cb;
        r = vkQueueSubmit(graphicsQueue_, 1, &si, VK_NULL_HANDLE);
        if (r != VK_SUCCESS) { LOGE("vkQueueSubmit(one-time) failed: %d", r); vkFreeCommandBuffers(device_, commandPool_, 1, &cb); return false; }
        vkQueueWaitIdle(graphicsQueue_);
        vkFreeCommandBuffers(device_, commandPool_, 1, &cb);
        return true;
    }

    void transitionImageLayoutLocked(VkCommandBuffer cb, VkImage image,
                                      VkImageLayout oldLayout, VkImageLayout newLayout) {
        VkImageMemoryBarrier barrier{};
        barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        barrier.oldLayout = oldLayout;
        barrier.newLayout = newLayout;
        barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.image = image;
        barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        barrier.subresourceRange.baseMipLevel = 0;
        barrier.subresourceRange.levelCount = 1;
        barrier.subresourceRange.baseArrayLayer = 0;
        barrier.subresourceRange.layerCount = 1;

        VkPipelineStageFlags srcStage, dstStage;
        if (oldLayout == VK_IMAGE_LAYOUT_UNDEFINED && newLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
            barrier.srcAccessMask = 0;
            barrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            srcStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
            dstStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
        } else {
            barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
            srcStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
            dstStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
        }
        vkCmdPipelineBarrier(cb, srcStage, dstStage, 0, 0, nullptr, 0, nullptr, 1, &barrier);
    }

    // ─── shader / asset helpers ───

    VkShaderModule createShaderModuleLocked(std::initializer_list<const char*> paths) {
        AAssetManager* am = static_cast<Derived*>(this)->getAssetManager();
        std::vector<char> spv;
        for (const char* p : paths) {
            spv = readAssetLocked(am, p);
            if (!spv.empty()) break;
        }
        if (spv.empty()) { LOGE("Shader not found"); return VK_NULL_HANDLE; }
        VkShaderModuleCreateInfo ci{};
        ci.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
        ci.codeSize = spv.size();
        ci.pCode = reinterpret_cast<const uint32_t*>(spv.data());
        VkShaderModule mod = VK_NULL_HANDLE;
        VkResult r = vkCreateShaderModule(device_, &ci, nullptr, &mod);
        if (r != VK_SUCCESS) { LOGE("vkCreateShaderModule failed: %d", r); return VK_NULL_HANDLE; }
        return mod;
    }

    static std::vector<char> readAssetLocked(AAssetManager* am, const char* path) {
        AAsset* a = AAssetManager_open(am, path, AASSET_MODE_BUFFER);
        if (a == nullptr) return {};
        auto len = static_cast<size_t>(AAsset_getLength(a));
        std::vector<char> data(len);
        int64_t read = AAsset_read(a, data.data(), len);
        AAsset_close(a);
        if (static_cast<size_t>(read) != len) { LOGE("AAsset_read(%s) short: %lld/%zu", path, (long long)read, len); return {}; }
        return data;
    }

    // ─── generic texture upload (parameterized) ───

    bool uploadTextureLocked(uint32_t& texId, VkImage& image, VkDeviceMemory& mem,
                              VkImageView& view, VkSampler& sampler,
                              VkDescriptorSet descSet, uint32_t binding,
                              const int32_t* rgbaPixels, uint32_t w, uint32_t h,
                              VkFilter filter, VkSamplerAddressMode addressMode) {
        if (rgbaPixels == nullptr || w == 0 || h == 0) return false;
        VkDeviceSize imageSize = static_cast<VkDeviceSize>(w) * h * 4;

        VkBuffer stageBuf = VK_NULL_HANDLE;
        VkDeviceMemory stageMem = VK_NULL_HANDLE;
        VkBufferCreateInfo bi{};
        bi.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        bi.size = imageSize;
        bi.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
        if (vkCreateBuffer(device_, &bi, nullptr, &stageBuf) != VK_SUCCESS) return false;

        VkMemoryRequirements mr{};
        vkGetBufferMemoryRequirements(device_, stageBuf, &mr);
        VkMemoryAllocateInfo ai{};
        ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        ai.allocationSize = mr.size;
        ai.memoryTypeIndex = vkFindMemoryType(physicalDevice_, mr.memoryTypeBits,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        if (vkAllocateMemory(device_, &ai, nullptr, &stageMem) != VK_SUCCESS || stageMem == VK_NULL_HANDLE) {
            vkDestroyBuffer(device_, stageBuf, nullptr); return false;
        }
        vkBindBufferMemory(device_, stageBuf, stageMem, 0);

        void* mapped = nullptr;
        if (vkMapMemory(device_, stageMem, 0, imageSize, 0, &mapped) != VK_SUCCESS || mapped == nullptr) {
            vkDestroyBuffer(device_, stageBuf, nullptr); vkFreeMemory(device_, stageMem, nullptr); return false;
        }
        std::memcpy(mapped, rgbaPixels, imageSize);
        vkUnmapMemory(device_, stageMem);

        // Destroy old texture if exists
        if (view != VK_NULL_HANDLE) vkDestroyImageView(device_, view, nullptr);
        if (sampler != VK_NULL_HANDLE) vkDestroySampler(device_, sampler, nullptr);
        if (image != VK_NULL_HANDLE) vkDestroyImage(device_, image, nullptr);
        if (mem != VK_NULL_HANDLE) vkFreeMemory(device_, mem, nullptr);

        VkImageCreateInfo ii{};
        ii.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        ii.imageType = VK_IMAGE_TYPE_2D;
        ii.format = VK_FORMAT_R8G8B8A8_UNORM;
        ii.extent = {w, h, 1};
        ii.mipLevels = 1;
        ii.arrayLayers = 1;
        ii.samples = VK_SAMPLE_COUNT_1_BIT;
        ii.tiling = VK_IMAGE_TILING_OPTIMAL;
        ii.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        ii.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        if (vkCreateImage(device_, &ii, nullptr, &image) != VK_SUCCESS) {
            vkDestroyBuffer(device_, stageBuf, nullptr); vkFreeMemory(device_, stageMem, nullptr); return false;
        }

        VkMemoryRequirements imr{};
        vkGetImageMemoryRequirements(device_, image, &imr);
        VkMemoryAllocateInfo mai{};
        mai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        mai.allocationSize = imr.size;
        mai.memoryTypeIndex = vkFindMemoryType(physicalDevice_, imr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if (vkAllocateMemory(device_, &mai, nullptr, &mem) != VK_SUCCESS || mem == VK_NULL_HANDLE) {
            vkDestroyImage(device_, image, nullptr); vkDestroyBuffer(device_, stageBuf, nullptr); vkFreeMemory(device_, stageMem, nullptr); return false;
        }
        vkBindImageMemory(device_, image, mem, 0);

        VkCommandBuffer cb = beginOneTimeCommandsLocked();
        if (cb == VK_NULL_HANDLE) { vkDestroyImage(device_, image, nullptr); vkFreeMemory(device_, mem, nullptr); vkDestroyBuffer(device_, stageBuf, nullptr); vkFreeMemory(device_, stageMem, nullptr); return false; }

        transitionImageLayoutLocked(cb, image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        VkBufferImageCopy region{};
        region.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        region.imageSubresource.layerCount = 1;
        region.imageExtent = {w, h, 1};
        vkCmdCopyBufferToImage(cb, stageBuf, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);
        transitionImageLayoutLocked(cb, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

        if (!endOneTimeCommandsLocked(cb)) {
            vkDestroyImage(device_, image, nullptr); vkFreeMemory(device_, mem, nullptr); vkDestroyBuffer(device_, stageBuf, nullptr); vkFreeMemory(device_, stageMem, nullptr); return false;
        }

        vkDestroyBuffer(device_, stageBuf, nullptr);
        vkFreeMemory(device_, stageMem, nullptr);

        VkImageViewCreateInfo vi{};
        vi.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        vi.image = image;
        vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
        vi.format = VK_FORMAT_R8G8B8A8_UNORM;
        vi.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        vi.subresourceRange.levelCount = 1;
        vi.subresourceRange.layerCount = 1;
        vkCreateImageView(device_, &vi, nullptr, &view);

        VkSamplerCreateInfo sci{};
        sci.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
        sci.magFilter = filter;
        sci.minFilter = filter;
        sci.addressModeU = addressMode;
        sci.addressModeV = addressMode;
        sci.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        vkCreateSampler(device_, &sci, nullptr, &sampler);

        if (descSet != VK_NULL_HANDLE) {
            VkDescriptorImageInfo dii{};
            dii.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            dii.imageView = view;
            dii.sampler = sampler;
            VkWriteDescriptorSet wds{};
            wds.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            wds.dstSet = descSet;
            wds.dstBinding = binding;
            wds.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            wds.descriptorCount = 1;
            wds.pImageInfo = &dii;
            vkUpdateDescriptorSets(device_, 1, &wds, 0, nullptr);
        }
        return true;
    }

    // ─── member access ─── via hooks that Derived overrides ───

    bool isReadyLocked() const {
        return instance_ != VK_NULL_HANDLE
            && device_ != VK_NULL_HANDLE
            && surface_ != VK_NULL_HANDLE
            && swapchain_ != VK_NULL_HANDLE
            && renderPass_ != VK_NULL_HANDLE
            && !swapchainImageViews_.empty()
            && !swapchainFramebuffers_.empty()
            && !swapchainCommandBuffers_.empty()
            && static_cast<const Derived*>(this)->isSceneReadyLocked();
    }

protected:
    // ─── Vulkan state (shared) ───
    VkInstance instance_ = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice_ = VK_NULL_HANDLE;
    VkDevice device_ = VK_NULL_HANDLE;
    uint32_t queueFamilyIndex_ = 0;
    VkQueue graphicsQueue_ = VK_NULL_HANDLE;
    VkCommandPool commandPool_ = VK_NULL_HANDLE;
    VkSurfaceKHR surface_ = VK_NULL_HANDLE;
    VkSwapchainKHR swapchain_ = VK_NULL_HANDLE;
    VkFormat swapchainFormat_ = VK_FORMAT_R8G8B8A8_UNORM;
    VkExtent2D swapchainExtent_{};
    VkRenderPass renderPass_ = VK_NULL_HANDLE;
    VkSemaphore imageAvailableSemaphore_ = VK_NULL_HANDLE;
    VkSemaphore renderFinishedSemaphore_ = VK_NULL_HANDLE;
    VkFence inFlightFence_ = VK_NULL_HANDLE;
    std::vector<VkImage> swapchainImages_;
    std::vector<VkImageView> swapchainImageViews_;
    std::vector<VkFramebuffer> swapchainFramebuffers_;
    std::vector<VkCommandBuffer> swapchainCommandBuffers_;
};
