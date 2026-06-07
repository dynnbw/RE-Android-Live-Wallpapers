#define LOG_TAG "Galaxy4VK"
#include "vk_common.h"
#include <cmath>

namespace {

struct PushConstants {
    float mvp[16];
    float alpha;
    float particleSize;
    float particleOpacity;
};

struct TextureResource {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView imageView = VK_NULL_HANDLE;
    VkSampler sampler = VK_NULL_HANDLE;
    uint32_t width = 0, height = 0;
    std::vector<uint8_t> pendingPixels;
    uint32_t pendingWidth = 0, pendingHeight = 0;
};

struct CloudVertex { float angle, dist, z, pointSize; };
struct StarVertex  { float angle, dist, z; };
struct StaticVertex { float x, y, pointSize; };

// Matrix helpers (shared with Fall/Galaxy VK)
static void multiplyMat4(const float a[16], const float b[16], float out[16]) {
    for (int row = 0; row < 4; row++) {
        for (int col = 0; col < 4; col++) {
            out[col * 4 + row] = a[0 * 4 + row] * b[col * 4 + 0]
                               + a[1 * 4 + row] * b[col * 4 + 1]
                               + a[2 * 4 + row] * b[col * 4 + 2]
                               + a[3 * 4 + row] * b[col * 4 + 3];
        }
    }
}

static void getVulkanClipCorrection(float out[16]) {
    for (int i = 0; i < 16; i++) out[i] = 0.0f;
    out[0] = 1.0f; out[5] = -1.0f; out[10] = 0.5f;
    out[14] = 0.5f; out[15] = 1.0f;
}

class Galaxy4VkRenderer : public VkRendererBase<Galaxy4VkRenderer> {
public:
    explicit Galaxy4VkRenderer(AAssetManager* am) : assetManager_(am) {}
    ~Galaxy4VkRenderer() { destroy(); }

    bool isSceneReadyLocked() const {
        return bgPipeline_ != VK_NULL_HANDLE
            && cloudPipeline_ != VK_NULL_HANDLE && starPipeline_ != VK_NULL_HANDLE
            && staticPipeline_ != VK_NULL_HANDLE
            && bgDescSet_ != VK_NULL_HANDLE && cloudDescSet_ != VK_NULL_HANDLE
            && starDescSet_ != VK_NULL_HANDLE && staticDescSet_ != VK_NULL_HANDLE
            && bgTexture_.imageView != VK_NULL_HANDLE
            && cloudTexture_.imageView != VK_NULL_HANDLE
            && staticTex1_.imageView != VK_NULL_HANDLE
            && staticTex2_.imageView != VK_NULL_HANDLE;
    }

    // ── Texture setters (called from Java) ──

    void setBackgroundTexture(JNIEnv* env, jintArray argb, jint w, jint h) {
        std::lock_guard<std::mutex> lock(mutex_);
        storePendingTexture(env, argb, w, h, bgTexture_.pendingPixels,
                bgTexture_.pendingWidth, bgTexture_.pendingHeight);
        if (device_ != VK_NULL_HANDLE) uploadTextureLocked(bgTexture_, bgDescSet_);
    }

    void setCloudTexture(JNIEnv* env, jintArray argb, jint w, jint h) {
        std::lock_guard<std::mutex> lock(mutex_);
        storePendingTexture(env, argb, w, h, cloudTexture_.pendingPixels,
                cloudTexture_.pendingWidth, cloudTexture_.pendingHeight);
        if (device_ != VK_NULL_HANDLE) uploadTextureLocked(cloudTexture_, cloudDescSet_);
    }

    void setStaticStarTextures(JNIEnv* env,
            jintArray argb1, jint w1, jint h1,
            jintArray argb2, jint w2, jint h2) {
        std::lock_guard<std::mutex> lock(mutex_);
        storePendingTexture(env, argb1, w1, h1, staticTex1_.pendingPixels,
                staticTex1_.pendingWidth, staticTex1_.pendingHeight);
        storePendingTexture(env, argb2, w2, h2, staticTex2_.pendingPixels,
                staticTex2_.pendingWidth, staticTex2_.pendingHeight);
        if (device_ != VK_NULL_HANDLE) {
            uploadTextureLocked(staticTex1_, staticDescSet_);
            uploadTextureLocked(staticTex2_, staticDescSet_);
        }
    }

    // ── Render ──

