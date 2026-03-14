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

#define LOG_TAG "GrassVK"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// ---- constants ----
constexpr uint32_t kMaxGrassVertices = 50000;
constexpr uint32_t kMaxGrassIndices  = 150000;
constexpr uint32_t kSkyTextureCount  = 4; // night, sunrise, sunset, sky

// ---- vertex layout ----
struct GrassVertex {
    float x, y;
    float r, g, b, a;
    float s, t;
};
static_assert(sizeof(GrassVertex) == 32, "GrassVertex must be 32 bytes");

// ---- push constants ----
struct SkyPushConstants {
    float weightNight;
    float weightSunrise;
    float weightSunset;
    float weightSky;
    float nightInvert;
    float pad[3];
};

struct GrassPushConstants {
    float mvp[16];
};

// ---- pipeline types ----
enum PipelineType {
    PIPELINE_SKY   = 0,
    PIPELINE_GRASS = 1,
};

// ---- helper: one GPU texture ----
struct GpuTexture {
    VkImage        image      = VK_NULL_HANDLE;
    VkDeviceMemory memory     = VK_NULL_HANDLE;
    VkImageView    view       = VK_NULL_HANDLE;
    VkSampler      sampler    = VK_NULL_HANDLE;
    uint32_t       width      = 0;
    uint32_t       height     = 0;

    bool isValid() const {
        return image != VK_NULL_HANDLE && view != VK_NULL_HANDLE && sampler != VK_NULL_HANDLE;
    }
};

// ---- main renderer class ----
class GrassVkRenderer {
public:
    explicit GrassVkRenderer(AAssetManager* am) : assetManager_(am) {}
    ~GrassVkRenderer() { destroy(); }

    // ---- public interface ----

    bool createOrUpdateSurface(JNIEnv* env, jobject surface, int width, int height) {
        std::lock_guard<std::mutex> lock(mutex_);
        width_  = width;
        height_ = height;

        if (!createInstanceLocked()) return false;

        destroySurfaceLocked();

        window_ = ANativeWindow_fromSurface(env, surface);
        if (!window_) { LOGE("ANativeWindow_fromSurface failed"); return false; }

        VkAndroidSurfaceCreateInfoKHR sci{};
        sci.sType  = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
        sci.window = window_;
        if (vkCreateAndroidSurfaceKHR(instance_, &sci, nullptr, &surface_) != VK_SUCCESS) {
            LOGE("vkCreateAndroidSurfaceKHR failed");
            ANativeWindow_release(window_);
            window_ = nullptr;
            return false;
        }

        if (!createDeviceLocked() || !createSwapchainResourcesLocked()) {
            destroySurfaceLocked();
            return false;
        }
        return true;
    }

    void destroySurface() {
        std::lock_guard<std::mutex> lock(mutex_);
        destroySurfaceLocked();
    }

    void render(JNIEnv* env,
            jfloatArray skyWeightsArr,
            jfloatArray grassMvpArr,
            jfloatArray grassVertsArr, jint grassVertCount,
            jshortArray grassIdxArr,   jint grassIdxCount) {
        std::lock_guard<std::mutex> lock(mutex_);

        if (!isReadyLocked()) {
            if (!recoverRenderStateLocked()) return;
        }
        if (!isReadyLocked()) return;

        // --- upload grass geometry ---
        const uint32_t vertexCount = static_cast<uint32_t>(
                std::min<int>(grassVertCount, static_cast<int>(kMaxGrassVertices)));
        const uint32_t indexCount  = static_cast<uint32_t>(
                std::min<int>(grassIdxCount,  static_cast<int>(kMaxGrassIndices)));
        uploadGrassGeometryLocked(env, grassVertsArr, vertexCount,
                                       grassIdxArr,   indexCount);

        // --- build push constants ---
        SkyPushConstants skyPC{};
        {
            jfloat* sw = env->GetFloatArrayElements(skyWeightsArr, nullptr);
            if (sw) {
                skyPC.weightNight   = sw[0];
                skyPC.weightSunrise = sw[1];
                skyPC.weightSunset  = sw[2];
                skyPC.weightSky     = sw[3];
                skyPC.nightInvert   = sw[4];
                env->ReleaseFloatArrayElements(skyWeightsArr, sw, JNI_ABORT);
            }
        }

        GrassPushConstants grassPC{};
        {
            jfloat* mp = env->GetFloatArrayElements(grassMvpArr, nullptr);
            if (mp) {
                std::memcpy(grassPC.mvp, mp, 16 * sizeof(float));
                env->ReleaseFloatArrayElements(grassMvpArr, mp, JNI_ABORT);
            }
        }

        // --- acquire image ---
        vkWaitForFences(device_, 1, &inFlightFence_, VK_TRUE, UINT64_MAX);

        uint32_t imageIndex = 0;
        VkResult acquire = vkAcquireNextImageKHR(device_, swapchain_, UINT64_MAX,
                imageAvailableSemaphore_, VK_NULL_HANDLE, &imageIndex);
        if (acquire == VK_ERROR_SURFACE_LOST_KHR)  { recreateSurfaceAndSwapchainLocked(); return; }
        if (acquire == VK_ERROR_OUT_OF_DATE_KHR || acquire == VK_SUBOPTIMAL_KHR) { recreateSwapchainLocked(); return; }
        if (acquire != VK_SUCCESS) { LOGE("vkAcquireNextImageKHR failed: %d", acquire); return; }

        VkCommandBuffer cb = commandBuffers_[imageIndex];
        vkResetCommandBuffer(cb, 0);
        if (!recordCommandBufferLocked(cb, imageIndex, vertexCount, indexCount, skyPC, grassPC)) return;

        VkPipelineStageFlags waitStages[] = {VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT};
        VkSubmitInfo si{};
        si.sType                = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        si.waitSemaphoreCount   = 1;
        si.pWaitSemaphores      = &imageAvailableSemaphore_;
        si.pWaitDstStageMask    = waitStages;
        si.commandBufferCount   = 1;
        si.pCommandBuffers      = &cb;
        si.signalSemaphoreCount = 1;
        si.pSignalSemaphores    = &renderFinishedSemaphore_;

        vkResetFences(device_, 1, &inFlightFence_);
        VkResult submit = vkQueueSubmit(graphicsQueue_, 1, &si, inFlightFence_);
        if (submit != VK_SUCCESS) {
            LOGE("vkQueueSubmit failed: %d", submit);
            recreateInFlightFenceLocked();
            return;
        }

        VkPresentInfoKHR pi{};
        pi.sType              = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
        pi.waitSemaphoreCount = 1;
        pi.pWaitSemaphores    = &renderFinishedSemaphore_;
        pi.swapchainCount     = 1;
        pi.pSwapchains        = &swapchain_;
        pi.pImageIndices      = &imageIndex;

        VkResult present = vkQueuePresentKHR(graphicsQueue_, &pi);
        if (present == VK_ERROR_SURFACE_LOST_KHR) { recreateSurfaceAndSwapchainLocked(); return; }
        if (present == VK_ERROR_OUT_OF_DATE_KHR || present == VK_SUBOPTIMAL_KHR) { recreateSwapchainLocked(); return; }
        if (present != VK_SUCCESS) { LOGE("vkQueuePresentKHR failed: %d", present); }
    }

    // slot: 0=night 1=sunrise 2=sunset 3=sky
    void setSkyTexture(JNIEnv* env, jint slot, jintArray argbPixels, jint width, jint height) {
        if (slot < 0 || slot >= static_cast<jint>(kSkyTextureCount)) return;
        if (!argbPixels || width <= 0 || height <= 0) return;

        jsize pixelCount = env->GetArrayLength(argbPixels);
        if (static_cast<int64_t>(width) * height > pixelCount) return;

        std::vector<uint8_t> rgba(static_cast<size_t>(width) * height * 4u);
        jint* pixels = env->GetIntArrayElements(argbPixels, nullptr);
        if (!pixels) return;
        for (size_t i = 0; i < static_cast<size_t>(width) * height; ++i) {
            uint32_t argb = static_cast<uint32_t>(pixels[i]);
            rgba[i*4+0] = static_cast<uint8_t>((argb >> 16) & 0xFF);
            rgba[i*4+1] = static_cast<uint8_t>((argb >>  8) & 0xFF);
            rgba[i*4+2] = static_cast<uint8_t>( argb        & 0xFF);
            rgba[i*4+3] = static_cast<uint8_t>((argb >> 24) & 0xFF);
        }
        env->ReleaseIntArrayElements(argbPixels, pixels, JNI_ABORT);

        std::lock_guard<std::mutex> lock(mutex_);
        pendingSkyPixels_[slot] = std::move(rgba);
        pendingSkyWidth_[slot]  = static_cast<uint32_t>(width);
        pendingSkyHeight_[slot] = static_cast<uint32_t>(height);

        if (device_ != VK_NULL_HANDLE && commandPool_ != VK_NULL_HANDLE && graphicsQueue_ != VK_NULL_HANDLE) {
            ensureSkyTextureLocked(static_cast<uint32_t>(slot));
        }
    }

