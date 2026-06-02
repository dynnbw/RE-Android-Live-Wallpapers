#define LOG_TAG "GrassVK"
#include "vk_common.h"

namespace {

// ---- constants ----
constexpr uint32_t kMaxGrassVertices = 50000;
constexpr uint32_t kMaxGrassIndices  = 150000;
constexpr uint32_t kSkyTextureCount  = 5; // night, sunrise, sunset, sky, solar eclipse
constexpr uint32_t kSpriteTextureCount = 5; // sun, dandelion, firefly1, moon, firefly2
constexpr uint32_t kMaxSpriteVertices = 8192;

// ---- vertex layout ----
struct GrassVertex {
    float x, y;
    float r, g, b, a;
    float s, t;
};
static_assert(sizeof(GrassVertex) == 32, "GrassVertex must be 32 bytes");

struct SpriteVertex {
    float x, y;
    float u, v;
    float a;
};
static_assert(sizeof(SpriteVertex) == 20, "SpriteVertex must be 20 bytes");

// ---- push constants ----
struct SkyPushConstants {
    float weightNight;
    float weightSunrise;
    float weightSunset;
    float weightSky;
    float weightSolarEclipse;
    float nightInvert;
    float pad[2];
};

struct GrassPushConstants {
    float mvp[16];
};

struct MoonPushConstants {
    float mvp[16];
    float p0[4];
    float p1[4];
    float p2[4];
};

// ---- pipeline types ----
enum PipelineType {
    PIPELINE_SKY   = 0,
    PIPELINE_GRASS = 1,
    PIPELINE_SPRITE = 2,
    PIPELINE_MOON = 3,
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
class GrassVkRenderer : public VkRendererBase<GrassVkRenderer> {
public:
    explicit GrassVkRenderer(AAssetManager* am) : assetManager_(am) {}
    ~GrassVkRenderer() { destroy(); }

    // ── CRTP hooks ──

    AAssetManager* getAssetManager() { return assetManager_; }

    bool isSceneReadyLocked() const {
        return skyPipeline_ != VK_NULL_HANDLE && grassPipeline_ != VK_NULL_HANDLE
            && spritePipeline_ != VK_NULL_HANDLE && moonPipeline_ != VK_NULL_HANDLE
            && skyDescriptorSet_ != VK_NULL_HANDLE
            && grassDescriptorSet_ != VK_NULL_HANDLE
            && spriteDescriptorSets_[0] != VK_NULL_HANDLE
            && moonDescriptorSet_ != VK_NULL_HANDLE
            && !swapchainCommandBuffers_.empty();
    }

    bool onDeviceCreated() {
        if (!createGrassGeometryBuffersLocked()) return false;
        if (!createSpriteGeometryBuffersLocked()) return false;
        if (!createSkyDescriptorResourcesLocked()) return false;
        if (!createGrassDescriptorResourcesLocked()) return false;
        if (!createSpriteDescriptorResourcesLocked()) return false;
        if (!createMoonDescriptorResourcesLocked()) return false;
        // Upload any pending textures
        for (uint32_t s = 0; s < kSkyTextureCount; ++s) {
            ensureSkyTextureLocked(s);
        }
        ensureAATextureLocked();
        for (uint32_t s = 0; s < kSpriteTextureCount; ++s) {
            ensureSpriteTextureLocked(s);
        }
        updateMoonDescriptorLocked();
        return true;
    }

    bool onSwapchainCreated() {
        return true;
    }

    void destroyTexturesLocked() {
        for (auto& st : skyTextures_) destroyGpuTextureLocked(st);
        destroyGpuTextureLocked(aaTexture_);
        for (auto& st : spriteTextures_) destroyGpuTextureLocked(st);

        if (skyDescriptorPool_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorPool(device_, skyDescriptorPool_, nullptr);
            skyDescriptorPool_ = VK_NULL_HANDLE;
            skyDescriptorSet_ = VK_NULL_HANDLE;
        }
        if (skyDescriptorSetLayout_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(device_, skyDescriptorSetLayout_, nullptr);
            skyDescriptorSetLayout_ = VK_NULL_HANDLE;
        }
        if (grassDescriptorPool_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorPool(device_, grassDescriptorPool_, nullptr);
            grassDescriptorPool_ = VK_NULL_HANDLE;
            grassDescriptorSet_ = VK_NULL_HANDLE;
        }
        if (grassDescriptorSetLayout_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(device_, grassDescriptorSetLayout_, nullptr);
            grassDescriptorSetLayout_ = VK_NULL_HANDLE;
        }
        if (spriteDescriptorPool_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorPool(device_, spriteDescriptorPool_, nullptr);
            spriteDescriptorPool_ = VK_NULL_HANDLE;
            for (uint32_t i = 0; i < kSpriteTextureCount; ++i) {
                spriteDescriptorSets_[i] = VK_NULL_HANDLE;
            }
        }
        if (spriteDescriptorSetLayout_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(device_, spriteDescriptorSetLayout_, nullptr);
            spriteDescriptorSetLayout_ = VK_NULL_HANDLE;
        }
        if (moonDescriptorPool_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorPool(device_, moonDescriptorPool_, nullptr);
            moonDescriptorPool_ = VK_NULL_HANDLE;
            moonDescriptorSet_ = VK_NULL_HANDLE;
        }
        if (moonDescriptorSetLayout_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(device_, moonDescriptorSetLayout_, nullptr);
            moonDescriptorSetLayout_ = VK_NULL_HANDLE;
        }
    }

