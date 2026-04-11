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

#define LOG_TAG "GalaxyVK"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr uint32_t kMaxParticleCount = 20000;

struct PushConstants {
    float mvp[16];
    float alpha;
    float padding[3];
};

struct ParticleVertex {
    float angle;
    float dist;
    float z;
    float r;
    float g;
    float b;
    float size;
};

enum PipelineType {
    PIPELINE_BG = 0,
    PIPELINE_PARTICLES = 1,
    PIPELINE_LIGHT = 2,
};

class GalaxyVkRenderer {
public:
    explicit GalaxyVkRenderer(AAssetManager* assetManager)
        : assetManager_(assetManager) {
    }

    ~GalaxyVkRenderer() {
        destroy();
    }

    bool createOrUpdateSurface(JNIEnv* env, jobject surface, int width, int height) {
        std::lock_guard<std::mutex> lock(mutex_);
        width_ = width;
        height_ = height;

        if (!createInstanceLocked()) {
            return false;
        }

        destroySurfaceLocked();

        window_ = ANativeWindow_fromSurface(env, surface);
        if (window_ == nullptr) {
            LOGE("ANativeWindow_fromSurface failed");
            return false;
        }

        VkAndroidSurfaceCreateInfoKHR surfaceInfo{};
        surfaceInfo.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
        surfaceInfo.window = window_;
        VkResult result = vkCreateAndroidSurfaceKHR(instance_, &surfaceInfo, nullptr, &surface_);
        if (result != VK_SUCCESS) {
            LOGE("vkCreateAndroidSurfaceKHR failed: %d", result);
            ANativeWindow_release(window_);
            window_ = nullptr;
            return false;
        }

        if (!createDeviceLocked()) {
            destroySurfaceLocked();
            return false;
        }
        if (!createSwapchainResourcesLocked()) {
            destroySurfaceLocked();
            return false;
        }
        return true;
    }

    void destroySurface() {
        std::lock_guard<std::mutex> lock(mutex_);
        destroySurfaceLocked();
    }

    void render(JNIEnv* env, jfloatArray mvpMatrixArray, jfloatArray positionsArray,
            jfloatArray colorsArray, jint particleCount, jfloat alpha) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!isReadyLocked()) {
            if (!recoverRenderStateLocked()) {
                return;
            }
        }
        if (!isReadyLocked()) {
            return;
        }

        const uint32_t drawCount = std::min<uint32_t>(static_cast<uint32_t>(std::max(particleCount, 0)),
                kMaxParticleCount);
        uploadParticlesLocked(env, positionsArray, colorsArray, drawCount);

        PushConstants pushConstants{};
        env->GetFloatArrayRegion(mvpMatrixArray, 0, 16, pushConstants.mvp);
        pushConstants.alpha = alpha;

        vkWaitForFences(device_, 1, &inFlightFence_, VK_TRUE, UINT64_MAX);

        uint32_t imageIndex = 0;
        VkResult acquire = vkAcquireNextImageKHR(device_, swapchain_, UINT64_MAX,
                imageAvailableSemaphore_, VK_NULL_HANDLE, &imageIndex);
        if (acquire == VK_ERROR_SURFACE_LOST_KHR) {
            recreateSurfaceAndSwapchainLocked();
            return;
        }
        if (acquire == VK_ERROR_OUT_OF_DATE_KHR || acquire == VK_SUBOPTIMAL_KHR) {
            recreateSwapchainLocked();
            return;
        }
        if (acquire != VK_SUCCESS) {
            LOGE("vkAcquireNextImageKHR failed: %d", acquire);
            return;
        }

        VkCommandBuffer commandBuffer = commandBuffers_[imageIndex];
        vkResetCommandBuffer(commandBuffer, 0);
        if (!recordCommandBufferLocked(commandBuffer, imageIndex, drawCount, pushConstants)) {
            return;
        }

        VkPipelineStageFlags waitStages[] = {VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT};
        VkSubmitInfo submitInfo{};
        submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        submitInfo.waitSemaphoreCount = 1;
        submitInfo.pWaitSemaphores = &imageAvailableSemaphore_;
        submitInfo.pWaitDstStageMask = waitStages;
        submitInfo.commandBufferCount = 1;
        submitInfo.pCommandBuffers = &commandBuffer;
        submitInfo.signalSemaphoreCount = 1;
        submitInfo.pSignalSemaphores = &renderFinishedSemaphore_;

        vkResetFences(device_, 1, &inFlightFence_);
        VkResult submit = vkQueueSubmit(graphicsQueue_, 1, &submitInfo, inFlightFence_);
        if (submit != VK_SUCCESS) {
            LOGE("vkQueueSubmit failed: %d", submit);
            recreateInFlightFenceLocked();
            return;
        }

        VkPresentInfoKHR presentInfo{};
        presentInfo.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
        presentInfo.waitSemaphoreCount = 1;
        presentInfo.pWaitSemaphores = &renderFinishedSemaphore_;
        presentInfo.swapchainCount = 1;
        presentInfo.pSwapchains = &swapchain_;
        presentInfo.pImageIndices = &imageIndex;

        VkResult present = vkQueuePresentKHR(graphicsQueue_, &presentInfo);
        if (present == VK_ERROR_SURFACE_LOST_KHR) {
            recreateSurfaceAndSwapchainLocked();
            return;
        }
        if (present == VK_ERROR_OUT_OF_DATE_KHR || present == VK_SUBOPTIMAL_KHR) {
            recreateSwapchainLocked();
            return;
        }
        if (present != VK_SUCCESS) {
            LOGE("vkQueuePresentKHR failed: %d", present);
        }
    }

    void setLightTexture(JNIEnv* env, jintArray argbPixels, jint width, jint height) {
        if (argbPixels == nullptr || width <= 0 || height <= 0) {
            return;
        }
        std::lock_guard<std::mutex> lock(mutex_);

        const jsize pixelCount = env->GetArrayLength(argbPixels);
        if (pixelCount <= 0 || static_cast<int64_t>(width) * static_cast<int64_t>(height) > pixelCount) {
            return;
        }

        jint* pixels = env->GetIntArrayElements(argbPixels, nullptr);
        if (pixels == nullptr) {
            return;
        }

        pendingLightWidth_ = static_cast<uint32_t>(width);
        pendingLightHeight_ = static_cast<uint32_t>(height);
        pendingLightPixels_.resize(static_cast<size_t>(pendingLightWidth_) * pendingLightHeight_ * 4u);

        for (size_t i = 0; i < static_cast<size_t>(pendingLightWidth_) * pendingLightHeight_; ++i) {
            const uint32_t argb = static_cast<uint32_t>(pixels[i]);
            pendingLightPixels_[i * 4 + 0] = static_cast<uint8_t>((argb >> 16) & 0xFF);
            pendingLightPixels_[i * 4 + 1] = static_cast<uint8_t>((argb >> 8) & 0xFF);
            pendingLightPixels_[i * 4 + 2] = static_cast<uint8_t>(argb & 0xFF);
            pendingLightPixels_[i * 4 + 3] = static_cast<uint8_t>((argb >> 24) & 0xFF);
        }
        env->ReleaseIntArrayElements(argbPixels, pixels, JNI_ABORT);

        if (device_ != VK_NULL_HANDLE && commandPool_ != VK_NULL_HANDLE && graphicsQueue_ != VK_NULL_HANDLE) {
            ensureLightTextureLocked();
        }
    }

    void setBackgroundTexture(JNIEnv* env, jintArray argbPixels, jint width, jint height) {
        if (argbPixels == nullptr || width <= 0 || height <= 0) {
            return;
        }
        std::lock_guard<std::mutex> lock(mutex_);

        const jsize pixelCount = env->GetArrayLength(argbPixels);
        if (pixelCount <= 0 || static_cast<int64_t>(width) * static_cast<int64_t>(height) > pixelCount) {
            return;
        }

        jint* pixels = env->GetIntArrayElements(argbPixels, nullptr);
        if (pixels == nullptr) {
            return;
        }

        pendingBgWidth_ = static_cast<uint32_t>(width);
        pendingBgHeight_ = static_cast<uint32_t>(height);
        pendingBgPixels_.resize(static_cast<size_t>(pendingBgWidth_) * pendingBgHeight_ * 4u);

        for (size_t i = 0; i < static_cast<size_t>(pendingBgWidth_) * pendingBgHeight_; ++i) {
            const uint32_t argb = static_cast<uint32_t>(pixels[i]);
            pendingBgPixels_[i * 4 + 0] = static_cast<uint8_t>((argb >> 16) & 0xFF);
            pendingBgPixels_[i * 4 + 1] = static_cast<uint8_t>((argb >> 8) & 0xFF);
            pendingBgPixels_[i * 4 + 2] = static_cast<uint8_t>(argb & 0xFF);
            pendingBgPixels_[i * 4 + 3] = static_cast<uint8_t>((argb >> 24) & 0xFF);
        }
        env->ReleaseIntArrayElements(argbPixels, pixels, JNI_ABORT);

        if (device_ != VK_NULL_HANDLE && commandPool_ != VK_NULL_HANDLE && graphicsQueue_ != VK_NULL_HANDLE) {
            ensureBackgroundTextureLocked();
        }
    }

    void destroy() {
        std::lock_guard<std::mutex> lock(mutex_);
        destroySurfaceLocked();
        destroyDeviceLocked();
        if (instance_ != VK_NULL_HANDLE) {
            vkDestroyInstance(instance_, nullptr);
            instance_ = VK_NULL_HANDLE;
        }
    }

    static bool isVulkanSupported() {
        uint32_t extensionCount = 0;
        return vkEnumerateInstanceExtensionProperties(nullptr, &extensionCount, nullptr) == VK_SUCCESS
                && extensionCount > 0;
    }