    void setAATexture(JNIEnv* env, jintArray argbPixels, jint width, jint height) {
        if (!argbPixels || width <= 0 || height <= 0) return;

        jsize pixelCount = env->GetArrayLength(argbPixels);
        if (static_cast<int64_t>(width) * height > pixelCount) return;

        std::vector<uint8_t> rgba(static_cast<size_t>(width) * height * 4u);
        jint* pixels = env->GetIntArrayElements(argbPixels, nullptr);
        if (!pixels) return;
        for (size_t i = 0; i < static_cast<size_t>(width) * height; ++i) {
            uint32_t argb = static_cast<uint32_t>(pixels[i]);
            rgba[i*4+0] = static_cast<uint8_t>((argb >> 16) & 0xFF);
            rgba[i*4+1] = static_cast<uint8_t>((argb >>  8) & 0xFF);
            rgba[i*4+2] = static_cast<uint8_t>( argb        & 0xFF);
            rgba[i*4+3] = static_cast<uint8_t>((argb >> 24) & 0xFF);
        }
        env->ReleaseIntArrayElements(argbPixels, pixels, JNI_ABORT);

        std::lock_guard<std::mutex> lock(mutex_);
        pendingAAPixels_ = std::move(rgba);
        pendingAAWidth_  = static_cast<uint32_t>(width);
        pendingAAHeight_ = static_cast<uint32_t>(height);

        if (device_ != VK_NULL_HANDLE && commandPool_ != VK_NULL_HANDLE && graphicsQueue_ != VK_NULL_HANDLE) {
            ensureAATextureLocked();
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
        uint32_t extCount = 0;
        return vkEnumerateInstanceExtensionProperties(nullptr, &extCount, nullptr) == VK_SUCCESS
                && extCount > 0;
    }

private:
    // ===== instance / device =====

    bool createInstanceLocked() {
        if (instance_ != VK_NULL_HANDLE) return true;

        VkApplicationInfo ai{};
        ai.sType              = VK_STRUCTURE_TYPE_APPLICATION_INFO;
        ai.pApplicationName   = "GrassVK";
        ai.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
        ai.pEngineName        = "GrassVK";
        ai.engineVersion      = VK_MAKE_VERSION(1, 0, 0);
        ai.apiVersion         = VK_API_VERSION_1_0;

        std::array<const char*, 2> exts = {
                VK_KHR_SURFACE_EXTENSION_NAME,
                VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};

        VkInstanceCreateInfo ci{};
        ci.sType                   = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
        ci.pApplicationInfo        = &ai;
        ci.enabledExtensionCount   = static_cast<uint32_t>(exts.size());
        ci.ppEnabledExtensionNames = exts.data();

        if (vkCreateInstance(&ci, nullptr, &instance_) != VK_SUCCESS) {
            LOGE("vkCreateInstance failed");
            instance_ = VK_NULL_HANDLE;
            return false;
        }
        return true;
    }

    bool createDeviceLocked() {
        if (device_ != VK_NULL_HANDLE) return true;

        uint32_t devCount = 0;
        vkEnumeratePhysicalDevices(instance_, &devCount, nullptr);
        if (devCount == 0) { LOGE("No Vulkan physical devices"); return false; }
        std::vector<VkPhysicalDevice> devs(devCount);
        vkEnumeratePhysicalDevices(instance_, &devCount, devs.data());

        for (VkPhysicalDevice candidate : devs) {
            uint32_t qfCount = 0;
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, &qfCount, nullptr);
            std::vector<VkQueueFamilyProperties> qfProps(qfCount);
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, &qfCount, qfProps.data());
            for (uint32_t i = 0; i < qfCount; ++i) {
                VkBool32 present = VK_FALSE;
                vkGetPhysicalDeviceSurfaceSupportKHR(candidate, i, surface_, &present);
                if ((qfProps[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) && present) {
                    physicalDevice_   = candidate;
                    queueFamilyIndex_ = i;
                    break;
                }
            }
            if (physicalDevice_ != VK_NULL_HANDLE) break;
        }
        if (physicalDevice_ == VK_NULL_HANDLE) { LOGE("No suitable queue family"); return false; }

        float prio = 1.0f;
        VkDeviceQueueCreateInfo qci{};
        qci.sType            = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
        qci.queueFamilyIndex = queueFamilyIndex_;
        qci.queueCount       = 1;
        qci.pQueuePriorities = &prio;

        VkPhysicalDeviceFeatures features{};
        const char* devExts[] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};

        VkDeviceCreateInfo dci{};
        dci.sType                   = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
        dci.queueCreateInfoCount    = 1;
        dci.pQueueCreateInfos       = &qci;
        dci.pEnabledFeatures        = &features;
        dci.enabledExtensionCount   = 1;
        dci.ppEnabledExtensionNames = devExts;

        if (vkCreateDevice(physicalDevice_, &dci, nullptr, &device_) != VK_SUCCESS) {
            LOGE("vkCreateDevice failed");
            device_ = VK_NULL_HANDLE;
            return false;
        }
        vkGetDeviceQueue(device_, queueFamilyIndex_, 0, &graphicsQueue_);

        // command pool
        VkCommandPoolCreateInfo cpi{};
        cpi.sType            = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
        cpi.queueFamilyIndex = queueFamilyIndex_;
        cpi.flags            = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        if (vkCreateCommandPool(device_, &cpi, nullptr, &commandPool_) != VK_SUCCESS) {
            LOGE("vkCreateCommandPool failed");
            return false;
        }

        // semaphores + fence
        VkSemaphoreCreateInfo semi{};
        semi.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
        vkCreateSemaphore(device_, &semi, nullptr, &imageAvailableSemaphore_);
        vkCreateSemaphore(device_, &semi, nullptr, &renderFinishedSemaphore_);

        VkFenceCreateInfo fi{};
        fi.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        fi.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        vkCreateFence(device_, &fi, nullptr, &inFlightFence_);

        if (!createGrassGeometryBuffersLocked()) return false;
        if (!createSkyDescriptorResourcesLocked()) return false;
        if (!createGrassDescriptorResourcesLocked()) return false;