    void render(JNIEnv* env, jfloatArray projection,
            jfloatArray spaceClouds, jfloatArray bgStars, jfloatArray staticStars,
            jint spaceCloudCount, jint bgStarCount, jfloat timeSeconds,
            jfloat particleSize, jfloat particleOpacity) {
        std::lock_guard<std::mutex> lock(mutex_);

        uploadParticlesLocked(env, spaceClouds, bgStars, staticStars,
                spaceCloudCount, bgStarCount);

        if (!isReadyLocked()) {
            LOGI("not ready, recovering... instance=%p device=%p surface=%p swapchain=%p",
                 instance_, device_, surface_, swapchain_);
            recoverRenderStateLocked(width_, height_);
        }
        if (!isReadyLocked()) {
            LOGI("still not ready after recover, sceneReady=%d", isSceneReadyLocked());
            return;
        }

        float mvp[16];
        env->GetFloatArrayRegion(projection, 0, 16, mvp);

        vkWaitForFences(device_, 1, &inFlightFence_, VK_TRUE, UINT64_MAX);

        uint32_t imageIndex = 0;
        VkResult acquire = vkAcquireNextImageKHR(device_, swapchain_, UINT64_MAX,
                imageAvailableSemaphore_, VK_NULL_HANDLE, &imageIndex);
        if (acquire == VK_ERROR_SURFACE_LOST_KHR) {
            createOrUpdateSurfaceLocked(window_, width_, height_); return;
        }
        if (acquire == VK_ERROR_OUT_OF_DATE_KHR || acquire == VK_SUBOPTIMAL_KHR) {
            recreateSwapchainLocked(); return;
        }
        if (acquire != VK_SUCCESS) { LOGE("acquire failed: %d", acquire); return; }

        VkCommandBuffer cmd = swapchainCommandBuffers_[imageIndex];
        vkResetCommandBuffer(cmd, 0);
        if (!recordFrameLocked(cmd, imageIndex, mvp, timeSeconds,
                spaceCloudCount, bgStarCount, particleSize, particleOpacity)) return;

        VkPipelineStageFlags waitStages[] = {VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT};
        VkSubmitInfo submitInfo{};
        submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        submitInfo.waitSemaphoreCount = 1;
        submitInfo.pWaitSemaphores = &imageAvailableSemaphore_;
        submitInfo.pWaitDstStageMask = waitStages;
        submitInfo.commandBufferCount = 1;
        submitInfo.pCommandBuffers = &cmd;
        submitInfo.signalSemaphoreCount = 1;
        submitInfo.pSignalSemaphores = &renderFinishedSemaphore_;

        vkResetFences(device_, 1, &inFlightFence_);
        VkResult submit = vkQueueSubmit(graphicsQueue_, 1, &submitInfo, inFlightFence_);
        if (submit != VK_SUCCESS) { LOGE("submit failed: %d", submit); return; }

        VkPresentInfoKHR presentInfo{};
        presentInfo.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
        presentInfo.waitSemaphoreCount = 1;
        presentInfo.pWaitSemaphores = &renderFinishedSemaphore_;
        presentInfo.swapchainCount = 1;
        presentInfo.pSwapchains = &swapchain_;
        presentInfo.pImageIndices = &imageIndex;
        vkQueuePresentKHR(graphicsQueue_, &presentInfo);
    }


    // ── CRTP hooks (must be public for base class access) ──
    void recordCommandBuffersLocked(int, int) {}
    bool createDescriptorResourcesLocked() { return createAllDescriptorsLocked(); }
    void destroyTexturesLocked() {
        destroyTextureLocked(bgTexture_); destroyTextureLocked(cloudTexture_);
        destroyTextureLocked(staticTex1_); destroyTextureLocked(staticTex2_);
        destroyParticleBuffersLocked();
        if (bgQuadBuf_ != VK_NULL_HANDLE) { vkDestroyBuffer(device_, bgQuadBuf_, nullptr); bgQuadBuf_ = VK_NULL_HANDLE; }
        if (bgQuadMem_ != VK_NULL_HANDLE) { vkFreeMemory(device_, bgQuadMem_, nullptr); bgQuadMem_ = VK_NULL_HANDLE; }
        destroyDescriptorsLocked();
    }

    // ── Additional CRTP hooks ──
    bool onSwapchainCreated() { return true; }
    bool onDeviceCreated() {
        return createDescriptorResourcesLocked();
    }
    AAssetManager* getAssetManager() { return assetManager_; }


    bool createOrUpdateSurface(JNIEnv* env, jobject surface, int width, int height) {
        std::lock_guard<std::mutex> lock(mutex_);
        width_ = width; height_ = height;
        if (!createInstanceLocked("Galaxy4VK")) return false;
        ANativeWindow* w = ANativeWindow_fromSurface(env, surface);
        if (!w) return false;
        if (window_) ANativeWindow_release(window_);
        window_ = w;
        if (!createOrUpdateSurfaceLocked(window_, width_, height_)) {
            ANativeWindow_release(window_); window_ = nullptr; return false;
        }
        // Upload pending textures now that device is ready
        uploadPendingTextures();
        LOGI("surface created: %dx%d, instance=%p device=%p swapchain=%p",
             width, height, instance_, device_, swapchain_);
        return true;
    }

    void uploadPendingTextures() {
        if (!bgTexture_.pendingPixels.empty())
            uploadTextureLocked(bgTexture_, bgDescSet_);
        if (!cloudTexture_.pendingPixels.empty())
            uploadTextureLocked(cloudTexture_, cloudDescSet_);
        if (!staticTex1_.pendingPixels.empty())
            uploadTextureLocked(staticTex1_, staticDescSet_);
        if (!staticTex2_.pendingPixels.empty())
            uploadTextureLocked(staticTex2_, staticDescSet_);
    }

    void destroySurface() {
        std::lock_guard<std::mutex> lock(mutex_);
        destroySurfaceLocked();
        if (window_) { ANativeWindow_release(window_); window_ = nullptr; }
    }

    void destroy() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (window_ != nullptr) { ANativeWindow_release(window_); window_ = nullptr; }
        destroyDeviceLocked();
    }

    AAssetManager* assetManager_;

    // ── Pipelines ──
    VkPipeline bgPipeline_ = VK_NULL_HANDLE, cloudPipeline_ = VK_NULL_HANDLE;
    VkPipeline starPipeline_ = VK_NULL_HANDLE, staticPipeline_ = VK_NULL_HANDLE;
    VkPipelineLayout bgLayout_ = VK_NULL_HANDLE, cloudLayout_ = VK_NULL_HANDLE;
    VkPipelineLayout starLayout_ = VK_NULL_HANDLE, staticLayout_ = VK_NULL_HANDLE;

    // ── Descriptors ──
    VkDescriptorSetLayout bgDescLayout_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout cloudDescLayout_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout starDescLayout_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout staticDescLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool bgDescPool_ = VK_NULL_HANDLE, cloudDescPool_ = VK_NULL_HANDLE;
    VkDescriptorPool starDescPool_ = VK_NULL_HANDLE, staticDescPool_ = VK_NULL_HANDLE;
    VkDescriptorSet bgDescSet_ = VK_NULL_HANDLE, cloudDescSet_ = VK_NULL_HANDLE;
    VkDescriptorSet starDescSet_ = VK_NULL_HANDLE, staticDescSet_ = VK_NULL_HANDLE;

    // ── Textures ──
    TextureResource bgTexture_, cloudTexture_, staticTex1_, staticTex2_;