    void recordCommandBuffersLocked(int /*w*/, int /*h*/) {
        // Command buffers are recorded per-frame in render()
    }

    bool recoverRenderStateLocked() {
        if (device_ == VK_NULL_HANDLE || window_ == nullptr) return false;
        VkRendererBase::recoverRenderStateLocked(width_, height_);
        return isReadyLocked();
    }

    // ---- public interface ----

    bool createOrUpdateSurface(JNIEnv* env, jobject surface, int width, int height) {
        std::lock_guard<std::mutex> lock(mutex_);
        width_  = width;
        height_ = height;

        if (!createInstanceLocked("GrassVK")) return false;

        ANativeWindow* newWindow = ANativeWindow_fromSurface(env, surface);
        if (!newWindow) { LOGE("ANativeWindow_fromSurface failed"); return false; }

        if (window_ != nullptr) {
            ANativeWindow_release(window_);
        }
        window_ = newWindow;

        if (!createOrUpdateSurfaceLocked(window_, width_, height_)) {
            ANativeWindow_release(window_);
            window_ = nullptr;
            return false;
        }
        return true;
    }

    void destroySurface() {
        std::lock_guard<std::mutex> lock(mutex_);
        destroySurfaceLocked();
        if (window_ != nullptr) {
            ANativeWindow_release(window_);
            window_ = nullptr;
        }
    }

    void render(JNIEnv* env,
            jfloatArray skyWeightsArr,
            jfloatArray grassMvpArr,
            jfloatArray grassVertsArr, jint grassVertCount,
            jshortArray grassIdxArr,   jint grassIdxCount,
            jfloatArray sunVertsArr, jint sunVertCount,
            jfloatArray dandelionVertsArr, jint dandelionVertCount,
            jfloatArray fireflyVertsArr, jint fireflyVertCount,
            jfloatArray fireflyFlareVertsArr, jint fireflyFlareVertCount,
            jfloatArray moonVertsArr, jint moonVertCount,
            jfloatArray moonParamsArr) {
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

        const uint32_t sunCount = static_cast<uint32_t>(
            std::min<int>(sunVertCount, static_cast<int>(kMaxSpriteVertices)));
        const uint32_t dandelionCount = static_cast<uint32_t>(
            std::min<int>(dandelionVertCount, static_cast<int>(kMaxSpriteVertices)));
        const uint32_t fireflyCount = static_cast<uint32_t>(
            std::min<int>(fireflyVertCount, static_cast<int>(kMaxSpriteVertices)));
        const uint32_t fireflyFlareCount = static_cast<uint32_t>(
            std::min<int>(fireflyFlareVertCount, static_cast<int>(kMaxSpriteVertices)));
        const uint32_t moonCount = static_cast<uint32_t>(
            std::min<int>(moonVertCount, static_cast<int>(kMaxSpriteVertices)));

        uploadSpriteGeometryLocked(env, sunVertsArr, sunCount,
            sunVertexMapped_, sunVertexBuffer_);
        uploadSpriteGeometryLocked(env, dandelionVertsArr, dandelionCount,
            dandelionVertexMapped_, dandelionVertexBuffer_);
        uploadSpriteGeometryLocked(env, fireflyVertsArr, fireflyCount,
            fireflyVertexMapped_, fireflyVertexBuffer_);
        uploadSpriteGeometryLocked(env, fireflyFlareVertsArr, fireflyFlareCount,
            fireflyFlareVertexMapped_, fireflyFlareVertexBuffer_);
        uploadSpriteGeometryLocked(env, moonVertsArr, moonCount,
            moonVertexMapped_, moonVertexBuffer_);

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
                skyPC.weightSolarEclipse = sw[5];
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

        MoonPushConstants moonPC{};
        std::memcpy(moonPC.mvp, grassPC.mvp, sizeof(grassPC.mvp));
        {
            jfloat* mp = env->GetFloatArrayElements(moonParamsArr, nullptr);
            if (mp) {
                moonPC.p0[0] = mp[0];
                moonPC.p0[1] = mp[1];
                moonPC.p0[2] = mp[2];
                moonPC.p0[3] = mp[3];
                moonPC.p1[0] = mp[4];
                moonPC.p1[1] = mp[5];
                moonPC.p1[2] = mp[6];
                moonPC.p1[3] = mp[7];
                moonPC.p2[0] = mp[8];
                moonPC.p2[1] = mp[9];
                moonPC.p2[2] = mp[10];
                moonPC.p2[3] = mp[11];
                env->ReleaseFloatArrayElements(moonParamsArr, mp, JNI_ABORT);
            }
        }

        // --- acquire image ---
        vkWaitForFences(device_, 1, &inFlightFence_, VK_TRUE, UINT64_MAX);

        uint32_t imageIndex = 0;
        VkResult acquire = vkAcquireNextImageKHR(device_, swapchain_, UINT64_MAX,
                imageAvailableSemaphore_, VK_NULL_HANDLE, &imageIndex);
        if (acquire == VK_ERROR_SURFACE_LOST_KHR)  { createOrUpdateSurfaceLocked(window_, width_, height_); return; }
        if (acquire == VK_ERROR_OUT_OF_DATE_KHR || acquire == VK_SUBOPTIMAL_KHR) { recreateSwapchainLocked(); return; }
        if (acquire != VK_SUCCESS) { LOGE("vkAcquireNextImageKHR failed: %d", acquire); return; }

        VkCommandBuffer cb = swapchainCommandBuffers_[imageIndex];
        vkResetCommandBuffer(cb, 0);
        if (!recordCommandBufferLocked(cb, imageIndex,
            vertexCount, indexCount,
            sunCount, dandelionCount, fireflyCount, fireflyFlareCount, moonCount,
            skyPC, grassPC, moonPC)) return;

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
        if (present == VK_ERROR_SURFACE_LOST_KHR) { createOrUpdateSurfaceLocked(window_, width_, height_); return; }
        if (present == VK_ERROR_OUT_OF_DATE_KHR || present == VK_SUBOPTIMAL_KHR) { recreateSwapchainLocked(); return; }
        if (present != VK_SUCCESS) { LOGE("vkQueuePresentKHR failed: %d", present); }
    }