        // Upload any pending textures
        for (uint32_t s = 0; s < kSkyTextureCount; ++s) {
            ensureSkyTextureLocked(s);
        }
        ensureAATextureLocked();
        return true;
    }

    // ===== grass geometry buffers =====

    bool createGrassGeometryBuffersLocked() {
        // Vertex buffer
        if (!createMappedBufferLocked(sizeof(GrassVertex) * kMaxGrassVertices,
                VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                grassVertexBuffer_, grassVertexMemory_, grassVertexMapped_)) {
            return false;
        }
        // Index buffer
        if (!createMappedBufferLocked(sizeof(uint16_t) * kMaxGrassIndices,
                VK_BUFFER_USAGE_INDEX_BUFFER_BIT,
                grassIndexBuffer_, grassIndexMemory_, grassIndexMapped_)) {
            return false;
        }
        return true;
    }

    bool createMappedBufferLocked(VkDeviceSize size, VkBufferUsageFlags usage,
            VkBuffer& outBuf, VkDeviceMemory& outMem, void*& outMapped) {
        VkBufferCreateInfo bi{};
        bi.sType       = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        bi.size        = size;
        bi.usage       = usage;
        bi.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        if (vkCreateBuffer(device_, &bi, nullptr, &outBuf) != VK_SUCCESS) {
            LOGE("vkCreateBuffer failed (size=%llu)", (unsigned long long)size);
            return false;
        }

        VkMemoryRequirements req{};
        vkGetBufferMemoryRequirements(device_, outBuf, &req);

        VkMemoryAllocateInfo ai{};
        ai.sType           = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        ai.allocationSize  = req.size;
        ai.memoryTypeIndex = findMemoryTypeLocked(req.memoryTypeBits,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        if (vkAllocateMemory(device_, &ai, nullptr, &outMem) != VK_SUCCESS) {
            LOGE("vkAllocateMemory failed");
            vkDestroyBuffer(device_, outBuf, nullptr);
            outBuf = VK_NULL_HANDLE;
            return false;
        }
        vkBindBufferMemory(device_, outBuf, outMem, 0);
        if (vkMapMemory(device_, outMem, 0, size, 0, &outMapped) != VK_SUCCESS) {
            LOGE("vkMapMemory failed");
            outMapped = nullptr;
            return false;
        }
        return true;
    }

    void uploadGrassGeometryLocked(JNIEnv* env,
            jfloatArray vertsArr, uint32_t vertexCount,
            jshortArray idxArr,   uint32_t indexCount) {
        if (grassVertexMapped_ && vertsArr && vertexCount > 0) {
            jfloat* verts = env->GetFloatArrayElements(vertsArr, nullptr);
            if (verts) {
                std::memcpy(grassVertexMapped_, verts,
                        vertexCount * sizeof(GrassVertex));
                env->ReleaseFloatArrayElements(vertsArr, verts, JNI_ABORT);
            }
        }
        if (grassIndexMapped_ && idxArr && indexCount > 0) {
            jshort* idx = env->GetShortArrayElements(idxArr, nullptr);
            if (idx) {
                std::memcpy(grassIndexMapped_, idx,
                        indexCount * sizeof(uint16_t));
                env->ReleaseShortArrayElements(idxArr, idx, JNI_ABORT);
            }
        }
    }

    // ===== swapchain =====

    bool createSwapchainResourcesLocked() {
        VkSurfaceCapabilitiesKHR caps{};
        vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice_, surface_, &caps);

        uint32_t fmtCount = 0;
        vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice_, surface_, &fmtCount, nullptr);
        if (fmtCount == 0) { LOGE("No surface formats"); return false; }
        std::vector<VkSurfaceFormatKHR> fmts(fmtCount);
        vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice_, surface_, &fmtCount, fmts.data());

        VkSurfaceFormatKHR selFmt = fmts[0];
        for (const auto& f : fmts) {
            if (f.format == VK_FORMAT_B8G8R8A8_UNORM || f.format == VK_FORMAT_R8G8B8A8_UNORM) {
                selFmt = f; break;
            }
        }

        VkExtent2D extent{};
        if (caps.currentExtent.width != std::numeric_limits<uint32_t>::max()) {
            extent = caps.currentExtent;
        } else {
            extent.width  = std::max(caps.minImageExtent.width,
                    std::min(caps.maxImageExtent.width,  static_cast<uint32_t>(std::max(width_,  1))));
            extent.height = std::max(caps.minImageExtent.height,
                    std::min(caps.maxImageExtent.height, static_cast<uint32_t>(std::max(height_, 1))));
        }

        uint32_t imgCount = caps.minImageCount + 1;
        if (caps.maxImageCount > 0 && imgCount > caps.maxImageCount) imgCount = caps.maxImageCount;

        VkSwapchainCreateInfoKHR sci{};
        sci.sType            = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
        sci.surface          = surface_;
        sci.minImageCount    = imgCount;
        sci.imageFormat      = selFmt.format;
        sci.imageColorSpace  = selFmt.colorSpace;
        sci.imageExtent      = extent;
        sci.imageArrayLayers = 1;
        sci.imageUsage       = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
        sci.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
        sci.preTransform     = caps.currentTransform;
        sci.compositeAlpha   = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
        sci.presentMode      = VK_PRESENT_MODE_FIFO_KHR;
        sci.clipped          = VK_TRUE;
        sci.oldSwapchain     = VK_NULL_HANDLE;

        if (vkCreateSwapchainKHR(device_, &sci, nullptr, &swapchain_) != VK_SUCCESS) {
            LOGE("vkCreateSwapchainKHR failed"); return false;
        }

        uint32_t imgCnt = 0;
        vkGetSwapchainImagesKHR(device_, swapchain_, &imgCnt, nullptr);
        swapchainImages_.resize(imgCnt);
        vkGetSwapchainImagesKHR(device_, swapchain_, &imgCnt, swapchainImages_.data());
        swapchainFormat_ = selFmt.format;
        swapchainExtent_ = extent;

        swapchainImageViews_.resize(imgCnt);
        for (size_t i = 0; i < imgCnt; ++i) {
            VkImageViewCreateInfo vci{};
            vci.sType                           = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
            vci.image                           = swapchainImages_[i];
            vci.viewType                        = VK_IMAGE_VIEW_TYPE_2D;
            vci.format                          = swapchainFormat_;
            vci.subresourceRange.aspectMask     = VK_IMAGE_ASPECT_COLOR_BIT;
            vci.subresourceRange.baseMipLevel   = 0;
            vci.subresourceRange.levelCount     = 1;
            vci.subresourceRange.baseArrayLayer = 0;
            vci.subresourceRange.layerCount     = 1;
            if (vkCreateImageView(device_, &vci, nullptr, &swapchainImageViews_[i]) != VK_SUCCESS) {
                LOGE("vkCreateImageView failed"); return false;
            }
        }

        if (!createRenderPassLocked()) return false;
        if (!createPipelinesLocked())  return false;

        framebuffers_.resize(imgCnt);
        for (size_t i = 0; i < imgCnt; ++i) {
            VkImageView att[] = {swapchainImageViews_[i]};
            VkFramebufferCreateInfo fci{};
            fci.sType           = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
            fci.renderPass      = renderPass_;
            fci.attachmentCount = 1;
            fci.pAttachments    = att;
            fci.width           = swapchainExtent_.width;
            fci.height          = swapchainExtent_.height;
            fci.layers          = 1;
            if (vkCreateFramebuffer(device_, &fci, nullptr, &framebuffers_[i]) != VK_SUCCESS) {
                LOGE("vkCreateFramebuffer failed"); return false;
            }
        }

        commandBuffers_.resize(imgCnt);
        VkCommandBufferAllocateInfo cbai{};
        cbai.sType              = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        cbai.commandPool        = commandPool_;
        cbai.level              = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        cbai.commandBufferCount = static_cast<uint32_t>(imgCnt);
        if (vkAllocateCommandBuffers(device_, &cbai, commandBuffers_.data()) != VK_SUCCESS) {
            LOGE("vkAllocateCommandBuffers failed"); return false;
        }
        return true;
    }

    void destroySwapchainResourcesLocked() {
        if (device_ != VK_NULL_HANDLE) vkDeviceWaitIdle(device_);

        if (!commandBuffers_.empty()) {
            vkFreeCommandBuffers(device_, commandPool_,
                    static_cast<uint32_t>(commandBuffers_.size()), commandBuffers_.data());
            commandBuffers_.clear();
        }
        for (VkFramebuffer fb : framebuffers_) vkDestroyFramebuffer(device_, fb, nullptr);
        framebuffers_.clear();

        if (skyPipeline_   != VK_NULL_HANDLE) { vkDestroyPipeline(device_, skyPipeline_,   nullptr); skyPipeline_   = VK_NULL_HANDLE; }
        if (grassPipeline_ != VK_NULL_HANDLE) { vkDestroyPipeline(device_, grassPipeline_, nullptr); grassPipeline_ = VK_NULL_HANDLE; }
        if (skyPipelineLayout_   != VK_NULL_HANDLE) { vkDestroyPipelineLayout(device_, skyPipelineLayout_,   nullptr); skyPipelineLayout_   = VK_NULL_HANDLE; }
        if (grassPipelineLayout_ != VK_NULL_HANDLE) { vkDestroyPipelineLayout(device_, grassPipelineLayout_, nullptr); grassPipelineLayout_ = VK_NULL_HANDLE; }
        if (renderPass_ != VK_NULL_HANDLE) { vkDestroyRenderPass(device_, renderPass_, nullptr); renderPass_ = VK_NULL_HANDLE; }

        for (VkImageView iv : swapchainImageViews_) vkDestroyImageView(device_, iv, nullptr);
        swapchainImageViews_.clear();
        swapchainImages_.clear();

        if (swapchain_ != VK_NULL_HANDLE) { vkDestroySwapchainKHR(device_, swapchain_, nullptr); swapchain_ = VK_NULL_HANDLE; }
    }

    bool recreateSwapchainLocked() {
        if (device_ == VK_NULL_HANDLE || surface_ == VK_NULL_HANDLE) return false;
        destroySwapchainResourcesLocked();
        return createSwapchainResourcesLocked();
    }

    bool recreateSurfaceAndSwapchainLocked() {
        if (instance_ == VK_NULL_HANDLE || window_ == nullptr) return false;
        destroySwapchainResourcesLocked();
        if (surface_ != VK_NULL_HANDLE) { vkDestroySurfaceKHR(instance_, surface_, nullptr); surface_ = VK_NULL_HANDLE; }

        VkAndroidSurfaceCreateInfoKHR sci{};
        sci.sType  = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
        sci.window = window_;
        if (vkCreateAndroidSurfaceKHR(instance_, &sci, nullptr, &surface_) != VK_SUCCESS) {
            LOGE("vkCreateAndroidSurfaceKHR (recreate) failed");
            surface_ = VK_NULL_HANDLE;
            return false;
        }
        return createSwapchainResourcesLocked();
    }

    void recreateInFlightFenceLocked() {
        if (device_ == VK_NULL_HANDLE) return;
        if (inFlightFence_ != VK_NULL_HANDLE) { vkDestroyFence(device_, inFlightFence_, nullptr); inFlightFence_ = VK_NULL_HANDLE; }
        VkFenceCreateInfo fi{};
        fi.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        fi.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        vkCreateFence(device_, &fi, nullptr, &inFlightFence_);
    }

    bool recoverRenderStateLocked() {
        if (device_ == VK_NULL_HANDLE || window_ == nullptr) return false;

        if (surface_ == VK_NULL_HANDLE) {
            if (!recreateSurfaceAndSwapchainLocked()) return false;
        }
        if (swapchain_ == VK_NULL_HANDLE || renderPass_ == VK_NULL_HANDLE
                || skyPipeline_ == VK_NULL_HANDLE || grassPipeline_ == VK_NULL_HANDLE
                || commandBuffers_.empty()) {
            if (!recreateSwapchainLocked()) return false;
        }
        if (skyDescriptorSet_ == VK_NULL_HANDLE) {
            if (!createSkyDescriptorResourcesLocked()) return false;
        }
        if (grassDescriptorSet_ == VK_NULL_HANDLE) {
            if (!createGrassDescriptorResourcesLocked()) return false;
        }
        for (uint32_t s = 0; s < kSkyTextureCount; ++s) {
            if (!skyTextures_[s].isValid()) ensureSkyTextureLocked(s);
        }
        if (!aaTexture_.isValid()) ensureAATextureLocked();
        return true;
    }

    // ===== render pass =====

    bool createRenderPassLocked() {
        VkAttachmentDescription att{};
        att.format         = swapchainFormat_;
        att.samples        = VK_SAMPLE_COUNT_1_BIT;
        att.loadOp         = VK_ATTACHMENT_LOAD_OP_CLEAR;
        att.storeOp        = VK_ATTACHMENT_STORE_OP_STORE;
        att.stencilLoadOp  = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        att.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        att.initialLayout  = VK_IMAGE_LAYOUT_UNDEFINED;
        att.finalLayout    = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;

        VkAttachmentReference ref{};
        ref.attachment = 0;
        ref.layout     = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;

        VkSubpassDescription sub{};
        sub.pipelineBindPoint    = VK_PIPELINE_BIND_POINT_GRAPHICS;
        sub.colorAttachmentCount = 1;
        sub.pColorAttachments    = &ref;

        VkSubpassDependency dep{};
        dep.srcSubpass    = VK_SUBPASS_EXTERNAL;
        dep.dstSubpass    = 0;
        dep.srcStageMask  = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dep.dstStageMask  = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dep.srcAccessMask = 0;
        dep.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;

        VkRenderPassCreateInfo rpci{};
        rpci.sType           = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
        rpci.attachmentCount = 1;
        rpci.pAttachments    = &att;
        rpci.subpassCount    = 1;
        rpci.pSubpasses      = &sub;
        rpci.dependencyCount = 1;
        rpci.pDependencies   = &dep;

        if (vkCreateRenderPass(device_, &rpci, nullptr, &renderPass_) != VK_SUCCESS) {
            LOGE("vkCreateRenderPass failed"); return false;
        }
        return true;
    }

    // ===== pipelines =====

    bool createPipelinesLocked() {
        VkShaderModule skyVert  = createShaderModuleLocked({"shaders/grassvk_sky.vert.spv",  "grassvk_sky.vert.spv"});
        VkShaderModule skyFrag  = createShaderModuleLocked({"shaders/grassvk_sky.frag.spv",  "grassvk_sky.frag.spv"});
        VkShaderModule gVert    = createShaderModuleLocked({"shaders/grassvk_grass.vert.spv", "grassvk_grass.vert.spv"});
        VkShaderModule gFrag    = createShaderModuleLocked({"shaders/grassvk_grass.frag.spv", "grassvk_grass.frag.spv"});

        auto cleanup = [&](){
            if (skyVert  != VK_NULL_HANDLE) vkDestroyShaderModule(device_, skyVert,  nullptr);
            if (skyFrag  != VK_NULL_HANDLE) vkDestroyShaderModule(device_, skyFrag,  nullptr);
            if (gVert    != VK_NULL_HANDLE) vkDestroyShaderModule(device_, gVert,    nullptr);
            if (gFrag    != VK_NULL_HANDLE) vkDestroyShaderModule(device_, gFrag,    nullptr);
        };

        if (!skyVert || !skyFrag || !gVert || !gFrag) { cleanup(); return false; }

        skyPipelineLayout_   = createPipelineLayoutLocked(PIPELINE_SKY);
        grassPipelineLayout_ = createPipelineLayoutLocked(PIPELINE_GRASS);
        if (!skyPipelineLayout_ || !grassPipelineLayout_) { cleanup(); return false; }

        skyPipeline_   = createGraphicsPipelineLocked(skyVert,  skyFrag,  skyPipelineLayout_,   PIPELINE_SKY);
        grassPipeline_ = createGraphicsPipelineLocked(gVert,    gFrag,    grassPipelineLayout_,  PIPELINE_GRASS);

        cleanup();
        return skyPipeline_ != VK_NULL_HANDLE && grassPipeline_ != VK_NULL_HANDLE;
    }

    VkPipelineLayout createPipelineLayoutLocked(PipelineType type) {
        VkDescriptorSetLayout dsl = (type == PIPELINE_SKY)
                ? skyDescriptorSetLayout_
                : grassDescriptorSetLayout_;

        VkPushConstantRange pcRange{};
        pcRange.stageFlags = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
        pcRange.offset     = 0;
        pcRange.size       = (type == PIPELINE_SKY)
                ? static_cast<uint32_t>(sizeof(SkyPushConstants))
                : static_cast<uint32_t>(sizeof(GrassPushConstants));

        VkPipelineLayoutCreateInfo plci{};
        plci.sType                  = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        plci.setLayoutCount         = 1;
        plci.pSetLayouts            = &dsl;
        plci.pushConstantRangeCount = 1;
        plci.pPushConstantRanges    = &pcRange;

        VkPipelineLayout pl = VK_NULL_HANDLE;
        if (vkCreatePipelineLayout(device_, &plci, nullptr, &pl) != VK_SUCCESS) {
            LOGE("vkCreatePipelineLayout failed"); return VK_NULL_HANDLE;
        }
        return pl;
    }

    VkPipeline createGraphicsPipelineLocked(VkShaderModule vert, VkShaderModule frag,
            VkPipelineLayout layout, PipelineType type) {
        VkPipelineShaderStageCreateInfo stages[2]{};
        stages[0].sType  = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        stages[0].stage  = VK_SHADER_STAGE_VERTEX_BIT;
        stages[0].module = vert;
        stages[0].pName  = "main";
        stages[1].sType  = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        stages[1].stage  = VK_SHADER_STAGE_FRAGMENT_BIT;
        stages[1].module = frag;
        stages[1].pName  = "main";

        // Vertex input
        VkPipelineVertexInputStateCreateInfo vis{};
        vis.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;

        VkVertexInputBindingDescription binding{};
        VkVertexInputAttributeDescription attrs[3]{};

        if (type == PIPELINE_GRASS) {
            binding.binding   = 0;
            binding.stride    = sizeof(GrassVertex);
            binding.inputRate = VK_VERTEX_INPUT_RATE_VERTEX;

            // location 0: vec2 position
            attrs[0].binding  = 0; attrs[0].location = 0;
            attrs[0].format   = VK_FORMAT_R32G32_SFLOAT;
            attrs[0].offset   = offsetof(GrassVertex, x);
            // location 1: vec4 color
            attrs[1].binding  = 0; attrs[1].location = 1;
            attrs[1].format   = VK_FORMAT_R32G32B32A32_SFLOAT;
            attrs[1].offset   = offsetof(GrassVertex, r);
            // location 2: vec2 texcoord
            attrs[2].binding  = 0; attrs[2].location = 2;
            attrs[2].format   = VK_FORMAT_R32G32_SFLOAT;
            attrs[2].offset   = offsetof(GrassVertex, s);

            vis.vertexBindingDescriptionCount   = 1;
            vis.pVertexBindingDescriptions      = &binding;
            vis.vertexAttributeDescriptionCount = 3;
            vis.pVertexAttributeDescriptions    = attrs;
        }

        VkPipelineInputAssemblyStateCreateInfo ias{};
        ias.sType    = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
        ias.topology = (type == PIPELINE_SKY)
                ? VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST
                : VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;

        VkPipelineViewportStateCreateInfo vps{};
        vps.sType         = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
        vps.viewportCount = 1;
        vps.scissorCount  = 1;

        VkPipelineRasterizationStateCreateInfo rast{};
        rast.sType       = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
        rast.polygonMode = VK_POLYGON_MODE_FILL;
        rast.cullMode    = VK_CULL_MODE_NONE;
        rast.frontFace   = VK_FRONT_FACE_COUNTER_CLOCKWISE;
        rast.lineWidth   = 1.0f;

        VkPipelineMultisampleStateCreateInfo ms{};
        ms.sType                = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
        ms.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

        VkPipelineColorBlendAttachmentState cba{};
        cba.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
                           | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
        if (type == PIPELINE_GRASS) {
            cba.blendEnable         = VK_TRUE;
            cba.srcColorBlendFactor = VK_BLEND_FACTOR_SRC_ALPHA;
            cba.dstColorBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            cba.colorBlendOp        = VK_BLEND_OP_ADD;
            cba.srcAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
            cba.dstAlphaBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            cba.alphaBlendOp        = VK_BLEND_OP_ADD;
        } else {
            cba.blendEnable = VK_FALSE; // sky fills opaque
        }

        VkPipelineColorBlendStateCreateInfo cbs{};
        cbs.sType           = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
        cbs.attachmentCount = 1;
        cbs.pAttachments    = &cba;

        std::array<VkDynamicState, 2> dynStates = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
        VkPipelineDynamicStateCreateInfo dyn{};
        dyn.sType             = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
        dyn.dynamicStateCount = static_cast<uint32_t>(dynStates.size());
        dyn.pDynamicStates    = dynStates.data();

        VkGraphicsPipelineCreateInfo gpci{};
        gpci.sType               = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
        gpci.stageCount          = 2;
        gpci.pStages             = stages;
        gpci.pVertexInputState   = &vis;
        gpci.pInputAssemblyState = &ias;
        gpci.pViewportState      = &vps;
        gpci.pRasterizationState = &rast;
        gpci.pMultisampleState   = &ms;
        gpci.pColorBlendState    = &cbs;
        gpci.pDynamicState       = &dyn;
        gpci.layout              = layout;
        gpci.renderPass          = renderPass_;
        gpci.subpass             = 0;

        VkPipeline pl = VK_NULL_HANDLE;
        if (vkCreateGraphicsPipelines(device_, VK_NULL_HANDLE, 1, &gpci, nullptr, &pl) != VK_SUCCESS) {
            LOGE("vkCreateGraphicsPipelines failed"); return VK_NULL_HANDLE;
        }
        return pl;
    }

    // ===== descriptor sets =====

    bool createSkyDescriptorResourcesLocked() {
        if (skyDescriptorSetLayout_ != VK_NULL_HANDLE
                && skyDescriptorPool_ != VK_NULL_HANDLE
                && skyDescriptorSet_  != VK_NULL_HANDLE) return true;

        // 4 bindings, one per sky texture
        VkDescriptorSetLayoutBinding bindings[kSkyTextureCount]{};
        for (uint32_t i = 0; i < kSkyTextureCount; ++i) {
            bindings[i].binding         = i;
            bindings[i].descriptorCount = 1;
            bindings[i].descriptorType  = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            bindings[i].stageFlags      = VK_SHADER_STAGE_FRAGMENT_BIT;
        }

        VkDescriptorSetLayoutCreateInfo lci{};
        lci.sType        = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        lci.bindingCount = kSkyTextureCount;
        lci.pBindings    = bindings;
        if (vkCreateDescriptorSetLayout(device_, &lci, nullptr, &skyDescriptorSetLayout_) != VK_SUCCESS) {
            LOGE("vkCreateDescriptorSetLayout(sky) failed"); return false;
        }

        VkDescriptorPoolSize ps{};
        ps.type            = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        ps.descriptorCount = kSkyTextureCount;

        VkDescriptorPoolCreateInfo pci{};
        pci.sType         = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
        pci.poolSizeCount = 1;
        pci.pPoolSizes    = &ps;
        pci.maxSets       = 1;
        if (vkCreateDescriptorPool(device_, &pci, nullptr, &skyDescriptorPool_) != VK_SUCCESS) {
            LOGE("vkCreateDescriptorPool(sky) failed"); return false;
        }

        VkDescriptorSetAllocateInfo ai{};
        ai.sType              = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        ai.descriptorPool     = skyDescriptorPool_;
        ai.descriptorSetCount = 1;
        ai.pSetLayouts        = &skyDescriptorSetLayout_;
        if (vkAllocateDescriptorSets(device_, &ai, &skyDescriptorSet_) != VK_SUCCESS) {
            LOGE("vkAllocateDescriptorSets(sky) failed"); return false;
        }
        return true;
    }

    bool createGrassDescriptorResourcesLocked() {
        if (grassDescriptorSetLayout_ != VK_NULL_HANDLE
                && grassDescriptorPool_      != VK_NULL_HANDLE
                && grassDescriptorSet_       != VK_NULL_HANDLE) return true;

        VkDescriptorSetLayoutBinding binding{};
        binding.binding         = 0;
        binding.descriptorCount = 1;
        binding.descriptorType  = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        binding.stageFlags      = VK_SHADER_STAGE_FRAGMENT_BIT;

        VkDescriptorSetLayoutCreateInfo lci{};
        lci.sType        = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        lci.bindingCount = 1;
        lci.pBindings    = &binding;
        if (vkCreateDescriptorSetLayout(device_, &lci, nullptr, &grassDescriptorSetLayout_) != VK_SUCCESS) {
            LOGE("vkCreateDescriptorSetLayout(grass) failed"); return false;
        }

        VkDescriptorPoolSize ps{};
        ps.type            = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        ps.descriptorCount = 1;

        VkDescriptorPoolCreateInfo pci{};
        pci.sType         = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
        pci.poolSizeCount = 1;
        pci.pPoolSizes    = &ps;
        pci.maxSets       = 1;
        if (vkCreateDescriptorPool(device_, &pci, nullptr, &grassDescriptorPool_) != VK_SUCCESS) {
            LOGE("vkCreateDescriptorPool(grass) failed"); return false;
        }

        VkDescriptorSetAllocateInfo ai{};
        ai.sType              = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        ai.descriptorPool     = grassDescriptorPool_;
        ai.descriptorSetCount = 1;
        ai.pSetLayouts        = &grassDescriptorSetLayout_;
        if (vkAllocateDescriptorSets(device_, &ai, &grassDescriptorSet_) != VK_SUCCESS) {
            LOGE("vkAllocateDescriptorSets(grass) failed"); return false;
        }
        return true;
    }

    // update sky descriptor set binding for a given slot
    void updateSkyDescriptorLocked(uint32_t slot) {
        if (skyDescriptorSet_ == VK_NULL_HANDLE) return;
        if (!skyTextures_[slot].isValid()) return;

        VkDescriptorImageInfo ii{};
        ii.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        ii.imageView   = skyTextures_[slot].view;
        ii.sampler     = skyTextures_[slot].sampler;

        VkWriteDescriptorSet wd{};
        wd.sType           = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        wd.dstSet          = skyDescriptorSet_;
        wd.dstBinding      = slot;
        wd.descriptorCount = 1;
        wd.descriptorType  = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        wd.pImageInfo      = &ii;
        vkUpdateDescriptorSets(device_, 1, &wd, 0, nullptr);
    }

    void updateGrassDescriptorLocked() {
        if (grassDescriptorSet_ == VK_NULL_HANDLE) return;
        if (!aaTexture_.isValid()) return;

        VkDescriptorImageInfo ii{};
        ii.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        ii.imageView   = aaTexture_.view;
        ii.sampler     = aaTexture_.sampler;

        VkWriteDescriptorSet wd{};
        wd.sType           = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        wd.dstSet          = grassDescriptorSet_;
        wd.dstBinding      = 0;
        wd.descriptorCount = 1;
        wd.descriptorType  = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        wd.pImageInfo      = &ii;
        vkUpdateDescriptorSets(device_, 1, &wd, 0, nullptr);
    }

    // ===== texture management =====

    void ensureSkyTextureLocked(uint32_t slot) {
        if (skyTextures_[slot].isValid()) return;

        if (!pendingSkyPixels_[slot].empty()
                && pendingSkyWidth_[slot] > 0
                && pendingSkyHeight_[slot] > 0) {
            if (uploadTextureLocked(pendingSkyPixels_[slot].data(),
                    pendingSkyWidth_[slot], pendingSkyHeight_[slot],
                    VK_FILTER_LINEAR, VK_SAMPLER_ADDRESS_MODE_REPEAT,
                    skyTextures_[slot])) {
                updateSkyDescriptorLocked(slot);
            }
            return;
        }
        // Fallback: 1x1 opaque black
        const uint8_t fallback[4] = {0,0,0,255};
        if (uploadTextureLocked(fallback, 1, 1,
                VK_FILTER_LINEAR, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
                skyTextures_[slot])) {
            updateSkyDescriptorLocked(slot);
        }
    }

    void ensureAATextureLocked() {
        if (aaTexture_.isValid()) return;

        if (!pendingAAPixels_.empty() && pendingAAWidth_ > 0 && pendingAAHeight_ > 0) {
            if (uploadTextureLocked(pendingAAPixels_.data(),
                    pendingAAWidth_, pendingAAHeight_,
                    VK_FILTER_NEAREST, VK_SAMPLER_ADDRESS_MODE_REPEAT,
                    aaTexture_)) {
                updateGrassDescriptorLocked();
            }
            return;
        }
        // Fallback: 1x1 white
        const uint8_t fallback[4] = {255,255,255,255};
        if (uploadTextureLocked(fallback, 1, 1,
                VK_FILTER_NEAREST, VK_SAMPLER_ADDRESS_MODE_REPEAT,
                aaTexture_)) {
            updateGrassDescriptorLocked();
        }
    }

    bool uploadTextureLocked(const uint8_t* rgbaPixels, uint32_t width, uint32_t height,
            VkFilter filter, VkSamplerAddressMode addressMode, GpuTexture& tex) {
        destroyGpuTextureLocked(tex);

        VkDeviceSize imageSize = static_cast<VkDeviceSize>(width) * height * 4u;

        // staging buffer
        VkBuffer stageBuf = VK_NULL_HANDLE;
        VkDeviceMemory stageMem = VK_NULL_HANDLE;
        {
            VkBufferCreateInfo bi{};
            bi.sType       = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
            bi.size        = imageSize;
            bi.usage       = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
            bi.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
            if (vkCreateBuffer(device_, &bi, nullptr, &stageBuf) != VK_SUCCESS) {
                LOGE("vkCreateBuffer (staging) failed"); return false;
            }
            VkMemoryRequirements req{};
            vkGetBufferMemoryRequirements(device_, stageBuf, &req);
            VkMemoryAllocateInfo ai{};
            ai.sType           = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
            ai.allocationSize  = req.size;
            ai.memoryTypeIndex = findMemoryTypeLocked(req.memoryTypeBits,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            if (vkAllocateMemory(device_, &ai, nullptr, &stageMem) != VK_SUCCESS) {
                LOGE("vkAllocateMemory (staging) failed");
                vkDestroyBuffer(device_, stageBuf, nullptr);
                return false;
            }
            vkBindBufferMemory(device_, stageBuf, stageMem, 0);
            void* mapped = nullptr;
            vkMapMemory(device_, stageMem, 0, imageSize, 0, &mapped);
            if (mapped) std::memcpy(mapped, rgbaPixels, static_cast<size_t>(imageSize));
            vkUnmapMemory(device_, stageMem);
        }

        // create image
        VkImageCreateInfo ici{};
        ici.sType         = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        ici.imageType     = VK_IMAGE_TYPE_2D;
        ici.extent        = {width, height, 1};
        ici.mipLevels     = 1;
        ici.arrayLayers   = 1;
        ici.format        = VK_FORMAT_R8G8B8A8_UNORM;
        ici.tiling        = VK_IMAGE_TILING_OPTIMAL;
        ici.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        ici.usage         = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        ici.sharingMode   = VK_SHARING_MODE_EXCLUSIVE;
        ici.samples       = VK_SAMPLE_COUNT_1_BIT;
        if (vkCreateImage(device_, &ici, nullptr, &tex.image) != VK_SUCCESS) {
            LOGE("vkCreateImage failed");
            vkFreeMemory(device_, stageMem, nullptr);
            vkDestroyBuffer(device_, stageBuf, nullptr);
            return false;
        }

        VkMemoryRequirements imgReq{};
        vkGetImageMemoryRequirements(device_, tex.image, &imgReq);
        VkMemoryAllocateInfo imgAI{};
        imgAI.sType           = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        imgAI.allocationSize  = imgReq.size;
        imgAI.memoryTypeIndex = findMemoryTypeLocked(imgReq.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if (vkAllocateMemory(device_, &imgAI, nullptr, &tex.memory) != VK_SUCCESS) {
            LOGE("vkAllocateMemory (image) failed");
            vkDestroyImage(device_, tex.image, nullptr); tex.image = VK_NULL_HANDLE;
            vkFreeMemory(device_, stageMem, nullptr);
            vkDestroyBuffer(device_, stageBuf, nullptr);
            return false;
        }
        vkBindImageMemory(device_, tex.image, tex.memory, 0);

        // copy via oneshot commands
        VkCommandBuffer cb = beginOneTimeCommandsLocked();
        if (cb == VK_NULL_HANDLE) {
            vkFreeMemory(device_, stageMem, nullptr);
            vkDestroyBuffer(device_, stageBuf, nullptr);
            return false;
        }
        transitionImageLayoutLocked(cb, tex.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        VkBufferImageCopy region{};
        region.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        region.imageSubresource.mipLevel   = 0;
        region.imageSubresource.layerCount = 1;
        region.imageExtent = {width, height, 1};
        vkCmdCopyBufferToImage(cb, stageBuf, tex.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);
        transitionImageLayoutLocked(cb, tex.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        endOneTimeCommandsLocked(cb);

        vkFreeMemory(device_, stageMem, nullptr);
        vkDestroyBuffer(device_, stageBuf, nullptr);

        // image view
        VkImageViewCreateInfo vci{};
        vci.sType                           = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        vci.image                           = tex.image;
        vci.viewType                        = VK_IMAGE_VIEW_TYPE_2D;
        vci.format                          = VK_FORMAT_R8G8B8A8_UNORM;
        vci.subresourceRange.aspectMask     = VK_IMAGE_ASPECT_COLOR_BIT;
        vci.subresourceRange.baseMipLevel   = 0;
        vci.subresourceRange.levelCount     = 1;
        vci.subresourceRange.baseArrayLayer = 0;
        vci.subresourceRange.layerCount     = 1;
        if (vkCreateImageView(device_, &vci, nullptr, &tex.view) != VK_SUCCESS) {
            LOGE("vkCreateImageView failed"); return false;
        }

        // sampler
        VkSamplerCreateInfo sci{};
        sci.sType        = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
        sci.magFilter    = filter;
        sci.minFilter    = filter;
        sci.mipmapMode   = VK_SAMPLER_MIPMAP_MODE_NEAREST;
        sci.addressModeU = addressMode;
        sci.addressModeV = addressMode;
        sci.addressModeW = addressMode;
        sci.maxAnisotropy = 1.0f;
        if (vkCreateSampler(device_, &sci, nullptr, &tex.sampler) != VK_SUCCESS) {
            LOGE("vkCreateSampler failed"); return false;
        }

        tex.width  = width;
        tex.height = height;
        return true;
    }

    void destroyGpuTextureLocked(GpuTexture& tex) {
        if (tex.sampler != VK_NULL_HANDLE) { vkDestroySampler(device_, tex.sampler, nullptr);    tex.sampler = VK_NULL_HANDLE; }
        if (tex.view    != VK_NULL_HANDLE) { vkDestroyImageView(device_, tex.view,  nullptr);    tex.view    = VK_NULL_HANDLE; }
        if (tex.image   != VK_NULL_HANDLE) { vkDestroyImage(device_, tex.image,     nullptr);    tex.image   = VK_NULL_HANDLE; }
        if (tex.memory  != VK_NULL_HANDLE) { vkFreeMemory(device_, tex.memory,      nullptr);    tex.memory  = VK_NULL_HANDLE; }
        tex.width = tex.height = 0;
    }

    // ===== command recording =====

    bool recordCommandBufferLocked(VkCommandBuffer cb, uint32_t imageIndex,
            uint32_t vertexCount, uint32_t indexCount,
            const SkyPushConstants& skyPC, const GrassPushConstants& grassPC) {
        VkCommandBufferBeginInfo bi{};
        bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        if (vkBeginCommandBuffer(cb, &bi) != VK_SUCCESS) { LOGE("vkBeginCommandBuffer failed"); return false; }

        VkClearValue clear{};
        clear.color = {{0.0f, 0.0f, 0.0f, 1.0f}};

        VkRenderPassBeginInfo rpbi{};
        rpbi.sType             = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
        rpbi.renderPass        = renderPass_;
        rpbi.framebuffer       = framebuffers_[imageIndex];
        rpbi.renderArea.offset = {0, 0};
        rpbi.renderArea.extent = swapchainExtent_;
        rpbi.clearValueCount   = 1;
        rpbi.pClearValues      = &clear;

        vkCmdBeginRenderPass(cb, &rpbi, VK_SUBPASS_CONTENTS_INLINE);

        VkViewport vp{};
        vp.x        = 0.0f;
        vp.y        = 0.0f;
        vp.width    = static_cast<float>(swapchainExtent_.width);
        vp.height   = static_cast<float>(swapchainExtent_.height);
        vp.minDepth = 0.0f;
        vp.maxDepth = 1.0f;
        vkCmdSetViewport(cb, 0, 1, &vp);

        VkRect2D scissor{};
        scissor.offset = {0,0};
        scissor.extent = swapchainExtent_;
        vkCmdSetScissor(cb, 0, 1, &scissor);

        // ----- sky pass -----
        if (skyDescriptorSet_ != VK_NULL_HANDLE) {
            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, skyPipeline_);
            vkCmdPushConstants(cb, skyPipelineLayout_,
                    VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                    0, sizeof(SkyPushConstants), &skyPC);
            vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS,
                    skyPipelineLayout_, 0, 1, &skyDescriptorSet_, 0, nullptr);
            vkCmdDraw(cb, 3, 1, 0, 0); // full-screen triangle
        }

        // ----- grass pass -----
        if (grassDescriptorSet_ != VK_NULL_HANDLE
                && grassVertexBuffer_ != VK_NULL_HANDLE
                && grassIndexBuffer_  != VK_NULL_HANDLE
                && vertexCount > 0 && indexCount > 0) {
            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, grassPipeline_);
            vkCmdPushConstants(cb, grassPipelineLayout_,
                    VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                    0, sizeof(GrassPushConstants), &grassPC);
            vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS,
                    grassPipelineLayout_, 0, 1, &grassDescriptorSet_, 0, nullptr);
            VkDeviceSize offsets[] = {0};
            vkCmdBindVertexBuffers(cb, 0, 1, &grassVertexBuffer_, offsets);
            vkCmdBindIndexBuffer(cb, grassIndexBuffer_, 0, VK_INDEX_TYPE_UINT16);
            vkCmdDrawIndexed(cb, indexCount, 1, 0, 0, 0);
        }

        vkCmdEndRenderPass(cb);
        if (vkEndCommandBuffer(cb) != VK_SUCCESS) { LOGE("vkEndCommandBuffer failed"); return false; }
        return true;
    }

    // ===== one-time commands =====

    VkCommandBuffer beginOneTimeCommandsLocked() {
        VkCommandBufferAllocateInfo ai{};
        ai.sType              = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        ai.level              = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        ai.commandPool        = commandPool_;
        ai.commandBufferCount = 1;
        VkCommandBuffer cb = VK_NULL_HANDLE;
        if (vkAllocateCommandBuffers(device_, &ai, &cb) != VK_SUCCESS) return VK_NULL_HANDLE;

        VkCommandBufferBeginInfo bi{};
        bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        if (vkBeginCommandBuffer(cb, &bi) != VK_SUCCESS) {
            vkFreeCommandBuffers(device_, commandPool_, 1, &cb);
            return VK_NULL_HANDLE;
        }
        return cb;
    }

    void endOneTimeCommandsLocked(VkCommandBuffer cb) {
        vkEndCommandBuffer(cb);
        VkSubmitInfo si{};
        si.sType              = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        si.commandBufferCount = 1;
        si.pCommandBuffers    = &cb;
        vkQueueSubmit(graphicsQueue_, 1, &si, VK_NULL_HANDLE);
        vkQueueWaitIdle(graphicsQueue_);
        vkFreeCommandBuffers(device_, commandPool_, 1, &cb);
    }

    void transitionImageLayoutLocked(VkCommandBuffer cb, VkImage image,
            VkImageLayout oldLayout, VkImageLayout newLayout) {
        VkImageMemoryBarrier barrier{};
        barrier.sType                           = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        barrier.oldLayout                       = oldLayout;
        barrier.newLayout                       = newLayout;
        barrier.srcQueueFamilyIndex             = VK_QUEUE_FAMILY_IGNORED;
        barrier.dstQueueFamilyIndex             = VK_QUEUE_FAMILY_IGNORED;
        barrier.image                           = image;
        barrier.subresourceRange.aspectMask     = VK_IMAGE_ASPECT_COLOR_BIT;
        barrier.subresourceRange.baseMipLevel   = 0;
        barrier.subresourceRange.levelCount     = 1;
        barrier.subresourceRange.baseArrayLayer = 0;
        barrier.subresourceRange.layerCount     = 1;

        VkPipelineStageFlags src, dst;
        if (oldLayout == VK_IMAGE_LAYOUT_UNDEFINED && newLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
            barrier.srcAccessMask = 0;
            barrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            src = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
            dst = VK_PIPELINE_STAGE_TRANSFER_BIT;
        } else {
            barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
            src = VK_PIPELINE_STAGE_TRANSFER_BIT;
            dst = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
        }
        vkCmdPipelineBarrier(cb, src, dst, 0, 0, nullptr, 0, nullptr, 1, &barrier);
    }

    // ===== shader loading =====

    VkShaderModule createShaderModuleLocked(std::initializer_list<const char*> candidates) {
        std::vector<uint8_t> bytes;
        for (const char* path : candidates) {
            bytes = readAssetLocked(path);
            if (!bytes.empty()) break;
        }
        if (bytes.empty()) { LOGE("Failed to load shader"); return VK_NULL_HANDLE; }

        VkShaderModuleCreateInfo ci{};
        ci.sType    = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
        ci.codeSize = bytes.size();
        ci.pCode    = reinterpret_cast<const uint32_t*>(bytes.data());
        VkShaderModule sm = VK_NULL_HANDLE;
        if (vkCreateShaderModule(device_, &ci, nullptr, &sm) != VK_SUCCESS) {
            LOGE("vkCreateShaderModule failed"); return VK_NULL_HANDLE;
        }
        return sm;
    }

    std::vector<uint8_t> readAssetLocked(const char* path) const {
        if (!assetManager_) return {};
        AAsset* asset = AAssetManager_open(assetManager_, path, AASSET_MODE_BUFFER);
        if (!asset) return {};
        off_t len = AAsset_getLength(asset);
        std::vector<uint8_t> data(static_cast<size_t>(len));
        AAsset_read(asset, data.data(), len);
        AAsset_close(asset);
        return data;
    }

    // ===== helpers =====

    uint32_t findMemoryTypeLocked(uint32_t typeFilter, VkMemoryPropertyFlags props) const {
        VkPhysicalDeviceMemoryProperties mp{};
        vkGetPhysicalDeviceMemoryProperties(physicalDevice_, &mp);
        for (uint32_t i = 0; i < mp.memoryTypeCount; ++i) {
            if ((typeFilter & (1u << i)) && (mp.memoryTypes[i].propertyFlags & props) == props)
                return i;
        }
        return 0;
    }

    bool isReadyLocked() const {
        return instance_ != VK_NULL_HANDLE && device_ != VK_NULL_HANDLE
                && surface_ != VK_NULL_HANDLE && swapchain_ != VK_NULL_HANDLE
                && renderPass_  != VK_NULL_HANDLE
                && skyPipeline_ != VK_NULL_HANDLE && grassPipeline_ != VK_NULL_HANDLE
                && skyDescriptorSet_  != VK_NULL_HANDLE
                && grassDescriptorSet_ != VK_NULL_HANDLE
                && !commandBuffers_.empty();
    }

    // ===== cleanup =====

    void destroySurfaceLocked() {
        destroySwapchainResourcesLocked();
        if (surface_ != VK_NULL_HANDLE) { vkDestroySurfaceKHR(instance_, surface_, nullptr); surface_ = VK_NULL_HANDLE; }
        if (window_  != nullptr)        { ANativeWindow_release(window_);                      window_  = nullptr; }
    }

    void destroyDeviceLocked() {
        if (device_ == VK_NULL_HANDLE) return;
        vkDeviceWaitIdle(device_);

        // sky textures
        for (auto& st : skyTextures_) destroyGpuTextureLocked(st);
        destroyGpuTextureLocked(aaTexture_);

        // grass geometry buffers
        if (grassVertexMapped_) { vkUnmapMemory(device_, grassVertexMemory_); grassVertexMapped_ = nullptr; }
        if (grassIndexMapped_)  { vkUnmapMemory(device_, grassIndexMemory_);  grassIndexMapped_  = nullptr; }
        if (grassVertexBuffer_ != VK_NULL_HANDLE) { vkDestroyBuffer(device_, grassVertexBuffer_, nullptr); grassVertexBuffer_ = VK_NULL_HANDLE; }
        if (grassVertexMemory_ != VK_NULL_HANDLE) { vkFreeMemory(device_,    grassVertexMemory_, nullptr); grassVertexMemory_ = VK_NULL_HANDLE; }
        if (grassIndexBuffer_  != VK_NULL_HANDLE) { vkDestroyBuffer(device_, grassIndexBuffer_,  nullptr); grassIndexBuffer_  = VK_NULL_HANDLE; }
        if (grassIndexMemory_  != VK_NULL_HANDLE) { vkFreeMemory(device_,    grassIndexMemory_,  nullptr); grassIndexMemory_  = VK_NULL_HANDLE; }

        if (imageAvailableSemaphore_ != VK_NULL_HANDLE) { vkDestroySemaphore(device_, imageAvailableSemaphore_, nullptr); imageAvailableSemaphore_ = VK_NULL_HANDLE; }
        if (renderFinishedSemaphore_ != VK_NULL_HANDLE) { vkDestroySemaphore(device_, renderFinishedSemaphore_, nullptr); renderFinishedSemaphore_ = VK_NULL_HANDLE; }
        if (inFlightFence_           != VK_NULL_HANDLE) { vkDestroyFence(device_,     inFlightFence_,           nullptr); inFlightFence_           = VK_NULL_HANDLE; }
        if (commandPool_             != VK_NULL_HANDLE) { vkDestroyCommandPool(device_, commandPool_,           nullptr); commandPool_             = VK_NULL_HANDLE; }

        if (skyDescriptorPool_   != VK_NULL_HANDLE) { vkDestroyDescriptorPool(device_, skyDescriptorPool_,   nullptr); skyDescriptorPool_   = VK_NULL_HANDLE; skyDescriptorSet_   = VK_NULL_HANDLE; }
        if (skyDescriptorSetLayout_   != VK_NULL_HANDLE) { vkDestroyDescriptorSetLayout(device_, skyDescriptorSetLayout_,   nullptr); skyDescriptorSetLayout_   = VK_NULL_HANDLE; }
        if (grassDescriptorPool_ != VK_NULL_HANDLE) { vkDestroyDescriptorPool(device_, grassDescriptorPool_, nullptr); grassDescriptorPool_ = VK_NULL_HANDLE; grassDescriptorSet_ = VK_NULL_HANDLE; }
        if (grassDescriptorSetLayout_ != VK_NULL_HANDLE) { vkDestroyDescriptorSetLayout(device_, grassDescriptorSetLayout_, nullptr); grassDescriptorSetLayout_ = VK_NULL_HANDLE; }

        vkDestroyDevice(device_, nullptr);
        device_ = VK_NULL_HANDLE;
        physicalDevice_ = VK_NULL_HANDLE;
        graphicsQueue_  = VK_NULL_HANDLE;
        queueFamilyIndex_ = 0;
    }

    // ===== member variables =====

    AAssetManager*  assetManager_ = nullptr;
    std::mutex      mutex_;

    int width_  = 0;
    int height_ = 0;

    ANativeWindow*  window_   = nullptr;
    VkInstance      instance_ = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice_ = VK_NULL_HANDLE;
    VkDevice        device_   = VK_NULL_HANDLE;
    VkQueue         graphicsQueue_   = VK_NULL_HANDLE;
    uint32_t        queueFamilyIndex_ = 0;

    VkSurfaceKHR        surface_   = VK_NULL_HANDLE;
    VkSwapchainKHR      swapchain_ = VK_NULL_HANDLE;
    VkFormat            swapchainFormat_ = VK_FORMAT_UNDEFINED;
    VkExtent2D          swapchainExtent_{};
    std::vector<VkImage>        swapchainImages_;
    std::vector<VkImageView>    swapchainImageViews_;
    std::vector<VkFramebuffer>  framebuffers_;
    std::vector<VkCommandBuffer> commandBuffers_;

    VkRenderPass     renderPass_          = VK_NULL_HANDLE;
    VkPipelineLayout skyPipelineLayout_   = VK_NULL_HANDLE;
    VkPipelineLayout grassPipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline       skyPipeline_         = VK_NULL_HANDLE;
    VkPipeline       grassPipeline_       = VK_NULL_HANDLE;
    VkCommandPool    commandPool_         = VK_NULL_HANDLE;

    // Sky descriptor set (4 textures)
    VkDescriptorSetLayout skyDescriptorSetLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool      skyDescriptorPool_      = VK_NULL_HANDLE;
    VkDescriptorSet       skyDescriptorSet_       = VK_NULL_HANDLE;

    // Grass descriptor set (1 AA texture)
    VkDescriptorSetLayout grassDescriptorSetLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool      grassDescriptorPool_      = VK_NULL_HANDLE;
    VkDescriptorSet       grassDescriptorSet_       = VK_NULL_HANDLE;

    // Sky textures (night, sunrise, sunset, sky)
    GpuTexture skyTextures_[kSkyTextureCount];
    std::vector<uint8_t> pendingSkyPixels_[kSkyTextureCount];
    uint32_t pendingSkyWidth_[kSkyTextureCount]  = {};
    uint32_t pendingSkyHeight_[kSkyTextureCount] = {};

    // AA texture
    GpuTexture aaTexture_;
    std::vector<uint8_t> pendingAAPixels_;
    uint32_t pendingAAWidth_  = 0;
    uint32_t pendingAAHeight_ = 0;

    // Grass geometry (host-visible, persistently mapped)
    VkBuffer        grassVertexBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory  grassVertexMemory_ = VK_NULL_HANDLE;
    void*           grassVertexMapped_ = nullptr;
    VkBuffer        grassIndexBuffer_  = VK_NULL_HANDLE;
    VkDeviceMemory  grassIndexMemory_  = VK_NULL_HANDLE;
    void*           grassIndexMapped_  = nullptr;

    VkSemaphore imageAvailableSemaphore_ = VK_NULL_HANDLE;
    VkSemaphore renderFinishedSemaphore_ = VK_NULL_HANDLE;
    VkFence     inFlightFence_           = VK_NULL_HANDLE;
};