    // ── Particle buffers ──
    VkBuffer cloudBuf_ = VK_NULL_HANDLE, starBuf_ = VK_NULL_HANDLE;
    VkBuffer staticBuf_ = VK_NULL_HANDLE;
    VkDeviceMemory cloudMem_ = VK_NULL_HANDLE, starMem_ = VK_NULL_HANDLE;
    VkDeviceMemory staticMem_ = VK_NULL_HANDLE;
    void* cloudMapped_ = nullptr, *starMapped_ = nullptr, *staticMapped_ = nullptr;
    size_t cloudCap_ = 0, starCap_ = 0, staticCap_ = 0;
    int cloudCount_ = 0, starCount_ = 0;

    // ── Required by VkRendererBase CRTP ──
    std::mutex mutex_;
    int width_ = 0, height_ = 0;
    ANativeWindow* window_ = nullptr;

    void storePendingTexture(JNIEnv* env, jintArray argb, jint width, jint height,
            std::vector<uint8_t>& pending, uint32_t& pw, uint32_t& ph) {
        jsize count = env->GetArrayLength(argb);
        if (count <= 0 || (int64_t)width * height > count) return;
        jint* pixels = env->GetIntArrayElements(argb, nullptr);
        if (!pixels) return;
        pw = (uint32_t)width; ph = (uint32_t)height;
        pending.resize((size_t)width * height * 4);
        uint8_t* dst = pending.data();
        for (size_t i = 0; i < (size_t)width * height; i++) {
            uint32_t c = (uint32_t)pixels[i];
            dst[i*4]   = (uint8_t)((c >> 16) & 0xFF);
            dst[i*4+1] = (uint8_t)((c >> 8) & 0xFF);
            dst[i*4+2] = (uint8_t)(c & 0xFF);
            dst[i*4+3] = (uint8_t)((c >> 24) & 0xFF);
        }
        env->ReleaseIntArrayElements(argb, pixels, JNI_ABORT);
    }
    void uploadTextureLocked(TextureResource& tex, VkDescriptorSet descSet) {
        if (tex.pendingPixels.empty() || tex.pendingWidth == 0 || tex.pendingHeight == 0) return;
        destroyTextureLocked(tex);
        uint32_t w = tex.pendingWidth, h = tex.pendingHeight;
        VkDeviceSize imageSize = (VkDeviceSize)w * h * 4;

        VkBuffer stagingBuf = VK_NULL_HANDLE;
        VkDeviceMemory stagingMem = VK_NULL_HANDLE;
        VkBufferCreateInfo bufInfo{};
        bufInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        bufInfo.size = imageSize;
        bufInfo.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
        if (vkCreateBuffer(device_, &bufInfo, nullptr, &stagingBuf) != VK_SUCCESS) return;
        VkMemoryRequirements mr;
        vkGetBufferMemoryRequirements(device_, stagingBuf, &mr);
        VkMemoryAllocateInfo alloc{};
        alloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        alloc.allocationSize = mr.size;
        alloc.memoryTypeIndex = vkFindMemoryType(physicalDevice_, mr.memoryTypeBits,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        if (vkAllocateMemory(device_, &alloc, nullptr, &stagingMem) != VK_SUCCESS) {
            vkDestroyBuffer(device_, stagingBuf, nullptr); return;
        }
        vkBindBufferMemory(device_, stagingBuf, stagingMem, 0);
        void* mapped;
        vkMapMemory(device_, stagingMem, 0, imageSize, 0, &mapped);
        memcpy(mapped, tex.pendingPixels.data(), (size_t)imageSize);
        vkUnmapMemory(device_, stagingMem);

        VkImageCreateInfo imgInfo{};
        imgInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        imgInfo.imageType = VK_IMAGE_TYPE_2D;
        imgInfo.extent = {w, h, 1};
        imgInfo.mipLevels = 1; imgInfo.arrayLayers = 1;
        imgInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
        imgInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
        imgInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        imgInfo.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        imgInfo.samples = VK_SAMPLE_COUNT_1_BIT;
        if (vkCreateImage(device_, &imgInfo, nullptr, &tex.image) != VK_SUCCESS) {
            vkFreeMemory(device_, stagingMem, nullptr); vkDestroyBuffer(device_, stagingBuf, nullptr); return;
        }
        VkMemoryRequirements imr;
        vkGetImageMemoryRequirements(device_, tex.image, &imr);
        VkMemoryAllocateInfo ialloc{};
        ialloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        ialloc.allocationSize = imr.size;
        ialloc.memoryTypeIndex = vkFindMemoryType(physicalDevice_, imr.memoryTypeBits,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if (vkAllocateMemory(device_, &ialloc, nullptr, &tex.memory) != VK_SUCCESS) {
            vkDestroyImage(device_, tex.image, nullptr); tex.image = VK_NULL_HANDLE;
            vkFreeMemory(device_, stagingMem, nullptr); vkDestroyBuffer(device_, stagingBuf, nullptr); return;
        }
        vkBindImageMemory(device_, tex.image, tex.memory, 0);

        VkCommandBuffer cb = beginOneTimeCommandsLocked();
        VkImageMemoryBarrier barrier{};
        barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        barrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        barrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.image = tex.image;
        barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        barrier.subresourceRange.levelCount = 1; barrier.subresourceRange.layerCount = 1;
        barrier.srcAccessMask = 0;
        barrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &barrier);

        VkBufferImageCopy region{};
        region.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        region.imageSubresource.layerCount = 1;
        region.imageExtent = {w, h, 1};
        vkCmdCopyBufferToImage(cb, stagingBuf, tex.image,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);

        barrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        barrier.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1, &barrier);
        endOneTimeCommandsLocked(cb);

        vkDestroyBuffer(device_, stagingBuf, nullptr);
        vkFreeMemory(device_, stagingMem, nullptr);

        VkImageViewCreateInfo viewInfo{};
        viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        viewInfo.image = tex.image;
        viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
        viewInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
        viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        viewInfo.subresourceRange.levelCount = 1; viewInfo.subresourceRange.layerCount = 1;
        if (vkCreateImageView(device_, &viewInfo, nullptr, &tex.imageView) != VK_SUCCESS) return;

        VkSamplerCreateInfo sampInfo{};
        sampInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
        sampInfo.magFilter = VK_FILTER_LINEAR; sampInfo.minFilter = VK_FILTER_LINEAR;
        sampInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        sampInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        sampInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        if (vkCreateSampler(device_, &sampInfo, nullptr, &tex.sampler) != VK_SUCCESS) return;

        tex.width = w; tex.height = h;
        tex.pendingPixels.clear();

        VkDescriptorImageInfo descImg{};
        descImg.sampler = tex.sampler;
        descImg.imageView = tex.imageView;
        descImg.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        VkWriteDescriptorSet write{};
        write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        write.dstSet = descSet; write.dstBinding = 0;
        write.descriptorCount = 1;
        write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        write.pImageInfo = &descImg;
        vkUpdateDescriptorSets(device_, 1, &write, 0, nullptr);
    }

