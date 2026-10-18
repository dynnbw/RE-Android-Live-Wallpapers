#pragma once

#ifndef LOG_TAG
#define LOG_TAG "VkPass"
#endif

#include "vk_common.h"

#include <map>
#include <unordered_map>
#include <utility>

/*
 * 通用 Vulkan pass 渲染器。
 *
 * 原来每个壁纸的 *vk_jni.cpp 都各写一遍帧循环、纹理上传、pipeline/descriptor 创建与
 * 命令录制。这里把这些收进一份实现，壁纸只声明“几个 pass + 顶点布局 + 每帧数据”。
 *
 * 第一版只画到屏幕（Task 1～4）；离屏目标在后续任务里加。
 */

struct VkPassDesc {
    std::string vsPath;
    std::string fsPath;
    std::vector<uint32_t> attribSizes; // location 0..n-1 的分量数（float）
    uint32_t strideBytes = 0;
    bool triangleStrip = true;
    /** 0 = strip, 1 = list, 2 = point list（与 Java 的 topology 常量一致）。 */
    uint32_t primitive = 0;
    bool blendEnable = false;
    uint32_t srcColor = 0; // VkBlendFactor
    uint32_t dstColor = 0;
    uint32_t srcAlpha = 0;
    uint32_t dstAlpha = 0;
    uint32_t pushConstantBytes = 0; // <= 128
    std::string bufferName;
    uint32_t vertexCount = 0;
    bool clear = true;
    float clearColor[4] = {0.0f, 0.0f, 0.0f, 0.0f};
    std::vector<std::pair<uint32_t, std::string>> textures; // binding -> texture name
};

class VkPassRenderer : public VkRendererBase<VkPassRenderer> {
public:
    explicit VkPassRenderer(AAssetManager* assetManager) : assetManager_(assetManager) {}
    ~VkPassRenderer() override = default;

    int addPass(VkPassDesc desc);
    void setClearColor(float r, float g, float b, float a);
    void createBuffer(const std::string& name, uint32_t strideBytes,
                      uint32_t capacityVertices, bool dynamic);
    void createTexture(const std::string& name);
    void bindTexture(int passIndex, uint32_t binding, const std::string& name);
    void setTexturePixels(const std::string& name, const int32_t* argb,
                          uint32_t width, uint32_t height);
    void uploadBuffer(const std::string& name, const void* data, size_t bytes);
    void setPushConstants(int passIndex, const float* values, size_t floatCount);
    void setVertexCount(int passIndex, uint32_t vertexCount);

    void drawFrame();

    bool createOrUpdateSurface(JNIEnv* env, jobject surface, int width, int height);
    void onSurfaceDestroyed();
    void destroy();

    // ── VkRendererBase CRTP hooks（基类要求 public）──
    AAssetManager* getAssetManager() { return assetManager_; }
    bool onDeviceCreated();
    bool createPipelinesLocked();
    bool onSwapchainCreated();
    bool isSceneReadyLocked() const;
    void destroyTexturesLocked();
    void destroyPipelinesLocked();
    void recordCommandBuffersLocked(int /*w*/, int /*h*/) {}

private:
    struct BufferEntry {
        VkBuffer buffer = VK_NULL_HANDLE;
        VkDeviceMemory memory = VK_NULL_HANDLE;
        VkDeviceSize size = 0;
        uint32_t strideBytes = 0;
        uint32_t capacityVertices = 0;
        bool dynamic = false;
        void* mapped = nullptr;
    };

    struct TextureEntry {
        VkImage image = VK_NULL_HANDLE;
        VkDeviceMemory memory = VK_NULL_HANDLE;
        VkImageView view = VK_NULL_HANDLE;
        VkSampler sampler = VK_NULL_HANDLE;
        // 上传成功后**不清空**：surface 销毁时纹理要能重新上传
        std::vector<uint32_t> pending;
        uint32_t pendingWidth = 0;
        uint32_t pendingHeight = 0;
    };

    struct PassRuntime {
        VkPipeline pipeline = VK_NULL_HANDLE;
        VkPipelineLayout layout = VK_NULL_HANDLE;
        VkDescriptorSet descriptors = VK_NULL_HANDLE;
        std::vector<float> push;
    };

    bool createBufferLocked(const std::string& name, BufferEntry& entry);
    void destroyBufferLocked(BufferEntry& entry);
    bool uploadPendingTexturesLocked();
    void refreshDescriptorsLocked();
    void recordFrameLocked(VkCommandBuffer cmd, uint32_t imageIndex);

    static constexpr uint32_t kMaxTextures = 8;

    AAssetManager* assetManager_ = nullptr;
    std::mutex mutex_;
    std::vector<VkPassDesc> passes_;
    std::vector<PassRuntime> runtime_;
    std::unordered_map<std::string, BufferEntry> buffers_;
    std::unordered_map<std::string, TextureEntry> textures_;
    VkDescriptorSetLayout descriptorSetLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool_ = VK_NULL_HANDLE;
    // 同一壁纸的很多 pass 共用 shader/布局（例如每个精灵一个 pass），按描述去重
    std::map<std::string, VkPipeline> pipelineCache_;
    std::map<uint32_t, VkPipelineLayout> layoutCache_;
    ANativeWindow* window_ = nullptr;
    int width_ = 0;
    int height_ = 0;
    float clearColor_[4] = {0.0f, 0.0f, 0.0f, 1.0f};
};