private:
    bool createInstanceLocked() {
        if (instance_ != VK_NULL_HANDLE) {
            return true;
        }

        VkApplicationInfo appInfo{};
        appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
        appInfo.pApplicationName = "GalaxyVK";
        appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
        appInfo.pEngineName = "GalaxyVK";
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
        if (device_ != VK_NULL_HANDLE) {
            return true;
        }

        uint32_t deviceCount = 0;
        vkEnumeratePhysicalDevices(instance_, &deviceCount, nullptr);
        if (deviceCount == 0) {
            LOGE("No Vulkan physical devices found");
            return false;
        }

        std::vector<VkPhysicalDevice> devices(deviceCount);
        vkEnumeratePhysicalDevices(instance_, &deviceCount, devices.data());
        for (VkPhysicalDevice candidate : devices) {
            uint32_t queueFamilyCount = 0;
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, &queueFamilyCount, nullptr);
            std::vector<VkQueueFamilyProperties> queueFamilies(queueFamilyCount);
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, &queueFamilyCount, queueFamilies.data());
            for (uint32_t i = 0; i < queueFamilyCount; ++i) {
                VkBool32 presentSupported = VK_FALSE;
                vkGetPhysicalDeviceSurfaceSupportKHR(candidate, i, surface_, &presentSupported);
                if ((queueFamilies[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) != 0 && presentSupported) {
                    physicalDevice_ = candidate;
                    queueFamilyIndex_ = i;
                    break;
                }
            }
            if (physicalDevice_ != VK_NULL_HANDLE) {
                break;
            }
        }

        if (physicalDevice_ == VK_NULL_HANDLE) {
            LOGE("No suitable Vulkan queue family found");
            return false;
        }

        float queuePriority = 1.0f;
        VkDeviceQueueCreateInfo queueCreateInfo{};
        queueCreateInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
        queueCreateInfo.queueFamilyIndex = queueFamilyIndex_;
        queueCreateInfo.queueCount = 1;
        queueCreateInfo.pQueuePriorities = &queuePriority;

        VkPhysicalDeviceFeatures deviceFeatures{};

        const char* deviceExtensions[] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};

        VkDeviceCreateInfo createInfo{};
        createInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
        createInfo.queueCreateInfoCount = 1;
        createInfo.pQueueCreateInfos = &queueCreateInfo;
        createInfo.pEnabledFeatures = &deviceFeatures;
        createInfo.enabledExtensionCount = 1;
        createInfo.ppEnabledExtensionNames = deviceExtensions;

        VkResult result = vkCreateDevice(physicalDevice_, &createInfo, nullptr, &device_);
        if (result != VK_SUCCESS) {
            LOGE("vkCreateDevice failed: %d", result);
            device_ = VK_NULL_HANDLE;
            return false;
        }

        vkGetDeviceQueue(device_, queueFamilyIndex_, 0, &graphicsQueue_);

        VkCommandPoolCreateInfo poolInfo{};
        poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
        poolInfo.queueFamilyIndex = queueFamilyIndex_;
        poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        result = vkCreateCommandPool(device_, &poolInfo, nullptr, &commandPool_);
        if (result != VK_SUCCESS) {
            LOGE("vkCreateCommandPool failed: %d", result);
            return false;
        }

        VkSemaphoreCreateInfo semaphoreInfo{};
        semaphoreInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
        vkCreateSemaphore(device_, &semaphoreInfo, nullptr, &imageAvailableSemaphore_);
        vkCreateSemaphore(device_, &semaphoreInfo, nullptr, &renderFinishedSemaphore_);

        VkFenceCreateInfo fenceInfo{};
        fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        vkCreateFence(device_, &fenceInfo, nullptr, &inFlightFence_);

        if (!createParticleBufferLocked()) {
            return false;
        }
        if (!createBackgroundDescriptorResourcesLocked()) {
            return false;
        }
        if (!createLightDescriptorResourcesLocked()) {
            return false;
        }
        if (!ensureBackgroundTextureLocked()) {
            return false;
        }
        return ensureLightTextureLocked();
    }

    bool createParticleBufferLocked() {
        if (particleBuffer_ != VK_NULL_HANDLE) {
            return true;
        }

        VkDeviceSize bufferSize = sizeof(ParticleVertex) * kMaxParticleCount;

        VkBufferCreateInfo bufferInfo{};
        bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        bufferInfo.size = bufferSize;
        bufferInfo.usage = VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
        bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

        VkResult result = vkCreateBuffer(device_, &bufferInfo, nullptr, &particleBuffer_);
        if (result != VK_SUCCESS) {
            LOGE("vkCreateBuffer failed: %d", result);
            return false;
        }

        VkMemoryRequirements memRequirements{};
        vkGetBufferMemoryRequirements(device_, particleBuffer_, &memRequirements);

        VkMemoryAllocateInfo allocInfo{};
        allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        allocInfo.allocationSize = memRequirements.size;
        allocInfo.memoryTypeIndex = findMemoryTypeLocked(memRequirements.memoryTypeBits,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);

        result = vkAllocateMemory(device_, &allocInfo, nullptr, &particleMemory_);
        if (result != VK_SUCCESS) {
            LOGE("vkAllocateMemory failed: %d", result);
            return false;
        }

        vkBindBufferMemory(device_, particleBuffer_, particleMemory_, 0);
        result = vkMapMemory(device_, particleMemory_, 0, bufferSize, 0, &particleMappedMemory_);
        if (result != VK_SUCCESS) {
            LOGE("vkMapMemory failed: %d", result);
            particleMappedMemory_ = nullptr;
            return false;
        }

        particleCapacity_ = kMaxParticleCount;
        return true;
    }

    bool createSwapchainResourcesLocked() {
        VkSurfaceCapabilitiesKHR capabilities{};
        vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice_, surface_, &capabilities);

        uint32_t formatCount = 0;
        vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice_, surface_, &formatCount, nullptr);
        if (formatCount == 0) {
            LOGE("No surface formats available");
            return false;
        }
        std::vector<VkSurfaceFormatKHR> formats(formatCount);
        vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice_, surface_, &formatCount, formats.data());

        VkSurfaceFormatKHR selectedFormat = formats[0];
        for (const auto& format : formats) {
            if (format.format == VK_FORMAT_B8G8R8A8_UNORM || format.format == VK_FORMAT_R8G8B8A8_UNORM) {
                selectedFormat = format;
                break;
            }
        }

        uint32_t presentModeCount = 0;
        vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice_, surface_, &presentModeCount, nullptr);
        std::vector<VkPresentModeKHR> presentModes(presentModeCount);
        vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice_, surface_, &presentModeCount, presentModes.data());
        VkPresentModeKHR presentMode = VK_PRESENT_MODE_FIFO_KHR;

        VkExtent2D extent = {};
        if (capabilities.currentExtent.width != std::numeric_limits<uint32_t>::max()) {
            extent = capabilities.currentExtent;
        } else {
            extent.width = static_cast<uint32_t>(std::max(width_, 1));
            extent.height = static_cast<uint32_t>(std::max(height_, 1));
            extent.width = std::max(capabilities.minImageExtent.width,
                    std::min(capabilities.maxImageExtent.width, extent.width));
            extent.height = std::max(capabilities.minImageExtent.height,
                    std::min(capabilities.maxImageExtent.height, extent.height));
        }

        uint32_t imageCount = capabilities.minImageCount + 1;
        if (capabilities.maxImageCount > 0 && imageCount > capabilities.maxImageCount) {
            imageCount = capabilities.maxImageCount;
        }

        VkSwapchainCreateInfoKHR createInfo{};
        createInfo.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
        createInfo.surface = surface_;
        createInfo.minImageCount = imageCount;
        createInfo.imageFormat = selectedFormat.format;
        createInfo.imageColorSpace = selectedFormat.colorSpace;
        createInfo.imageExtent = extent;
        createInfo.imageArrayLayers = 1;
        createInfo.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
        createInfo.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
        createInfo.preTransform = capabilities.currentTransform;
        createInfo.compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
        createInfo.presentMode = presentMode;
        createInfo.clipped = VK_TRUE;
        createInfo.oldSwapchain = VK_NULL_HANDLE;

        VkResult result = vkCreateSwapchainKHR(device_, &createInfo, nullptr, &swapchain_);
        if (result != VK_SUCCESS) {
            LOGE("vkCreateSwapchainKHR failed: %d", result);
            return false;
        }

        uint32_t swapchainImageCount = 0;
        vkGetSwapchainImagesKHR(device_, swapchain_, &swapchainImageCount, nullptr);
        swapchainImages_.resize(swapchainImageCount);
        vkGetSwapchainImagesKHR(device_, swapchain_, &swapchainImageCount, swapchainImages_.data());
        swapchainFormat_ = selectedFormat.format;
        swapchainExtent_ = extent;

        swapchainImageViews_.resize(swapchainImages_.size());
        for (size_t i = 0; i < swapchainImages_.size(); ++i) {
            VkImageViewCreateInfo viewInfo{};
            viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
            viewInfo.image = swapchainImages_[i];
            viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
            viewInfo.format = swapchainFormat_;
            viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
            viewInfo.subresourceRange.baseMipLevel = 0;
            viewInfo.subresourceRange.levelCount = 1;
            viewInfo.subresourceRange.baseArrayLayer = 0;
            viewInfo.subresourceRange.layerCount = 1;
            result = vkCreateImageView(device_, &viewInfo, nullptr, &swapchainImageViews_[i]);
            if (result != VK_SUCCESS) {
                LOGE("vkCreateImageView failed: %d", result);
                return false;
            }
        }

        if (!createRenderPassLocked()) {
            return false;
        }
        if (!createPipelinesLocked()) {
            return false;
        }

        framebuffers_.resize(swapchainImageViews_.size());
        for (size_t i = 0; i < swapchainImageViews_.size(); ++i) {
            VkImageView attachments[] = {swapchainImageViews_[i]};
            VkFramebufferCreateInfo framebufferInfo{};
            framebufferInfo.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
            framebufferInfo.renderPass = renderPass_;
            framebufferInfo.attachmentCount = 1;
            framebufferInfo.pAttachments = attachments;
            framebufferInfo.width = swapchainExtent_.width;
            framebufferInfo.height = swapchainExtent_.height;
            framebufferInfo.layers = 1;
            result = vkCreateFramebuffer(device_, &framebufferInfo, nullptr, &framebuffers_[i]);
            if (result != VK_SUCCESS) {
                LOGE("vkCreateFramebuffer failed: %d", result);
                return false;
            }
        }

        commandBuffers_.resize(framebuffers_.size());
        VkCommandBufferAllocateInfo allocInfo{};
        allocInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        allocInfo.commandPool = commandPool_;
        allocInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        allocInfo.commandBufferCount = static_cast<uint32_t>(commandBuffers_.size());
        result = vkAllocateCommandBuffers(device_, &allocInfo, commandBuffers_.data());
        if (result != VK_SUCCESS) {
            LOGE("vkAllocateCommandBuffers failed: %d", result);
            return false;
        }

        return true;
    }

    void destroySwapchainResourcesLocked() {
        if (device_ != VK_NULL_HANDLE) {
            vkDeviceWaitIdle(device_);
        }

        if (!commandBuffers_.empty()) {
            vkFreeCommandBuffers(device_, commandPool_, static_cast<uint32_t>(commandBuffers_.size()),
                    commandBuffers_.data());
            commandBuffers_.clear();
        }

        for (VkFramebuffer framebuffer : framebuffers_) {
            vkDestroyFramebuffer(device_, framebuffer, nullptr);
        }
        framebuffers_.clear();

        if (bgPipeline_ != VK_NULL_HANDLE) {
            vkDestroyPipeline(device_, bgPipeline_, nullptr);
            bgPipeline_ = VK_NULL_HANDLE;
        }
        if (particlePipeline_ != VK_NULL_HANDLE) {
            vkDestroyPipeline(device_, particlePipeline_, nullptr);
            particlePipeline_ = VK_NULL_HANDLE;
        }
        if (lightPipeline_ != VK_NULL_HANDLE) {
            vkDestroyPipeline(device_, lightPipeline_, nullptr);
            lightPipeline_ = VK_NULL_HANDLE;
        }

        if (bgPipelineLayout_ != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(device_, bgPipelineLayout_, nullptr);
            bgPipelineLayout_ = VK_NULL_HANDLE;
        }
        if (particlePipelineLayout_ != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(device_, particlePipelineLayout_, nullptr);
            particlePipelineLayout_ = VK_NULL_HANDLE;
        }
        if (lightPipelineLayout_ != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(device_, lightPipelineLayout_, nullptr);
            lightPipelineLayout_ = VK_NULL_HANDLE;
        }

        if (renderPass_ != VK_NULL_HANDLE) {
            vkDestroyRenderPass(device_, renderPass_, nullptr);
            renderPass_ = VK_NULL_HANDLE;
        }

        for (VkImageView imageView : swapchainImageViews_) {
            vkDestroyImageView(device_, imageView, nullptr);
        }
        swapchainImageViews_.clear();
        swapchainImages_.clear();

        if (swapchain_ != VK_NULL_HANDLE) {
            vkDestroySwapchainKHR(device_, swapchain_, nullptr);
            swapchain_ = VK_NULL_HANDLE;
        }
    }

    bool recreateSwapchainLocked() {
        if (device_ == VK_NULL_HANDLE || surface_ == VK_NULL_HANDLE || window_ == nullptr) {
            return false;
        }
        destroySwapchainResourcesLocked();
        return createSwapchainResourcesLocked();
    }

    bool recreateSurfaceAndSwapchainLocked() {
        if (instance_ == VK_NULL_HANDLE || window_ == nullptr) {
            return false;
        }

        destroySwapchainResourcesLocked();

        if (surface_ != VK_NULL_HANDLE) {
            vkDestroySurfaceKHR(instance_, surface_, nullptr);
            surface_ = VK_NULL_HANDLE;
        }

        VkAndroidSurfaceCreateInfoKHR surfaceInfo{};
        surfaceInfo.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
        surfaceInfo.window = window_;
        VkResult result = vkCreateAndroidSurfaceKHR(instance_, &surfaceInfo, nullptr, &surface_);
        if (result != VK_SUCCESS) {
            LOGE("vkCreateAndroidSurfaceKHR(recreate) failed: %d", result);
            surface_ = VK_NULL_HANDLE;
            return false;
        }

        return createSwapchainResourcesLocked();
    }

    bool recoverRenderStateLocked() {
        if (device_ == VK_NULL_HANDLE || window_ == nullptr) {
            return false;
        }

        if (surface_ == VK_NULL_HANDLE) {
            if (!recreateSurfaceAndSwapchainLocked()) {
                return false;
            }
        }

        if (swapchain_ == VK_NULL_HANDLE || renderPass_ == VK_NULL_HANDLE
                || bgPipeline_ == VK_NULL_HANDLE || particlePipeline_ == VK_NULL_HANDLE
                || lightPipeline_ == VK_NULL_HANDLE || commandBuffers_.empty()) {
            if (!recreateSwapchainLocked()) {
                return false;
            }
        }

        if (bgDescriptorSet_ == VK_NULL_HANDLE || bgTextureImageView_ == VK_NULL_HANDLE
                || bgTextureSampler_ == VK_NULL_HANDLE) {
            if (!createBackgroundDescriptorResourcesLocked() || !ensureBackgroundTextureLocked()) {
                return false;
            }
        }

        if (lightDescriptorSet_ == VK_NULL_HANDLE || lightTextureImageView_ == VK_NULL_HANDLE
                || lightTextureSampler_ == VK_NULL_HANDLE) {
            if (!createLightDescriptorResourcesLocked() || !ensureLightTextureLocked()) {
                return false;
            }
        }

        return true;
    }

    void recreateInFlightFenceLocked() {
        if (device_ == VK_NULL_HANDLE) {
            return;
        }
        if (inFlightFence_ != VK_NULL_HANDLE) {
            vkDestroyFence(device_, inFlightFence_, nullptr);
            inFlightFence_ = VK_NULL_HANDLE;
        }
        VkFenceCreateInfo fenceInfo{};
        fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        vkCreateFence(device_, &fenceInfo, nullptr, &inFlightFence_);
    }

    bool createRenderPassLocked() {
        VkAttachmentDescription colorAttachment{};
        colorAttachment.format = swapchainFormat_;
        colorAttachment.samples = VK_SAMPLE_COUNT_1_BIT;
        colorAttachment.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
        colorAttachment.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        colorAttachment.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        colorAttachment.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        colorAttachment.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        colorAttachment.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;

        VkAttachmentReference colorAttachmentRef{};
        colorAttachmentRef.attachment = 0;
        colorAttachmentRef.layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;

        VkSubpassDescription subpass{};
        subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        subpass.colorAttachmentCount = 1;
        subpass.pColorAttachments = &colorAttachmentRef;

        VkSubpassDependency dependency{};
        dependency.srcSubpass = VK_SUBPASS_EXTERNAL;
        dependency.dstSubpass = 0;
        dependency.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dependency.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dependency.srcAccessMask = 0;
        dependency.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;

        VkRenderPassCreateInfo renderPassInfo{};
        renderPassInfo.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
        renderPassInfo.attachmentCount = 1;
        renderPassInfo.pAttachments = &colorAttachment;
        renderPassInfo.subpassCount = 1;
        renderPassInfo.pSubpasses = &subpass;
        renderPassInfo.dependencyCount = 1;
        renderPassInfo.pDependencies = &dependency;

        VkResult result = vkCreateRenderPass(device_, &renderPassInfo, nullptr, &renderPass_);
        if (result != VK_SUCCESS) {
            LOGE("vkCreateRenderPass failed: %d", result);
            return false;
        }
        return true;
    }

    bool createPipelinesLocked() {
        VkShaderModule bgVert = createShaderModuleLocked({
                "shaders/galaxyvk_bg.vert.spv",
                "galaxyvk_bg.vert.spv"});
        VkShaderModule bgFrag = createShaderModuleLocked({
                "shaders/galaxyvk_bg.frag.spv",
                "galaxyvk_bg.frag.spv"});
        VkShaderModule particleVert = createShaderModuleLocked({
                "shaders/galaxyvk_particles.vert.spv",
                "galaxyvk_particles.vert.spv"});
        VkShaderModule particleFrag = createShaderModuleLocked({
                "shaders/galaxyvk_particles.frag.spv",
                "galaxyvk_particles.frag.spv"});
        VkShaderModule lightVert = createShaderModuleLocked({
            "shaders/galaxyvk_light.vert.spv",
            "galaxyvk_light.vert.spv"});
        VkShaderModule lightFrag = createShaderModuleLocked({
            "shaders/galaxyvk_light.frag.spv",
            "galaxyvk_light.frag.spv"});
        if (bgVert == VK_NULL_HANDLE || bgFrag == VK_NULL_HANDLE
            || particleVert == VK_NULL_HANDLE || particleFrag == VK_NULL_HANDLE
            || lightVert == VK_NULL_HANDLE || lightFrag == VK_NULL_HANDLE) {
            if (bgVert != VK_NULL_HANDLE) vkDestroyShaderModule(device_, bgVert, nullptr);
            if (bgFrag != VK_NULL_HANDLE) vkDestroyShaderModule(device_, bgFrag, nullptr);
            if (particleVert != VK_NULL_HANDLE) vkDestroyShaderModule(device_, particleVert, nullptr);
            if (particleFrag != VK_NULL_HANDLE) vkDestroyShaderModule(device_, particleFrag, nullptr);
            if (lightVert != VK_NULL_HANDLE) vkDestroyShaderModule(device_, lightVert, nullptr);
            if (lightFrag != VK_NULL_HANDLE) vkDestroyShaderModule(device_, lightFrag, nullptr);
            return false;
        }

        bgPipelineLayout_ = createPipelineLayoutLocked(false, true, false);
        particlePipelineLayout_ = createPipelineLayoutLocked(true, false, false);
        lightPipelineLayout_ = createPipelineLayoutLocked(true, false, true);
        if (bgPipelineLayout_ == VK_NULL_HANDLE || particlePipelineLayout_ == VK_NULL_HANDLE
            || lightPipelineLayout_ == VK_NULL_HANDLE) {
            return false;
        }

        bgPipeline_ = createGraphicsPipelineLocked(bgVert, bgFrag, bgPipelineLayout_, PIPELINE_BG);
        particlePipeline_ = createGraphicsPipelineLocked(
            particleVert, particleFrag, particlePipelineLayout_, PIPELINE_PARTICLES);
        lightPipeline_ = createGraphicsPipelineLocked(lightVert, lightFrag, lightPipelineLayout_, PIPELINE_LIGHT);

        vkDestroyShaderModule(device_, bgVert, nullptr);
        vkDestroyShaderModule(device_, bgFrag, nullptr);
        vkDestroyShaderModule(device_, particleVert, nullptr);
        vkDestroyShaderModule(device_, particleFrag, nullptr);
        vkDestroyShaderModule(device_, lightVert, nullptr);
        vkDestroyShaderModule(device_, lightFrag, nullptr);

        return bgPipeline_ != VK_NULL_HANDLE
            && particlePipeline_ != VK_NULL_HANDLE
            && lightPipeline_ != VK_NULL_HANDLE;
    }

    VkPipelineLayout createPipelineLayoutLocked(bool withPushConstants,
            bool withBgDescriptor, bool withLightDescriptor) {
        VkPipelineLayoutCreateInfo layoutInfo{};
        layoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;

        VkDescriptorSetLayout descriptorLayouts[1] = {VK_NULL_HANDLE};
        if (withBgDescriptor) {
            descriptorLayouts[0] = bgDescriptorSetLayout_;
            layoutInfo.setLayoutCount = 1;
            layoutInfo.pSetLayouts = descriptorLayouts;
        } else if (withLightDescriptor) {
            descriptorLayouts[0] = lightDescriptorSetLayout_;
            layoutInfo.setLayoutCount = 1;
            layoutInfo.pSetLayouts = descriptorLayouts;
        }

        VkPushConstantRange pushRange{};
        if (withPushConstants) {
            pushRange.stageFlags = VK_SHADER_STAGE_VERTEX_BIT;
            pushRange.offset = 0;
            pushRange.size = sizeof(PushConstants);
            layoutInfo.pushConstantRangeCount = 1;
            layoutInfo.pPushConstantRanges = &pushRange;
        }

        VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
        VkResult result = vkCreatePipelineLayout(device_, &layoutInfo, nullptr, &pipelineLayout);
        if (result != VK_SUCCESS) {
            LOGE("vkCreatePipelineLayout failed: %d", result);
            return VK_NULL_HANDLE;
        }
        return pipelineLayout;
    }

    VkPipeline createGraphicsPipelineLocked(VkShaderModule vert, VkShaderModule frag,
            VkPipelineLayout layout, PipelineType type) {
        VkPipelineShaderStageCreateInfo vertStage{};
        vertStage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        vertStage.stage = VK_SHADER_STAGE_VERTEX_BIT;
        vertStage.module = vert;
        vertStage.pName = "main";

        VkPipelineShaderStageCreateInfo fragStage{};
        fragStage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        fragStage.stage = VK_SHADER_STAGE_FRAGMENT_BIT;
        fragStage.module = frag;
        fragStage.pName = "main";

        VkPipelineShaderStageCreateInfo stages[] = {vertStage, fragStage};

        VkPipelineVertexInputStateCreateInfo vertexInput{};
        vertexInput.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;

        VkVertexInputBindingDescription bindingDescription{};
        VkVertexInputAttributeDescription attributeDescriptions[2]{};
        if (type == PIPELINE_PARTICLES) {
            bindingDescription.binding = 0;
            bindingDescription.stride = sizeof(ParticleVertex);
            bindingDescription.inputRate = VK_VERTEX_INPUT_RATE_VERTEX;

            attributeDescriptions[0].binding = 0;
            attributeDescriptions[0].location = 0;
            attributeDescriptions[0].format = VK_FORMAT_R32G32B32_SFLOAT;
            attributeDescriptions[0].offset = 0;

            attributeDescriptions[1].binding = 0;
            attributeDescriptions[1].location = 1;
            attributeDescriptions[1].format = VK_FORMAT_R32G32B32A32_SFLOAT;
            attributeDescriptions[1].offset = sizeof(float) * 3;

            vertexInput.vertexBindingDescriptionCount = 1;
            vertexInput.pVertexBindingDescriptions = &bindingDescription;
            vertexInput.vertexAttributeDescriptionCount = 2;
            vertexInput.pVertexAttributeDescriptions = attributeDescriptions;
        }

        VkPipelineInputAssemblyStateCreateInfo inputAssembly{};
        inputAssembly.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
        if (type == PIPELINE_PARTICLES) {
            inputAssembly.topology = VK_PRIMITIVE_TOPOLOGY_POINT_LIST;
        } else if (type == PIPELINE_LIGHT) {
            inputAssembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
        } else {
            inputAssembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
        }

        VkPipelineViewportStateCreateInfo viewportState{};
        viewportState.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
        viewportState.viewportCount = 1;
        viewportState.scissorCount = 1;

        VkPipelineRasterizationStateCreateInfo rasterizer{};
        rasterizer.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
        rasterizer.polygonMode = VK_POLYGON_MODE_FILL;
        rasterizer.cullMode = VK_CULL_MODE_NONE;
        rasterizer.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
        rasterizer.lineWidth = 1.0f;

        VkPipelineMultisampleStateCreateInfo multisampling{};
        multisampling.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
        multisampling.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

        VkPipelineColorBlendAttachmentState colorBlendAttachment{};
        colorBlendAttachment.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
                | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
        colorBlendAttachment.blendEnable =
            (type == PIPELINE_PARTICLES || type == PIPELINE_LIGHT) ? VK_TRUE : VK_FALSE;
        if (type == PIPELINE_PARTICLES || type == PIPELINE_LIGHT) {
            colorBlendAttachment.srcColorBlendFactor = VK_BLEND_FACTOR_SRC_ALPHA;
            colorBlendAttachment.dstColorBlendFactor = VK_BLEND_FACTOR_ONE;
            colorBlendAttachment.colorBlendOp = VK_BLEND_OP_ADD;
            colorBlendAttachment.srcAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
            colorBlendAttachment.dstAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
            colorBlendAttachment.alphaBlendOp = VK_BLEND_OP_ADD;
        }

        VkPipelineColorBlendStateCreateInfo colorBlending{};
        colorBlending.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
        colorBlending.attachmentCount = 1;
        colorBlending.pAttachments = &colorBlendAttachment;

        std::array<VkDynamicState, 2> dynamicStates = {
                VK_DYNAMIC_STATE_VIEWPORT,
                VK_DYNAMIC_STATE_SCISSOR};
        VkPipelineDynamicStateCreateInfo dynamicState{};
        dynamicState.sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
        dynamicState.dynamicStateCount = static_cast<uint32_t>(dynamicStates.size());
        dynamicState.pDynamicStates = dynamicStates.data();

        VkGraphicsPipelineCreateInfo pipelineInfo{};
        pipelineInfo.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
        pipelineInfo.stageCount = 2;
        pipelineInfo.pStages = stages;
        pipelineInfo.pVertexInputState = &vertexInput;
        pipelineInfo.pInputAssemblyState = &inputAssembly;
        pipelineInfo.pViewportState = &viewportState;
        pipelineInfo.pRasterizationState = &rasterizer;
        pipelineInfo.pMultisampleState = &multisampling;
        pipelineInfo.pColorBlendState = &colorBlending;
        pipelineInfo.pDynamicState = &dynamicState;
        pipelineInfo.layout = layout;
        pipelineInfo.renderPass = renderPass_;
        pipelineInfo.subpass = 0;

        VkPipeline pipeline = VK_NULL_HANDLE;
        VkResult result = vkCreateGraphicsPipelines(device_, VK_NULL_HANDLE, 1, &pipelineInfo, nullptr, &pipeline);
        if (result != VK_SUCCESS) {
            LOGE("vkCreateGraphicsPipelines failed: %d", result);
            return VK_NULL_HANDLE;
        }
        return pipeline;
    }

    bool recordCommandBufferLocked(VkCommandBuffer commandBuffer, uint32_t imageIndex,
            uint32_t particleCount, const PushConstants& pushConstants) {
        VkCommandBufferBeginInfo beginInfo{};
        beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        VkResult result = vkBeginCommandBuffer(commandBuffer, &beginInfo);
        if (result != VK_SUCCESS) {
            LOGE("vkBeginCommandBuffer failed: %d", result);
            return false;
        }

        VkClearValue clearValue{};
        clearValue.color = {{0.0f, 0.0f, 0.0f, 1.0f}};

        VkRenderPassBeginInfo renderPassInfo{};
        renderPassInfo.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
        renderPassInfo.renderPass = renderPass_;
        renderPassInfo.framebuffer = framebuffers_[imageIndex];
        renderPassInfo.renderArea.offset = {0, 0};
        renderPassInfo.renderArea.extent = swapchainExtent_;
        renderPassInfo.clearValueCount = 1;
        renderPassInfo.pClearValues = &clearValue;

        vkCmdBeginRenderPass(commandBuffer, &renderPassInfo, VK_SUBPASS_CONTENTS_INLINE);

        VkViewport viewport{};
        viewport.x = 0.0f;
        viewport.y = 0.0f;
        viewport.width = static_cast<float>(swapchainExtent_.width);
        viewport.height = static_cast<float>(swapchainExtent_.height);
        viewport.minDepth = 0.0f;
        viewport.maxDepth = 1.0f;
        vkCmdSetViewport(commandBuffer, 0, 1, &viewport);

        VkRect2D scissor{};
        scissor.offset = {0, 0};
        scissor.extent = swapchainExtent_;
        vkCmdSetScissor(commandBuffer, 0, 1, &scissor);

        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, bgPipeline_);
        vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
            bgPipelineLayout_, 0, 1, &bgDescriptorSet_, 0, nullptr);
        vkCmdDraw(commandBuffer, 3, 1, 0, 0);

        if (particleCount > 0) {
            VkDeviceSize offsets[] = {0};
            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, particlePipeline_);
            vkCmdPushConstants(commandBuffer, particlePipelineLayout_, VK_SHADER_STAGE_VERTEX_BIT,
                    0, sizeof(PushConstants), &pushConstants);
            vkCmdBindVertexBuffers(commandBuffer, 0, 1, &particleBuffer_, offsets);
            vkCmdDraw(commandBuffer, particleCount, 1, 0, 0);
        }

        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, lightPipeline_);
        vkCmdPushConstants(commandBuffer, lightPipelineLayout_, VK_SHADER_STAGE_VERTEX_BIT,
            0, sizeof(PushConstants), &pushConstants);
        vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
            lightPipelineLayout_, 0, 1, &lightDescriptorSet_, 0, nullptr);
        vkCmdDraw(commandBuffer, 4, 1, 0, 0);

        vkCmdEndRenderPass(commandBuffer);
        result = vkEndCommandBuffer(commandBuffer);
        if (result != VK_SUCCESS) {
            LOGE("vkEndCommandBuffer failed: %d", result);
            return false;
        }
        return true;
    }

    void uploadParticlesLocked(JNIEnv* env, jfloatArray positionsArray, jfloatArray colorsArray, uint32_t count) {
        if (particleMappedMemory_ == nullptr || count == 0 || positionsArray == nullptr) {
            return;
        }

        jfloat* positions = env->GetFloatArrayElements(positionsArray, nullptr);
        if (positions == nullptr) {
            return;
        }

        jfloat* colors = nullptr;
        const bool hasColors = (colorsArray != nullptr);
        if (hasColors) {
            colors = env->GetFloatArrayElements(colorsArray, nullptr);
            if (colors == nullptr) {
                env->ReleaseFloatArrayElements(positionsArray, positions, JNI_ABORT);
                return;
            }
        }
        auto* vertices = reinterpret_cast<ParticleVertex*>(particleMappedMemory_);

        for (uint32_t i = 0; i < count; ++i) {
            const uint32_t posIndex = i * 3;
            vertices[i].angle = positions[posIndex];
            vertices[i].dist = positions[posIndex + 1];
            vertices[i].z = positions[posIndex + 2];
            if (hasColors) {
                const uint32_t colorIndex = i * 4;
                vertices[i].r = colors[colorIndex];
                vertices[i].g = colors[colorIndex + 1];
                vertices[i].b = colors[colorIndex + 2];
                vertices[i].size = colors[colorIndex + 3];
            }
        }

        env->ReleaseFloatArrayElements(positionsArray, positions, JNI_ABORT);
        if (hasColors) {
            env->ReleaseFloatArrayElements(colorsArray, colors, JNI_ABORT);
        }
    }

    uint32_t findMemoryTypeLocked(uint32_t typeFilter, VkMemoryPropertyFlags properties) const {
        VkPhysicalDeviceMemoryProperties memProperties{};
        vkGetPhysicalDeviceMemoryProperties(physicalDevice_, &memProperties);
        for (uint32_t i = 0; i < memProperties.memoryTypeCount; i++) {
            if ((typeFilter & (1u << i)) != 0 && (memProperties.memoryTypes[i].propertyFlags & properties) == properties) {
                return i;
            }
        }
        return 0;
    }

    bool createLightDescriptorResourcesLocked() {
        if (lightDescriptorSetLayout_ != VK_NULL_HANDLE && lightDescriptorPool_ != VK_NULL_HANDLE
                && lightDescriptorSet_ != VK_NULL_HANDLE) {
            return true;
        }

        VkDescriptorSetLayoutBinding binding{};
        binding.binding = 0;
        binding.descriptorCount = 1;
        binding.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        binding.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;

        VkDescriptorSetLayoutCreateInfo layoutInfo{};
        layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        layoutInfo.bindingCount = 1;
        layoutInfo.pBindings = &binding;
        VkResult result = vkCreateDescriptorSetLayout(device_, &layoutInfo, nullptr, &lightDescriptorSetLayout_);
        if (result != VK_SUCCESS) {
            LOGE("vkCreateDescriptorSetLayout failed: %d", result);
            return false;
        }

        VkDescriptorPoolSize poolSize{};
        poolSize.type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        poolSize.descriptorCount = 1;
        VkDescriptorPoolCreateInfo poolInfo{};
        poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
        poolInfo.poolSizeCount = 1;
        poolInfo.pPoolSizes = &poolSize;
        poolInfo.maxSets = 1;
        result = vkCreateDescriptorPool(device_, &poolInfo, nullptr, &lightDescriptorPool_);
        if (result != VK_SUCCESS) {
            LOGE("vkCreateDescriptorPool failed: %d", result);
            return false;
        }

        VkDescriptorSetAllocateInfo allocInfo{};
        allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        allocInfo.descriptorPool = lightDescriptorPool_;
        allocInfo.descriptorSetCount = 1;
        allocInfo.pSetLayouts = &lightDescriptorSetLayout_;
        result = vkAllocateDescriptorSets(device_, &allocInfo, &lightDescriptorSet_);
        if (result != VK_SUCCESS) {
            LOGE("vkAllocateDescriptorSets failed: %d", result);
            return false;
        }
        return true;
    }

    bool createBackgroundDescriptorResourcesLocked() {
        if (bgDescriptorSetLayout_ != VK_NULL_HANDLE && bgDescriptorPool_ != VK_NULL_HANDLE
                && bgDescriptorSet_ != VK_NULL_HANDLE) {
            return true;
        }

        VkDescriptorSetLayoutBinding binding{};
        binding.binding = 0;
        binding.descriptorCount = 1;
        binding.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        binding.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;

        VkDescriptorSetLayoutCreateInfo layoutInfo{};
        layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        layoutInfo.bindingCount = 1;
        layoutInfo.pBindings = &binding;
        VkResult result = vkCreateDescriptorSetLayout(device_, &layoutInfo, nullptr, &bgDescriptorSetLayout_);
        if (result != VK_SUCCESS) {
            LOGE("vkCreateDescriptorSetLayout(bg) failed: %d", result);
            return false;
        }

        VkDescriptorPoolSize poolSize{};
        poolSize.type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        poolSize.descriptorCount = 1;
        VkDescriptorPoolCreateInfo poolInfo{};
        poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
        poolInfo.poolSizeCount = 1;
        poolInfo.pPoolSizes = &poolSize;
        poolInfo.maxSets = 1;
        result = vkCreateDescriptorPool(device_, &poolInfo, nullptr, &bgDescriptorPool_);
        if (result != VK_SUCCESS) {
            LOGE("vkCreateDescriptorPool(bg) failed: %d", result);
            return false;
        }

        VkDescriptorSetAllocateInfo allocInfo{};
        allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        allocInfo.descriptorPool = bgDescriptorPool_;
        allocInfo.descriptorSetCount = 1;
        allocInfo.pSetLayouts = &bgDescriptorSetLayout_;
        result = vkAllocateDescriptorSets(device_, &allocInfo, &bgDescriptorSet_);
        if (result != VK_SUCCESS) {
            LOGE("vkAllocateDescriptorSets(bg) failed: %d", result);
            return false;
        }
        return true;
    }

    bool ensureBackgroundTextureLocked() {
        if (bgTextureImage_ != VK_NULL_HANDLE && bgTextureImageView_ != VK_NULL_HANDLE
                && bgTextureSampler_ != VK_NULL_HANDLE) {
            return true;
        }

        if (!pendingBgPixels_.empty() && pendingBgWidth_ > 0 && pendingBgHeight_ > 0) {
            return uploadBackgroundTextureLocked(pendingBgPixels_.data(), pendingBgWidth_, pendingBgHeight_);
        }

        const uint8_t fallbackPixel[4] = {0, 0, 0, 255};
        return uploadBackgroundTextureLocked(fallbackPixel, 1, 1);
    }

    bool uploadBackgroundTextureLocked(const uint8_t* rgbaPixels, uint32_t width, uint32_t height) {
        if (rgbaPixels == nullptr || width == 0 || height == 0) {
            return false;
        }

        destroyBackgroundTextureLocked();

        VkDeviceSize imageSize = static_cast<VkDeviceSize>(width) * height * 4u;

        VkBuffer stagingBuffer = VK_NULL_HANDLE;
        VkDeviceMemory stagingMemory = VK_NULL_HANDLE;

        VkBufferCreateInfo bufferInfo{};
        bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        bufferInfo.size = imageSize;
        bufferInfo.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
        bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        VkResult result = vkCreateBuffer(device_, &bufferInfo, nullptr, &stagingBuffer);
        if (result != VK_SUCCESS) {
            LOGE("vkCreateBuffer(bg staging) failed: %d", result);
            return false;
        }

        VkMemoryRequirements stagingReq{};
        vkGetBufferMemoryRequirements(device_, stagingBuffer, &stagingReq);
        VkMemoryAllocateInfo stagingAlloc{};
        stagingAlloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        stagingAlloc.allocationSize = stagingReq.size;
        stagingAlloc.memoryTypeIndex = findMemoryTypeLocked(stagingReq.memoryTypeBits,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        result = vkAllocateMemory(device_, &stagingAlloc, nullptr, &stagingMemory);
        if (result != VK_SUCCESS) {
            LOGE("vkAllocateMemory(bg staging) failed: %d", result);
            vkDestroyBuffer(device_, stagingBuffer, nullptr);
            return false;
        }
        vkBindBufferMemory(device_, stagingBuffer, stagingMemory, 0);

        void* mapped = nullptr;
        result = vkMapMemory(device_, stagingMemory, 0, imageSize, 0, &mapped);
        if (result != VK_SUCCESS || mapped == nullptr) {
            LOGE("vkMapMemory(bg staging) failed: %d", result);
            vkFreeMemory(device_, stagingMemory, nullptr);
            vkDestroyBuffer(device_, stagingBuffer, nullptr);
            return false;
        }
        std::memcpy(mapped, rgbaPixels, static_cast<size_t>(imageSize));
        vkUnmapMemory(device_, stagingMemory);

        VkImageCreateInfo imageInfo{};
        imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        imageInfo.imageType = VK_IMAGE_TYPE_2D;
        imageInfo.extent.width = width;
        imageInfo.extent.height = height;
        imageInfo.extent.depth = 1;
        imageInfo.mipLevels = 1;
        imageInfo.arrayLayers = 1;
        imageInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
        imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
        imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        imageInfo.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;

        result = vkCreateImage(device_, &imageInfo, nullptr, &bgTextureImage_);
        if (result != VK_SUCCESS) {
            LOGE("vkCreateImage(bg) failed: %d", result);
            vkFreeMemory(device_, stagingMemory, nullptr);
            vkDestroyBuffer(device_, stagingBuffer, nullptr);
            return false;
        }

        VkMemoryRequirements imageReq{};
        vkGetImageMemoryRequirements(device_, bgTextureImage_, &imageReq);
        VkMemoryAllocateInfo imageAlloc{};
        imageAlloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        imageAlloc.allocationSize = imageReq.size;
        imageAlloc.memoryTypeIndex = findMemoryTypeLocked(imageReq.memoryTypeBits,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        result = vkAllocateMemory(device_, &imageAlloc, nullptr, &bgTextureMemory_);
        if (result != VK_SUCCESS) {
            LOGE("vkAllocateMemory(bg) failed: %d", result);
            vkDestroyImage(device_, bgTextureImage_, nullptr);
            bgTextureImage_ = VK_NULL_HANDLE;
            vkFreeMemory(device_, stagingMemory, nullptr);
            vkDestroyBuffer(device_, stagingBuffer, nullptr);
            return false;
        }
        vkBindImageMemory(device_, bgTextureImage_, bgTextureMemory_, 0);

        VkCommandBuffer commandBuffer = beginOneTimeCommandsLocked();
        if (commandBuffer == VK_NULL_HANDLE) {
            vkFreeMemory(device_, stagingMemory, nullptr);
            vkDestroyBuffer(device_, stagingBuffer, nullptr);
            return false;
        }

        transitionImageLayoutLocked(commandBuffer, bgTextureImage_,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

        VkBufferImageCopy copyRegion{};
        copyRegion.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        copyRegion.imageSubresource.mipLevel = 0;
        copyRegion.imageSubresource.baseArrayLayer = 0;
        copyRegion.imageSubresource.layerCount = 1;
        copyRegion.imageExtent = {width, height, 1};
        vkCmdCopyBufferToImage(commandBuffer, stagingBuffer, bgTextureImage_,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copyRegion);

        transitionImageLayoutLocked(commandBuffer, bgTextureImage_,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

        if (!endOneTimeCommandsLocked(commandBuffer)) {
            vkFreeMemory(device_, stagingMemory, nullptr);
            vkDestroyBuffer(device_, stagingBuffer, nullptr);
            return false;
        }

        vkFreeMemory(device_, stagingMemory, nullptr);
        vkDestroyBuffer(device_, stagingBuffer, nullptr);

        VkImageViewCreateInfo viewInfo{};
        viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        viewInfo.image = bgTextureImage_;
        viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
        viewInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
        viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        viewInfo.subresourceRange.baseMipLevel = 0;
        viewInfo.subresourceRange.levelCount = 1;
        viewInfo.subresourceRange.baseArrayLayer = 0;
        viewInfo.subresourceRange.layerCount = 1;
        result = vkCreateImageView(device_, &viewInfo, nullptr, &bgTextureImageView_);
        if (result != VK_SUCCESS) {
            LOGE("vkCreateImageView(bg) failed: %d", result);
            return false;
        }

        VkSamplerCreateInfo samplerInfo{};
        samplerInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
        samplerInfo.magFilter = VK_FILTER_LINEAR;
        samplerInfo.minFilter = VK_FILTER_LINEAR;
        samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_LINEAR;
        samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        samplerInfo.maxAnisotropy = 1.0f;
        result = vkCreateSampler(device_, &samplerInfo, nullptr, &bgTextureSampler_);
        if (result != VK_SUCCESS) {
            LOGE("vkCreateSampler(bg) failed: %d", result);
            return false;
        }

        VkDescriptorImageInfo imageDescriptor{};
        imageDescriptor.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        imageDescriptor.imageView = bgTextureImageView_;
        imageDescriptor.sampler = bgTextureSampler_;

        VkWriteDescriptorSet write{};
        write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        write.dstSet = bgDescriptorSet_;
        write.dstBinding = 0;
        write.descriptorCount = 1;
        write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        write.pImageInfo = &imageDescriptor;
        vkUpdateDescriptorSets(device_, 1, &write, 0, nullptr);

        bgTextureWidth_ = width;
        bgTextureHeight_ = height;
        return true;
    }

    void destroyBackgroundTextureLocked() {
        if (bgTextureSampler_ != VK_NULL_HANDLE) {
            vkDestroySampler(device_, bgTextureSampler_, nullptr);
            bgTextureSampler_ = VK_NULL_HANDLE;
        }
        if (bgTextureImageView_ != VK_NULL_HANDLE) {
            vkDestroyImageView(device_, bgTextureImageView_, nullptr);
            bgTextureImageView_ = VK_NULL_HANDLE;
        }
        if (bgTextureImage_ != VK_NULL_HANDLE) {
            vkDestroyImage(device_, bgTextureImage_, nullptr);
            bgTextureImage_ = VK_NULL_HANDLE;
        }
        if (bgTextureMemory_ != VK_NULL_HANDLE) {
            vkFreeMemory(device_, bgTextureMemory_, nullptr);
            bgTextureMemory_ = VK_NULL_HANDLE;
        }
        bgTextureWidth_ = 0;
        bgTextureHeight_ = 0;
    }

    bool ensureLightTextureLocked() {
        if (lightTextureImage_ != VK_NULL_HANDLE && lightTextureImageView_ != VK_NULL_HANDLE
                && lightTextureSampler_ != VK_NULL_HANDLE) {
            return true;
        }

        if (!pendingLightPixels_.empty() && pendingLightWidth_ > 0 && pendingLightHeight_ > 0) {
            return uploadLightTextureLocked(pendingLightPixels_.data(), pendingLightWidth_, pendingLightHeight_);
        }

        const uint8_t fallbackPixel[4] = {255, 255, 255, 255};
        return uploadLightTextureLocked(fallbackPixel, 1, 1);
    }

    bool uploadLightTextureLocked(const uint8_t* rgbaPixels, uint32_t width, uint32_t height) {
        if (rgbaPixels == nullptr || width == 0 || height == 0) {
            return false;
        }

        destroyLightTextureLocked();

        VkDeviceSize imageSize = static_cast<VkDeviceSize>(width) * height * 4u;

        VkBuffer stagingBuffer = VK_NULL_HANDLE;
        VkDeviceMemory stagingMemory = VK_NULL_HANDLE;

        VkBufferCreateInfo bufferInfo{};
        bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        bufferInfo.size = imageSize;
        bufferInfo.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
        bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        VkResult result = vkCreateBuffer(device_, &bufferInfo, nullptr, &stagingBuffer);
        if (result != VK_SUCCESS) {
            LOGE("vkCreateBuffer(staging) failed: %d", result);
            return false;
        }

        VkMemoryRequirements stagingReq{};
        vkGetBufferMemoryRequirements(device_, stagingBuffer, &stagingReq);
        VkMemoryAllocateInfo stagingAlloc{};
        stagingAlloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        stagingAlloc.allocationSize = stagingReq.size;
        stagingAlloc.memoryTypeIndex = findMemoryTypeLocked(stagingReq.memoryTypeBits,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        result = vkAllocateMemory(device_, &stagingAlloc, nullptr, &stagingMemory);
        if (result != VK_SUCCESS) {
            LOGE("vkAllocateMemory(staging) failed: %d", result);
            vkDestroyBuffer(device_, stagingBuffer, nullptr);
            return false;
        }
        vkBindBufferMemory(device_, stagingBuffer, stagingMemory, 0);

        void* mapped = nullptr;
        result = vkMapMemory(device_, stagingMemory, 0, imageSize, 0, &mapped);
        if (result != VK_SUCCESS || mapped == nullptr) {
            LOGE("vkMapMemory(staging) failed: %d", result);
            vkFreeMemory(device_, stagingMemory, nullptr);
            vkDestroyBuffer(device_, stagingBuffer, nullptr);
            return false;
        }
        std::memcpy(mapped, rgbaPixels, static_cast<size_t>(imageSize));
        vkUnmapMemory(device_, stagingMemory);

        VkImageCreateInfo imageInfo{};
        imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        imageInfo.imageType = VK_IMAGE_TYPE_2D;
        imageInfo.extent.width = width;
        imageInfo.extent.height = height;
        imageInfo.extent.depth = 1;
        imageInfo.mipLevels = 1;
        imageInfo.arrayLayers = 1;
        imageInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
        imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
        imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        imageInfo.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;

        result = vkCreateImage(device_, &imageInfo, nullptr, &lightTextureImage_);
        if (result != VK_SUCCESS) {
            LOGE("vkCreateImage(light) failed: %d", result);
            vkFreeMemory(device_, stagingMemory, nullptr);
            vkDestroyBuffer(device_, stagingBuffer, nullptr);
            return false;
        }

        VkMemoryRequirements imageReq{};
        vkGetImageMemoryRequirements(device_, lightTextureImage_, &imageReq);
        VkMemoryAllocateInfo imageAlloc{};
        imageAlloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        imageAlloc.allocationSize = imageReq.size;
        imageAlloc.memoryTypeIndex = findMemoryTypeLocked(imageReq.memoryTypeBits,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        result = vkAllocateMemory(device_, &imageAlloc, nullptr, &lightTextureMemory_);
        if (result != VK_SUCCESS) {
            LOGE("vkAllocateMemory(light) failed: %d", result);
            vkDestroyImage(device_, lightTextureImage_, nullptr);
            lightTextureImage_ = VK_NULL_HANDLE;
            vkFreeMemory(device_, stagingMemory, nullptr);
            vkDestroyBuffer(device_, stagingBuffer, nullptr);
            return false;
        }
        vkBindImageMemory(device_, lightTextureImage_, lightTextureMemory_, 0);

        VkCommandBuffer commandBuffer = beginOneTimeCommandsLocked();
        if (commandBuffer == VK_NULL_HANDLE) {
            vkFreeMemory(device_, stagingMemory, nullptr);
            vkDestroyBuffer(device_, stagingBuffer, nullptr);
            return false;
        }

        transitionImageLayoutLocked(commandBuffer, lightTextureImage_,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

        VkBufferImageCopy copyRegion{};
        copyRegion.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        copyRegion.imageSubresource.mipLevel = 0;
        copyRegion.imageSubresource.baseArrayLayer = 0;
        copyRegion.imageSubresource.layerCount = 1;
        copyRegion.imageExtent = {width, height, 1};
        vkCmdCopyBufferToImage(commandBuffer, stagingBuffer, lightTextureImage_,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copyRegion);

        transitionImageLayoutLocked(commandBuffer, lightTextureImage_,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

        if (!endOneTimeCommandsLocked(commandBuffer)) {
            vkFreeMemory(device_, stagingMemory, nullptr);
            vkDestroyBuffer(device_, stagingBuffer, nullptr);
            return false;
        }

        vkFreeMemory(device_, stagingMemory, nullptr);
        vkDestroyBuffer(device_, stagingBuffer, nullptr);

        VkImageViewCreateInfo viewInfo{};
        viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        viewInfo.image = lightTextureImage_;
        viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
        viewInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
        viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        viewInfo.subresourceRange.baseMipLevel = 0;
        viewInfo.subresourceRange.levelCount = 1;
        viewInfo.subresourceRange.baseArrayLayer = 0;
        viewInfo.subresourceRange.layerCount = 1;
        result = vkCreateImageView(device_, &viewInfo, nullptr, &lightTextureImageView_);
        if (result != VK_SUCCESS) {
            LOGE("vkCreateImageView(light) failed: %d", result);
            return false;
        }

        VkSamplerCreateInfo samplerInfo{};
        samplerInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
        samplerInfo.magFilter = VK_FILTER_LINEAR;
        samplerInfo.minFilter = VK_FILTER_LINEAR;
        samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_LINEAR;
        samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        samplerInfo.maxAnisotropy = 1.0f;
        result = vkCreateSampler(device_, &samplerInfo, nullptr, &lightTextureSampler_);
        if (result != VK_SUCCESS) {
            LOGE("vkCreateSampler(light) failed: %d", result);
            return false;
        }

        VkDescriptorImageInfo imageDescriptor{};
        imageDescriptor.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        imageDescriptor.imageView = lightTextureImageView_;
        imageDescriptor.sampler = lightTextureSampler_;

        VkWriteDescriptorSet write{};
        write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        write.dstSet = lightDescriptorSet_;
        write.dstBinding = 0;
        write.descriptorCount = 1;
        write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        write.pImageInfo = &imageDescriptor;
        vkUpdateDescriptorSets(device_, 1, &write, 0, nullptr);

        lightTextureWidth_ = width;
        lightTextureHeight_ = height;
        return true;
    }

    void destroyLightTextureLocked() {
        if (lightTextureSampler_ != VK_NULL_HANDLE) {
            vkDestroySampler(device_, lightTextureSampler_, nullptr);
            lightTextureSampler_ = VK_NULL_HANDLE;
        }
        if (lightTextureImageView_ != VK_NULL_HANDLE) {
            vkDestroyImageView(device_, lightTextureImageView_, nullptr);
            lightTextureImageView_ = VK_NULL_HANDLE;
        }
        if (lightTextureImage_ != VK_NULL_HANDLE) {
            vkDestroyImage(device_, lightTextureImage_, nullptr);
            lightTextureImage_ = VK_NULL_HANDLE;
        }
        if (lightTextureMemory_ != VK_NULL_HANDLE) {
            vkFreeMemory(device_, lightTextureMemory_, nullptr);
            lightTextureMemory_ = VK_NULL_HANDLE;
        }
        lightTextureWidth_ = 0;
        lightTextureHeight_ = 0;
    }

    VkCommandBuffer beginOneTimeCommandsLocked() {
        VkCommandBufferAllocateInfo allocInfo{};
        allocInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        allocInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        allocInfo.commandPool = commandPool_;
        allocInfo.commandBufferCount = 1;

        VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
        VkResult result = vkAllocateCommandBuffers(device_, &allocInfo, &commandBuffer);
        if (result != VK_SUCCESS) {
            LOGE("vkAllocateCommandBuffers(one-time) failed: %d", result);
            return VK_NULL_HANDLE;
        }

        VkCommandBufferBeginInfo beginInfo{};
        beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        result = vkBeginCommandBuffer(commandBuffer, &beginInfo);
        if (result != VK_SUCCESS) {
            LOGE("vkBeginCommandBuffer(one-time) failed: %d", result);
            vkFreeCommandBuffers(device_, commandPool_, 1, &commandBuffer);
            return VK_NULL_HANDLE;
        }
        return commandBuffer;
    }

    bool endOneTimeCommandsLocked(VkCommandBuffer commandBuffer) {
        VkResult result = vkEndCommandBuffer(commandBuffer);
        if (result != VK_SUCCESS) {
            LOGE("vkEndCommandBuffer(one-time) failed: %d", result);
            vkFreeCommandBuffers(device_, commandPool_, 1, &commandBuffer);
            return false;
        }

        VkSubmitInfo submitInfo{};
        submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        submitInfo.commandBufferCount = 1;
        submitInfo.pCommandBuffers = &commandBuffer;
        result = vkQueueSubmit(graphicsQueue_, 1, &submitInfo, VK_NULL_HANDLE);
        if (result != VK_SUCCESS) {
            LOGE("vkQueueSubmit(one-time) failed: %d", result);
            vkFreeCommandBuffers(device_, commandPool_, 1, &commandBuffer);
            return false;
        }
        vkQueueWaitIdle(graphicsQueue_);
        vkFreeCommandBuffers(device_, commandPool_, 1, &commandBuffer);
        return true;
    }

    void transitionImageLayoutLocked(VkCommandBuffer commandBuffer, VkImage image,
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

        VkPipelineStageFlags srcStage;
        VkPipelineStageFlags dstStage;
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

        vkCmdPipelineBarrier(commandBuffer,
                srcStage, dstStage,
                0,
                0, nullptr,
                0, nullptr,
                1, &barrier);
    }

    VkShaderModule createShaderModuleLocked(std::initializer_list<const char*> candidates) {
        std::vector<uint8_t> bytes;
        for (const char* path : candidates) {
            bytes = readAssetLocked(path);
            if (!bytes.empty()) {
                break;
            }
        }

        if (bytes.empty()) {
            LOGE("Failed to load shader asset");
            return VK_NULL_HANDLE;
        }

        VkShaderModuleCreateInfo createInfo{};
        createInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
        createInfo.codeSize = bytes.size();
        createInfo.pCode = reinterpret_cast<const uint32_t*>(bytes.data());

        VkShaderModule shaderModule = VK_NULL_HANDLE;
        VkResult result = vkCreateShaderModule(device_, &createInfo, nullptr, &shaderModule);
        if (result != VK_SUCCESS) {
            LOGE("vkCreateShaderModule failed: %d", result);
            return VK_NULL_HANDLE;
        }
        return shaderModule;
    }

    std::vector<uint8_t> readAssetLocked(const char* path) const {
        if (assetManager_ == nullptr) {
            return {};
        }
        AAsset* asset = AAssetManager_open(assetManager_, path, AASSET_MODE_BUFFER);
        if (asset == nullptr) {
            return {};
        }
        const off_t length = AAsset_getLength(asset);
        std::vector<uint8_t> data(static_cast<size_t>(length));
        const int64_t read = AAsset_read(asset, data.data(), length);
        AAsset_close(asset);
        if (read != length) {
            return {};
        }
        return data;
    }

    bool isReadyLocked() const {
        return instance_ != VK_NULL_HANDLE && device_ != VK_NULL_HANDLE && surface_ != VK_NULL_HANDLE
                && swapchain_ != VK_NULL_HANDLE && renderPass_ != VK_NULL_HANDLE
                && bgPipeline_ != VK_NULL_HANDLE
                && bgDescriptorSet_ != VK_NULL_HANDLE
                && bgTextureImageView_ != VK_NULL_HANDLE
                && bgTextureSampler_ != VK_NULL_HANDLE
                && particlePipeline_ != VK_NULL_HANDLE
                && lightPipeline_ != VK_NULL_HANDLE
                && lightDescriptorSet_ != VK_NULL_HANDLE
                && lightTextureImageView_ != VK_NULL_HANDLE
                && lightTextureSampler_ != VK_NULL_HANDLE;
    }

    void destroySurfaceLocked() {
        destroySwapchainResourcesLocked();

        if (surface_ != VK_NULL_HANDLE) {
            vkDestroySurfaceKHR(instance_, surface_, nullptr);
            surface_ = VK_NULL_HANDLE;
        }

        if (window_ != nullptr) {
            ANativeWindow_release(window_);
            window_ = nullptr;
        }
    }

    void destroyDeviceLocked() {
        if (device_ == VK_NULL_HANDLE) {
            return;
        }
        vkDeviceWaitIdle(device_);

        if (particleMappedMemory_ != nullptr) {
            vkUnmapMemory(device_, particleMemory_);
            particleMappedMemory_ = nullptr;
        }
        destroyBackgroundTextureLocked();
        destroyLightTextureLocked();
        if (particleBuffer_ != VK_NULL_HANDLE) {
            vkDestroyBuffer(device_, particleBuffer_, nullptr);
            particleBuffer_ = VK_NULL_HANDLE;
        }
        if (particleMemory_ != VK_NULL_HANDLE) {
            vkFreeMemory(device_, particleMemory_, nullptr);
            particleMemory_ = VK_NULL_HANDLE;
        }
        if (imageAvailableSemaphore_ != VK_NULL_HANDLE) {
            vkDestroySemaphore(device_, imageAvailableSemaphore_, nullptr);
            imageAvailableSemaphore_ = VK_NULL_HANDLE;
        }
        if (renderFinishedSemaphore_ != VK_NULL_HANDLE) {
            vkDestroySemaphore(device_, renderFinishedSemaphore_, nullptr);
            renderFinishedSemaphore_ = VK_NULL_HANDLE;
        }
        if (inFlightFence_ != VK_NULL_HANDLE) {
            vkDestroyFence(device_, inFlightFence_, nullptr);
            inFlightFence_ = VK_NULL_HANDLE;
        }
        if (commandPool_ != VK_NULL_HANDLE) {
            vkDestroyCommandPool(device_, commandPool_, nullptr);
            commandPool_ = VK_NULL_HANDLE;
        }
        if (bgDescriptorPool_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorPool(device_, bgDescriptorPool_, nullptr);
            bgDescriptorPool_ = VK_NULL_HANDLE;
            bgDescriptorSet_ = VK_NULL_HANDLE;
        }
        if (bgDescriptorSetLayout_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(device_, bgDescriptorSetLayout_, nullptr);
            bgDescriptorSetLayout_ = VK_NULL_HANDLE;
        }
        if (lightDescriptorPool_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorPool(device_, lightDescriptorPool_, nullptr);
            lightDescriptorPool_ = VK_NULL_HANDLE;
            lightDescriptorSet_ = VK_NULL_HANDLE;
        }
        if (lightDescriptorSetLayout_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(device_, lightDescriptorSetLayout_, nullptr);
            lightDescriptorSetLayout_ = VK_NULL_HANDLE;
        }
        vkDestroyDevice(device_, nullptr);
        device_ = VK_NULL_HANDLE;
        physicalDevice_ = VK_NULL_HANDLE;
        graphicsQueue_ = VK_NULL_HANDLE;
        queueFamilyIndex_ = 0;
    }

    AAssetManager* assetManager_ = nullptr;
    std::mutex mutex_;

    int width_ = 0;
    int height_ = 0;

    ANativeWindow* window_ = nullptr;

    VkInstance instance_ = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice_ = VK_NULL_HANDLE;
    VkDevice device_ = VK_NULL_HANDLE;
    VkQueue graphicsQueue_ = VK_NULL_HANDLE;
    uint32_t queueFamilyIndex_ = 0;

    VkSurfaceKHR surface_ = VK_NULL_HANDLE;
    VkSwapchainKHR swapchain_ = VK_NULL_HANDLE;
    VkFormat swapchainFormat_ = VK_FORMAT_UNDEFINED;
    VkExtent2D swapchainExtent_{};

    std::vector<VkImage> swapchainImages_;
    std::vector<VkImageView> swapchainImageViews_;
    std::vector<VkFramebuffer> framebuffers_;
    std::vector<VkCommandBuffer> commandBuffers_;

    VkRenderPass renderPass_ = VK_NULL_HANDLE;
    VkPipelineLayout bgPipelineLayout_ = VK_NULL_HANDLE;
    VkPipelineLayout particlePipelineLayout_ = VK_NULL_HANDLE;
    VkPipelineLayout lightPipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline bgPipeline_ = VK_NULL_HANDLE;
    VkPipeline particlePipeline_ = VK_NULL_HANDLE;
    VkPipeline lightPipeline_ = VK_NULL_HANDLE;
    VkCommandPool commandPool_ = VK_NULL_HANDLE;

    VkDescriptorSetLayout bgDescriptorSetLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool bgDescriptorPool_ = VK_NULL_HANDLE;
    VkDescriptorSet bgDescriptorSet_ = VK_NULL_HANDLE;

    VkImage bgTextureImage_ = VK_NULL_HANDLE;
    VkDeviceMemory bgTextureMemory_ = VK_NULL_HANDLE;
    VkImageView bgTextureImageView_ = VK_NULL_HANDLE;
    VkSampler bgTextureSampler_ = VK_NULL_HANDLE;
    uint32_t bgTextureWidth_ = 0;
    uint32_t bgTextureHeight_ = 0;
    std::vector<uint8_t> pendingBgPixels_;
    uint32_t pendingBgWidth_ = 0;
    uint32_t pendingBgHeight_ = 0;

    VkDescriptorSetLayout lightDescriptorSetLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool lightDescriptorPool_ = VK_NULL_HANDLE;
    VkDescriptorSet lightDescriptorSet_ = VK_NULL_HANDLE;

    VkImage lightTextureImage_ = VK_NULL_HANDLE;
    VkDeviceMemory lightTextureMemory_ = VK_NULL_HANDLE;
    VkImageView lightTextureImageView_ = VK_NULL_HANDLE;
    VkSampler lightTextureSampler_ = VK_NULL_HANDLE;
    uint32_t lightTextureWidth_ = 0;
    uint32_t lightTextureHeight_ = 0;
    std::vector<uint8_t> pendingLightPixels_;
    uint32_t pendingLightWidth_ = 0;
    uint32_t pendingLightHeight_ = 0;

    VkSemaphore imageAvailableSemaphore_ = VK_NULL_HANDLE;
    VkSemaphore renderFinishedSemaphore_ = VK_NULL_HANDLE;
    VkFence inFlightFence_ = VK_NULL_HANDLE;

    VkBuffer particleBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory particleMemory_ = VK_NULL_HANDLE;
    void* particleMappedMemory_ = nullptr;
    size_t particleCapacity_ = 0;
};

template <typename T>
GalaxyVkRenderer* asRenderer(T handle) {
    return reinterpret_cast<GalaxyVkRenderer*>(handle);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_reandroid_wallpaper_galaxy_GalaxyVKNative_nCreateRenderer(
        JNIEnv* env, jclass, jobject assetManager) {
    auto* renderer = new GalaxyVkRenderer(AAssetManager_fromJava(env, assetManager));
    return reinterpret_cast<jlong>(renderer);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reandroid_wallpaper_galaxy_GalaxyVKNative_nDestroyRenderer(
        JNIEnv*, jclass, jlong handle) {
    delete asRenderer(handle);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reandroid_wallpaper_galaxy_GalaxyVKNative_nOnSurfaceCreated(
        JNIEnv* env, jclass, jlong handle, jobject surface, jint width, jint height) {
    auto* renderer = asRenderer(handle);
    return renderer != nullptr && renderer->createOrUpdateSurface(env, surface, width, height);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reandroid_wallpaper_galaxy_GalaxyVKNative_nOnSurfaceChanged(
        JNIEnv* env, jclass, jlong handle, jobject surface, jint width, jint height) {
    auto* renderer = asRenderer(handle);
    if (renderer != nullptr) {
        renderer->createOrUpdateSurface(env, surface, width, height);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_reandroid_wallpaper_galaxy_GalaxyVKNative_nOnSurfaceDestroyed(
        JNIEnv*, jclass, jlong handle) {
    auto* renderer = asRenderer(handle);
    if (renderer != nullptr) {
        renderer->destroySurface();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_reandroid_wallpaper_galaxy_GalaxyVKNative_nRenderFrame(
        JNIEnv* env, jclass, jlong handle, jfloatArray mvpMatrix, jfloatArray particlePositions,
        jfloatArray particleColors, jint particleCount, jfloat particleAlphaMultiplier) {
    auto* renderer = asRenderer(handle);
    if (renderer != nullptr) {
        renderer->render(env, mvpMatrix, particlePositions, particleColors, particleCount,
                particleAlphaMultiplier);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reandroid_wallpaper_galaxy_GalaxyVKNative_nIsVulkanSupported(
        JNIEnv*, jclass) {
    return GalaxyVkRenderer::isVulkanSupported();
}

extern "C" JNIEXPORT void JNICALL
Java_com_reandroid_wallpaper_galaxy_GalaxyVKNative_nSetLightTexture(
        JNIEnv* env, jclass, jlong handle, jintArray argbPixels, jint width, jint height) {
    auto* renderer = asRenderer(handle);
    if (renderer != nullptr) {
        renderer->setLightTexture(env, argbPixels, width, height);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_reandroid_wallpaper_galaxy_GalaxyVKNative_nSetBackgroundTexture(
        JNIEnv* env, jclass, jlong handle, jintArray argbPixels, jint width, jint height) {
    auto* renderer = asRenderer(handle);
    if (renderer != nullptr) {
        renderer->setBackgroundTexture(env, argbPixels, width, height);
    }
}