    void destroyTextureLocked(TextureResource& tex) {
        if (tex.sampler) { vkDestroySampler(device_, tex.sampler, nullptr); tex.sampler = VK_NULL_HANDLE; }
        if (tex.imageView) { vkDestroyImageView(device_, tex.imageView, nullptr); tex.imageView = VK_NULL_HANDLE; }
        if (tex.image) { vkDestroyImage(device_, tex.image, nullptr); tex.image = VK_NULL_HANDLE; }
        if (tex.memory) { vkFreeMemory(device_, tex.memory, nullptr); tex.memory = VK_NULL_HANDLE; }
    }

    // ── Background quad ──
    VkBuffer bgQuadBuf_ = VK_NULL_HANDLE;
    VkDeviceMemory bgQuadMem_ = VK_NULL_HANDLE;


    bool createPipelinesLocked() {
        auto loadShader = [this](const char* name) {
            return createShaderModuleLocked({name, name});
        };

        VkShaderModule bgVert = loadShader("shaders/galaxy4vk_bg.vert.spv");
        VkShaderModule bgFrag = loadShader("shaders/galaxy4vk_bg.frag.spv");
        VkShaderModule cloudVert = loadShader("shaders/galaxy4vk_cloud.vert.spv");
        VkShaderModule cloudFrag = loadShader("shaders/galaxy4vk_cloud.frag.spv");
        VkShaderModule starVert = loadShader("shaders/galaxy4vk_star.vert.spv");
        VkShaderModule starFrag = loadShader("shaders/galaxy4vk_star.frag.spv");
        VkShaderModule staticVert = loadShader("shaders/galaxy4vk_static.vert.spv");
        VkShaderModule staticFrag = loadShader("shaders/galaxy4vk_static.frag.spv");

        if (!bgVert || !bgFrag || !cloudVert || !cloudFrag ||
            !starVert || !starFrag || !staticVert || !staticFrag) {
            LOGE("Shader module creation failed");
            return false;
        }

        bgLayout_ = createLayoutLocked(true, bgDescLayout_);
        cloudLayout_ = createLayoutLocked(true, cloudDescLayout_);
        starLayout_ = createLayoutLocked(true, starDescLayout_);
        staticLayout_ = createLayoutLocked(true, staticDescLayout_);

        bgPipeline_ = createPipelineLocked(bgVert, bgFrag, bgLayout_, false, false, 0);
        cloudPipeline_ = createPipelineLocked(cloudVert, cloudFrag, cloudLayout_, true, true, 1);
        starPipeline_ = createPipelineLocked(starVert, starFrag, starLayout_, true, true, 2);
        staticPipeline_ = createPipelineLocked(staticVert, staticFrag, staticLayout_, true, true, 3);

        vkDestroyShaderModule(device_, bgVert, nullptr);
        vkDestroyShaderModule(device_, bgFrag, nullptr);
        vkDestroyShaderModule(device_, cloudVert, nullptr);
        vkDestroyShaderModule(device_, cloudFrag, nullptr);
        vkDestroyShaderModule(device_, starVert, nullptr);
        vkDestroyShaderModule(device_, starFrag, nullptr);
        vkDestroyShaderModule(device_, staticVert, nullptr);
        vkDestroyShaderModule(device_, staticFrag, nullptr);

        return bgPipeline_ && cloudPipeline_ && starPipeline_ && staticPipeline_;
    }

    void destroyPipelinesLocked() {
        if (bgPipeline_) { vkDestroyPipeline(device_, bgPipeline_, nullptr); bgPipeline_ = VK_NULL_HANDLE; }
        if (cloudPipeline_) { vkDestroyPipeline(device_, cloudPipeline_, nullptr); cloudPipeline_ = VK_NULL_HANDLE; }
        if (starPipeline_) { vkDestroyPipeline(device_, starPipeline_, nullptr); starPipeline_ = VK_NULL_HANDLE; }
        if (staticPipeline_) { vkDestroyPipeline(device_, staticPipeline_, nullptr); staticPipeline_ = VK_NULL_HANDLE; }
        if (bgLayout_) { vkDestroyPipelineLayout(device_, bgLayout_, nullptr); bgLayout_ = VK_NULL_HANDLE; }
        if (cloudLayout_) { vkDestroyPipelineLayout(device_, cloudLayout_, nullptr); cloudLayout_ = VK_NULL_HANDLE; }
        if (starLayout_) { vkDestroyPipelineLayout(device_, starLayout_, nullptr); starLayout_ = VK_NULL_HANDLE; }
        if (staticLayout_) { vkDestroyPipelineLayout(device_, staticLayout_, nullptr); staticLayout_ = VK_NULL_HANDLE; }
    }

    // ── Descriptor setup ──

