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
#include <cmath>
#include <cstdint>
#include <cstring>
#include <limits>
#include <mutex>
#include <vector>

#define LOG_TAG "FallVK"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct PushConstants {
    float mvp[16];
    float alpha;
    float leafFrameIndex;
    float leafFrameInvCount;
    float padding;
};

struct TextureResource {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView imageView = VK_NULL_HANDLE;
    VkSampler sampler = VK_NULL_HANDLE;
    uint32_t width = 0;
    uint32_t height = 0;
    std::vector<uint8_t> pendingPixels;
    uint32_t pendingWidth = 0;
    uint32_t pendingHeight = 0;
};

struct LeafDraw {
    float x;
    float y;
    float scale;
    float angleDeg;
    float altitude;
    int textureIndex;
};

struct WaterVertex {
    float x;
    float y;
    float z;
    float u;
    float v;
};

class FallVkRenderer {
public:
    explicit FallVkRenderer(AAssetManager* assetManager) : assetManager_(assetManager) {}

    ~FallVkRenderer() {
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

    void setBackgroundTexture(JNIEnv* env, jintArray argbPixels, jint width, jint height) {
        std::lock_guard<std::mutex> lock(mutex_);
        storePendingTextureLocked(env, argbPixels, width, height, bgTexture_);
        if (device_ != VK_NULL_HANDLE) {
            ensureTextureLocked(bgTexture_, bgDescriptorSet_);
        }
    }

    void setLeafTexture(JNIEnv* env, jintArray argbPixels, jint width, jint height) {
        std::lock_guard<std::mutex> lock(mutex_);
        storePendingTextureLocked(env, argbPixels, width, height, leafTexture_);
        if (device_ != VK_NULL_HANDLE) {
            ensureTextureLocked(leafTexture_, leafDescriptorSet_);
        }
    }

    void setLeafAtlasFrameCount(int frameCount) {
        std::lock_guard<std::mutex> lock(mutex_);
        leafAtlasFrameCount_ = std::max(1, frameCount);
    }

        void render(JNIEnv* env, jfloatArray projectionArray, jfloatArray viewArray,
            jfloatArray leavesArray, jint leafCount, jfloat xOffset,
            jfloatArray waterVertices, jfloatArray waterTexCoords, jshortArray waterIndices,
            jint waterVertexCount, jint waterIndexCount) {
        std::lock_guard<std::mutex> lock(mutex_);

            if (waterVertexCount > 0) {
                if (!uploadWaterMeshLocked(env, waterVertices, waterTexCoords, waterIndices,
                        waterVertexCount, waterIndexCount)) {
                    return;
                }
            }

            if (waterVertexBuffer_ == VK_NULL_HANDLE || waterIndexBuffer_ == VK_NULL_HANDLE
                    || waterIndexCount_ <= 0) {
                return;
            }

        if (!isReadyLocked()) {
            if (!recoverRenderStateLocked()) {
                return;
            }
        }
        if (!isReadyLocked()) {
            return;
        }

        std::vector<LeafDraw> leaves = readLeavesLocked(env, leavesArray, leafCount);

        float projection[16];
        float view[16];
        env->GetFloatArrayRegion(projectionArray, 0, 16, projection);
        env->GetFloatArrayRegion(viewArray, 0, 16, view);

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
        if (!recordCommandBufferLocked(commandBuffer, imageIndex, projection, view, leaves, xOffset,
            waterIndexCount_)) {
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
        appInfo.pApplicationName = "FallVK";
        appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
        appInfo.pEngineName = "FallVK";
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

        const char* deviceExtensions[] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};

        VkDeviceCreateInfo createInfo{};
        createInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
        createInfo.queueCreateInfoCount = 1;
        createInfo.pQueueCreateInfos = &queueCreateInfo;
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

        if (!createDescriptorResourcesLocked()) {
            return false;
        }
        if (!ensureTextureLocked(bgTexture_, bgDescriptorSet_)) {
            return false;
        }
        return ensureTextureLocked(leafTexture_, leafDescriptorSet_);
    }

    bool createSwapchainResourcesLocked() {
        VkSurfaceCapabilitiesKHR capabilities{};
        vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice_, surface_, &capabilities);

        uint32_t formatCount = 0;
        vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice_, surface_, &formatCount, nullptr);
        if (formatCount == 0) {
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

        VkExtent2D extent{};
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
        createInfo.presentMode = VK_PRESENT_MODE_FIFO_KHR;
        createInfo.clipped = VK_TRUE;

        VkResult result = vkCreateSwapchainKHR(device_, &createInfo, nullptr, &swapchain_);
        if (result != VK_SUCCESS) {
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
            viewInfo.subresourceRange.levelCount = 1;
            viewInfo.subresourceRange.layerCount = 1;
            result = vkCreateImageView(device_, &viewInfo, nullptr, &swapchainImageViews_[i]);
            if (result != VK_SUCCESS) {
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
            VkFramebufferCreateInfo fbInfo{};
            fbInfo.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
            fbInfo.renderPass = renderPass_;
            fbInfo.attachmentCount = 1;
            fbInfo.pAttachments = attachments;
            fbInfo.width = swapchainExtent_.width;
            fbInfo.height = swapchainExtent_.height;
            fbInfo.layers = 1;
            result = vkCreateFramebuffer(device_, &fbInfo, nullptr, &framebuffers_[i]);
            if (result != VK_SUCCESS) {
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
        return result == VK_SUCCESS;
    }

    bool createRenderPassLocked() {
        VkAttachmentDescription colorAttachment{};
        colorAttachment.format = swapchainFormat_;
        colorAttachment.samples = VK_SAMPLE_COUNT_1_BIT;
        colorAttachment.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
        colorAttachment.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        colorAttachment.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        colorAttachment.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;

        VkAttachmentReference colorRef{};
        colorRef.attachment = 0;
        colorRef.layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;

        VkSubpassDescription subpass{};
        subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        subpass.colorAttachmentCount = 1;
        subpass.pColorAttachments = &colorRef;

        VkSubpassDependency dep{};
        dep.srcSubpass = VK_SUBPASS_EXTERNAL;
        dep.dstSubpass = 0;
        dep.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dep.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dep.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;

        VkRenderPassCreateInfo info{};
        info.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
        info.attachmentCount = 1;
        info.pAttachments = &colorAttachment;
        info.subpassCount = 1;
        info.pSubpasses = &subpass;
        info.dependencyCount = 1;
        info.pDependencies = &dep;

        return vkCreateRenderPass(device_, &info, nullptr, &renderPass_) == VK_SUCCESS;
    }

    bool createPipelinesLocked() {
        VkShaderModule bgVert = createShaderModuleLocked({"shaders/fallvk_water.vert.spv", "shaders/fallvk_water.vert.spv"});
        VkShaderModule bgFrag = createShaderModuleLocked({"shaders/fallvk_water.frag.spv", "shaders/fallvk_water.frag.spv"});
        VkShaderModule leafVert = createShaderModuleLocked({"shaders/fallvk_leaf.vert.spv", "shaders/fallvk_leaf.vert.spv"});
        VkShaderModule leafFrag = createShaderModuleLocked({"shaders/fallvk_leaf.frag.spv", "shaders/fallvk_leaf.frag.spv"});
        if (bgVert == VK_NULL_HANDLE || bgFrag == VK_NULL_HANDLE || leafVert == VK_NULL_HANDLE || leafFrag == VK_NULL_HANDLE) {
            if (bgVert != VK_NULL_HANDLE) vkDestroyShaderModule(device_, bgVert, nullptr);
            if (bgFrag != VK_NULL_HANDLE) vkDestroyShaderModule(device_, bgFrag, nullptr);
            if (leafVert != VK_NULL_HANDLE) vkDestroyShaderModule(device_, leafVert, nullptr);
            if (leafFrag != VK_NULL_HANDLE) vkDestroyShaderModule(device_, leafFrag, nullptr);
            return false;
        }

        bgPipelineLayout_ = createPipelineLayoutLocked(true, bgDescriptorSetLayout_);
        leafPipelineLayout_ = createPipelineLayoutLocked(true, leafDescriptorSetLayout_);
        if (bgPipelineLayout_ == VK_NULL_HANDLE || leafPipelineLayout_ == VK_NULL_HANDLE) {
            return false;
        }

        bgPipeline_ = createGraphicsPipelineLocked(bgVert, bgFrag, bgPipelineLayout_, false);
        leafPipeline_ = createGraphicsPipelineLocked(leafVert, leafFrag, leafPipelineLayout_, true);

        vkDestroyShaderModule(device_, bgVert, nullptr);
        vkDestroyShaderModule(device_, bgFrag, nullptr);
        vkDestroyShaderModule(device_, leafVert, nullptr);
        vkDestroyShaderModule(device_, leafFrag, nullptr);

        return bgPipeline_ != VK_NULL_HANDLE && leafPipeline_ != VK_NULL_HANDLE;
    }

    VkPipelineLayout createPipelineLayoutLocked(bool withPushConstants, VkDescriptorSetLayout descriptorSetLayout) {
        VkPipelineLayoutCreateInfo layoutInfo{};
        layoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        if (descriptorSetLayout != VK_NULL_HANDLE) {
            layoutInfo.setLayoutCount = 1;
            layoutInfo.pSetLayouts = &descriptorSetLayout;
        }

        VkPushConstantRange pushRange{};
        if (withPushConstants) {
            pushRange.stageFlags = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
            pushRange.offset = 0;
            pushRange.size = sizeof(PushConstants);
            layoutInfo.pushConstantRangeCount = 1;
            layoutInfo.pPushConstantRanges = &pushRange;
        }

        VkPipelineLayout layout = VK_NULL_HANDLE;
        if (vkCreatePipelineLayout(device_, &layoutInfo, nullptr, &layout) != VK_SUCCESS) {
            return VK_NULL_HANDLE;
        }
        return layout;
    }

    VkPipeline createGraphicsPipelineLocked(VkShaderModule vert, VkShaderModule frag,
            VkPipelineLayout layout, bool leafPipeline) {
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

        VkVertexInputBindingDescription binding{};
        VkVertexInputAttributeDescription attrs[2]{};
        if (!leafPipeline) {
            binding.binding = 0;
            binding.stride = sizeof(WaterVertex);
            binding.inputRate = VK_VERTEX_INPUT_RATE_VERTEX;

            attrs[0].binding = 0;
            attrs[0].location = 0;
            attrs[0].format = VK_FORMAT_R32G32B32_SFLOAT;
            attrs[0].offset = 0;

            attrs[1].binding = 0;
            attrs[1].location = 1;
            attrs[1].format = VK_FORMAT_R32G32_SFLOAT;
            attrs[1].offset = sizeof(float) * 3;

            vertexInput.vertexBindingDescriptionCount = 1;
            vertexInput.pVertexBindingDescriptions = &binding;
            vertexInput.vertexAttributeDescriptionCount = 2;
            vertexInput.pVertexAttributeDescriptions = attrs;
        }

        VkPipelineInputAssemblyStateCreateInfo inputAssembly{};
        inputAssembly.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
        inputAssembly.topology = leafPipeline ? VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP : VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;

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

        VkPipelineColorBlendAttachmentState blendAttachment{};
        blendAttachment.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
                | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
        blendAttachment.blendEnable = VK_TRUE;
        blendAttachment.srcColorBlendFactor = VK_BLEND_FACTOR_SRC_ALPHA;
        blendAttachment.dstColorBlendFactor = leafPipeline ? VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA : VK_BLEND_FACTOR_ZERO;
        blendAttachment.colorBlendOp = VK_BLEND_OP_ADD;
        blendAttachment.srcAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
        blendAttachment.dstAlphaBlendFactor = leafPipeline ? VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA : VK_BLEND_FACTOR_ZERO;
        blendAttachment.alphaBlendOp = VK_BLEND_OP_ADD;

        VkPipelineColorBlendStateCreateInfo colorBlending{};
        colorBlending.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
        colorBlending.attachmentCount = 1;
        colorBlending.pAttachments = &blendAttachment;

        std::array<VkDynamicState, 2> dynamicStates = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
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

        VkPipeline pipeline = VK_NULL_HANDLE;
        if (vkCreateGraphicsPipelines(device_, VK_NULL_HANDLE, 1, &pipelineInfo, nullptr, &pipeline) != VK_SUCCESS) {
            return VK_NULL_HANDLE;
        }
        return pipeline;
    }

        bool recordCommandBufferLocked(VkCommandBuffer commandBuffer, uint32_t imageIndex,
            const float projection[16], const float view[16], const std::vector<LeafDraw>& leaves,
            float xOffset, int waterIndexCount) {
        VkCommandBufferBeginInfo beginInfo{};
        beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        if (vkBeginCommandBuffer(commandBuffer, &beginInfo) != VK_SUCCESS) {
            return false;
        }

        VkClearValue clearValue{};
        clearValue.color = {{0.0f, 0.0f, 0.0f, 1.0f}};

        VkRenderPassBeginInfo rpInfo{};
        rpInfo.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
        rpInfo.renderPass = renderPass_;
        rpInfo.framebuffer = framebuffers_[imageIndex];
        rpInfo.renderArea.offset = {0, 0};
        rpInfo.renderArea.extent = swapchainExtent_;
        rpInfo.clearValueCount = 1;
        rpInfo.pClearValues = &clearValue;

        vkCmdBeginRenderPass(commandBuffer, &rpInfo, VK_SUBPASS_CONTENTS_INLINE);

        VkViewport viewport{};
        viewport.width = static_cast<float>(swapchainExtent_.width);
        viewport.height = static_cast<float>(swapchainExtent_.height);
        viewport.maxDepth = 1.0f;
        vkCmdSetViewport(commandBuffer, 0, 1, &viewport);

        VkRect2D scissor{};
        scissor.extent = swapchainExtent_;
        vkCmdSetScissor(commandBuffer, 0, 1, &scissor);

        float pv[16];
        multiplyMat4(projection, view, pv);

        float clipCorrection[16];
        getVulkanClipCorrection(clipCorrection);

        float pvCorrected[16];
        multiplyMat4(clipCorrection, pv, pvCorrected);

        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, bgPipeline_);
        vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
            bgPipelineLayout_, 0, 1, &bgDescriptorSet_, 0, nullptr);
        VkDeviceSize offsets[] = {0};
        vkCmdBindVertexBuffers(commandBuffer, 0, 1, &waterVertexBuffer_, offsets);
        vkCmdBindIndexBuffer(commandBuffer, waterIndexBuffer_, 0, VK_INDEX_TYPE_UINT16);
        PushConstants waterPush{};
        std::memcpy(waterPush.mvp, pvCorrected, sizeof(waterPush.mvp));
        waterPush.alpha = 1.0f;
        waterPush.leafFrameIndex = 0.0f;
        waterPush.leafFrameInvCount = 1.0f;
        vkCmdPushConstants(commandBuffer, bgPipelineLayout_,
            VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
            0, sizeof(PushConstants), &waterPush);
        vkCmdDrawIndexed(commandBuffer, static_cast<uint32_t>(std::max(0, waterIndexCount)), 1, 0, 0, 0);

        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, leafPipeline_);
        vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
                leafPipelineLayout_, 0, 1, &leafDescriptorSet_, 0, nullptr);

