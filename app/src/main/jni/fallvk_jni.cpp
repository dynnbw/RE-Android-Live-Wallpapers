#define LOG_TAG "FallVK"
#include "vk_common.h"

#include <cmath>

namespace {

struct PushConstants {
    float mvp[16];           // offset 0
    float alpha;             // offset 64
    float leafFrameIndex;    // offset 68
    float leafFrameInvCount; // offset 72
    float shadow;            // offset 76: 1 = 画剪影（叶子影子），0 = 正常叶片
    float glHeight;          // offset 80
    float bgScale;           // offset 84
    float meshScaleX;        // offset 88
    float meshScaleY;        // offset 92
    float dxMul;             // offset 96
    float xOffset;           // offset 100
    int rotate;              // offset 104
    /*
     * 最后 5 个 float（108..127）按 pass 复用：
     *   水面 = 星空的 amount / time / aspect；
     *   叶子 = 日夜染色的 rgb / amount / value。
     * 两块需要的字段互不重叠，共用正好压进 128 字节的 push constant 上限。
     */
    float lighting[5];       // offset 108
};
// Drops moved to UBO (binding 1) — no hard limit

struct DropUbo {
    float drops[80 * 4];    // 80 drops × 4 floats, offset 0
    int dropCount;          // offset 1280
    int _pad[3];            // align to 16-byte boundary (offset 1296)
    float skyWeights[4];    // offset 1296: [夜,晨,昏,昼]，水面片元着色器混天空用
    float algae[80 * 4];    // offset 1312: (power, worldX, worldY, 0)，蓝藻用
    float algaeAmount;      // offset 2592: 水面蓝藻可见度
    float leafAlgaeAmount;  // offset 2596: 叶片受光可见度
    float algaeMetric[2];   // offset 2600: 世界→网格的距离度量
    float algaeMeshScale[2];// offset 2608: 世界→网格的坐标尺度
    float algaeMeshOffset[2];// offset 2616: 世界→网格的坐标偏移
    float algaeThreshold;   // offset 2624
    float algaeBand;        // offset 2628
    float algaeNoiseTile;   // offset 2632
    float algaeArcScale;    // offset 2636
    float algaeGain;        // offset 2640
    int _pad2[3];           // 对齐 vec4（2656）
    float algaeLow[4];      // offset 2656
    float algaeHigh[4];     // offset 2672
    float lightColor[4];    // offset 2688
    float emitter[4];       // offset 2704: (屏幕 x, y, 横向半宽, 纵向半宽)
    float emitterColor[4];  // offset 2720: rgb + 增益
};

struct TextureResource {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView imageView = VK_NULL_HANDLE;
    VkSampler sampler = VK_NULL_HANDLE;
    uint32_t width = 0;
    uint32_t height = 0;
    // 河床遮罩是单通道（R8_UNORM），其余贴图是 RGBA8 —— 格式随资源走，
    // 好让同一条上传路径伺候两者，遮罩的显存因此只有四通道的四分之一。
    VkFormat format = VK_FORMAT_R8G8B8A8_UNORM;
    uint32_t bytesPerPixel = 4;
    uint32_t descriptorBinding = 0;
    std::vector<uint8_t> pendingPixels;
    uint32_t pendingWidth = 0;
    uint32_t pendingHeight = 0;
    // 同一张图要同时喂两个描述符集时用：蓝藻噪声水面在 binding 3、叶子在 binding 2
    VkDescriptorSet extraSet = VK_NULL_HANDLE;
    uint32_t extraBinding = 0;
    /** 平铺采样。噪声贴图必须能重复铺满整片水面。 */
    bool repeat = false;
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

class FallVkRenderer : public VkRendererBase<FallVkRenderer> {
public:
    explicit FallVkRenderer(AAssetManager* assetManager) : assetManager_(assetManager) {}

    ~FallVkRenderer() {
        destroy();
    }

    // ── CRTP hooks ──

    AAssetManager* getAssetManager() { return assetManager_; }

    bool isSceneReadyLocked() const {
        return bgPipeline_ != VK_NULL_HANDLE && leafPipeline_ != VK_NULL_HANDLE
            && bgDescriptorSet_ != VK_NULL_HANDLE && leafDescriptorSet_ != VK_NULL_HANDLE
            && bgTexture_.imageView != VK_NULL_HANDLE && bgTexture_.sampler != VK_NULL_HANDLE
            && skyTexture_.imageView != VK_NULL_HANDLE && skyTexture_.sampler != VK_NULL_HANDLE
            && leafTexture_.imageView != VK_NULL_HANDLE && leafTexture_.sampler != VK_NULL_HANDLE
            && algaeTexture_.imageView != VK_NULL_HANDLE && algaeTexture_.sampler != VK_NULL_HANDLE
            && waterVertexBuffer_ != VK_NULL_HANDLE && waterIndexBuffer_ != VK_NULL_HANDLE;
    }

    bool onDeviceCreated() {
        glowFormatSupported_ = queryGlowFormatSupportLocked();
        if (!createDescriptorResourcesLocked()) return false;
        // 蓝藻噪声同时喂水面与叶子两个描述符集，所以在这里补上第二个目标
        configureAlgaeTextureLocked();
        if (!ensureTextureLocked(bgTexture_, bgDescriptorSet_)) return false;
        if (!ensureTextureLocked(skyTexture_, bgDescriptorSet_)) return false;
        if (!ensureTextureLocked(algaeTexture_, bgDescriptorSet_)) return false;
        return ensureTextureLocked(leafTexture_, leafDescriptorSet_);
    }

    bool onSwapchainCreated() {
        // 建不起来只是没有辉光，不阻止画面
        createGlowResourcesLocked();
        return true;
    }

    void destroyTexturesLocked() {
        destroyTextureLocked(bgTexture_);
        destroyTextureLocked(skyTexture_);
        destroyTextureLocked(leafTexture_);
        destroyTextureLocked(algaeTexture_);
        destroyWaterBuffersLocked();
        destroyDropUboLocked();
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
    }

    void recordCommandBuffersLocked(int /*w*/, int /*h*/) {
        // Command buffers are recorded per-frame in render()
    }

    bool recoverRenderStateLocked() {
        if (device_ == VK_NULL_HANDLE || window_ == nullptr) return false;
        VkRendererBase::recoverRenderStateLocked(width_, height_);
        return isReadyLocked();
    }

    // ── Public interface ──