    bool createAllDescriptorsLocked() {
        return createOneDescLocked(bgDescLayout_, bgDescPool_, bgDescSet_) &&
               createOneDescLocked(cloudDescLayout_, cloudDescPool_, cloudDescSet_) &&
               createOneDescLocked(starDescLayout_, starDescPool_, starDescSet_) &&
               createStaticDescLocked();
    }

    bool createOneDescLocked(VkDescriptorSetLayout& layout, VkDescriptorPool& pool, VkDescriptorSet& set) {
        if (layout && pool && set) return true;
        VkDescriptorSetLayoutBinding binding{};
        binding.binding = 0;
        binding.descriptorCount = 1;
        binding.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        binding.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;

        VkDescriptorSetLayoutCreateInfo linfo{};
        linfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        linfo.bindingCount = 1;
        linfo.pBindings = &binding;
        if (vkCreateDescriptorSetLayout(device_, &linfo, nullptr, &layout) != VK_SUCCESS) return false;

        VkDescriptorPoolSize psize{};
        psize.type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        psize.descriptorCount = 1;
        VkDescriptorPoolCreateInfo pinfo{};
        pinfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
        pinfo.poolSizeCount = 1;
        pinfo.pPoolSizes = &psize;
        pinfo.maxSets = 1;
        if (vkCreateDescriptorPool(device_, &pinfo, nullptr, &pool) != VK_SUCCESS) return false;

        VkDescriptorSetAllocateInfo ainfo{};
        ainfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        ainfo.descriptorPool = pool;
        ainfo.descriptorSetCount = 1;
        ainfo.pSetLayouts = &layout;
        return vkAllocateDescriptorSets(device_, &ainfo, &set) == VK_SUCCESS;
    }

    bool createStaticDescLocked() {
        if (staticDescLayout_ && staticDescPool_ && staticDescSet_) return true;
        VkDescriptorSetLayoutBinding bindings[2]{};
        bindings[0].binding = 0;
        bindings[0].descriptorCount = 1;
        bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        bindings[0].stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
        bindings[1].binding = 1;
        bindings[1].descriptorCount = 1;
        bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        bindings[1].stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;

        VkDescriptorSetLayoutCreateInfo linfo{};
        linfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        linfo.bindingCount = 2;
        linfo.pBindings = bindings;
        if (vkCreateDescriptorSetLayout(device_, &linfo, nullptr, &staticDescLayout_) != VK_SUCCESS) return false;

        VkDescriptorPoolSize psizes[2]{};
        psizes[0].type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; psizes[0].descriptorCount = 1;
        psizes[1].type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; psizes[1].descriptorCount = 1;
        VkDescriptorPoolCreateInfo pinfo{};
        pinfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
        pinfo.poolSizeCount = 2;
        pinfo.pPoolSizes = psizes;
        pinfo.maxSets = 1;
        if (vkCreateDescriptorPool(device_, &pinfo, nullptr, &staticDescPool_) != VK_SUCCESS) return false;

        VkDescriptorSetAllocateInfo ainfo{};
        ainfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        ainfo.descriptorPool = staticDescPool_;
        ainfo.descriptorSetCount = 1;
        ainfo.pSetLayouts = &staticDescLayout_;
        return vkAllocateDescriptorSets(device_, &ainfo, &staticDescSet_) == VK_SUCCESS;
    }

    void destroyDescriptorsLocked() {
        auto cleanup = [this](VkDescriptorSetLayout& l, VkDescriptorPool& p, VkDescriptorSet& s) {
            if (p) { vkDestroyDescriptorPool(device_, p, nullptr); p = VK_NULL_HANDLE; s = VK_NULL_HANDLE; }
            if (l) { vkDestroyDescriptorSetLayout(device_, l, nullptr); l = VK_NULL_HANDLE; }
        };
        cleanup(bgDescLayout_, bgDescPool_, bgDescSet_);
        cleanup(cloudDescLayout_, cloudDescPool_, cloudDescSet_);
        cleanup(starDescLayout_, starDescPool_, starDescSet_);
        cleanup(staticDescLayout_, staticDescPool_, staticDescSet_);
    }

    // ── Pipeline helpers ──

    VkPipelineLayout createLayoutLocked(bool push, VkDescriptorSetLayout dsl) {
        VkPipelineLayoutCreateInfo info{};
        info.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        if (dsl) { info.setLayoutCount = 1; info.pSetLayouts = &dsl; }
        VkPushConstantRange pcr{};
        if (push) {
            pcr.stageFlags = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
            pcr.size = sizeof(PushConstants);
            info.pushConstantRangeCount = 1;
            info.pPushConstantRanges = &pcr;
        }
        VkPipelineLayout layout;
        if (vkCreatePipelineLayout(device_, &info, nullptr, &layout) != VK_SUCCESS) return VK_NULL_HANDLE;
        return layout;
    }