    // slot: 0=night 1=sunrise 2=sunset 3=sky 4=solar eclipse
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

    // slot: 0=sun 1=dandelion 2=firefly1 3=moon 4=firefly2
    void setSpriteTexture(JNIEnv* env, jint slot, jintArray argbPixels, jint width, jint height) {
        if (slot < 0 || slot >= static_cast<jint>(kSpriteTextureCount)) return;
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
        pendingSpritePixels_[slot] = std::move(rgba);
        pendingSpriteWidth_[slot]  = static_cast<uint32_t>(width);
        pendingSpriteHeight_[slot] = static_cast<uint32_t>(height);

        if (device_ != VK_NULL_HANDLE && commandPool_ != VK_NULL_HANDLE && graphicsQueue_ != VK_NULL_HANDLE) {
            ensureSpriteTextureLocked(static_cast<uint32_t>(slot));
        }
    }

    void destroy() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (window_ != nullptr) {
            ANativeWindow_release(window_);
            window_ = nullptr;
        }
        destroyDeviceLocked();
    }

// CRTP hooks called by VkRendererBase (public access required)
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

    bool createSpriteGeometryBuffersLocked() {
        if (!createMappedBufferLocked(sizeof(SpriteVertex) * kMaxSpriteVertices,
                VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                sunVertexBuffer_, sunVertexMemory_, sunVertexMapped_)) {
            return false;
        }
        if (!createMappedBufferLocked(sizeof(SpriteVertex) * kMaxSpriteVertices,
                VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                dandelionVertexBuffer_, dandelionVertexMemory_, dandelionVertexMapped_)) {
            return false;
        }
        if (!createMappedBufferLocked(sizeof(SpriteVertex) * kMaxSpriteVertices,
                VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                fireflyVertexBuffer_, fireflyVertexMemory_, fireflyVertexMapped_)) {
            return false;
        }
        if (!createMappedBufferLocked(sizeof(SpriteVertex) * kMaxSpriteVertices,
                VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                fireflyFlareVertexBuffer_, fireflyFlareVertexMemory_, fireflyFlareVertexMapped_)) {
            return false;
        }
        if (!createMappedBufferLocked(sizeof(SpriteVertex) * kMaxSpriteVertices,
                VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                moonVertexBuffer_, moonVertexMemory_, moonVertexMapped_)) {
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
        ai.memoryTypeIndex = vkFindMemoryType(physicalDevice_, req.memoryTypeBits,
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

    void uploadSpriteGeometryLocked(JNIEnv* env,
            jfloatArray vertsArr, uint32_t vertexCount,
            void* mapped, VkBuffer /*buffer*/) {
        if (!mapped || !vertsArr || vertexCount == 0) {
            return;
        }
        jfloat* verts = env->GetFloatArrayElements(vertsArr, nullptr);
        if (!verts) {
            return;
        }
        std::memcpy(mapped, verts, vertexCount * sizeof(SpriteVertex));
        env->ReleaseFloatArrayElements(vertsArr, verts, JNI_ABORT);
    }

    // ===== pipelines =====

    bool createPipelinesLocked() {
        VkShaderModule skyVert  = createShaderModuleLocked({"shaders/grassvk_sky.vert.spv",  "shaders/grassvk_sky.vert.spv"});
        VkShaderModule skyFrag  = createShaderModuleLocked({"shaders/grassvk_sky.frag.spv",  "shaders/grassvk_sky.frag.spv"});
        VkShaderModule gVert    = createShaderModuleLocked({"shaders/grassvk_grass.vert.spv", "shaders/grassvk_grass.vert.spv"});
        VkShaderModule gFrag    = createShaderModuleLocked({"shaders/grassvk_grass.frag.spv", "shaders/grassvk_grass.frag.spv"});
        VkShaderModule sVert    = createShaderModuleLocked({"shaders/grassvk_sprite.vert.spv", "shaders/grassvk_sprite.vert.spv"});
        VkShaderModule sFrag    = createShaderModuleLocked({"shaders/grassvk_sprite.frag.spv", "shaders/grassvk_sprite.frag.spv"});
        VkShaderModule mVert    = createShaderModuleLocked({"shaders/grassvk_moon.vert.spv", "shaders/grassvk_moon.vert.spv"});
        VkShaderModule mFrag    = createShaderModuleLocked({"shaders/grassvk_moon.frag.spv", "shaders/grassvk_moon.frag.spv"});

        auto cleanup = [&](){
            if (skyVert  != VK_NULL_HANDLE) vkDestroyShaderModule(device_, skyVert,  nullptr);
            if (skyFrag  != VK_NULL_HANDLE) vkDestroyShaderModule(device_, skyFrag,  nullptr);
            if (gVert    != VK_NULL_HANDLE) vkDestroyShaderModule(device_, gVert,    nullptr);
            if (gFrag    != VK_NULL_HANDLE) vkDestroyShaderModule(device_, gFrag,    nullptr);
            if (sVert    != VK_NULL_HANDLE) vkDestroyShaderModule(device_, sVert,    nullptr);
            if (sFrag    != VK_NULL_HANDLE) vkDestroyShaderModule(device_, sFrag,    nullptr);
            if (mVert    != VK_NULL_HANDLE) vkDestroyShaderModule(device_, mVert,    nullptr);
            if (mFrag    != VK_NULL_HANDLE) vkDestroyShaderModule(device_, mFrag,    nullptr);
        };

        if (!skyVert || !skyFrag || !gVert || !gFrag || !sVert || !sFrag || !mVert || !mFrag) { cleanup(); return false; }

        skyPipelineLayout_   = createPipelineLayoutLocked(PIPELINE_SKY);
        grassPipelineLayout_ = createPipelineLayoutLocked(PIPELINE_GRASS);
        spritePipelineLayout_ = createPipelineLayoutLocked(PIPELINE_SPRITE);
        moonPipelineLayout_ = createPipelineLayoutLocked(PIPELINE_MOON);
        if (!skyPipelineLayout_ || !grassPipelineLayout_ || !spritePipelineLayout_ || !moonPipelineLayout_) { cleanup(); return false; }

        skyPipeline_   = createGraphicsPipelineLocked(skyVert,  skyFrag,  skyPipelineLayout_,   PIPELINE_SKY);
        grassPipeline_ = createGraphicsPipelineLocked(gVert,    gFrag,    grassPipelineLayout_,  PIPELINE_GRASS);
        spritePipeline_ = createGraphicsPipelineLocked(sVert,    sFrag,    spritePipelineLayout_, PIPELINE_SPRITE);
        moonPipeline_ = createGraphicsPipelineLocked(mVert,    mFrag,    moonPipelineLayout_, PIPELINE_MOON);

        cleanup();
        return skyPipeline_ != VK_NULL_HANDLE && grassPipeline_ != VK_NULL_HANDLE
            && spritePipeline_ != VK_NULL_HANDLE
            && moonPipeline_ != VK_NULL_HANDLE;
    }

    void destroyPipelinesLocked() {
        if (skyPipeline_   != VK_NULL_HANDLE) { vkDestroyPipeline(device_, skyPipeline_,   nullptr); skyPipeline_   = VK_NULL_HANDLE; }
        if (grassPipeline_ != VK_NULL_HANDLE) { vkDestroyPipeline(device_, grassPipeline_, nullptr); grassPipeline_ = VK_NULL_HANDLE; }
        if (spritePipeline_ != VK_NULL_HANDLE) { vkDestroyPipeline(device_, spritePipeline_, nullptr); spritePipeline_ = VK_NULL_HANDLE; }
        if (moonPipeline_ != VK_NULL_HANDLE) { vkDestroyPipeline(device_, moonPipeline_, nullptr); moonPipeline_ = VK_NULL_HANDLE; }
        if (skyPipelineLayout_   != VK_NULL_HANDLE) { vkDestroyPipelineLayout(device_, skyPipelineLayout_,   nullptr); skyPipelineLayout_   = VK_NULL_HANDLE; }
        if (grassPipelineLayout_ != VK_NULL_HANDLE) { vkDestroyPipelineLayout(device_, grassPipelineLayout_, nullptr); grassPipelineLayout_ = VK_NULL_HANDLE; }
        if (spritePipelineLayout_ != VK_NULL_HANDLE) { vkDestroyPipelineLayout(device_, spritePipelineLayout_, nullptr); spritePipelineLayout_ = VK_NULL_HANDLE; }
        if (moonPipelineLayout_ != VK_NULL_HANDLE) { vkDestroyPipelineLayout(device_, moonPipelineLayout_, nullptr); moonPipelineLayout_ = VK_NULL_HANDLE; }
    }

    VkPipelineLayout createPipelineLayoutLocked(PipelineType type) {
        VkDescriptorSetLayout dsl = grassDescriptorSetLayout_;
        if (type == PIPELINE_SKY) {
            dsl = skyDescriptorSetLayout_;
        } else if (type == PIPELINE_SPRITE) {
            dsl = spriteDescriptorSetLayout_;
        } else if (type == PIPELINE_MOON) {
            dsl = moonDescriptorSetLayout_;
        }

        VkPushConstantRange pcRange{};
        pcRange.stageFlags = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
        pcRange.offset     = 0;
        if (type == PIPELINE_SKY) {
            pcRange.size = static_cast<uint32_t>(sizeof(SkyPushConstants));
        } else if (type == PIPELINE_MOON) {
            pcRange.size = static_cast<uint32_t>(sizeof(MoonPushConstants));
        } else {
            pcRange.size = static_cast<uint32_t>(sizeof(GrassPushConstants));
        }

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

            attrs[0].binding  = 0; attrs[0].location = 0;
            attrs[0].format   = VK_FORMAT_R32G32_SFLOAT;
            attrs[0].offset   = offsetof(GrassVertex, x);
            attrs[1].binding  = 0; attrs[1].location = 1;
            attrs[1].format   = VK_FORMAT_R32G32B32A32_SFLOAT;
            attrs[1].offset   = offsetof(GrassVertex, r);
            attrs[2].binding  = 0; attrs[2].location = 2;
            attrs[2].format   = VK_FORMAT_R32G32_SFLOAT;
            attrs[2].offset   = offsetof(GrassVertex, s);

            vis.vertexBindingDescriptionCount   = 1;
            vis.pVertexBindingDescriptions      = &binding;
            vis.vertexAttributeDescriptionCount = 3;
            vis.pVertexAttributeDescriptions    = attrs;
        } else if (type == PIPELINE_SPRITE || type == PIPELINE_MOON) {
            binding.binding = 0;
            binding.stride = sizeof(SpriteVertex);
            binding.inputRate = VK_VERTEX_INPUT_RATE_VERTEX;

            attrs[0].binding = 0;
            attrs[0].location = 0;
            attrs[0].format = VK_FORMAT_R32G32_SFLOAT;
            attrs[0].offset = offsetof(SpriteVertex, x);

            attrs[1].binding = 0;
            attrs[1].location = 1;
            attrs[1].format = VK_FORMAT_R32G32_SFLOAT;
            attrs[1].offset = offsetof(SpriteVertex, u);

            attrs[2].binding = 0;
            attrs[2].location = 2;
            attrs[2].format = VK_FORMAT_R32_SFLOAT;
            attrs[2].offset = offsetof(SpriteVertex, a);

            vis.vertexBindingDescriptionCount = 1;
            vis.pVertexBindingDescriptions = &binding;
            vis.vertexAttributeDescriptionCount = 3;
            vis.pVertexAttributeDescriptions = attrs;
        }

        VkPipelineInputAssemblyStateCreateInfo ias{};
        ias.sType    = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
        ias.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;

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
        if (type == PIPELINE_GRASS || type == PIPELINE_SPRITE || type == PIPELINE_MOON) {
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

    bool createSpriteDescriptorResourcesLocked() {
        if (spriteDescriptorSetLayout_ != VK_NULL_HANDLE
                && spriteDescriptorPool_ != VK_NULL_HANDLE
                && spriteDescriptorSets_[0] != VK_NULL_HANDLE) return true;

        VkDescriptorSetLayoutBinding binding{};
        binding.binding = 0;
        binding.descriptorCount = 1;
        binding.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        binding.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;

        VkDescriptorSetLayoutCreateInfo lci{};
        lci.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        lci.bindingCount = 1;
        lci.pBindings = &binding;
        if (vkCreateDescriptorSetLayout(device_, &lci, nullptr, &spriteDescriptorSetLayout_) != VK_SUCCESS) {
            LOGE("vkCreateDescriptorSetLayout(sprite) failed");
            return false;
        }

        VkDescriptorPoolSize ps{};
        ps.type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        ps.descriptorCount = kSpriteTextureCount;

        VkDescriptorPoolCreateInfo pci{};
        pci.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
        pci.poolSizeCount = 1;
        pci.pPoolSizes = &ps;
        pci.maxSets = kSpriteTextureCount;
        if (vkCreateDescriptorPool(device_, &pci, nullptr, &spriteDescriptorPool_) != VK_SUCCESS) {
            LOGE("vkCreateDescriptorPool(sprite) failed");
            return false;
        }

        std::vector<VkDescriptorSetLayout> layouts(kSpriteTextureCount, spriteDescriptorSetLayout_);
        VkDescriptorSetAllocateInfo ai{};
        ai.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        ai.descriptorPool = spriteDescriptorPool_;
        ai.descriptorSetCount = kSpriteTextureCount;
        ai.pSetLayouts = layouts.data();
        if (vkAllocateDescriptorSets(device_, &ai, spriteDescriptorSets_) != VK_SUCCESS) {
            LOGE("vkAllocateDescriptorSets(sprite) failed");
            return false;
        }
        return true;
    }

    bool createMoonDescriptorResourcesLocked() {
        if (moonDescriptorSetLayout_ != VK_NULL_HANDLE
                && moonDescriptorPool_ != VK_NULL_HANDLE
                && moonDescriptorSet_ != VK_NULL_HANDLE) return true;

        VkDescriptorSetLayoutBinding binding{};
        binding.binding = 0;
        binding.descriptorCount = 1;
        binding.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        binding.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;

        VkDescriptorSetLayoutCreateInfo lci{};
        lci.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        lci.bindingCount = 1;
        lci.pBindings = &binding;
        if (vkCreateDescriptorSetLayout(device_, &lci, nullptr, &moonDescriptorSetLayout_) != VK_SUCCESS) {
            LOGE("vkCreateDescriptorSetLayout(moon) failed");
            return false;
        }

        VkDescriptorPoolSize ps{};
        ps.type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        ps.descriptorCount = 1;

        VkDescriptorPoolCreateInfo pci{};
        pci.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
        pci.poolSizeCount = 1;
        pci.pPoolSizes = &ps;
        pci.maxSets = 1;
        if (vkCreateDescriptorPool(device_, &pci, nullptr, &moonDescriptorPool_) != VK_SUCCESS) {
            LOGE("vkCreateDescriptorPool(moon) failed");
            return false;
        }

        VkDescriptorSetAllocateInfo ai{};
        ai.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        ai.descriptorPool = moonDescriptorPool_;
        ai.descriptorSetCount = 1;
        ai.pSetLayouts = &moonDescriptorSetLayout_;
        if (vkAllocateDescriptorSets(device_, &ai, &moonDescriptorSet_) != VK_SUCCESS) {
            LOGE("vkAllocateDescriptorSets(moon) failed");
            return false;
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

    void updateSpriteDescriptorLocked(uint32_t slot) {
        if (slot >= kSpriteTextureCount) return;
        if (spriteDescriptorSets_[slot] == VK_NULL_HANDLE) return;
        if (!spriteTextures_[slot].isValid()) return;

        VkDescriptorImageInfo ii{};
        ii.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        ii.imageView = spriteTextures_[slot].view;
        ii.sampler = spriteTextures_[slot].sampler;

        VkWriteDescriptorSet wd{};
        wd.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        wd.dstSet = spriteDescriptorSets_[slot];
        wd.dstBinding = 0;
        wd.descriptorCount = 1;
        wd.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        wd.pImageInfo = &ii;
        vkUpdateDescriptorSets(device_, 1, &wd, 0, nullptr);

        if (slot == 3) {
            updateMoonDescriptorLocked();
        }
    }

    void updateMoonDescriptorLocked() {
        if (moonDescriptorSet_ == VK_NULL_HANDLE) return;
        if (!spriteTextures_[3].isValid()) return;

        VkDescriptorImageInfo ii{};
        ii.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        ii.imageView = spriteTextures_[3].view;
        ii.sampler = spriteTextures_[3].sampler;

        VkWriteDescriptorSet wd{};
        wd.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        wd.dstSet = moonDescriptorSet_;
        wd.dstBinding = 0;
        wd.descriptorCount = 1;
        wd.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        wd.pImageInfo = &ii;
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
        const uint8_t fallback[4] = {255,255,255,255};
        if (uploadTextureLocked(fallback, 1, 1,
                VK_FILTER_NEAREST, VK_SAMPLER_ADDRESS_MODE_REPEAT,
                aaTexture_)) {
            updateGrassDescriptorLocked();
        }
    }

    void ensureSpriteTextureLocked(uint32_t slot) {
        if (slot >= kSpriteTextureCount) return;
        if (spriteTextures_[slot].isValid()) return;

        if (!pendingSpritePixels_[slot].empty()
                && pendingSpriteWidth_[slot] > 0
                && pendingSpriteHeight_[slot] > 0) {
            if (uploadTextureLocked(pendingSpritePixels_[slot].data(),
                    pendingSpriteWidth_[slot], pendingSpriteHeight_[slot],
                    VK_FILTER_LINEAR, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
                    spriteTextures_[slot])) {
                updateSpriteDescriptorLocked(slot);
            }
            return;
        }

        const uint8_t fallback[4] = {255, 255, 255, 255};
        if (uploadTextureLocked(fallback, 1, 1,
                VK_FILTER_LINEAR, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
                spriteTextures_[slot])) {
            updateSpriteDescriptorLocked(slot);
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
            ai.memoryTypeIndex = vkFindMemoryType(physicalDevice_, req.memoryTypeBits,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            if (vkAllocateMemory(device_, &ai, nullptr, &stageMem) != VK_SUCCESS) {
                LOGE("vkAllocateMemory (staging) failed");
                vkDestroyBuffer(device_, stageBuf, nullptr);
                return false;
            }
            vkBindBufferMemory(device_, stageBuf, stageMem, 0);
            void* mapped = nullptr;
            if (vkMapMemory(device_, stageMem, 0, imageSize, 0, &mapped) != VK_SUCCESS || mapped == nullptr) {
                LOGE("vkMapMemory (staging) failed");
                vkFreeMemory(device_, stageMem, nullptr);
                vkDestroyBuffer(device_, stageBuf, nullptr);
                return false;
            }
            if (rgbaPixels) std::memcpy(mapped, rgbaPixels, static_cast<size_t>(imageSize));
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
        imgAI.memoryTypeIndex = vkFindMemoryType(physicalDevice_, imgReq.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
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
        if (!endOneTimeCommandsLocked(cb)) {
            vkFreeMemory(device_, stageMem, nullptr);
            vkDestroyBuffer(device_, stageBuf, nullptr);
            return false;
        }

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
            uint32_t sunCount, uint32_t dandelionCount, uint32_t fireflyCount,
            uint32_t fireflyFlareCount, uint32_t moonCount,
            const SkyPushConstants& skyPC, const GrassPushConstants& grassPC,
            const MoonPushConstants& moonPC) {
        VkCommandBufferBeginInfo bi{};
        bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        if (vkBeginCommandBuffer(cb, &bi) != VK_SUCCESS) { LOGE("vkBeginCommandBuffer failed"); return false; }

        VkClearValue clear{};
        clear.color = {{0.0f, 0.0f, 0.0f, 1.0f}};

        VkRenderPassBeginInfo rpbi{};
        rpbi.sType             = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
        rpbi.renderPass        = renderPass_;
        rpbi.framebuffer       = swapchainFramebuffers_[imageIndex];
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

        // ----- sprite pass -----
        if (spritePipeline_ != VK_NULL_HANDLE && spritePipelineLayout_ != VK_NULL_HANDLE) {
            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, spritePipeline_);
            vkCmdPushConstants(cb, spritePipelineLayout_,
                    VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                    0, sizeof(GrassPushConstants), &grassPC);

            auto drawSpriteGroup = [&](uint32_t slot, VkBuffer vb, uint32_t count) {
                if (count == 0 || vb == VK_NULL_HANDLE || slot >= kSpriteTextureCount) return;
                if (spriteDescriptorSets_[slot] == VK_NULL_HANDLE) return;
                vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS,
                        spritePipelineLayout_, 0, 1, &spriteDescriptorSets_[slot], 0, nullptr);
                VkDeviceSize offsets[] = {0};
                vkCmdBindVertexBuffers(cb, 0, 1, &vb, offsets);
                vkCmdDraw(cb, count, 1, 0, 0);
            };

            drawSpriteGroup(0, sunVertexBuffer_, sunCount);
            drawSpriteGroup(1, dandelionVertexBuffer_, dandelionCount);
            drawSpriteGroup(2, fireflyVertexBuffer_, fireflyCount);
            drawSpriteGroup(4, fireflyFlareVertexBuffer_, fireflyFlareCount);
        }

        if (moonPipeline_ != VK_NULL_HANDLE && moonPipelineLayout_ != VK_NULL_HANDLE
                && moonDescriptorSet_ != VK_NULL_HANDLE && moonVertexBuffer_ != VK_NULL_HANDLE
                && moonCount > 0) {
            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, moonPipeline_);
            vkCmdPushConstants(cb, moonPipelineLayout_,
                    VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                    0, sizeof(MoonPushConstants), &moonPC);
            vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS,
                    moonPipelineLayout_, 0, 1, &moonDescriptorSet_, 0, nullptr);
            VkDeviceSize offsets[] = {0};
            vkCmdBindVertexBuffers(cb, 0, 1, &moonVertexBuffer_, offsets);
            vkCmdDraw(cb, moonCount, 1, 0, 0);
        }

        vkCmdEndRenderPass(cb);
        if (vkEndCommandBuffer(cb) != VK_SUCCESS) { LOGE("vkEndCommandBuffer failed"); return false; }
        return true;
    }

    // ===== members =====

    AAssetManager*  assetManager_ = nullptr;
    std::mutex      mutex_;
    int width_  = 0;
    int height_ = 0;
    ANativeWindow*  window_   = nullptr;

    VkPipelineLayout skyPipelineLayout_   = VK_NULL_HANDLE;
    VkPipelineLayout grassPipelineLayout_ = VK_NULL_HANDLE;
    VkPipelineLayout spritePipelineLayout_ = VK_NULL_HANDLE;
    VkPipelineLayout moonPipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline       skyPipeline_         = VK_NULL_HANDLE;
    VkPipeline       grassPipeline_       = VK_NULL_HANDLE;
    VkPipeline       spritePipeline_      = VK_NULL_HANDLE;
    VkPipeline       moonPipeline_        = VK_NULL_HANDLE;

    // Sky descriptor set (5 textures)
    VkDescriptorSetLayout skyDescriptorSetLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool      skyDescriptorPool_      = VK_NULL_HANDLE;
    VkDescriptorSet       skyDescriptorSet_       = VK_NULL_HANDLE;

    // Grass descriptor set (1 AA texture)
    VkDescriptorSetLayout grassDescriptorSetLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool      grassDescriptorPool_      = VK_NULL_HANDLE;
    VkDescriptorSet       grassDescriptorSet_       = VK_NULL_HANDLE;

    // Sprite descriptor sets (sun, dandelion, firefly, moon)
    VkDescriptorSetLayout spriteDescriptorSetLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool      spriteDescriptorPool_      = VK_NULL_HANDLE;
    VkDescriptorSet       spriteDescriptorSets_[kSpriteTextureCount] = {};

    VkDescriptorSetLayout moonDescriptorSetLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool      moonDescriptorPool_      = VK_NULL_HANDLE;
    VkDescriptorSet       moonDescriptorSet_       = VK_NULL_HANDLE;

    // Sky textures (night, sunrise, sunset, sky, solar eclipse)
    GpuTexture skyTextures_[kSkyTextureCount];
    std::vector<uint8_t> pendingSkyPixels_[kSkyTextureCount];
    uint32_t pendingSkyWidth_[kSkyTextureCount]  = {};
    uint32_t pendingSkyHeight_[kSkyTextureCount] = {};

    // AA texture
    GpuTexture aaTexture_;
    std::vector<uint8_t> pendingAAPixels_;
    uint32_t pendingAAWidth_  = 0;
    uint32_t pendingAAHeight_ = 0;

    // Sprite textures
    GpuTexture spriteTextures_[kSpriteTextureCount];
    std::vector<uint8_t> pendingSpritePixels_[kSpriteTextureCount];
    uint32_t pendingSpriteWidth_[kSpriteTextureCount] = {};
    uint32_t pendingSpriteHeight_[kSpriteTextureCount] = {};

    // Grass geometry (host-visible, persistently mapped)
    VkBuffer        grassVertexBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory  grassVertexMemory_ = VK_NULL_HANDLE;
    void*           grassVertexMapped_ = nullptr;
    VkBuffer        grassIndexBuffer_  = VK_NULL_HANDLE;
    VkDeviceMemory  grassIndexMemory_  = VK_NULL_HANDLE;
    void*           grassIndexMapped_  = nullptr;

    // Sprite geometry
    VkBuffer        sunVertexBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory  sunVertexMemory_ = VK_NULL_HANDLE;
    void*           sunVertexMapped_ = nullptr;
    VkBuffer        dandelionVertexBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory  dandelionVertexMemory_ = VK_NULL_HANDLE;
    void*           dandelionVertexMapped_ = nullptr;
    VkBuffer        fireflyVertexBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory  fireflyVertexMemory_ = VK_NULL_HANDLE;
    void*           fireflyVertexMapped_ = nullptr;
    VkBuffer        fireflyFlareVertexBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory  fireflyFlareVertexMemory_ = VK_NULL_HANDLE;
    void*           fireflyFlareVertexMapped_ = nullptr;
    VkBuffer        moonVertexBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory  moonVertexMemory_ = VK_NULL_HANDLE;
    void*           moonVertexMapped_ = nullptr;
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
        jshortArray grassIndices, jint grassIndexCount,
        jfloatArray sunVerts, jint sunVertCount,
        jfloatArray dandelionVerts, jint dandelionVertCount,
        jfloatArray fireflyVerts, jint fireflyVertCount,
        jfloatArray fireflyFlareVerts, jint fireflyFlareVertCount,
        jfloatArray moonVerts, jint moonVertCount,
        jfloatArray moonParams) {
    auto* r = asRenderer(handle);
    if (r) r->render(env, skyWeights, grassMvp,
            grassVerts, grassVertCount,
            grassIndices, grassIndexCount,
            sunVerts, sunVertCount,
            dandelionVerts, dandelionVertCount,
            fireflyVerts, fireflyVertCount,
            fireflyFlareVerts, fireflyFlareVertCount,
            moonVerts, moonVertCount,
            moonParams);
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

extern "C" JNIEXPORT void JNICALL
Java_com_reandroid_wallpaper_grass_GrassVKNative_nSetSpriteTexture(
        JNIEnv* env, jclass, jlong handle, jint slot, jintArray argbPixels, jint width, jint height) {
    auto* r = asRenderer(handle);
    if (r) r->setSpriteTexture(env, slot, argbPixels, width, height);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reandroid_wallpaper_grass_GrassVKNative_nIsVulkanSupported(
        JNIEnv*, jclass) {
    return vkIsVulkanSupported();
}