    bool createOrUpdateSurface(JNIEnv* env, jobject surface, int width, int height) {
        std::lock_guard<std::mutex> lock(mutex_);
        width_ = width;
        height_ = height;

        if (!createInstanceLocked("FallVK")) return false;

        ANativeWindow* newWindow = ANativeWindow_fromSurface(env, surface);
        if (newWindow == nullptr) {
            LOGE("ANativeWindow_fromSurface failed");
            return false;
        }

        // Release old window before overwriting
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

    /** 河床遮罩：单通道 R8，binding 0（水面着色器的 uMaskTex）。 */
    void setMaskTexture(JNIEnv* env, jbyteArray mask, jint width, jint height) {
        std::lock_guard<std::mutex> lock(mutex_);
        bgTexture_.format = VK_FORMAT_R8_UNORM;
        bgTexture_.bytesPerPixel = 1;
        bgTexture_.descriptorBinding = 0;
        // 格式变了就得重建，否则会沿用上一次的 RGBA8 镜像
        destroyTextureLocked(bgTexture_);
        storePendingMaskLocked(env, mask, width, height, bgTexture_);
        if (device_ != VK_NULL_HANDLE) {
            ensureTextureLocked(bgTexture_, bgDescriptorSet_);
        }
    }

    /** 天空色带：24x64 RGBA，binding 2（水面着色器的 uSkyTex）。 */
    void setSkyTexture(JNIEnv* env, jintArray argbPixels, jint width, jint height) {
        std::lock_guard<std::mutex> lock(mutex_);
        skyTexture_.format = VK_FORMAT_R8G8B8A8_UNORM;
        skyTexture_.bytesPerPixel = 4;
        skyTexture_.descriptorBinding = 2;
        destroyTextureLocked(skyTexture_);
        storePendingTextureLocked(env, argbPixels, width, height, skyTexture_);
        if (device_ != VK_NULL_HANDLE) {
            ensureTextureLocked(skyTexture_, bgDescriptorSet_);
        }
    }

    /**
     * 蓝藻噪声：单通道 R8、REPEAT 采样。
     *
     * <p>水面与叶子用的是同一张图，所以在两个描述符集里各占一个 binding
     * （水面 3、叶子 2）—— 见 {@link TextureResource#extraSet}。
     */
    void setAlgaeTexture(JNIEnv* env, jbyteArray pixels, jint width, jint height) {
        std::lock_guard<std::mutex> lock(mutex_);
        configureAlgaeTextureLocked();
        destroyTextureLocked(algaeTexture_);
        storePendingMaskLocked(env, pixels, width, height, algaeTexture_);
        if (device_ != VK_NULL_HANDLE) {
            ensureTextureLocked(algaeTexture_, bgDescriptorSet_);
        }
    }

    void configureAlgaeTextureLocked() {
        algaeTexture_.format = VK_FORMAT_R8_UNORM;
        algaeTexture_.bytesPerPixel = 1;
        algaeTexture_.descriptorBinding = 3;
        algaeTexture_.extraSet = leafDescriptorSet_;
        algaeTexture_.extraBinding = 2;
        algaeTexture_.repeat = true;
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

    /** 辉光的每帧参数，顺序见 {@code FallVkDelegate#buildGlowParams}。 */
    static void writeGlowParamsLocked(DropUbo* ubo, const float* p) {
        if (ubo == nullptr || p == nullptr) return;
        ubo->emitter[0] = p[5];
        ubo->emitter[1] = p[6];
        ubo->emitter[2] = p[7];
        ubo->emitter[3] = p[8];
        ubo->emitterColor[0] = p[9];
        ubo->emitterColor[1] = p[10];
        ubo->emitterColor[2] = p[11];
        ubo->emitterColor[3] = p[4];
    }

    /**
     * 本帧走不走离屏 + 辉光。
     *
     * <p>三个条件缺一不可：开关打开、设备的格式支持、离屏资源建起来了。
     * 任何一项不满足就退回原来的单遍路径 —— 画面照常，只是没有辉光。
     */
    void applyGlowParams(const float* p, bool enabled) {
        glowRequested_ = enabled;
        if (p != nullptr) {
            glowThreshold_ = p[0];
            glowSoftKnee_ = p[1];
            glowRadius_ = p[2];
            glowStrength_ = p[3];
        }
        glowActive_ = glowRequested_ && glowFormatSupported_ && glowReady_;
    }

    /** 蓝藻的每帧参数，顺序见 {@code FallVkDelegate#buildAlgaeParams}。 */
    static void writeAlgaeParamsLocked(DropUbo* ubo, const float* p) {
        if (ubo == nullptr || p == nullptr) return;
        ubo->algaeAmount = p[0];
        ubo->leafAlgaeAmount = p[1];
        ubo->algaeThreshold = p[2];
        ubo->algaeBand = p[3];
        ubo->algaeGain = p[4];
        ubo->algaeNoiseTile = p[5];
        ubo->algaeArcScale = p[6];
        for (int i = 0; i < 3; i++) {
            ubo->algaeLow[i] = p[7 + i];
            ubo->algaeHigh[i] = p[10 + i];
            ubo->lightColor[i] = p[13 + i];
        }
        ubo->algaeLow[3] = 0.0f;
        ubo->algaeHigh[3] = 0.0f;
        ubo->lightColor[3] = 0.0f;
        for (int i = 0; i < 2; i++) {
            ubo->algaeMetric[i] = p[16 + i];
            ubo->algaeMeshScale[i] = p[18 + i];
            ubo->algaeMeshOffset[i] = p[20 + i];
        }
    }

    void render(JNIEnv* env, const float* lighting, const float* algaeParams,
            const float* glowParams, bool glowEnabled,
            jfloatArray projectionArray, jfloatArray viewArray,
            jfloatArray leavesArray, jint leafCount, jfloat xOffset,
            jfloatArray waterVertices, jfloatArray waterTexCoords, jshortArray waterIndices,
            jint waterVertexCount, jint waterIndexCount,
            jfloatArray dropDataArray, jfloatArray algaePowerArray, jfloatArray algaeWorldArray,
            jint dropCount,
            jfloat glHeight, jfloat bgScale, jfloat meshScaleX, jfloat meshScaleY,
            jfloat dxMul, jint rotate) {
        std::lock_guard<std::mutex> lock(mutex_);

        // Full mesh upload only on rebuild (waterVertexCount > 0 signals rebuild)
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

        // 先把上一帧等完，再覆写 UBO —— 否则 GPU 可能还在读正在被改写的常量
        vkWaitForFences(device_, 1, &inFlightFence_, VK_TRUE, UINT64_MAX);

        // 辉光走不走离屏路径要在写 UBO 之前定下来：发光体也要跟着开关
        applyGlowParams(glowParams, glowEnabled);

        // Write drop data to UBO (no hard limit — matches GLES behavior)
        if (!ensureDropUboLocked()) {
            return;
        }
        int maxDrops = std::min(dropCount, 80);
        if (dropUboMapped_ != nullptr) {
            auto* ubo = static_cast<DropUbo*>(dropUboMapped_);
            if (dropDataArray != nullptr && maxDrops > 0) {
                env->GetFloatArrayRegion(dropDataArray, 0, maxDrops * 4, ubo->drops);
            }
            ubo->dropCount = maxDrops;
            // 天空权重：[夜,晨,昏,昼]，每条都逐帧更新，日夜过渡才不会跳
            for (int i = 0; i < 4; i++) {
                ubo->skyWeights[i] = lighting != nullptr ? lighting[8 + i] : 0.0f;
            }
            // 蓝藻：每次扰动的强度与世界坐标，与 u_drop 一一对应
            if (maxDrops > 0) {
                float power[80] = {};
                float world[160] = {};
                if (algaePowerArray != nullptr) {
                    env->GetFloatArrayRegion(algaePowerArray, 0, maxDrops, power);
                }
                if (algaeWorldArray != nullptr) {
                    env->GetFloatArrayRegion(algaeWorldArray, 0, maxDrops * 2, world);
                }
                for (int i = 0; i < maxDrops; i++) {
                    ubo->algae[i * 4 + 0] = power[i];
                    ubo->algae[i * 4 + 1] = world[i * 2];
                    ubo->algae[i * 4 + 2] = world[i * 2 + 1];
                    ubo->algae[i * 4 + 3] = 0.0f;
                }
            }
            writeAlgaeParamsLocked(ubo, algaeParams);
            writeGlowParamsLocked(ubo, glowParams);
            // 辉光没生效时发光体也不画：一个没有辉光收尾的高斯斑看着就是块白渍
            if (!glowActive_) {
                ubo->emitterColor[3] = 0.0f;
            }
        }

        // Build slim push constants (drops now in UBO)
        PushConstants pc{};
        pc.glHeight = glHeight;
        pc.bgScale = bgScale;
        pc.meshScaleX = meshScaleX;
        pc.meshScaleY = meshScaleY;
        pc.dxMul = dxMul;
        pc.xOffset = xOffset;
        pc.rotate = rotate;

        uint32_t imageIndex = 0;
        VkResult acquire = vkAcquireNextImageKHR(device_, swapchain_, UINT64_MAX,
                imageAvailableSemaphore_, VK_NULL_HANDLE, &imageIndex);
        if (acquire == VK_ERROR_SURFACE_LOST_KHR) {
            createOrUpdateSurfaceLocked(window_, width_, height_);
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

        VkCommandBuffer commandBuffer = swapchainCommandBuffers_[imageIndex];
        vkResetCommandBuffer(commandBuffer, 0);
        if (!recordCommandBufferLocked(commandBuffer, imageIndex, projection, view, leaves, xOffset,
            waterIndexCount_, pc, lighting)) {
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
            createOrUpdateSurfaceLocked(window_, width_, height_);
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
        if (window_ != nullptr) {
            ANativeWindow_release(window_);
            window_ = nullptr;
        }
        destroyDeviceLocked();
    }

// CRTP hooks called by VkRendererBase (public access required)
    // ── Pipeline management ──

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

        bgPipeline_ = createGraphicsPipelineLocked(
                bgVert, bgFrag, bgPipelineLayout_, false, renderPass_);
        leafPipeline_ = createGraphicsPipelineLocked(
                leafVert, leafFrag, leafPipelineLayout_, true, renderPass_);

        vkDestroyShaderModule(device_, bgVert, nullptr);
        vkDestroyShaderModule(device_, bgFrag, nullptr);
        vkDestroyShaderModule(device_, leafVert, nullptr);
        vkDestroyShaderModule(device_, leafFrag, nullptr);

        return bgPipeline_ != VK_NULL_HANDLE && leafPipeline_ != VK_NULL_HANDLE;
    }

    void destroyPipelinesLocked() {
        if (bgPipeline_ != VK_NULL_HANDLE) { vkDestroyPipeline(device_, bgPipeline_, nullptr); bgPipeline_ = VK_NULL_HANDLE; }
        if (leafPipeline_ != VK_NULL_HANDLE) { vkDestroyPipeline(device_, leafPipeline_, nullptr); leafPipeline_ = VK_NULL_HANDLE; }
        if (bgPipelineLayout_ != VK_NULL_HANDLE) { vkDestroyPipelineLayout(device_, bgPipelineLayout_, nullptr); bgPipelineLayout_ = VK_NULL_HANDLE; }
        if (leafPipelineLayout_ != VK_NULL_HANDLE) { vkDestroyPipelineLayout(device_, leafPipelineLayout_, nullptr); leafPipelineLayout_ = VK_NULL_HANDLE; }
        destroyGlowResourcesLocked();
    }

    // ── 辉光：离屏场景 → 亮部提取 → 横纵模糊 → 合成 ──
    //
    // 结构与 GLES 的 GlowRenderer 逐遍对应。差别只在平台细节：Vulkan 的纹理 v 朝下、
    // NDC y 也朝下，所以全屏四边形不需要翻转；格式支持要自己查。

    struct GlowBrightPush {
        float texelX, texelY, threshold, knee;
    };
    struct GlowBlurPush {
        float stepX, stepY, sigma, pad;
    };
    struct GlowCompositePush {
        float strength, pad0, pad1, pad2;
    };

    /**
     * 离屏场景与辉光缓冲的格式支持。
     *
     * <p>要同时能做颜色附件、能被采样、能线性过滤 —— 缺任何一项就整体退化：
     * 换成 8 位缓冲也能跑，但亮部会被截断成一块死白，那正是这个功能要解决的问题。
     */
    bool queryGlowFormatSupportLocked() const {
        VkFormatProperties props{};
        vkGetPhysicalDeviceFormatProperties(
                physicalDevice_, VK_FORMAT_R16G16B16A16_SFLOAT, &props);
        const VkFormatFeatureFlags need = VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT
                | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT
                | VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
        return (props.optimalTilingFeatures & need) == need;
    }

    bool createGlowColorTargetLocked(
            VkImage& image, VkDeviceMemory& memory, VkImageView& view,
            uint32_t width, uint32_t height) {
        VkImageCreateInfo ii{};
        ii.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        ii.imageType = VK_IMAGE_TYPE_2D;
        ii.extent = {width, height, 1};
        ii.mipLevels = 1;
        ii.arrayLayers = 1;
        ii.format = VK_FORMAT_R16G16B16A16_SFLOAT;
        ii.tiling = VK_IMAGE_TILING_OPTIMAL;
        ii.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        ii.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        ii.samples = VK_SAMPLE_COUNT_1_BIT;
        ii.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        if (vkCreateImage(device_, &ii, nullptr, &image) != VK_SUCCESS) {
            image = VK_NULL_HANDLE;
            return false;
        }

        VkMemoryRequirements req{};
        vkGetImageMemoryRequirements(device_, image, &req);
        VkMemoryAllocateInfo ai{};
        ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        ai.allocationSize = req.size;
        ai.memoryTypeIndex = vkFindMemoryType(
                physicalDevice_, req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if (vkAllocateMemory(device_, &ai, nullptr, &memory) != VK_SUCCESS) {
            memory = VK_NULL_HANDLE;
            destroyGlowResourcesLocked();
            return false;
        }
        vkBindImageMemory(device_, image, memory, 0);

        VkImageViewCreateInfo vi{};
        vi.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        vi.image = image;
        vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
        vi.format = VK_FORMAT_R16G16B16A16_SFLOAT;
        vi.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        vi.subresourceRange.levelCount = 1;
        vi.subresourceRange.layerCount = 1;
        if (vkCreateImageView(device_, &vi, nullptr, &view) != VK_SUCCESS) {
            view = VK_NULL_HANDLE;
            destroyGlowResourcesLocked();
            return false;
        }
        return true;
    }

    /** 单附件、清屏、结束即转成可采样 —— 场景与辉光缓冲共用这一个。 */
    VkRenderPass createGlowRenderPassLocked() {
        VkAttachmentDescription ca{};
        ca.format = VK_FORMAT_R16G16B16A16_SFLOAT;
        ca.samples = VK_SAMPLE_COUNT_1_BIT;
        ca.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
        ca.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        ca.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        ca.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        ca.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        ca.finalLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

        VkAttachmentReference ref{};
        ref.attachment = 0;
        ref.layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;

        VkSubpassDescription sp{};
        sp.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        sp.colorAttachmentCount = 1;
        sp.pColorAttachments = &ref;

        // 上一遍读完这张图、这一遍又要写它 —— 读→写的依赖挂在外部依赖上
        VkSubpassDependency dep{};
        dep.srcSubpass = VK_SUBPASS_EXTERNAL;
        dep.dstSubpass = 0;
        dep.srcStageMask = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
                | VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dep.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dep.srcAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        dep.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;

        VkRenderPassCreateInfo rp{};
        rp.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
        rp.attachmentCount = 1;
        rp.pAttachments = &ca;
        rp.subpassCount = 1;
        rp.pSubpasses = &sp;
        rp.dependencyCount = 1;
        rp.pDependencies = &dep;
        VkRenderPass pass = VK_NULL_HANDLE;
        if (vkCreateRenderPass(device_, &rp, nullptr, &pass) != VK_SUCCESS) {
            return VK_NULL_HANDLE;
        }
        return pass;
    }

    VkDescriptorSetLayout createGlowSetLayoutLocked(uint32_t samplerCount) {
        VkDescriptorSetLayoutBinding bindings[2]{};
        for (uint32_t i = 0; i < samplerCount; i++) {
            bindings[i].binding = i;
            bindings[i].descriptorCount = 1;
            bindings[i].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            bindings[i].stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
        }
        VkDescriptorSetLayoutCreateInfo li{};
        li.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        li.bindingCount = samplerCount;
        li.pBindings = bindings;
        VkDescriptorSetLayout layout = VK_NULL_HANDLE;
        if (vkCreateDescriptorSetLayout(device_, &li, nullptr, &layout) != VK_SUCCESS) {
            return VK_NULL_HANDLE;
        }
        return layout;
    }

    VkPipelineLayout createGlowPipelineLayoutLocked(
            VkDescriptorSetLayout setLayout, uint32_t pushSize) {
        VkPipelineLayoutCreateInfo li{};
        li.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        li.setLayoutCount = 1;
        li.pSetLayouts = &setLayout;
        VkPushConstantRange range{};
        range.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
        range.offset = 0;
        range.size = pushSize;
        li.pushConstantRangeCount = 1;
        li.pPushConstantRanges = &range;
        VkPipelineLayout layout = VK_NULL_HANDLE;
        if (vkCreatePipelineLayout(device_, &li, nullptr, &layout) != VK_SUCCESS) {
            return VK_NULL_HANDLE;
        }
        return layout;
    }

    /** 全屏四边形：顶点在着色器里按 gl_VertexIndex 生成，不接任何缓冲。 */
    VkPipeline createGlowPipelineLocked(VkShaderModule vert, VkShaderModule frag,
            VkPipelineLayout layout, VkRenderPass pass) {
        VkPipelineShaderStageCreateInfo stages[2]{};
        stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
        stages[0].module = vert;
        stages[0].pName = "main";
        stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
        stages[1].module = frag;
        stages[1].pName = "main";

        VkPipelineVertexInputStateCreateInfo vinput{};
        vinput.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;

        VkPipelineInputAssemblyStateCreateInfo ia{};
        ia.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
        ia.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;

        VkPipelineViewportStateCreateInfo vp{};
        vp.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
        vp.viewportCount = 1;
        vp.scissorCount = 1;

        VkPipelineRasterizationStateCreateInfo rs{};
        rs.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
        rs.polygonMode = VK_POLYGON_MODE_FILL;
        rs.cullMode = VK_CULL_MODE_NONE;
        rs.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
        rs.lineWidth = 1.0f;

        VkPipelineMultisampleStateCreateInfo ms{};
        ms.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
        ms.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

        VkPipelineColorBlendAttachmentState cb{};
        cb.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
                | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
        cb.blendEnable = VK_FALSE;

        VkPipelineColorBlendStateCreateInfo colorBlend{};
        colorBlend.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
        colorBlend.attachmentCount = 1;
        colorBlend.pAttachments = &cb;

        std::array<VkDynamicState, 2> dynamicStates = {
                VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
        VkPipelineDynamicStateCreateInfo dyn{};
        dyn.sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
        dyn.dynamicStateCount = static_cast<uint32_t>(dynamicStates.size());
        dyn.pDynamicStates = dynamicStates.data();

        VkGraphicsPipelineCreateInfo pi{};
        pi.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
        pi.stageCount = 2;
        pi.pStages = stages;
        pi.pVertexInputState = &vinput;
        pi.pInputAssemblyState = &ia;
        pi.pViewportState = &vp;
        pi.pRasterizationState = &rs;
        pi.pMultisampleState = &ms;
        pi.pColorBlendState = &colorBlend;
        pi.pDynamicState = &dyn;
        pi.layout = layout;
        pi.renderPass = pass;

        VkPipeline pipeline = VK_NULL_HANDLE;
        if (vkCreateGraphicsPipelines(device_, VK_NULL_HANDLE, 1, &pi, nullptr, &pipeline) != VK_SUCCESS) {
            return VK_NULL_HANDLE;
        }
        return pipeline;
    }

    static void writeGlowSamplerLocked(VkDevice descriptorDevice, VkDescriptorSet set,
            uint32_t binding, VkImageView view, VkSampler sampler) {
        if (set == VK_NULL_HANDLE || view == VK_NULL_HANDLE) return;
        VkDescriptorImageInfo info{};
        info.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        info.imageView = view;
        info.sampler = sampler;
        VkWriteDescriptorSet write{};
        write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        write.dstSet = set;
        write.dstBinding = binding;
        write.descriptorCount = 1;
        write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        write.pImageInfo = &info;
        vkUpdateDescriptorSets(descriptorDevice, 1, &write, 0, nullptr);
    }

    /**
     * 建离屏场景与辉光缓冲。**任何一步失败都整体退化**（{@code glowReady_} = false），
     * 调用方照常用单遍路径画 —— 增强可以没有，但不能把画面弄坏。
     */
    bool createGlowResourcesLocked() {
        destroyGlowResourcesLocked();
        if (!glowFormatSupported_ || device_ == VK_NULL_HANDLE
                || swapchainExtent_.width == 0 || swapchainExtent_.height == 0) {
            return true;
        }

        sceneWidth_ = swapchainExtent_.width;
        sceneHeight_ = swapchainExtent_.height;
        bloomWidth_ = std::max(1u, sceneWidth_ / 2);
        bloomHeight_ = std::max(1u, sceneHeight_ / 2);

        if (!createGlowColorTargetLocked(
                    sceneImage_, sceneMemory_, sceneView_, sceneWidth_, sceneHeight_)) {
            return true;
        }
        if (!createGlowColorTargetLocked(
                    bloomImageA_, bloomMemoryA_, bloomViewA_, bloomWidth_, bloomHeight_)) {
            return true;
        }
        if (!createGlowColorTargetLocked(
                    bloomImageB_, bloomMemoryB_, bloomViewB_, bloomWidth_, bloomHeight_)) {
            return true;
        }

        scenePass_ = createGlowRenderPassLocked();
        bloomPass_ = createGlowRenderPassLocked();
        if (scenePass_ == VK_NULL_HANDLE || bloomPass_ == VK_NULL_HANDLE) {
            return true;
        }

        /*
         * 场景管线要再建一对：Vulkan 要求管线与 render pass 相容，而离屏那一遍的
         * 附件是 RGBA16F、交换链是 8 位 —— 拿交换链的管线去画离屏遍什么都画不出来。
         */
        VkShaderModule sceneVert = createShaderModuleLocked(
                {"shaders/fallvk_water.vert.spv", "shaders/fallvk_water.vert.spv"});
        VkShaderModule sceneFrag = createShaderModuleLocked(
                {"shaders/fallvk_water.frag.spv", "shaders/fallvk_water.frag.spv"});
        VkShaderModule sceneLeafVert = createShaderModuleLocked(
                {"shaders/fallvk_leaf.vert.spv", "shaders/fallvk_leaf.vert.spv"});
        VkShaderModule sceneLeafFrag = createShaderModuleLocked(
                {"shaders/fallvk_leaf.frag.spv", "shaders/fallvk_leaf.frag.spv"});
        if (sceneVert != VK_NULL_HANDLE && sceneFrag != VK_NULL_HANDLE
                && sceneLeafVert != VK_NULL_HANDLE && sceneLeafFrag != VK_NULL_HANDLE) {
            bgPipelineScene_ = createGraphicsPipelineLocked(
                    sceneVert, sceneFrag, bgPipelineLayout_, false, scenePass_);
            leafPipelineScene_ = createGraphicsPipelineLocked(
                    sceneLeafVert, sceneLeafFrag, leafPipelineLayout_, true, scenePass_);
        }
        if (sceneVert != VK_NULL_HANDLE) vkDestroyShaderModule(device_, sceneVert, nullptr);
        if (sceneFrag != VK_NULL_HANDLE) vkDestroyShaderModule(device_, sceneFrag, nullptr);
        if (sceneLeafVert != VK_NULL_HANDLE) vkDestroyShaderModule(device_, sceneLeafVert, nullptr);
        if (sceneLeafFrag != VK_NULL_HANDLE) vkDestroyShaderModule(device_, sceneLeafFrag, nullptr);
        if (bgPipelineScene_ == VK_NULL_HANDLE || leafPipelineScene_ == VK_NULL_HANDLE) {
            return true;
        }

        const VkImageView targets[3] = {sceneView_, bloomViewA_, bloomViewB_};
        VkExtent2D extents[3] = {
                {sceneWidth_, sceneHeight_}, {bloomWidth_, bloomHeight_}, {bloomWidth_, bloomHeight_}};
        VkFramebuffer* framebuffers[3] = {&sceneFramebuffer_, &bloomFramebufferA_, &bloomFramebufferB_};
        for (int i = 0; i < 3; i++) {
            VkFramebufferCreateInfo fi{};
            fi.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
            fi.renderPass = i == 0 ? scenePass_ : bloomPass_;
            fi.attachmentCount = 1;
            fi.pAttachments = &targets[i];
            fi.width = extents[i].width;
            fi.height = extents[i].height;
            fi.layers = 1;
            if (vkCreateFramebuffer(device_, &fi, nullptr, framebuffers[i]) != VK_SUCCESS) {
                return true;
            }
        }

        VkSamplerCreateInfo si{};
        si.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
        si.magFilter = VK_FILTER_LINEAR;
        si.minFilter = VK_FILTER_LINEAR;
        si.mipmapMode = VK_SAMPLER_MIPMAP_MODE_LINEAR;
        si.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        si.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        si.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        if (vkCreateSampler(device_, &si, nullptr, &glowSampler_) != VK_SUCCESS) {
            return true;
        }

        glowOneSetLayout_ = createGlowSetLayoutLocked(1);
        glowTwoSetLayout_ = createGlowSetLayoutLocked(2);
        if (glowOneSetLayout_ == VK_NULL_HANDLE || glowTwoSetLayout_ == VK_NULL_HANDLE) {
            return true;
        }

        VkDescriptorPoolSize poolSize{};
        poolSize.type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        poolSize.descriptorCount = 5;
        VkDescriptorPoolCreateInfo pi{};
        pi.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
        pi.poolSizeCount = 1;
        pi.pPoolSizes = &poolSize;
        pi.maxSets = 4;
        if (vkCreateDescriptorPool(device_, &pi, nullptr, &glowPool_) != VK_SUCCESS) {
            return true;
        }

        const VkDescriptorSetLayout layouts[4] = {
                glowOneSetLayout_, glowOneSetLayout_, glowOneSetLayout_, glowTwoSetLayout_};
        VkDescriptorSet sets[4] = {
                VK_NULL_HANDLE, VK_NULL_HANDLE, VK_NULL_HANDLE, VK_NULL_HANDLE};
        VkDescriptorSetAllocateInfo ai{};
        ai.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        ai.descriptorPool = glowPool_;
        ai.descriptorSetCount = 4;
        ai.pSetLayouts = layouts;
        if (vkAllocateDescriptorSets(device_, &ai, sets) != VK_SUCCESS) {
            return true;
        }
        brightSet_ = sets[0];
        blurSetA_ = sets[1];
        blurSetB_ = sets[2];
        compositeSet_ = sets[3];

        brightLayout_ = createGlowPipelineLayoutLocked(glowOneSetLayout_, sizeof(GlowBrightPush));
        blurLayout_ = createGlowPipelineLayoutLocked(glowOneSetLayout_, sizeof(GlowBlurPush));
        compositeLayout_ = createGlowPipelineLayoutLocked(
                glowTwoSetLayout_, sizeof(GlowCompositePush));
        if (brightLayout_ == VK_NULL_HANDLE || blurLayout_ == VK_NULL_HANDLE
                || compositeLayout_ == VK_NULL_HANDLE) {
            return true;
        }

        VkShaderModule vert = createShaderModuleLocked(
                {"shaders/fallvk_glow.vert.spv", "shaders/fallvk_glow.vert.spv"});
        VkShaderModule brightFrag = createShaderModuleLocked(
                {"shaders/fallvk_glow_bright.frag.spv", "shaders/fallvk_glow_bright.frag.spv"});
        VkShaderModule blurFrag = createShaderModuleLocked(
                {"shaders/fallvk_glow_blur.frag.spv", "shaders/fallvk_glow_blur.frag.spv"});
        VkShaderModule compositeFrag = createShaderModuleLocked(
                {"shaders/fallvk_glow_composite.frag.spv", "shaders/fallvk_glow_composite.frag.spv"});
        if (vert == VK_NULL_HANDLE || brightFrag == VK_NULL_HANDLE
                || blurFrag == VK_NULL_HANDLE || compositeFrag == VK_NULL_HANDLE) {
            if (vert) vkDestroyShaderModule(device_, vert, nullptr);
            if (brightFrag) vkDestroyShaderModule(device_, brightFrag, nullptr);
            if (blurFrag) vkDestroyShaderModule(device_, blurFrag, nullptr);
            if (compositeFrag) vkDestroyShaderModule(device_, compositeFrag, nullptr);
            return true;
        }

        brightPipeline_ = createGlowPipelineLocked(vert, brightFrag, brightLayout_, bloomPass_);
        blurPipeline_ = createGlowPipelineLocked(vert, blurFrag, blurLayout_, bloomPass_);
        compositePipeline_ = createGlowPipelineLocked(
                vert, compositeFrag, compositeLayout_, renderPass_);
        vkDestroyShaderModule(device_, vert, nullptr);
        vkDestroyShaderModule(device_, brightFrag, nullptr);
        vkDestroyShaderModule(device_, blurFrag, nullptr);
        vkDestroyShaderModule(device_, compositeFrag, nullptr);

        if (brightPipeline_ == VK_NULL_HANDLE || blurPipeline_ == VK_NULL_HANDLE
                || compositePipeline_ == VK_NULL_HANDLE) {
            return true;
        }

        writeGlowSamplerLocked(device_, brightSet_, 0, sceneView_, glowSampler_);
        writeGlowSamplerLocked(device_, blurSetA_, 0, bloomViewA_, glowSampler_);
        writeGlowSamplerLocked(device_, blurSetB_, 0, bloomViewB_, glowSampler_);
        writeGlowSamplerLocked(device_, compositeSet_, 0, sceneView_, glowSampler_);
        writeGlowSamplerLocked(device_, compositeSet_, 1, bloomViewA_, glowSampler_);

        glowReady_ = true;
        return true;
    }

    void destroyGlowResourcesLocked() {
        glowReady_ = false;
        if (device_ == VK_NULL_HANDLE) return;
        if (bgPipelineScene_ != VK_NULL_HANDLE) { vkDestroyPipeline(device_, bgPipelineScene_, nullptr); bgPipelineScene_ = VK_NULL_HANDLE; }
        if (leafPipelineScene_ != VK_NULL_HANDLE) { vkDestroyPipeline(device_, leafPipelineScene_, nullptr); leafPipelineScene_ = VK_NULL_HANDLE; }
        if (brightPipeline_ != VK_NULL_HANDLE) { vkDestroyPipeline(device_, brightPipeline_, nullptr); brightPipeline_ = VK_NULL_HANDLE; }
        if (blurPipeline_ != VK_NULL_HANDLE) { vkDestroyPipeline(device_, blurPipeline_, nullptr); blurPipeline_ = VK_NULL_HANDLE; }
        if (compositePipeline_ != VK_NULL_HANDLE) { vkDestroyPipeline(device_, compositePipeline_, nullptr); compositePipeline_ = VK_NULL_HANDLE; }
        if (brightLayout_ != VK_NULL_HANDLE) { vkDestroyPipelineLayout(device_, brightLayout_, nullptr); brightLayout_ = VK_NULL_HANDLE; }
        if (blurLayout_ != VK_NULL_HANDLE) { vkDestroyPipelineLayout(device_, blurLayout_, nullptr); blurLayout_ = VK_NULL_HANDLE; }
        if (compositeLayout_ != VK_NULL_HANDLE) { vkDestroyPipelineLayout(device_, compositeLayout_, nullptr); compositeLayout_ = VK_NULL_HANDLE; }
        brightSet_ = blurSetA_ = blurSetB_ = compositeSet_ = VK_NULL_HANDLE;
        if (glowPool_ != VK_NULL_HANDLE) { vkDestroyDescriptorPool(device_, glowPool_, nullptr); glowPool_ = VK_NULL_HANDLE; }
        if (glowOneSetLayout_ != VK_NULL_HANDLE) { vkDestroyDescriptorSetLayout(device_, glowOneSetLayout_, nullptr); glowOneSetLayout_ = VK_NULL_HANDLE; }
        if (glowTwoSetLayout_ != VK_NULL_HANDLE) { vkDestroyDescriptorSetLayout(device_, glowTwoSetLayout_, nullptr); glowTwoSetLayout_ = VK_NULL_HANDLE; }
        if (glowSampler_ != VK_NULL_HANDLE) { vkDestroySampler(device_, glowSampler_, nullptr); glowSampler_ = VK_NULL_HANDLE; }
        if (sceneFramebuffer_ != VK_NULL_HANDLE) { vkDestroyFramebuffer(device_, sceneFramebuffer_, nullptr); sceneFramebuffer_ = VK_NULL_HANDLE; }
        if (bloomFramebufferA_ != VK_NULL_HANDLE) { vkDestroyFramebuffer(device_, bloomFramebufferA_, nullptr); bloomFramebufferA_ = VK_NULL_HANDLE; }
        if (bloomFramebufferB_ != VK_NULL_HANDLE) { vkDestroyFramebuffer(device_, bloomFramebufferB_, nullptr); bloomFramebufferB_ = VK_NULL_HANDLE; }
        if (scenePass_ != VK_NULL_HANDLE) { vkDestroyRenderPass(device_, scenePass_, nullptr); scenePass_ = VK_NULL_HANDLE; }
        if (bloomPass_ != VK_NULL_HANDLE) { vkDestroyRenderPass(device_, bloomPass_, nullptr); bloomPass_ = VK_NULL_HANDLE; }
        if (sceneView_ != VK_NULL_HANDLE) { vkDestroyImageView(device_, sceneView_, nullptr); sceneView_ = VK_NULL_HANDLE; }
        if (bloomViewA_ != VK_NULL_HANDLE) { vkDestroyImageView(device_, bloomViewA_, nullptr); bloomViewA_ = VK_NULL_HANDLE; }
        if (bloomViewB_ != VK_NULL_HANDLE) { vkDestroyImageView(device_, bloomViewB_, nullptr); bloomViewB_ = VK_NULL_HANDLE; }
        if (sceneImage_ != VK_NULL_HANDLE) { vkDestroyImage(device_, sceneImage_, nullptr); sceneImage_ = VK_NULL_HANDLE; }
        if (bloomImageA_ != VK_NULL_HANDLE) { vkDestroyImage(device_, bloomImageA_, nullptr); bloomImageA_ = VK_NULL_HANDLE; }
        if (bloomImageB_ != VK_NULL_HANDLE) { vkDestroyImage(device_, bloomImageB_, nullptr); bloomImageB_ = VK_NULL_HANDLE; }
        if (sceneMemory_ != VK_NULL_HANDLE) { vkFreeMemory(device_, sceneMemory_, nullptr); sceneMemory_ = VK_NULL_HANDLE; }
        if (bloomMemoryA_ != VK_NULL_HANDLE) { vkFreeMemory(device_, bloomMemoryA_, nullptr); bloomMemoryA_ = VK_NULL_HANDLE; }
        if (bloomMemoryB_ != VK_NULL_HANDLE) { vkFreeMemory(device_, bloomMemoryB_, nullptr); bloomMemoryB_ = VK_NULL_HANDLE; }
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
            VkPipelineLayout layout, bool leafPipeline, VkRenderPass renderPassToUse) {
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
        pipelineInfo.renderPass = renderPassToUse;

        VkPipeline pipeline = VK_NULL_HANDLE;
        if (vkCreateGraphicsPipelines(device_, VK_NULL_HANDLE, 1, &pipelineInfo, nullptr, &pipeline) != VK_SUCCESS) {
            return VK_NULL_HANDLE;
        }
        return pipeline;
    }

    // ── Descriptor / texture management ──

    bool createDescriptorResourcesLocked() {
        if (!createBgDescriptorLocked()) return false;
        return createLeafDescriptorLocked();
    }

    bool createLeafDescriptorLocked() {
        if (leafDescriptorSetLayout_ != VK_NULL_HANDLE && leafDescriptorPool_ != VK_NULL_HANDLE
                && leafDescriptorSet_ != VK_NULL_HANDLE) {
            return true;
        }

        // Binding 0: 叶片图集
        // Binding 1: 与水面共用的 UBO（蓝藻的波纹、噪声参数）
        // Binding 2: 蓝藻噪声
        VkDescriptorSetLayoutBinding bindings[3]{};
        bindings[0].binding = 0;
        bindings[0].descriptorCount = 1;
        bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        bindings[0].stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;

        bindings[1].binding = 1;
        bindings[1].descriptorCount = 1;
        bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
        bindings[1].stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;

        bindings[2].binding = 2;
        bindings[2].descriptorCount = 1;
        bindings[2].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        bindings[2].stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;

        VkDescriptorSetLayoutCreateInfo layoutInfo{};
        layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        layoutInfo.bindingCount = 3;
        layoutInfo.pBindings = bindings;
        if (vkCreateDescriptorSetLayout(device_, &layoutInfo, nullptr, &leafDescriptorSetLayout_) != VK_SUCCESS) {
            return false;
        }

        VkDescriptorPoolSize poolSizes[2]{};
        poolSizes[0].type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        poolSizes[0].descriptorCount = 2;
        poolSizes[1].type = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
        poolSizes[1].descriptorCount = 1;
        VkDescriptorPoolCreateInfo poolInfo{};
        poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
        poolInfo.poolSizeCount = 2;
        poolInfo.pPoolSizes = poolSizes;
        poolInfo.maxSets = 1;
        if (vkCreateDescriptorPool(device_, &poolInfo, nullptr, &leafDescriptorPool_) != VK_SUCCESS) {
            return false;
        }

        VkDescriptorSetAllocateInfo allocInfo{};
        allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        allocInfo.descriptorPool = leafDescriptorPool_;
        allocInfo.descriptorSetCount = 1;
        allocInfo.pSetLayouts = &leafDescriptorSetLayout_;
        return vkAllocateDescriptorSets(device_, &allocInfo, &leafDescriptorSet_) == VK_SUCCESS;
    }

    bool createBgDescriptorLocked() {
        if (bgDescriptorSetLayout_ != VK_NULL_HANDLE && bgDescriptorPool_ != VK_NULL_HANDLE
                && bgDescriptorSet_ != VK_NULL_HANDLE) {
            return true;
        }

        // Binding 0: background texture sampler
        // Binding 1: drop uniform buffer
        // Binding 2: sky gradient sampler
        // Binding 3: algae noise sampler
        VkDescriptorSetLayoutBinding bindings[4]{};
        bindings[0].binding = 0;
        bindings[0].descriptorCount = 1;
        bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        bindings[0].stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;

        bindings[1].binding = 1;
        bindings[1].descriptorCount = 1;
        bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
        // 顶点读雨滴，片元读天空权重与蓝藻
        bindings[1].stageFlags = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;

        bindings[2].binding = 2;
        bindings[2].descriptorCount = 1;
        bindings[2].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        bindings[2].stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;

        bindings[3].binding = 3;
        bindings[3].descriptorCount = 1;
        bindings[3].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        bindings[3].stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;

        VkDescriptorSetLayoutCreateInfo layoutInfo{};
        layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        layoutInfo.bindingCount = 4;
        layoutInfo.pBindings = bindings;
        if (vkCreateDescriptorSetLayout(device_, &layoutInfo, nullptr, &bgDescriptorSetLayout_) != VK_SUCCESS) {
            return false;
        }

        VkDescriptorPoolSize poolSizes[2]{};
        poolSizes[0].type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        poolSizes[0].descriptorCount = 3;
        poolSizes[1].type = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
        poolSizes[1].descriptorCount = 1;
        VkDescriptorPoolCreateInfo poolInfo{};
        poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
        poolInfo.poolSizeCount = 2;
        poolInfo.pPoolSizes = poolSizes;
        poolInfo.maxSets = 1;
        if (vkCreateDescriptorPool(device_, &poolInfo, nullptr, &bgDescriptorPool_) != VK_SUCCESS) {
            return false;
        }

        VkDescriptorSetAllocateInfo allocInfo{};
        allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        allocInfo.descriptorPool = bgDescriptorPool_;
        allocInfo.descriptorSetCount = 1;
        allocInfo.pSetLayouts = &bgDescriptorSetLayout_;
        return vkAllocateDescriptorSets(device_, &allocInfo, &bgDescriptorSet_) == VK_SUCCESS;
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
            const uint8_t* pixels, uint32_t width, uint32_t height) {
        if (device_ == VK_NULL_HANDLE) return false;
        if (pixels == nullptr || width == 0 || height == 0) {
            return false;
        }

        destroyTextureLocked(texture);

        const uint32_t bpp = texture.bytesPerPixel;
        const VkDeviceSize imageSize = static_cast<VkDeviceSize>(width) * height * bpp;

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
        stagingAlloc.memoryTypeIndex = vkFindMemoryType(physicalDevice_, stagingReq.memoryTypeBits,
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
        std::memcpy(mapped, pixels, static_cast<size_t>(imageSize));
        vkUnmapMemory(device_, stagingMemory);

        VkImageCreateInfo imageInfo{};
        imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        imageInfo.imageType = VK_IMAGE_TYPE_2D;
        imageInfo.extent = {width, height, 1};
        imageInfo.mipLevels = 1;
        imageInfo.arrayLayers = 1;
        imageInfo.format = texture.format;
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
        imageAlloc.memoryTypeIndex = vkFindMemoryType(physicalDevice_, imageReq.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
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
        viewInfo.format = texture.format;
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
        const VkSamplerAddressMode addressMode = texture.repeat
                ? VK_SAMPLER_ADDRESS_MODE_REPEAT
                : VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        samplerInfo.addressModeU = addressMode;
        samplerInfo.addressModeV = addressMode;
        samplerInfo.addressModeW = addressMode;
        if (vkCreateSampler(device_, &samplerInfo, nullptr, &texture.sampler) != VK_SUCCESS) {
            return false;
        }

        VkDescriptorImageInfo imageDesc{};
        imageDesc.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        imageDesc.imageView = texture.imageView;
        imageDesc.sampler = texture.sampler;

        VkWriteDescriptorSet writes[2]{};
        uint32_t writeCount = 1;
        writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[0].dstSet = descriptorSet;
        writes[0].dstBinding = texture.descriptorBinding;
        writes[0].descriptorCount = 1;
        writes[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        writes[0].pImageInfo = &imageDesc;
        if (texture.extraSet != VK_NULL_HANDLE) {
            writes[1] = writes[0];
            writes[1].dstSet = texture.extraSet;
            writes[1].dstBinding = texture.extraBinding;
            writeCount = 2;
        }
        vkUpdateDescriptorSets(device_, writeCount, writes, 0, nullptr);

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
        const size_t pixelCountSize = static_cast<size_t>(expected);
        texture.pendingPixels.resize(pixelCountSize * 4u);

        // Convert ARGB -> RGBA in-place using shared helper, then copy bytes
        vkArgbToRgba(pixels, pixelCountSize);
        std::memcpy(texture.pendingPixels.data(), pixels, pixelCountSize * 4u);

        env->ReleaseIntArrayElements(argbPixels, pixels, JNI_ABORT);
    }

    void storePendingMaskLocked(JNIEnv* env, jbyteArray mask, jint width, jint height, TextureResource& texture) {
        if (mask == nullptr || width <= 0 || height <= 0) {
            return;
        }
        const jsize byteCount = env->GetArrayLength(mask);
        const int64_t expected = static_cast<int64_t>(width) * static_cast<int64_t>(height);
        if (byteCount <= 0 || expected > byteCount) {
            return;
        }

        jbyte* bytes = env->GetByteArrayElements(mask, nullptr);
        if (bytes == nullptr) {
            return;
        }

        texture.pendingWidth = static_cast<uint32_t>(width);
        texture.pendingHeight = static_cast<uint32_t>(height);
        const size_t pixelCountSize = static_cast<size_t>(expected);
        texture.pendingPixels.resize(pixelCountSize);
        std::memcpy(texture.pendingPixels.data(), bytes, pixelCountSize);

        env->ReleaseByteArrayElements(mask, bytes, JNI_ABORT);
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

    // ── Water mesh management ──

    bool uploadWaterMeshLocked(JNIEnv* env, jfloatArray waterVertices, jfloatArray waterTexCoords,
            jshortArray waterIndices, jint waterVertexCount, jint waterIndexCount) {
        /*
         * 设备没建起来时直接退出。Stack G11（Android 9 / SDK 28，STA 固件）就是这种情况：
         * 它 vkEnumeratePhysicalDevices 返回 0，日志里只有一行 "No Vulkan physical devices"。
         * 这条路径在 isReadyLocked() 之前跑，漏了这道判断就是 vkCreateBuffer(nullptr)
         * —— 表现是预览线程里 SIGSEGV，看着像帧数据的问题，其实是初始化失败没传出来。
         */
        if (device_ == VK_NULL_HANDLE) return false;

        // Full mesh upload only (positions + static texcoords + indices).
        // Per-frame texcoord upload removed — ripple computed in vertex shader.
        if (waterVertices == nullptr || waterTexCoords == nullptr || waterIndices == nullptr
                || waterVertexCount <= 0 || waterIndexCount <= 0) {
            return false;
        }

        const jsize verticesLen = env->GetArrayLength(waterVertices);
        const jsize texLen = env->GetArrayLength(waterTexCoords);
        const jsize idxLen = env->GetArrayLength(waterIndices);
        if (verticesLen < waterVertexCount * 3 || texLen < waterVertexCount * 2
                || idxLen < waterIndexCount) {
            return false;
        }

        if (!ensureWaterBuffersLocked(static_cast<size_t>(waterVertexCount),
                static_cast<size_t>(waterIndexCount))) {
            return false;
        }

        tempWaterPositions_.resize(static_cast<size_t>(waterVertexCount) * 3);
        tempWaterTexcoords_.resize(static_cast<size_t>(waterVertexCount) * 2);
        tempWaterIndices_.resize(static_cast<size_t>(waterIndexCount));
        env->GetFloatArrayRegion(waterVertices, 0, static_cast<jsize>(tempWaterPositions_.size()),
                tempWaterPositions_.data());
        env->GetFloatArrayRegion(waterTexCoords, 0, static_cast<jsize>(tempWaterTexcoords_.size()),
                tempWaterTexcoords_.data());
        env->GetShortArrayRegion(waterIndices, 0, static_cast<jsize>(tempWaterIndices_.size()),
                reinterpret_cast<jshort*>(tempWaterIndices_.data()));

        auto* mappedVertices = reinterpret_cast<WaterVertex*>(waterVertexMapped_);
        for (int i = 0; i < waterVertexCount; ++i) {
            const int p = i * 3;
            const int t = i * 2;
            mappedVertices[i].x = tempWaterPositions_[p];
            mappedVertices[i].y = tempWaterPositions_[p + 1];
            mappedVertices[i].z = tempWaterPositions_[p + 2];
            mappedVertices[i].u = tempWaterTexcoords_[t];
            mappedVertices[i].v = tempWaterTexcoords_[t + 1];
        }

        std::memcpy(waterIndexMapped_, tempWaterIndices_.data(),
                static_cast<size_t>(waterIndexCount) * sizeof(uint16_t));
        waterIndexCount_ = waterIndexCount;
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
        vbAlloc.memoryTypeIndex = vkFindMemoryType(physicalDevice_, vbReq.memoryTypeBits,
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
        ibAlloc.memoryTypeIndex = vkFindMemoryType(physicalDevice_, ibReq.memoryTypeBits,
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

    // ── Drop UBO (no hard limit, replaces push constants for ripple data) ──

    bool ensureDropUboLocked() {
        if (device_ == VK_NULL_HANDLE) return false;
        if (dropUbo_ != VK_NULL_HANDLE) return true;

        const VkDeviceSize uboSize = sizeof(DropUbo);
        VkBufferCreateInfo bufferInfo{};
        bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        bufferInfo.size = uboSize;
        bufferInfo.usage = VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
        bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        if (vkCreateBuffer(device_, &bufferInfo, nullptr, &dropUbo_) != VK_SUCCESS) {
            return false;
        }

        VkMemoryRequirements memReq{};
        vkGetBufferMemoryRequirements(device_, dropUbo_, &memReq);
        VkMemoryAllocateInfo allocInfo{};
        allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        allocInfo.allocationSize = memReq.size;
        allocInfo.memoryTypeIndex = vkFindMemoryType(physicalDevice_, memReq.memoryTypeBits,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        if (vkAllocateMemory(device_, &allocInfo, nullptr, &dropUboMemory_) != VK_SUCCESS) {
            vkDestroyBuffer(device_, dropUbo_, nullptr);
            dropUbo_ = VK_NULL_HANDLE;
            return false;
        }
        vkBindBufferMemory(device_, dropUbo_, dropUboMemory_, 0);
        if (vkMapMemory(device_, dropUboMemory_, 0, uboSize, 0, &dropUboMapped_) != VK_SUCCESS) {
            dropUboMapped_ = nullptr;
        }

        // Write descriptor set for binding 1 —— 水面（顶点+片元）与叶子（片元）共用这一个 UBO
        VkDescriptorBufferInfo descInfo{};
        descInfo.buffer = dropUbo_;
        descInfo.offset = 0;
        descInfo.range = uboSize;

        VkWriteDescriptorSet writes[2]{};
        writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[0].dstSet = bgDescriptorSet_;
        writes[0].dstBinding = 1;
        writes[0].descriptorCount = 1;
        writes[0].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
        writes[0].pBufferInfo = &descInfo;

        uint32_t writeCount = 1;
        if (leafDescriptorSet_ != VK_NULL_HANDLE) {
            writes[1] = writes[0];
            writes[1].dstSet = leafDescriptorSet_;
            writeCount = 2;
        }
        vkUpdateDescriptorSets(device_, writeCount, writes, 0, nullptr);

        return dropUboMapped_ != nullptr;
    }

    void destroyDropUboLocked() {
        if (dropUboMapped_ != nullptr && dropUboMemory_ != VK_NULL_HANDLE) {
            vkUnmapMemory(device_, dropUboMemory_);
            dropUboMapped_ = nullptr;
        }
        if (dropUbo_ != VK_NULL_HANDLE) {
            vkDestroyBuffer(device_, dropUbo_, nullptr);
            dropUbo_ = VK_NULL_HANDLE;
        }
        if (dropUboMemory_ != VK_NULL_HANDLE) {
            vkFreeMemory(device_, dropUboMemory_, nullptr);
            dropUboMemory_ = VK_NULL_HANDLE;
        }
    }

    // ── Leaf helpers ──

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
        buildLeafModel(leaf, xOffset, 0.0f, 0.0f, out);
    }

    /**
     * 影子与叶片共用同一套模型矩阵，只是整体平移 (dx, dy) —— 与 GLES 的
     * drawLeaf 一致：offset = altitude * 0.2，x 和 y 各减一份。
     */
    void buildLeafModel(const LeafDraw& leaf, float xOffset, float dx, float dy,
            float out[16]) const {
        const float drawX = leaf.x + dx - xOffset * 2.0f;
        const float drawY = leaf.y + dy;
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

    // ── Math helpers ──

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
        std::memset(out, 0, sizeof(float) * 16);
        out[0] = 1.0f;
        out[5] = -1.0f;
        out[10] = 0.5f;
        out[14] = 0.5f;
        out[15] = 1.0f;
    }

    // ── Command recording ──

    bool recordCommandBufferLocked(VkCommandBuffer commandBuffer, uint32_t imageIndex,
            const float projection[16], const float view[16], const std::vector<LeafDraw>& leaves,
            float xOffset, int waterIndexCount, const PushConstants& pc, const float* lighting) {
        VkCommandBufferBeginInfo beginInfo{};
        beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        if (vkBeginCommandBuffer(commandBuffer, &beginInfo) != VK_SUCCESS) {
            return false;
        }

        if (glowActive_) {
            recordGlowLocked(commandBuffer, imageIndex, projection, view, leaves, xOffset,
                    waterIndexCount, pc, lighting);
        } else {
            // 辉光关掉时走原来的单遍路径，画面与加这套之前逐位相同
            beginGlowPassLocked(commandBuffer, renderPass_,
                    swapchainFramebuffers_[imageIndex], swapchainExtent_);
            recordSceneDrawsLocked(commandBuffer, false, projection, view, leaves, xOffset,
                    waterIndexCount, pc, lighting);
            vkCmdEndRenderPass(commandBuffer);
        }
        return vkEndCommandBuffer(commandBuffer) == VK_SUCCESS;
    }

    /** 开始一遍全屏绘制并设好视口与裁剪区。 */
    static void beginGlowPassLocked(VkCommandBuffer commandBuffer, VkRenderPass pass,
            VkFramebuffer framebuffer, VkExtent2D extent) {
        VkClearValue clearValue{};
        clearValue.color = {{0.0f, 0.0f, 0.0f, 1.0f}};

        VkRenderPassBeginInfo rpInfo{};
        rpInfo.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
        rpInfo.renderPass = pass;
        rpInfo.framebuffer = framebuffer;
        rpInfo.renderArea.offset = {0, 0};
        rpInfo.renderArea.extent = extent;
        rpInfo.clearValueCount = 1;
        rpInfo.pClearValues = &clearValue;
        vkCmdBeginRenderPass(commandBuffer, &rpInfo, VK_SUBPASS_CONTENTS_INLINE);

        VkViewport viewport{};
        viewport.width = static_cast<float>(extent.width);
        viewport.height = static_cast<float>(extent.height);
        viewport.maxDepth = 1.0f;
        vkCmdSetViewport(commandBuffer, 0, 1, &viewport);

        VkRect2D scissor{};
        scissor.extent = extent;
        vkCmdSetScissor(commandBuffer, 0, 1, &scissor);
    }

    /**
     * 离屏图在"刚写完"与"马上要被采样"之间加一道依赖。
     *
     * <p>布局不变（两遍之间都是 SHADER_READ_ONLY），要的只是可见性 ——
     * 少了这道屏障，手机上多半看不出问题，换台机器就是辉光偶尔缺一块。
     */
    static void glowReadBarrierLocked(VkCommandBuffer commandBuffer, VkImage image) {
        VkImageMemoryBarrier barrier{};
        barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        barrier.oldLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        barrier.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.image = image;
        barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        barrier.subresourceRange.levelCount = 1;
        barrier.subresourceRange.layerCount = 1;
        barrier.srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        vkCmdPipelineBarrier(commandBuffer,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                0, 0, nullptr, 0, nullptr, 1, &barrier);
    }

    /**
     * 辉光的五遍：场景 → 亮部提取 → 横向模糊 → 纵向模糊 → 合成到交换链。
     *
     * <p>与 GLES 的 {@code GlowRenderer.endScene} 一一对应；模糊步长取半径的 1/4，
     * 5 抽头对着 ±1.38 / ±3.23 步采样，等效 9 抽头 —— 都是照搬那边的取值。
     */
    void recordGlowLocked(VkCommandBuffer commandBuffer, uint32_t imageIndex,
            const float projection[16], const float view[16], const std::vector<LeafDraw>& leaves,
            float xOffset, int waterIndexCount, const PushConstants& pc, const float* lighting) {
        const VkExtent2D sceneExtent = {sceneWidth_, sceneHeight_};
        const VkExtent2D bloomExtent = {bloomWidth_, bloomHeight_};

        beginGlowPassLocked(commandBuffer, scenePass_, sceneFramebuffer_, sceneExtent);
        recordSceneDrawsLocked(commandBuffer, true, projection, view, leaves, xOffset,
                waterIndexCount, pc, lighting);
        vkCmdEndRenderPass(commandBuffer);
        glowReadBarrierLocked(commandBuffer, sceneImage_);

        beginGlowPassLocked(commandBuffer, bloomPass_, bloomFramebufferA_, bloomExtent);
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, brightPipeline_);
        vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
                brightLayout_, 0, 1, &brightSet_, 0, nullptr);
        GlowBrightPush brightPush{};
        brightPush.texelX = 1.0f / static_cast<float>(sceneWidth_);
        brightPush.texelY = 1.0f / static_cast<float>(sceneHeight_);
        brightPush.threshold = glowThreshold_;
        brightPush.knee = glowSoftKnee_;
        vkCmdPushConstants(commandBuffer, brightLayout_, VK_SHADER_STAGE_FRAGMENT_BIT,
                0, sizeof(brightPush), &brightPush);
        vkCmdDraw(commandBuffer, 4, 1, 0, 0);
        vkCmdEndRenderPass(commandBuffer);
        glowReadBarrierLocked(commandBuffer, bloomImageA_);

        // 横向 → B
        beginGlowPassLocked(commandBuffer, bloomPass_, bloomFramebufferB_, bloomExtent);
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, blurPipeline_);
        vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
                blurLayout_, 0, 1, &blurSetA_, 0, nullptr);
        GlowBlurPush blurPush{};
        blurPush.stepX = glowRadius_ * 0.25f / static_cast<float>(std::max(1u, bloomWidth_));
        blurPush.stepY = 0.0f;
        vkCmdPushConstants(commandBuffer, blurLayout_, VK_SHADER_STAGE_FRAGMENT_BIT,
                0, sizeof(blurPush), &blurPush);
        vkCmdDraw(commandBuffer, 4, 1, 0, 0);
        vkCmdEndRenderPass(commandBuffer);
        glowReadBarrierLocked(commandBuffer, bloomImageB_);

        // 纵向 → A
        beginGlowPassLocked(commandBuffer, bloomPass_, bloomFramebufferA_, bloomExtent);
        vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
                blurLayout_, 0, 1, &blurSetB_, 0, nullptr);
        blurPush.stepX = 0.0f;
        blurPush.stepY = glowRadius_ * 0.25f / static_cast<float>(std::max(1u, bloomHeight_));
        vkCmdPushConstants(commandBuffer, blurLayout_, VK_SHADER_STAGE_FRAGMENT_BIT,
                0, sizeof(blurPush), &blurPush);
        vkCmdDraw(commandBuffer, 4, 1, 0, 0);
        vkCmdEndRenderPass(commandBuffer);
        glowReadBarrierLocked(commandBuffer, bloomImageA_);

        // 合成 → 交换链
        beginGlowPassLocked(commandBuffer, renderPass_,
                swapchainFramebuffers_[imageIndex], swapchainExtent_);
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, compositePipeline_);
        vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
                compositeLayout_, 0, 1, &compositeSet_, 0, nullptr);
        GlowCompositePush compositePush{};
        compositePush.strength = glowStrength_;
        vkCmdPushConstants(commandBuffer, compositeLayout_, VK_SHADER_STAGE_FRAGMENT_BIT,
                0, sizeof(compositePush), &compositePush);
        vkCmdDraw(commandBuffer, 4, 1, 0, 0);
        vkCmdEndRenderPass(commandBuffer);
    }

    /** 场景本身（水面 + 落叶）。render pass 的开始与结束由调用方管。 */
    void recordSceneDrawsLocked(VkCommandBuffer commandBuffer, bool glowPass,
            const float projection[16], const float view[16], const std::vector<LeafDraw>& leaves,
            float xOffset, int waterIndexCount, const PushConstants& pc, const float* lighting) {
        // 离屏那一遍的 render pass 附件是 RGBA16F，管线必须与它相容，不能共用交换链那对
        VkPipeline bgPipeline = glowPass ? bgPipelineScene_ : bgPipeline_;
        VkPipeline leafPipeline = glowPass ? leafPipelineScene_ : leafPipeline_;
        float pv[16];
        multiplyMat4(projection, view, pv);

        float clipCorrection[16];
        getVulkanClipCorrection(clipCorrection);

        float pvCorrected[16];
        multiplyMat4(clipCorrection, pv, pvCorrected);

        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, bgPipeline);
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
        // Copy ripple scene params from scene (drops come from UBO binding 1)
        waterPush.glHeight = pc.glHeight;
        waterPush.bgScale = pc.bgScale;
        waterPush.meshScaleX = pc.meshScaleX;
        waterPush.meshScaleY = pc.meshScaleY;
        waterPush.dxMul = pc.dxMul;
        waterPush.xOffset = pc.xOffset;
        waterPush.rotate = pc.rotate;
        // 水面的三个星空参数
        waterPush.lighting[0] = lighting != nullptr ? lighting[0] : 0.0f;
        waterPush.lighting[1] = lighting != nullptr ? lighting[1] : 0.0f;
        waterPush.lighting[2] = lighting != nullptr ? lighting[2] : 0.0f;
        waterPush.lighting[3] = 0.0f;
        waterPush.lighting[4] = 0.0f;
        vkCmdPushConstants(commandBuffer, bgPipelineLayout_,
            VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
            0, sizeof(PushConstants), &waterPush);
        vkCmdDrawIndexed(commandBuffer, static_cast<uint32_t>(std::max(0, waterIndexCount)), 1, 0, 0, 0);

        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, leafPipeline);
        vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
                leafPipelineLayout_, 0, 1, &leafDescriptorSet_, 0, nullptr);

        for (const LeafDraw& leaf : leaves) {
            float alpha = computeLeafAlpha(leaf.altitude);
            if (alpha <= 0.001f) {
                continue;
            }

            const int frameCount = std::max(1, leafAtlasFrameCount_);
            const int frameIndex = ((leaf.textureIndex % frameCount) + frameCount) % frameCount;

            // 影子：只在离地时出现，算法与 GLES 的 drawLeaf 逐字一致 ——
            // 越高越淡（0.4 以上线性消失），基础不透明度 0.15，位置斜下方偏移 altitude*0.2。
            if (leaf.altitude > 0.0f) {
                float shadowAlpha = 1.0f;
                if (leaf.altitude >= 0.4f) {
                    shadowAlpha = 1.0f - (leaf.altitude - 0.4f) / 0.1f;
                }
                shadowAlpha = std::max(0.0f, std::min(1.0f, shadowAlpha)) * 0.15f;
                const float offset = leaf.altitude * 0.2f;

                float shadowModel[16];
                buildLeafModel(leaf, xOffset, -offset, -offset, shadowModel);

                PushConstants shadowPush{};
                multiplyMat4(pvCorrected, shadowModel, shadowPush.mvp);
                shadowPush.alpha = shadowAlpha;
                shadowPush.leafFrameIndex = static_cast<float>(frameIndex);
                shadowPush.leafFrameInvCount = 1.0f / static_cast<float>(frameCount);
                shadowPush.shadow = 1.0f;
                shadowPush.glHeight = pc.glHeight;
                shadowPush.bgScale = pc.bgScale;
                shadowPush.meshScaleX = pc.meshScaleX;
                shadowPush.meshScaleY = pc.meshScaleY;
                shadowPush.dxMul = pc.dxMul;
                shadowPush.xOffset = pc.xOffset;
                shadowPush.rotate = pc.rotate;
                if (lighting != nullptr) {
                    for (int i = 0; i < 5; i++) shadowPush.lighting[i] = lighting[3 + i];
                }

                vkCmdPushConstants(commandBuffer, leafPipelineLayout_,
                        VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                        0, sizeof(PushConstants), &shadowPush);
                vkCmdDraw(commandBuffer, 4, 1, 0, 0);
            }

            float model[16];
            buildLeafModel(leaf, xOffset, model);

            PushConstants push{};
            multiplyMat4(pvCorrected, model, push.mvp);
            push.alpha = alpha;
            push.leafFrameIndex = static_cast<float>(frameIndex);
            push.leafFrameInvCount = 1.0f / static_cast<float>(frameCount);
            push.shadow = 0.0f;
            /*
             * 蓝藻受光要逐像素知道"这个像素在世界里的位置"。这六个槽在水面着色器里
             * 是涟漪参数，叶子用不到，于是挪来放叶片中心与两个半轴（含缩放与旋转）——
             * 与 GLES 的 drawLeafQuad 同一套算式。影子不发光，那六个槽留空。
             */
            {
                const float drawX = leaf.x - xOffset * 2.0f;
                const float half = 0.55f * leaf.scale;
                const float leafAngle = leaf.angleDeg * 0.01745329252f;
                const float leafCos = std::cos(leafAngle);
                const float leafSin = std::sin(leafAngle);
                push.glHeight = drawX;              // 中心 x
                push.bgScale = leaf.y;              // 中心 y
                push.meshScaleX = leafCos * half;   // axisX.x
                push.meshScaleY = leafSin * half;   // axisX.y
                push.dxMul = -leafSin * half;       // axisY.x
                push.xOffset = leafCos * half;      // axisY.y
            }
            if (lighting != nullptr) {
                for (int i = 0; i < 5; i++) push.lighting[i] = lighting[3 + i];
            }

            vkCmdPushConstants(commandBuffer, leafPipelineLayout_,
                    VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                    0, sizeof(PushConstants), &push);
            vkCmdDraw(commandBuffer, 4, 1, 0, 0);
        }
    }

    // ── 辉光状态 ──

    bool glowFormatSupported_ = false;  // 设备能不能拿 RGBA16F 当颜色附件并线性采样
    bool glowRequested_ = false;        // 设置里的开关
    bool glowActive_ = false;           // 本帧真的走离屏路径
    bool glowReady_ = false;            // 离屏资源建起来了
    float glowThreshold_ = 0.80f;
    float glowSoftKnee_ = 0.25f;
    float glowRadius_ = 9.0f;
    float glowStrength_ = 0.70f;

    VkImage sceneImage_ = VK_NULL_HANDLE;
    VkDeviceMemory sceneMemory_ = VK_NULL_HANDLE;
    VkImageView sceneView_ = VK_NULL_HANDLE;
    VkImage bloomImageA_ = VK_NULL_HANDLE;
    VkDeviceMemory bloomMemoryA_ = VK_NULL_HANDLE;
    VkImageView bloomViewA_ = VK_NULL_HANDLE;
    VkImage bloomImageB_ = VK_NULL_HANDLE;
    VkDeviceMemory bloomMemoryB_ = VK_NULL_HANDLE;
    VkImageView bloomViewB_ = VK_NULL_HANDLE;
    VkRenderPass scenePass_ = VK_NULL_HANDLE;
    VkRenderPass bloomPass_ = VK_NULL_HANDLE;
    VkFramebuffer sceneFramebuffer_ = VK_NULL_HANDLE;
    VkFramebuffer bloomFramebufferA_ = VK_NULL_HANDLE;
    VkFramebuffer bloomFramebufferB_ = VK_NULL_HANDLE;
    VkSampler glowSampler_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout glowOneSetLayout_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout glowTwoSetLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool glowPool_ = VK_NULL_HANDLE;
    VkDescriptorSet brightSet_ = VK_NULL_HANDLE;
    VkDescriptorSet blurSetA_ = VK_NULL_HANDLE;
    VkDescriptorSet blurSetB_ = VK_NULL_HANDLE;
    VkDescriptorSet compositeSet_ = VK_NULL_HANDLE;
    VkPipelineLayout brightLayout_ = VK_NULL_HANDLE;
    VkPipelineLayout blurLayout_ = VK_NULL_HANDLE;
    VkPipelineLayout compositeLayout_ = VK_NULL_HANDLE;
    VkPipeline brightPipeline_ = VK_NULL_HANDLE;
    VkPipeline blurPipeline_ = VK_NULL_HANDLE;
    VkPipeline compositePipeline_ = VK_NULL_HANDLE;
    /** 与 scenePass_（RGBA16F）相容的那一对场景管线。 */
    VkPipeline bgPipelineScene_ = VK_NULL_HANDLE;
    VkPipeline leafPipelineScene_ = VK_NULL_HANDLE;
    uint32_t sceneWidth_ = 0;
    uint32_t sceneHeight_ = 0;
    uint32_t bloomWidth_ = 0;
    uint32_t bloomHeight_ = 0;

    // ── Members ──

    AAssetManager* assetManager_ = nullptr;
    std::mutex mutex_;
    int width_ = 0;
    int height_ = 0;
    ANativeWindow* window_ = nullptr;

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

    TextureResource bgTexture_;    // 河床遮罩（R8，binding 0）
    TextureResource skyTexture_;   // 天空色带（RGBA8，binding 2）
    TextureResource leafTexture_;
    TextureResource algaeTexture_; // 蓝藻噪声（R8，水面 binding 3 / 叶子 binding 2）

    VkBuffer waterVertexBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory waterVertexMemory_ = VK_NULL_HANDLE;
    void* waterVertexMapped_ = nullptr;
    size_t waterVertexCapacity_ = 0;

    VkBuffer waterIndexBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory waterIndexMemory_ = VK_NULL_HANDLE;
    void* waterIndexMapped_ = nullptr;
    size_t waterIndexCapacity_ = 0;
    int waterIndexCount_ = 0;

    // Drop UBO (no hard limit — replaces push constants for ripple data)
    VkBuffer dropUbo_ = VK_NULL_HANDLE;
    VkDeviceMemory dropUboMemory_ = VK_NULL_HANDLE;
    void* dropUboMapped_ = nullptr;

    int leafAtlasFrameCount_ = 1;

    std::vector<float> tempLeafRaw_;
    std::vector<float> tempWaterPositions_;
    std::vector<float> tempWaterTexcoords_;
    std::vector<uint16_t> tempWaterIndices_;
};

static template <typename T>
FallVkRenderer* asRenderer(T handle) {
    return reinterpret_cast<FallVkRenderer*>(handle);
}

}  // namespace

static jlong JNICALL nCreateRenderer(
        JNIEnv* env, jclass, jobject assetManager) {
    auto* renderer = new FallVkRenderer(AAssetManager_fromJava(env, assetManager));
    return reinterpret_cast<jlong>(renderer);
}

static void JNICALL nDestroyRenderer(
        JNIEnv*, jclass, jlong handle) {
    delete asRenderer(handle);
}

static jboolean JNICALL nOnSurfaceCreated(
        JNIEnv* env, jclass, jlong handle, jobject surface, jint width, jint height) {
    auto* renderer = asRenderer(handle);
    return renderer != nullptr && renderer->createOrUpdateSurface(env, surface, width, height);
}

static void JNICALL nOnSurfaceChanged(
        JNIEnv* env, jclass, jlong handle, jobject surface, jint width, jint height) {
    auto* renderer = asRenderer(handle);
    if (renderer != nullptr) {
        renderer->createOrUpdateSurface(env, surface, width, height);
    }
}

static void JNICALL nOnSurfaceDestroyed(
        JNIEnv*, jclass, jlong handle) {
    auto* renderer = asRenderer(handle);
    if (renderer != nullptr) {
        renderer->destroySurface();
    }
}

static void JNICALL nRenderFrame(
        JNIEnv* env, jclass, jlong handle, jfloatArray lightingArray, jfloatArray algaeParamsArray,
        jfloatArray glowParamsArray, jboolean glowEnabled,
        jfloatArray projectionMatrix, jfloatArray viewMatrix,
        jfloatArray leavesData, jint leafCount, jfloat xOffset,
        jfloatArray waterVertices, jfloatArray waterTexCoords, jshortArray waterIndices,
        jint waterVertexCount, jint waterIndexCount,
        jfloatArray dropData, jfloatArray algaePower, jfloatArray algaeWorld, jint dropCount,
        jfloat glHeight, jfloat bgScale, jfloat meshScaleX, jfloat meshScaleY,
        jfloat dxMul, jint rotate) {
    auto* renderer = asRenderer(handle);
    if (renderer != nullptr) {
        float lighting[12]{};
        if (lightingArray != nullptr) {
            env->GetFloatArrayRegion(lightingArray, 0, 12, lighting);
        }
        float algaeParams[22]{};
        if (algaeParamsArray != nullptr) {
            env->GetFloatArrayRegion(algaeParamsArray, 0, 22, algaeParams);
        }
        float glowParams[12]{};
        if (glowParamsArray != nullptr) {
            env->GetFloatArrayRegion(glowParamsArray, 0, 12, glowParams);
        }
        renderer->render(env, lighting, algaeParams, glowParams, glowEnabled == JNI_TRUE,
                projectionMatrix, viewMatrix, leavesData, leafCount, xOffset,
                waterVertices, waterTexCoords, waterIndices, waterVertexCount, waterIndexCount,
                dropData, algaePower, algaeWorld, dropCount,
                glHeight, bgScale, meshScaleX, meshScaleY, dxMul, rotate);
    }
}

static void JNICALL nSetMaskTexture(
        JNIEnv* env, jclass, jlong handle, jbyteArray mask, jint width, jint height) {
    auto* renderer = asRenderer(handle);
    if (renderer != nullptr) {
        renderer->setMaskTexture(env, mask, width, height);
    }
}

static void JNICALL nSetSkyTexture(
        JNIEnv* env, jclass, jlong handle, jintArray argbPixels, jint width, jint height) {
    auto* renderer = asRenderer(handle);
    if (renderer != nullptr) {
        renderer->setSkyTexture(env, argbPixels, width, height);
    }
}

static void JNICALL nSetAlgaeTexture(
        JNIEnv* env, jclass, jlong handle, jbyteArray pixels, jint width, jint height) {
    auto* renderer = asRenderer(handle);
    if (renderer != nullptr) {
        renderer->setAlgaeTexture(env, pixels, width, height);
    }
}

static void JNICALL nSetLeafTexture(
        JNIEnv* env, jclass, jlong handle, jintArray argbPixels, jint width, jint height) {
    auto* renderer = asRenderer(handle);
    if (renderer != nullptr) {
        renderer->setLeafTexture(env, argbPixels, width, height);
    }
}

static void JNICALL nSetLeafAtlasFrameCount(
        JNIEnv*, jclass, jlong handle, jint frameCount) {
    auto* renderer = asRenderer(handle);
    if (renderer != nullptr) {
        renderer->setLeafAtlasFrameCount(frameCount);
    }
}

static jboolean JNICALL nIsVulkanSupported(
        JNIEnv*, jclass) {
    return vkIsVulkanSupported();
}

// ---- JNI 注册表 ----
// 原来这里是手写的 Java_com_... stub（四个壁纸四份，只差类名）。现在只是静态函数 +
// 一张表，由 jni_bridge.cpp 的 JNI_OnLoad 统一注册；签名由 Java 侧的 native 声明反推。
static const JNINativeMethod kMethods[] = {
    {"nCreateRenderer", "(Landroid/content/res/AssetManager;)J", reinterpret_cast<void*>(nCreateRenderer)},
    {"nDestroyRenderer", "(J)V", reinterpret_cast<void*>(nDestroyRenderer)},
    {"nOnSurfaceCreated", "(JLandroid/view/Surface;II)Z", reinterpret_cast<void*>(nOnSurfaceCreated)},
    {"nOnSurfaceChanged", "(JLandroid/view/Surface;II)V", reinterpret_cast<void*>(nOnSurfaceChanged)},
    {"nOnSurfaceDestroyed", "(J)V", reinterpret_cast<void*>(nOnSurfaceDestroyed)},
    {"nRenderFrame", "(J[F[F[FZ[F[F[FIF[F[F[SII[F[F[FIFFFFFI)V", reinterpret_cast<void*>(nRenderFrame)},
    {"nSetMaskTexture", "(J[BII)V", reinterpret_cast<void*>(nSetMaskTexture)},
    {"nSetSkyTexture", "(J[III)V", reinterpret_cast<void*>(nSetSkyTexture)},
    {"nSetAlgaeTexture", "(J[BII)V", reinterpret_cast<void*>(nSetAlgaeTexture)},
    {"nSetLeafTexture", "(J[III)V", reinterpret_cast<void*>(nSetLeafTexture)},
    {"nSetLeafAtlasFrameCount", "(JI)V", reinterpret_cast<void*>(nSetLeafAtlasFrameCount)},
    {"nIsVulkanSupported", "()Z", reinterpret_cast<void*>(nIsVulkanSupported)},
};

bool registerFallVk(JNIEnv* env) {
    jclass cls = env->FindClass("com/reandroid/wallpaper/fall/FallVKNative");
    if (cls == nullptr) return false;
    const int count = sizeof(kMethods) / sizeof(kMethods[0]);
    return env->RegisterNatives(cls, kMethods, count) == JNI_OK;
}