    VkPipeline createPipelineLocked(VkShaderModule vs, VkShaderModule fs, VkPipelineLayout layout, bool blend, bool pointList, int vertType) {
        VkPipelineShaderStageCreateInfo vstage{}, fstage{};
        vstage.sType = fstage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        vstage.stage = VK_SHADER_STAGE_VERTEX_BIT; vstage.module = vs; vstage.pName = "main";
        fstage.stage = VK_SHADER_STAGE_FRAGMENT_BIT; fstage.module = fs; fstage.pName = "main";
        VkPipelineShaderStageCreateInfo stages[] = {vstage, fstage};

        // Vertex input bindings per type:
        // 0=bg quad (x,y,z + u,v), 1=cloud (angle,dist,z + size), 2=star (angle,dist,z), 3=static (x,y + size)
        VkVertexInputBindingDescription bindings[1]{};
        VkVertexInputAttributeDescription attrs[2]{};
        uint32_t attrCount = 0;
        VkPipelineVertexInputStateCreateInfo vi{};
        vi.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;

        switch (vertType) {
        case 0: // bg quad: pos(3) + texcoord(2), stride 20
            bindings[0] = {0, 20, VK_VERTEX_INPUT_RATE_VERTEX};
            attrs[0] = {0, 0, VK_FORMAT_R32G32B32_SFLOAT, 0};
            attrs[1] = {1, 0, VK_FORMAT_R32G32_SFLOAT, 12};
            attrCount = 2;
            break;
        case 1: // cloud: angle/dist/z(3) + pointSize(1), stride 16
            bindings[0] = {0, 16, VK_VERTEX_INPUT_RATE_VERTEX};
            attrs[0] = {0, 0, VK_FORMAT_R32G32B32_SFLOAT, 0};
            attrs[1] = {1, 0, VK_FORMAT_R32_SFLOAT, 12};
            attrCount = 2;
            break;
        case 2: // star: angle/dist/z(3), stride 12
            bindings[0] = {0, 12, VK_VERTEX_INPUT_RATE_VERTEX};
            attrs[0] = {0, 0, VK_FORMAT_R32G32B32_SFLOAT, 0};
            attrCount = 1;
            break;
        case 3: // static: x/y(2) + pointSize(1), stride 12
            bindings[0] = {0, 12, VK_VERTEX_INPUT_RATE_VERTEX};
            attrs[0] = {0, 0, VK_FORMAT_R32G32_SFLOAT, 0};
            attrs[1] = {1, 0, VK_FORMAT_R32_SFLOAT, 8};
            attrCount = 2;
            break;
        }
        vi.vertexBindingDescriptionCount = 1;
        vi.pVertexBindingDescriptions = bindings;
        vi.vertexAttributeDescriptionCount = attrCount;
        vi.pVertexAttributeDescriptions = attrs;

        VkPipelineInputAssemblyStateCreateInfo ia{};
        ia.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
        ia.topology = pointList ? VK_PRIMITIVE_TOPOLOGY_POINT_LIST : VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;

        VkPipelineRasterizationStateCreateInfo rs{};
        rs.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
        rs.lineWidth = 1.0f;

        VkPipelineViewportStateCreateInfo vp{};
        vp.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
        vp.viewportCount = 1; vp.scissorCount = 1;

        VkPipelineMultisampleStateCreateInfo ms{};
        ms.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
        ms.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

        VkPipelineColorBlendAttachmentState ba{};
        ba.colorWriteMask = 0xf;
        if (blend) {
            ba.blendEnable = VK_TRUE;
            ba.srcColorBlendFactor = VK_BLEND_FACTOR_SRC_ALPHA;
            ba.dstColorBlendFactor = VK_BLEND_FACTOR_ONE;  // additive: matches GLES glBlendFunc(SRC_ALPHA, ONE)
            ba.colorBlendOp = VK_BLEND_OP_ADD;
            ba.srcAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
            ba.dstAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
            ba.alphaBlendOp = VK_BLEND_OP_ADD;
        }

        VkPipelineColorBlendStateCreateInfo cb{};
        cb.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
        cb.attachmentCount = 1; cb.pAttachments = &ba;

        VkDynamicState dyn[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
        VkPipelineDynamicStateCreateInfo ds{};
        ds.sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
        ds.dynamicStateCount = 2; ds.pDynamicStates = dyn;

        VkGraphicsPipelineCreateInfo pi{};
        pi.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
        pi.stageCount = 2; pi.pStages = stages;
        pi.pVertexInputState = &vi; pi.pInputAssemblyState = &ia;
        pi.pViewportState = &vp; pi.pRasterizationState = &rs;
        pi.pMultisampleState = &ms; pi.pColorBlendState = &cb;
        pi.pDynamicState = &ds;
        pi.layout = layout; pi.renderPass = renderPass_;

        VkPipeline pipeline;
        if (vkCreateGraphicsPipelines(device_, VK_NULL_HANDLE, 1, &pi, nullptr, &pipeline) != VK_SUCCESS)
            return VK_NULL_HANDLE;
        return pipeline;
    }

    // ── BG quad ──

    void ensureBgQuadLocked() {
        float maxDim = (float)std::max(width_, height_);
        float scaleX = maxDim / (float)std::max(1, width_);
        float scaleY = maxDim / (float)std::max(1, height_);
        float verts[] = {
            -scaleX, -scaleY, 0, 0, 1,
             scaleX, -scaleY, 0, 1, 1,
            -scaleX,  scaleY, 0, 0, 0,
             scaleX,  scaleY, 0, 1, 0,
        };
        VkDeviceSize size = sizeof(verts);
        if (bgQuadBuf_ != VK_NULL_HANDLE && bgQuadMem_ != VK_NULL_HANDLE) {
            void* data;
            vkMapMemory(device_, bgQuadMem_, 0, size, 0, &data);
            memcpy(data, verts, size);
            vkUnmapMemory(device_, bgQuadMem_);
            return;
        }

        VkBufferCreateInfo bi{};
        bi.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        bi.size = size;
        bi.usage = VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
        if (vkCreateBuffer(device_, &bi, nullptr, &bgQuadBuf_) != VK_SUCCESS) return;

        VkMemoryRequirements mr;
        vkGetBufferMemoryRequirements(device_, bgQuadBuf_, &mr);
        VkMemoryAllocateInfo ai{};
        ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        ai.allocationSize = mr.size;
        ai.memoryTypeIndex = vkFindMemoryType(physicalDevice_, mr.memoryTypeBits,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        if (vkAllocateMemory(device_, &ai, nullptr, &bgQuadMem_) != VK_SUCCESS) return;
        vkBindBufferMemory(device_, bgQuadBuf_, bgQuadMem_, 0);
        void* data;
        vkMapMemory(device_, bgQuadMem_, 0, size, 0, &data);
        memcpy(data, verts, size);
        vkUnmapMemory(device_, bgQuadMem_);
    }

    // ── Particle upload ──

    void uploadParticlesLocked(JNIEnv* env, jfloatArray clouds, jfloatArray stars,
            jfloatArray statics, jint cloudCount, jint starCount) {
        if (cloudCount > 0) {
            size_t sz = cloudCount * sizeof(CloudVertex);
            if (!ensureBufferLocked(cloudBuf_, cloudMem_, cloudMapped_, cloudCap_, sz)) return;
            auto* dst = static_cast<CloudVertex*>(cloudMapped_);
            jsize len = env->GetArrayLength(clouds);
            std::vector<float> tmp(len);
            env->GetFloatArrayRegion(clouds, 0, len, tmp.data());
            for (int i = 0; i < cloudCount; i++) {
                dst[i].angle = tmp[i*3]; dst[i].dist = tmp[i*3+1];
                dst[i].z = tmp[i*3+2]; dst[i].pointSize = 1.0f;
            }
        }
        if (starCount > 0) {
            size_t sz = starCount * sizeof(StarVertex);
            if (!ensureBufferLocked(starBuf_, starMem_, starMapped_, starCap_, sz)) return;
            auto* dst = static_cast<StarVertex*>(starMapped_);
            jsize len = env->GetArrayLength(stars);
            std::vector<float> tmp(len);
            env->GetFloatArrayRegion(stars, 0, len, tmp.data());
            for (int i = 0; i < starCount; i++) {
                dst[i].angle = tmp[i*3]; dst[i].dist = tmp[i*3+1]; dst[i].z = tmp[i*3+2];
            }
        }
        {
            size_t sz = 50 * sizeof(StaticVertex);
            if (!ensureBufferLocked(staticBuf_, staticMem_, staticMapped_, staticCap_, sz)) return;
            auto* dst = static_cast<StaticVertex*>(staticMapped_);
            jsize len = env->GetArrayLength(statics);
            std::vector<float> tmp(len);
            env->GetFloatArrayRegion(statics, 0, len, tmp.data());
            for (int i = 0; i < 50; i++) {
                dst[i].x = tmp[i*3]; dst[i].y = tmp[i*3+1]; dst[i].pointSize = tmp[i*3+2];
            }
        }
        cloudCount_ = cloudCount; starCount_ = starCount;
    }

    bool ensureBufferLocked(VkBuffer& buf, VkDeviceMemory& mem, void*& mapped,
            size_t& cap, size_t needed) {
        if (buf != VK_NULL_HANDLE && cap >= needed) return true;
        if (buf != VK_NULL_HANDLE) {
            vkUnmapMemory(device_, mem);
            vkDestroyBuffer(device_, buf, nullptr);
            vkFreeMemory(device_, mem, nullptr);
        }
        VkBufferCreateInfo bi{};
        bi.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        bi.size = needed;
        bi.usage = VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
        if (vkCreateBuffer(device_, &bi, nullptr, &buf) != VK_SUCCESS) return false;
        VkMemoryRequirements mr;
        vkGetBufferMemoryRequirements(device_, buf, &mr);
        VkMemoryAllocateInfo ai{};
        ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        ai.allocationSize = mr.size;
        ai.memoryTypeIndex = vkFindMemoryType(physicalDevice_, mr.memoryTypeBits,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        if (vkAllocateMemory(device_, &ai, nullptr, &mem) != VK_SUCCESS) return false;
        vkBindBufferMemory(device_, buf, mem, 0);
        if (vkMapMemory(device_, mem, 0, needed, 0, &mapped) != VK_SUCCESS) { mapped = nullptr; return false; }
        cap = needed;
        return true;
    }

    void destroyParticleBuffersLocked() {
        auto cleanup = [this](VkBuffer& b, VkDeviceMemory& m, void*& p, size_t& c) {
            if (p) { vkUnmapMemory(device_, m); p = nullptr; }
            if (b) { vkDestroyBuffer(device_, b, nullptr); b = VK_NULL_HANDLE; }
            if (m) { vkFreeMemory(device_, m, nullptr); m = VK_NULL_HANDLE; }
            c = 0;
        };
        cleanup(cloudBuf_, cloudMem_, cloudMapped_, cloudCap_);
        cleanup(starBuf_, starMem_, starMapped_, starCap_);
        cleanup(staticBuf_, staticMem_, staticMapped_, staticCap_);
    }

    // ── Command recording ──

    bool recordFrameLocked(VkCommandBuffer cmd, uint32_t imageIndex,
            const float mvp[16], float time, int cloudCount, int starCount,
            float particleSize, float particleOpacity) {
        VkCommandBufferBeginInfo bi{};
        bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        if (vkBeginCommandBuffer(cmd, &bi) != VK_SUCCESS) return false;

        VkClearValue cv{}; cv.color = {{0,0,0,1}};
        VkRenderPassBeginInfo rp{};
        rp.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
        rp.renderPass = renderPass_;
        rp.framebuffer = swapchainFramebuffers_[imageIndex];
        rp.renderArea.extent = swapchainExtent_;
        rp.clearValueCount = 1; rp.pClearValues = &cv;
        vkCmdBeginRenderPass(cmd, &rp, VK_SUBPASS_CONTENTS_INLINE);

        VkViewport vp{}; vp.width = (float)swapchainExtent_.width;
        vp.height = (float)swapchainExtent_.height; vp.maxDepth = 1;
        vkCmdSetViewport(cmd, 0, 1, &vp);
        VkRect2D sc{}; sc.extent = swapchainExtent_;
        vkCmdSetScissor(cmd, 0, 1, &sc);

        float clipCorrection[16]; getVulkanClipCorrection(clipCorrection);
        float mvpCorrected[16]; multiplyMat4(clipCorrection, mvp, mvpCorrected);

        PushConstants pc{};
        memcpy(pc.mvp, mvpCorrected, sizeof(pc.mvp));
        pc.alpha = time;
        pc.particleSize = particleSize;
        pc.particleOpacity = particleOpacity;

        VkDeviceSize off = 0;

        // Pass 1: Background quad (with clip correction for Vulkan NDC)
        ensureBgQuadLocked();
        if (bgQuadBuf_ != VK_NULL_HANDLE) {
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, bgPipeline_);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, bgLayout_, 0,1,&bgDescSet_,0,nullptr);
            float ident[16] = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
            float bgCorrected[16]; multiplyMat4(clipCorrection, ident, bgCorrected);
            PushConstants bgPc{};
            memcpy(bgPc.mvp, bgCorrected, sizeof(bgPc.mvp));
            vkCmdPushConstants(cmd, bgLayout_, VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT,
                    0, sizeof(PushConstants), &bgPc);
            vkCmdBindVertexBuffers(cmd, 0, 1, &bgQuadBuf_, &off);
            vkCmdDraw(cmd, 4, 1, 0, 0);
        }

        // Pass 2: Cloud particles
        if (cloudCount > 0 && cloudBuf_ != VK_NULL_HANDLE) {
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, cloudPipeline_);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, cloudLayout_, 0,1,&cloudDescSet_,0,nullptr);
            vkCmdPushConstants(cmd, cloudLayout_, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                    0, sizeof(PushConstants), &pc);
            vkCmdBindVertexBuffers(cmd, 0, 1, &cloudBuf_, &off);
            vkCmdDraw(cmd, cloudCount, 1, 0, 0);
        }