template <typename T>
GrassVkRenderer* asRenderer(T handle) {
    return reinterpret_cast<GrassVkRenderer*>(handle);
}

}  // namespace

// ===== JNI exports =====

extern "C" JNIEXPORT jlong JNICALL
Java_com_reandroid_wallpaper_grass_GrassVKNative_nCreateRenderer(
        JNIEnv* env, jclass, jobject assetManager) {
    auto* r = new GrassVkRenderer(AAssetManager_fromJava(env, assetManager));
    return reinterpret_cast<jlong>(r);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reandroid_wallpaper_grass_GrassVKNative_nDestroyRenderer(
        JNIEnv*, jclass, jlong handle) {
    delete asRenderer(handle);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reandroid_wallpaper_grass_GrassVKNative_nOnSurfaceCreated(
        JNIEnv* env, jclass, jlong handle, jobject surface, jint width, jint height) {
    auto* r = asRenderer(handle);
    return r != nullptr && r->createOrUpdateSurface(env, surface, width, height);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reandroid_wallpaper_grass_GrassVKNative_nOnSurfaceChanged(
        JNIEnv* env, jclass, jlong handle, jobject surface, jint width, jint height) {
    auto* r = asRenderer(handle);
    if (r) r->createOrUpdateSurface(env, surface, width, height);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reandroid_wallpaper_grass_GrassVKNative_nOnSurfaceDestroyed(
        JNIEnv*, jclass, jlong handle) {
    auto* r = asRenderer(handle);
    if (r) r->destroySurface();
}

extern "C" JNIEXPORT void JNICALL
Java_com_reandroid_wallpaper_grass_GrassVKNative_nRenderFrame(
        JNIEnv* env, jclass, jlong handle,
        jfloatArray skyWeights, jfloatArray grassMvp,
        jfloatArray grassVerts, jint grassVertCount,
        jshortArray grassIndices, jint grassIndexCount) {
    auto* r = asRenderer(handle);
    if (r) r->render(env, skyWeights, grassMvp, grassVerts, grassVertCount, grassIndices, grassIndexCount);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reandroid_wallpaper_grass_GrassVKNative_nSetSkyTexture(
        JNIEnv* env, jclass, jlong handle, jint slot, jintArray argbPixels, jint width, jint height) {
    auto* r = asRenderer(handle);
    if (r) r->setSkyTexture(env, slot, argbPixels, width, height);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reandroid_wallpaper_grass_GrassVKNative_nSetAATexture(
        JNIEnv* env, jclass, jlong handle, jintArray argbPixels, jint width, jint height) {
    auto* r = asRenderer(handle);
    if (r) r->setAATexture(env, argbPixels, width, height);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reandroid_wallpaper_grass_GrassVKNative_nIsVulkanSupported(
        JNIEnv*, jclass) {
    return GrassVkRenderer::isVulkanSupported();
}