        for (const LeafDraw& leaf : leaves) {
            float alpha = computeLeafAlpha(leaf.altitude);
            if (alpha <= 0.001f) {
                continue;
            }

            float model[16];
            buildLeafModel(leaf, xOffset, model);

            PushConstants push{};
            multiplyMat4(pvCorrected, model, push.mvp);
            push.alpha = alpha;
                const int frameCount = std::max(1, leafAtlasFrameCount_);
                const int frameIndex = ((leaf.textureIndex % frameCount) + frameCount) % frameCount;
                push.leafFrameIndex = static_cast<float>(frameIndex);
                push.leafFrameInvCount = 1.0f / static_cast<float>(frameCount);

            vkCmdPushConstants(commandBuffer, leafPipelineLayout_,
                    VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                    0, sizeof(PushConstants), &push);
            vkCmdDraw(commandBuffer, 4, 1, 0, 0);
        }

        vkCmdEndRenderPass(commandBuffer);
        return vkEndCommandBuffer(commandBuffer) == VK_SUCCESS;
    }

    float computeLeafAlpha(float altitude) const {
        if (altitude <= 0.0f) {
            return 1.0f;
        }
        float alpha = 1.0f;
        if (altitude >= 0.4f) {
            alpha = 1.0f - (altitude - 0.4f) / 0.1f;
        }
        return std::max(0.0f, std::min(1.0f, alpha));
    }

    void buildLeafModel(const LeafDraw& leaf, float xOffset, float out[16]) const {
        const float drawX = leaf.x - xOffset * 2.0f;
        const float drawY = leaf.y;
        const float s = leaf.scale;
        const float angle = leaf.angleDeg * 0.01745329252f;
        const float c = std::cos(angle);
        const float si = std::sin(angle);

        setIdentity(out);
        out[0] = s * c;
        out[1] = s * si;
        out[4] = -s * si;
        out[5] = s * c;
        out[12] = drawX;
        out[13] = drawY;
    }

    std::vector<LeafDraw> readLeavesLocked(JNIEnv* env, jfloatArray leavesArray, jint leafCount) {
        std::vector<LeafDraw> leaves;
        if (leavesArray == nullptr || leafCount <= 0) {
            return leaves;
        }

        const jsize arraySize = env->GetArrayLength(leavesArray);
        constexpr int kStride = 6;
        const int safeCount = std::min<int>(leafCount, arraySize / kStride);
        if (safeCount <= 0) {
            return leaves;
        }

        tempLeafRaw_.resize(static_cast<size_t>(safeCount) * kStride);
        env->GetFloatArrayRegion(leavesArray, 0, static_cast<jsize>(tempLeafRaw_.size()), tempLeafRaw_.data());

        leaves.resize(safeCount);
        for (int i = 0; i < safeCount; ++i) {
            const int base = i * kStride;
            leaves[i].x = tempLeafRaw_[base];
            leaves[i].y = tempLeafRaw_[base + 1];
            leaves[i].scale = tempLeafRaw_[base + 2];
            leaves[i].angleDeg = tempLeafRaw_[base + 3];
            leaves[i].altitude = tempLeafRaw_[base + 4];
            leaves[i].textureIndex = static_cast<int>(tempLeafRaw_[base + 5]);
        }
        return leaves;
    }

    bool uploadWaterMeshLocked(JNIEnv* env, jfloatArray waterVertices, jfloatArray waterTexCoords,
            jshortArray waterIndices, jint waterVertexCount, jint waterIndexCount) {
        if (waterTexCoords == nullptr || waterVertexCount <= 0) {
            return false;
        }

        const jsize texLen = env->GetArrayLength(waterTexCoords);
        if (texLen < waterVertexCount * 2) {
            return false;
        }

        const bool fullUpload = (waterVertices != nullptr && waterIndices != nullptr && waterIndexCount > 0);

        if (fullUpload) {
            const jsize verticesLen = env->GetArrayLength(waterVertices);
            const jsize idxLen = env->GetArrayLength(waterIndices);
            if (verticesLen < waterVertexCount * 3 || idxLen < waterIndexCount) {
                return false;
            }
            if (!ensureWaterBuffersLocked(static_cast<size_t>(waterVertexCount), static_cast<size_t>(waterIndexCount))) {
                return false;
            }
        } else {
            if (waterVertexBuffer_ == VK_NULL_HANDLE || waterIndexBuffer_ == VK_NULL_HANDLE || waterVertexCount > waterVertexCapacity_) {
                return false;
            }
        }

        tempWaterTexcoords_.resize(static_cast<size_t>(waterVertexCount) * 2);
        env->GetFloatArrayRegion(waterTexCoords, 0, static_cast<jsize>(tempWaterTexcoords_.size()), tempWaterTexcoords_.data());

        auto* mappedVertices = reinterpret_cast<WaterVertex*>(waterVertexMapped_);
        if (fullUpload) {
            tempWaterPositions_.resize(static_cast<size_t>(waterVertexCount) * 3);
            tempWaterIndices_.resize(static_cast<size_t>(waterIndexCount));
            env->GetFloatArrayRegion(waterVertices, 0, static_cast<jsize>(tempWaterPositions_.size()), tempWaterPositions_.data());
            env->GetShortArrayRegion(waterIndices, 0, static_cast<jsize>(tempWaterIndices_.size()),
                    reinterpret_cast<jshort*>(tempWaterIndices_.data()));

            for (int i = 0; i < waterVertexCount; ++i) {
                const int p = i * 3;
                const int t = i * 2;
                mappedVertices[i].x = tempWaterPositions_[p];
                mappedVertices[i].y = tempWaterPositions_[p + 1];
                mappedVertices[i].z = tempWaterPositions_[p + 2];
                mappedVertices[i].u = tempWaterTexcoords_[t];
                mappedVertices[i].v = tempWaterTexcoords_[t + 1];
            }

            std::memcpy(waterIndexMapped_, tempWaterIndices_.data(), static_cast<size_t>(waterIndexCount) * sizeof(uint16_t));
            waterIndexCount_ = waterIndexCount;
        } else {
            for (int i = 0; i < waterVertexCount; ++i) {
                const int t = i * 2;
                mappedVertices[i].u = tempWaterTexcoords_[t];
                mappedVertices[i].v = tempWaterTexcoords_[t + 1];
            }
        }
        return true;
    }

    bool ensureWaterBuffersLocked(size_t vertexCount, size_t indexCount) {
        if (waterVertexBuffer_ != VK_NULL_HANDLE && waterIndexBuffer_ != VK_NULL_HANDLE
                && vertexCount <= waterVertexCapacity_ && indexCount <= waterIndexCapacity_) {
            return true;
        }

        destroyWaterBuffersLocked();

        const VkDeviceSize vertexSize = static_cast<VkDeviceSize>(std::max<size_t>(vertexCount, 1))
                * sizeof(WaterVertex);
        const VkDeviceSize indexSize = static_cast<VkDeviceSize>(std::max<size_t>(indexCount, 1))
                * sizeof(uint16_t);

        VkBufferCreateInfo vbInfo{};
        vbInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        vbInfo.size = vertexSize;
        vbInfo.usage = VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
        vbInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        if (vkCreateBuffer(device_, &vbInfo, nullptr, &waterVertexBuffer_) != VK_SUCCESS) {
            return false;
        }

        VkMemoryRequirements vbReq{};
        vkGetBufferMemoryRequirements(device_, waterVertexBuffer_, &vbReq);
        VkMemoryAllocateInfo vbAlloc{};
        vbAlloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        vbAlloc.allocationSize = vbReq.size;
        vbAlloc.memoryTypeIndex = findMemoryTypeLocked(vbReq.memoryTypeBits,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        if (vkAllocateMemory(device_, &vbAlloc, nullptr, &waterVertexMemory_) != VK_SUCCESS) {
            return false;
        }
        vkBindBufferMemory(device_, waterVertexBuffer_, waterVertexMemory_, 0);
        if (vkMapMemory(device_, waterVertexMemory_, 0, vertexSize, 0, &waterVertexMapped_) != VK_SUCCESS) {
            waterVertexMapped_ = nullptr;
            return false;
        }

        VkBufferCreateInfo ibInfo{};
        ibInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        ibInfo.size = indexSize;
        ibInfo.usage = VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
        ibInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        if (vkCreateBuffer(device_, &ibInfo, nullptr, &waterIndexBuffer_) != VK_SUCCESS) {
            return false;
        }

        VkMemoryRequirements ibReq{};
        vkGetBufferMemoryRequirements(device_, waterIndexBuffer_, &ibReq);
        VkMemoryAllocateInfo ibAlloc{};
        ibAlloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        ibAlloc.allocationSize = ibReq.size;
        ibAlloc.memoryTypeIndex = findMemoryTypeLocked(ibReq.memoryTypeBits,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        if (vkAllocateMemory(device_, &ibAlloc, nullptr, &waterIndexMemory_) != VK_SUCCESS) {
            return false;
        }
        vkBindBufferMemory(device_, waterIndexBuffer_, waterIndexMemory_, 0);
        if (vkMapMemory(device_, waterIndexMemory_, 0, indexSize, 0, &waterIndexMapped_) != VK_SUCCESS) {
            waterIndexMapped_ = nullptr;
            return false;
        }

        waterVertexCapacity_ = vertexCount;
        waterIndexCapacity_ = indexCount;
        waterIndexCount_ = 0;
        return true;
    }

    static void setIdentity(float m[16]) {
        std::memset(m, 0, sizeof(float) * 16);
        m[0] = m[5] = m[10] = m[15] = 1.0f;
    }

    static void multiplyMat4(const float a[16], const float b[16], float out[16]) {
        float r[16];
        for (int col = 0; col < 4; ++col) {
            for (int row = 0; row < 4; ++row) {
                r[col * 4 + row] =
                        a[0 * 4 + row] * b[col * 4 + 0] +
                        a[1 * 4 + row] * b[col * 4 + 1] +
                        a[2 * 4 + row] * b[col * 4 + 2] +
                        a[3 * 4 + row] * b[col * 4 + 3];
            }
        }
        std::memcpy(out, r, sizeof(r));
    }

    static void getVulkanClipCorrection(float out[16]) {
        // Convert OpenGL clip space to Vulkan clip space: flip Y, remap Z from [-1, 1] to [0, 1].
        std::memset(out, 0, sizeof(float) * 16);
        out[0] = 1.0f;
        out[5] = -1.0f;
        out[10] = 0.5f;
        out[14] = 0.5f;
        out[15] = 1.0f;
    }

    bool createDescriptorResourcesLocked() {
        if (!createOneDescriptorLocked(bgDescriptorSetLayout_, bgDescriptorPool_, bgDescriptorSet_)) {
            return false;
        }
        return createOneDescriptorLocked(leafDescriptorSetLayout_, leafDescriptorPool_, leafDescriptorSet_);
    }

    bool createOneDescriptorLocked(VkDescriptorSetLayout& setLayout, VkDescriptorPool& pool, VkDescriptorSet& set) {
        if (setLayout != VK_NULL_HANDLE && pool != VK_NULL_HANDLE && set != VK_NULL_HANDLE) {
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
        if (vkCreateDescriptorSetLayout(device_, &layoutInfo, nullptr, &setLayout) != VK_SUCCESS) {
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
        if (vkCreateDescriptorPool(device_, &poolInfo, nullptr, &pool) != VK_SUCCESS) {
            return false;
        }

        VkDescriptorSetAllocateInfo allocInfo{};
        allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        allocInfo.descriptorPool = pool;
        allocInfo.descriptorSetCount = 1;
        allocInfo.pSetLayouts = &setLayout;
        return vkAllocateDescriptorSets(device_, &allocInfo, &set) == VK_SUCCESS;
    }

    bool ensureTextureLocked(TextureResource& texture, VkDescriptorSet descriptorSet) {
        if (texture.image != VK_NULL_HANDLE && texture.imageView != VK_NULL_HANDLE && texture.sampler != VK_NULL_HANDLE) {
            return true;
        }

        if (!texture.pendingPixels.empty() && texture.pendingWidth > 0 && texture.pendingHeight > 0) {
            return uploadTextureLocked(texture, descriptorSet, texture.pendingPixels.data(), texture.pendingWidth, texture.pendingHeight);
        }

        const uint8_t fallback[4] = {255, 255, 255, 255};
        return uploadTextureLocked(texture, descriptorSet, fallback, 1, 1);
    }

    bool uploadTextureLocked(TextureResource& texture, VkDescriptorSet descriptorSet,
            const uint8_t* rgbaPixels, uint32_t width, uint32_t height) {
        if (rgbaPixels == nullptr || width == 0 || height == 0) {
            return false;
        }

        destroyTextureLocked(texture);

        const VkDeviceSize imageSize = static_cast<VkDeviceSize>(width) * height * 4u;

        VkBuffer stagingBuffer = VK_NULL_HANDLE;
        VkDeviceMemory stagingMemory = VK_NULL_HANDLE;

        VkBufferCreateInfo bufferInfo{};
        bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        bufferInfo.size = imageSize;
        bufferInfo.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
        bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        if (vkCreateBuffer(device_, &bufferInfo, nullptr, &stagingBuffer) != VK_SUCCESS) {
            return false;
        }

        VkMemoryRequirements stagingReq{};
        vkGetBufferMemoryRequirements(device_, stagingBuffer, &stagingReq);
        VkMemoryAllocateInfo stagingAlloc{};
        stagingAlloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        stagingAlloc.allocationSize = stagingReq.size;
        stagingAlloc.memoryTypeIndex = findMemoryTypeLocked(stagingReq.memoryTypeBits,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        if (vkAllocateMemory(device_, &stagingAlloc, nullptr, &stagingMemory) != VK_SUCCESS) {
            vkDestroyBuffer(device_, stagingBuffer, nullptr);
            return false;
        }
        vkBindBufferMemory(device_, stagingBuffer, stagingMemory, 0);

        void* mapped = nullptr;
        if (vkMapMemory(device_, stagingMemory, 0, imageSize, 0, &mapped) != VK_SUCCESS || mapped == nullptr) {
            vkFreeMemory(device_, stagingMemory, nullptr);
            vkDestroyBuffer(device_, stagingBuffer, nullptr);
            return false;
        }
        std::memcpy(mapped, rgbaPixels, static_cast<size_t>(imageSize));
        vkUnmapMemory(device_, stagingMemory);

        VkImageCreateInfo imageInfo{};
        imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        imageInfo.imageType = VK_IMAGE_TYPE_2D;
        imageInfo.extent = {width, height, 1};
        imageInfo.mipLevels = 1;
        imageInfo.arrayLayers = 1;
        imageInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
        imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
        imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        imageInfo.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;

        if (vkCreateImage(device_, &imageInfo, nullptr, &texture.image) != VK_SUCCESS) {
            vkFreeMemory(device_, stagingMemory, nullptr);
            vkDestroyBuffer(device_, stagingBuffer, nullptr);
            return false;
        }

        VkMemoryRequirements imageReq{};
        vkGetImageMemoryRequirements(device_, texture.image, &imageReq);
        VkMemoryAllocateInfo imageAlloc{};
        imageAlloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        imageAlloc.allocationSize = imageReq.size;
        imageAlloc.memoryTypeIndex = findMemoryTypeLocked(imageReq.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if (vkAllocateMemory(device_, &imageAlloc, nullptr, &texture.memory) != VK_SUCCESS) {
            vkDestroyImage(device_, texture.image, nullptr);
            texture.image = VK_NULL_HANDLE;
            vkFreeMemory(device_, stagingMemory, nullptr);
            vkDestroyBuffer(device_, stagingBuffer, nullptr);
            return false;
        }
        vkBindImageMemory(device_, texture.image, texture.memory, 0);

        VkCommandBuffer commandBuffer = beginOneTimeCommandsLocked();
        if (commandBuffer == VK_NULL_HANDLE) {
            vkFreeMemory(device_, stagingMemory, nullptr);
            vkDestroyBuffer(device_, stagingBuffer, nullptr);
            return false;
        }

        transitionImageLayoutLocked(commandBuffer, texture.image,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

        VkBufferImageCopy copyRegion{};
        copyRegion.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        copyRegion.imageSubresource.layerCount = 1;
        copyRegion.imageExtent = {width, height, 1};
        vkCmdCopyBufferToImage(commandBuffer, stagingBuffer, texture.image,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copyRegion);

        transitionImageLayoutLocked(commandBuffer, texture.image,
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
        viewInfo.image = texture.image;
        viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
        viewInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
        viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        viewInfo.subresourceRange.levelCount = 1;
        viewInfo.subresourceRange.layerCount = 1;
        if (vkCreateImageView(device_, &viewInfo, nullptr, &texture.imageView) != VK_SUCCESS) {
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
        if (vkCreateSampler(device_, &samplerInfo, nullptr, &texture.sampler) != VK_SUCCESS) {
            return false;
        }

        VkDescriptorImageInfo imageDesc{};
        imageDesc.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        imageDesc.imageView = texture.imageView;
        imageDesc.sampler = texture.sampler;

        VkWriteDescriptorSet write{};
        write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        write.dstSet = descriptorSet;
        write.dstBinding = 0;
        write.descriptorCount = 1;
        write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        write.pImageInfo = &imageDesc;
        vkUpdateDescriptorSets(device_, 1, &write, 0, nullptr);

        texture.width = width;
        texture.height = height;
        return true;
    }

    void storePendingTextureLocked(JNIEnv* env, jintArray argbPixels, jint width, jint height, TextureResource& texture) {
        if (argbPixels == nullptr || width <= 0 || height <= 0) {
            return;
        }
        const jsize pixelCount = env->GetArrayLength(argbPixels);
        const int64_t expected = static_cast<int64_t>(width) * static_cast<int64_t>(height);
        if (pixelCount <= 0 || expected > pixelCount) {
            return;
        }

        jint* pixels = env->GetIntArrayElements(argbPixels, nullptr);
        if (pixels == nullptr) {
            return;
        }

        texture.pendingWidth = static_cast<uint32_t>(width);
        texture.pendingHeight = static_cast<uint32_t>(height);
        texture.pendingPixels.resize(static_cast<size_t>(expected) * 4u);

        for (size_t i = 0; i < static_cast<size_t>(expected); ++i) {
            const uint32_t argb = static_cast<uint32_t>(pixels[i]);
            texture.pendingPixels[i * 4 + 0] = static_cast<uint8_t>((argb >> 16) & 0xFF);
            texture.pendingPixels[i * 4 + 1] = static_cast<uint8_t>((argb >> 8) & 0xFF);
            texture.pendingPixels[i * 4 + 2] = static_cast<uint8_t>(argb & 0xFF);
            texture.pendingPixels[i * 4 + 3] = static_cast<uint8_t>((argb >> 24) & 0xFF);
        }

        env->ReleaseIntArrayElements(argbPixels, pixels, JNI_ABORT);
    }

    uint32_t findMemoryTypeLocked(uint32_t typeFilter, VkMemoryPropertyFlags properties) const {
        VkPhysicalDeviceMemoryProperties memProperties{};
        vkGetPhysicalDeviceMemoryProperties(physicalDevice_, &memProperties);
        for (uint32_t i = 0; i < memProperties.memoryTypeCount; ++i) {
            if ((typeFilter & (1u << i)) != 0 && (memProperties.memoryTypes[i].propertyFlags & properties) == properties) {
                return i;
            }
        }
        return 0;
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
            return VK_NULL_HANDLE;
        }

        VkShaderModuleCreateInfo info{};
        info.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
        info.codeSize = bytes.size();
        info.pCode = reinterpret_cast<const uint32_t*>(bytes.data());

        VkShaderModule module = VK_NULL_HANDLE;
        if (vkCreateShaderModule(device_, &info, nullptr, &module) != VK_SUCCESS) {
            return VK_NULL_HANDLE;
        }
        return module;
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

    VkCommandBuffer beginOneTimeCommandsLocked() {
        VkCommandBufferAllocateInfo allocInfo{};
        allocInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        allocInfo.commandPool = commandPool_;
        allocInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        allocInfo.commandBufferCount = 1;

        VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
        if (vkAllocateCommandBuffers(device_, &allocInfo, &commandBuffer) != VK_SUCCESS) {
            return VK_NULL_HANDLE;
        }

        VkCommandBufferBeginInfo beginInfo{};
        beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        if (vkBeginCommandBuffer(commandBuffer, &beginInfo) != VK_SUCCESS) {
            vkFreeCommandBuffers(device_, commandPool_, 1, &commandBuffer);
            return VK_NULL_HANDLE;
        }
        return commandBuffer;
    }

    bool endOneTimeCommandsLocked(VkCommandBuffer commandBuffer) {
        if (vkEndCommandBuffer(commandBuffer) != VK_SUCCESS) {
            vkFreeCommandBuffers(device_, commandPool_, 1, &commandBuffer);
            return false;
        }

        VkSubmitInfo submitInfo{};
        submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        submitInfo.commandBufferCount = 1;
        submitInfo.pCommandBuffers = &commandBuffer;
        if (vkQueueSubmit(graphicsQueue_, 1, &submitInfo, VK_NULL_HANDLE) != VK_SUCCESS) {
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
        barrier.subresourceRange.levelCount = 1;
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

    bool isReadyLocked() const {
        return instance_ != VK_NULL_HANDLE && device_ != VK_NULL_HANDLE && surface_ != VK_NULL_HANDLE
                && swapchain_ != VK_NULL_HANDLE && renderPass_ != VK_NULL_HANDLE
                && bgPipeline_ != VK_NULL_HANDLE && leafPipeline_ != VK_NULL_HANDLE
                && bgDescriptorSet_ != VK_NULL_HANDLE && leafDescriptorSet_ != VK_NULL_HANDLE
                && bgTexture_.imageView != VK_NULL_HANDLE && bgTexture_.sampler != VK_NULL_HANDLE
                && leafTexture_.imageView != VK_NULL_HANDLE && leafTexture_.sampler != VK_NULL_HANDLE
                && waterVertexBuffer_ != VK_NULL_HANDLE && waterIndexBuffer_ != VK_NULL_HANDLE;
    }

    void destroySwapchainResourcesLocked() {
        if (device_ != VK_NULL_HANDLE) {
            vkDeviceWaitIdle(device_);
        }

        if (!commandBuffers_.empty()) {
            vkFreeCommandBuffers(device_, commandPool_, static_cast<uint32_t>(commandBuffers_.size()), commandBuffers_.data());
            commandBuffers_.clear();
        }

        for (VkFramebuffer fb : framebuffers_) {
            vkDestroyFramebuffer(device_, fb, nullptr);
        }
        framebuffers_.clear();

        if (bgPipeline_ != VK_NULL_HANDLE) {
            vkDestroyPipeline(device_, bgPipeline_, nullptr);
            bgPipeline_ = VK_NULL_HANDLE;
        }
        if (leafPipeline_ != VK_NULL_HANDLE) {
            vkDestroyPipeline(device_, leafPipeline_, nullptr);
            leafPipeline_ = VK_NULL_HANDLE;
        }
        if (bgPipelineLayout_ != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(device_, bgPipelineLayout_, nullptr);
            bgPipelineLayout_ = VK_NULL_HANDLE;
        }
        if (leafPipelineLayout_ != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(device_, leafPipelineLayout_, nullptr);
            leafPipelineLayout_ = VK_NULL_HANDLE;
        }
        if (renderPass_ != VK_NULL_HANDLE) {
            vkDestroyRenderPass(device_, renderPass_, nullptr);
            renderPass_ = VK_NULL_HANDLE;
        }

        for (VkImageView iv : swapchainImageViews_) {
            vkDestroyImageView(device_, iv, nullptr);
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
        if (vkCreateAndroidSurfaceKHR(instance_, &surfaceInfo, nullptr, &surface_) != VK_SUCCESS) {
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
                || bgPipeline_ == VK_NULL_HANDLE || leafPipeline_ == VK_NULL_HANDLE
                || commandBuffers_.empty()) {
            if (!recreateSwapchainLocked()) {
                return false;
            }
        }

        if (bgDescriptorSet_ == VK_NULL_HANDLE || bgTexture_.imageView == VK_NULL_HANDLE
                || bgTexture_.sampler == VK_NULL_HANDLE) {
            if (!createOneDescriptorLocked(bgDescriptorSetLayout_, bgDescriptorPool_, bgDescriptorSet_)
                    || !ensureTextureLocked(bgTexture_, bgDescriptorSet_)) {
                return false;
            }
        }

        if (leafDescriptorSet_ == VK_NULL_HANDLE || leafTexture_.imageView == VK_NULL_HANDLE
                || leafTexture_.sampler == VK_NULL_HANDLE) {
            if (!createOneDescriptorLocked(leafDescriptorSetLayout_, leafDescriptorPool_, leafDescriptorSet_)
                    || !ensureTextureLocked(leafTexture_, leafDescriptorSet_)) {
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

    void destroyTextureLocked(TextureResource& texture) {
        if (texture.sampler != VK_NULL_HANDLE) {
            vkDestroySampler(device_, texture.sampler, nullptr);
            texture.sampler = VK_NULL_HANDLE;
        }
        if (texture.imageView != VK_NULL_HANDLE) {
            vkDestroyImageView(device_, texture.imageView, nullptr);
            texture.imageView = VK_NULL_HANDLE;
        }
        if (texture.image != VK_NULL_HANDLE) {
            vkDestroyImage(device_, texture.image, nullptr);
            texture.image = VK_NULL_HANDLE;
        }
        if (texture.memory != VK_NULL_HANDLE) {
            vkFreeMemory(device_, texture.memory, nullptr);
            texture.memory = VK_NULL_HANDLE;
        }
    }

    void destroyWaterBuffersLocked() {
        if (waterVertexMapped_ != nullptr && waterVertexMemory_ != VK_NULL_HANDLE) {
            vkUnmapMemory(device_, waterVertexMemory_);
            waterVertexMapped_ = nullptr;
        }
        if (waterIndexMapped_ != nullptr && waterIndexMemory_ != VK_NULL_HANDLE) {
            vkUnmapMemory(device_, waterIndexMemory_);
            waterIndexMapped_ = nullptr;
        }
        if (waterVertexBuffer_ != VK_NULL_HANDLE) {
            vkDestroyBuffer(device_, waterVertexBuffer_, nullptr);
            waterVertexBuffer_ = VK_NULL_HANDLE;
        }
        if (waterIndexBuffer_ != VK_NULL_HANDLE) {
            vkDestroyBuffer(device_, waterIndexBuffer_, nullptr);
            waterIndexBuffer_ = VK_NULL_HANDLE;
        }
        if (waterVertexMemory_ != VK_NULL_HANDLE) {
            vkFreeMemory(device_, waterVertexMemory_, nullptr);
            waterVertexMemory_ = VK_NULL_HANDLE;
        }
        if (waterIndexMemory_ != VK_NULL_HANDLE) {
            vkFreeMemory(device_, waterIndexMemory_, nullptr);
            waterIndexMemory_ = VK_NULL_HANDLE;
        }
        waterVertexCapacity_ = 0;
        waterIndexCapacity_ = 0;
        waterIndexCount_ = 0;
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

        destroyTextureLocked(bgTexture_);
        destroyTextureLocked(leafTexture_);
        destroyWaterBuffersLocked();

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
        if (leafDescriptorPool_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorPool(device_, leafDescriptorPool_, nullptr);
            leafDescriptorPool_ = VK_NULL_HANDLE;
            leafDescriptorSet_ = VK_NULL_HANDLE;
        }
        if (bgDescriptorSetLayout_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(device_, bgDescriptorSetLayout_, nullptr);
            bgDescriptorSetLayout_ = VK_NULL_HANDLE;
        }
        if (leafDescriptorSetLayout_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(device_, leafDescriptorSetLayout_, nullptr);
            leafDescriptorSetLayout_ = VK_NULL_HANDLE;
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
    VkPipelineLayout leafPipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline bgPipeline_ = VK_NULL_HANDLE;
    VkPipeline leafPipeline_ = VK_NULL_HANDLE;

    VkDescriptorSetLayout bgDescriptorSetLayout_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout leafDescriptorSetLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool bgDescriptorPool_ = VK_NULL_HANDLE;
    VkDescriptorPool leafDescriptorPool_ = VK_NULL_HANDLE;
    VkDescriptorSet bgDescriptorSet_ = VK_NULL_HANDLE;
    VkDescriptorSet leafDescriptorSet_ = VK_NULL_HANDLE;

    TextureResource bgTexture_;
    TextureResource leafTexture_;

    VkBuffer waterVertexBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory waterVertexMemory_ = VK_NULL_HANDLE;
    void* waterVertexMapped_ = nullptr;
    size_t waterVertexCapacity_ = 0;

    VkBuffer waterIndexBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory waterIndexMemory_ = VK_NULL_HANDLE;
    void* waterIndexMapped_ = nullptr;
    size_t waterIndexCapacity_ = 0;
    int waterIndexCount_ = 0;

    int leafAtlasFrameCount_ = 1;

    std::vector<float> tempLeafRaw_;
    std::vector<float> tempWaterPositions_;
    std::vector<float> tempWaterTexcoords_;
    std::vector<uint16_t> tempWaterIndices_;

    VkCommandPool commandPool_ = VK_NULL_HANDLE;
    VkSemaphore imageAvailableSemaphore_ = VK_NULL_HANDLE;
    VkSemaphore renderFinishedSemaphore_ = VK_NULL_HANDLE;
    VkFence inFlightFence_ = VK_NULL_HANDLE;
};

template <typename T>
FallVkRenderer* asRenderer(T handle) {
    return reinterpret_cast<FallVkRenderer*>(handle);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_reandroid_wallpaper_fall_FallVKNative_nCreateRenderer(
        JNIEnv* env, jclass, jobject assetManager) {
    auto* renderer = new FallVkRenderer(AAssetManager_fromJava(env, assetManager));
    return reinterpret_cast<jlong>(renderer);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reandroid_wallpaper_fall_FallVKNative_nDestroyRenderer(
        JNIEnv*, jclass, jlong handle) {
    delete asRenderer(handle);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reandroid_wallpaper_fall_FallVKNative_nOnSurfaceCreated(
        JNIEnv* env, jclass, jlong handle, jobject surface, jint width, jint height) {
    auto* renderer = asRenderer(handle);
    return renderer != nullptr && renderer->createOrUpdateSurface(env, surface, width, height);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reandroid_wallpaper_fall_FallVKNative_nOnSurfaceChanged(
        JNIEnv* env, jclass, jlong handle, jobject surface, jint width, jint height) {
    auto* renderer = asRenderer(handle);
    if (renderer != nullptr) {
        renderer->createOrUpdateSurface(env, surface, width, height);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_reandroid_wallpaper_fall_FallVKNative_nOnSurfaceDestroyed(
        JNIEnv*, jclass, jlong handle) {
    auto* renderer = asRenderer(handle);
    if (renderer != nullptr) {
        renderer->destroySurface();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_reandroid_wallpaper_fall_FallVKNative_nRenderFrame(
        JNIEnv* env, jclass, jlong handle, jfloatArray projectionMatrix, jfloatArray viewMatrix,
        jfloatArray leavesData, jint leafCount, jfloat xOffset,
        jfloatArray waterVertices, jfloatArray waterTexCoords, jshortArray waterIndices,
        jint waterVertexCount, jint waterIndexCount) {
    auto* renderer = asRenderer(handle);
    if (renderer != nullptr) {
        renderer->render(env, projectionMatrix, viewMatrix, leavesData, leafCount, xOffset,
                waterVertices, waterTexCoords, waterIndices, waterVertexCount, waterIndexCount);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_reandroid_wallpaper_fall_FallVKNative_nSetBackgroundTexture(
        JNIEnv* env, jclass, jlong handle, jintArray argbPixels, jint width, jint height) {
    auto* renderer = asRenderer(handle);
    if (renderer != nullptr) {
        renderer->setBackgroundTexture(env, argbPixels, width, height);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_reandroid_wallpaper_fall_FallVKNative_nSetLeafTexture(
        JNIEnv* env, jclass, jlong handle, jintArray argbPixels, jint width, jint height) {
    auto* renderer = asRenderer(handle);
    if (renderer != nullptr) {
        renderer->setLeafTexture(env, argbPixels, width, height);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_reandroid_wallpaper_fall_FallVKNative_nSetLeafAtlasFrameCount(
        JNIEnv*, jclass, jlong handle, jint frameCount) {
    auto* renderer = asRenderer(handle);
    if (renderer != nullptr) {
        renderer->setLeafAtlasFrameCount(frameCount);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reandroid_wallpaper_fall_FallVKNative_nIsVulkanSupported(
        JNIEnv*, jclass) {
    return FallVkRenderer::isVulkanSupported();
}