        // Pass 3: Rotating stars
        if (starCount > 0 && starBuf_ != VK_NULL_HANDLE) {
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, starPipeline_);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, starLayout_, 0,1,&starDescSet_,0,nullptr);
            vkCmdPushConstants(cmd, starLayout_, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                    0, sizeof(PushConstants), &pc);
            vkCmdBindVertexBuffers(cmd, 0, 1, &starBuf_, &off);
            vkCmdDraw(cmd, starCount, 1, 0, 0);
        }

        // Pass 4: Static stars
        if (staticBuf_ != VK_NULL_HANDLE) {
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, staticPipeline_);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, staticLayout_, 0,1,&staticDescSet_,0,nullptr);
            PushConstants stPc{};
            memcpy(stPc.mvp, mvpCorrected, sizeof(stPc.mvp));
            stPc.alpha = time;
            vkCmdPushConstants(cmd, staticLayout_, VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT,
                    0, sizeof(PushConstants), &stPc);
            vkCmdBindVertexBuffers(cmd, 0, 1, &staticBuf_, &off);
            vkCmdDraw(cmd, 50, 1, 0, 0);
        }

        vkCmdEndRenderPass(cmd);
        return vkEndCommandBuffer(cmd) == VK_SUCCESS;
    }
};

template<typename T> Galaxy4VkRenderer* asRenderer(T h) { return reinterpret_cast<Galaxy4VkRenderer*>(h); }

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_reandroid_wallpaper_galaxy4_Galaxy4VKNative_nCreateRenderer(
        JNIEnv* env, jclass, jobject assetManager) {
    LOGI("nCreateRenderer called");
    auto* r = new Galaxy4VkRenderer(AAssetManager_fromJava(env, assetManager));
    LOGI("nCreateRenderer returning %p", r);
    return reinterpret_cast<jlong>(r);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reandroid_wallpaper_galaxy4_Galaxy4VKNative_nDestroyRenderer(JNIEnv*, jclass, jlong handle) {
    if (auto* r = asRenderer(handle)) { r->destroy(); delete r; }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reandroid_wallpaper_galaxy4_Galaxy4VKNative_nOnSurfaceCreated(
        JNIEnv* env, jclass, jlong handle, jobject surface, jint w, jint h) {
    auto* r = asRenderer(handle);
    return r != nullptr && r->createOrUpdateSurface(env, surface, w, h);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reandroid_wallpaper_galaxy4_Galaxy4VKNative_nOnSurfaceChanged(
        JNIEnv* env, jclass, jlong handle, jobject surface, jint w, jint h) {
    if (auto* r = asRenderer(handle)) r->createOrUpdateSurface(env, surface, w, h);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reandroid_wallpaper_galaxy4_Galaxy4VKNative_nOnSurfaceDestroyed(
        JNIEnv*, jclass, jlong handle) {
    if (auto* r = asRenderer(handle)) r->destroySurface();
}

extern "C" JNIEXPORT void JNICALL
Java_com_reandroid_wallpaper_galaxy4_Galaxy4VKNative_nRenderFrame(
        JNIEnv* env, jclass, jlong handle, jfloatArray proj,
        jfloatArray clouds, jfloatArray stars, jfloatArray statics,
        jint cloudCount, jint starCount, jfloat time,
        jfloat particleSize, jfloat particleOpacity) {
    if (auto* r = asRenderer(handle))
        r->render(env, proj, clouds, stars, statics, cloudCount, starCount, time,
                  particleSize, particleOpacity);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reandroid_wallpaper_galaxy4_Galaxy4VKNative_nSetBackgroundTexture(
        JNIEnv* env, jclass, jlong handle, jintArray pixels, jint w, jint h) {
    if (auto* r = asRenderer(handle)) r->setBackgroundTexture(env, pixels, w, h);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reandroid_wallpaper_galaxy4_Galaxy4VKNative_nSetCloudTexture(
        JNIEnv* env, jclass, jlong handle, jintArray pixels, jint w, jint h) {
    if (auto* r = asRenderer(handle)) r->setCloudTexture(env, pixels, w, h);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reandroid_wallpaper_galaxy4_Galaxy4VKNative_nSetStaticStarTextures(
        JNIEnv* env, jclass, jlong handle,
        jintArray pix1, jint w1, jint h1,
        jintArray pix2, jint w2, jint h2) {
    if (auto* r = asRenderer(handle)) r->setStaticStarTextures(env, pix1, w1, h1, pix2, w2, h2);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reandroid_wallpaper_galaxy4_Galaxy4VKNative_nIsVulkanSupported(JNIEnv*, jclass) {
    return vkIsVulkanSupported() ? JNI_TRUE : JNI_FALSE;
}
