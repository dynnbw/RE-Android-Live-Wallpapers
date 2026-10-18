#include "vk_pass_renderer.h"

#include <cstdio>

// ─────────────────────────── helpers ───────────────────────────

static VkFormat formatForComponents(uint32_t count) {
    switch (count) {
        case 1: return VK_FORMAT_R32_SFLOAT;
        case 2: return VK_FORMAT_R32G32_SFLOAT;
        case 3: return VK_FORMAT_R32G32B32_SFLOAT;
        default: return VK_FORMAT_R32G32B32A32_SFLOAT;
    }
}

static std::string jstringToStd(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string out(chars != nullptr ? chars : "");
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return out;
}

static VkPassRenderer* asRenderer(jlong handle) {
    return reinterpret_cast<VkPassRenderer*>(handle);
}

// ─────────────────────────── scene building ───────────────────────────

int VkPassRenderer::addPass(VkPassDesc desc) {
    std::lock_guard<std::mutex> lock(mutex_);
    passes_.push_back(std::move(desc));
    return static_cast<int>(passes_.size()) - 1;
}

void VkPassRenderer::setClearColor(float r, float g, float b, float a) {
    std::lock_guard<std::mutex> lock(mutex_);
    clearColor_[0] = r;
    clearColor_[1] = g;
    clearColor_[2] = b;
    clearColor_[3] = a;
}

void VkPassRenderer::createBuffer(const std::string& name, uint32_t strideBytes,
                                  uint32_t capacityVertices, bool dynamic) {
    std::lock_guard<std::mutex> lock(mutex_);
    BufferEntry entry;
    entry.strideBytes = strideBytes;
    entry.capacityVertices = capacityVertices;
    entry.size = static_cast<VkDeviceSize>(strideBytes) * capacityVertices;
    entry.dynamic = dynamic;
    bool ok = true;
    if (device_ != VK_NULL_HANDLE) {
        ok = createBufferLocked(name, entry);
    }
    if (ok) buffers_[name] = entry;
}

void VkPassRenderer::createTexture(const std::string& name) {
    std::lock_guard<std::mutex> lock(mutex_);
    textures_[name]; // 建一个空条目，像素后到
}

void VkPassRenderer::bindTexture(int passIndex, uint32_t binding,
                                 const std::string& name) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (passIndex < 0 || static_cast<size_t>(passIndex) >= passes_.size()) return;
    passes_[passIndex].textures.emplace_back(binding, name);
    if (device_ != VK_NULL_HANDLE) refreshDescriptorsLocked();
}

void VkPassRenderer::setTexturePixels(const std::string& name, const int32_t* argb,
                                      uint32_t width, uint32_t height) {
    if (argb == nullptr || width == 0 || height == 0) return;
    std::lock_guard<std::mutex> lock(mutex_);
    TextureEntry& tex = textures_[name];
    const size_t count = static_cast<size_t>(width) * height;
    tex.pending.resize(count);
    std::memcpy(tex.pending.data(), argb, count * sizeof(uint32_t));
    vkArgbToRgba(reinterpret_cast<int32_t*>(tex.pending.data()), count);
    tex.pendingWidth = width;
    tex.pendingHeight = height;

    if (device_ != VK_NULL_HANDLE && commandPool_ != VK_NULL_HANDLE) {
        uploadPendingTexturesLocked();
        refreshDescriptorsLocked();
    }
}

void VkPassRenderer::uploadBuffer(const std::string& name, const void* data, size_t bytes) {
    if (data == nullptr) return;
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = buffers_.find(name);
    if (it == buffers_.end() || it->second.mapped == nullptr) return;
    const size_t copy = std::min(bytes, static_cast<size_t>(it->second.size));
    std::memcpy(it->second.mapped, data, copy);
}

void VkPassRenderer::setPushConstants(int passIndex, const float* values, size_t floatCount) {
    if (values == nullptr || passIndex < 0) return;
    std::lock_guard<std::mutex> lock(mutex_);
    if (static_cast<size_t>(passIndex) >= runtime_.size()) return;
    std::vector<float>& dst = runtime_[passIndex].push;
    dst.assign(values, values + floatCount);
}

void VkPassRenderer::setVertexCount(int passIndex, uint32_t vertexCount) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (passIndex < 0 || static_cast<size_t>(passIndex) >= passes_.size()) return;
    passes_[passIndex].vertexCount = vertexCount;
}

// ─────────────────────────── CRTP hooks ───────────────────────────

bool VkPassRenderer::onDeviceCreated() {
    // descriptor 资源可能在 surface 重建时被销毁过，这里与 createPipelinesLocked 共用
    if (descriptorSetLayout_ == VK_NULL_HANDLE) {
        VkDescriptorSetLayoutBinding bindings[kMaxTextures]{};
        for (uint32_t i = 0; i < kMaxTextures; ++i) {
            bindings[i].binding = i;
            bindings[i].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            bindings[i].descriptorCount = 1;
            bindings[i].stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
        }
        VkDescriptorSetLayoutCreateInfo li{};
        li.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        li.bindingCount = kMaxTextures;
        li.pBindings = bindings;
        if (vkCreateDescriptorSetLayout(device_, &li, nullptr, &descriptorSetLayout_) != VK_SUCCESS) {
            LOGE("vkCreateDescriptorSetLayout failed");
            return false;
        }
    }
    if (descriptorPool_ == VK_NULL_HANDLE) {
        VkDescriptorPoolSize size{};
        size.type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        // 每个 set 按布局占用 kMaxTextures 个 sampler；池子要覆盖 maxSets 份
        size.descriptorCount = kMaxTextures * 64;
        VkDescriptorPoolCreateInfo pi{};
        pi.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
        pi.maxSets = 64;
        pi.poolSizeCount = 1;
        pi.pPoolSizes = &size;
        if (vkCreateDescriptorPool(device_, &pi, nullptr, &descriptorPool_) != VK_SUCCESS) {
            LOGE("vkCreateDescriptorPool failed");
            return false;
        }
    }

    for (auto& entry : buffers_) {
        if (entry.second.buffer == VK_NULL_HANDLE) {
            if (!createBufferLocked(entry.first, entry.second)) return false;
        }
    }

    if (commandPool_ != VK_NULL_HANDLE) {
        uploadPendingTexturesLocked();
    }
    return true;
}

bool VkPassRenderer::createPipelinesLocked() {
    if (device_ == VK_NULL_HANDLE || renderPass_ == VK_NULL_HANDLE) return false;
    if (descriptorSetLayout_ == VK_NULL_HANDLE || descriptorPool_ == VK_NULL_HANDLE) {
        LOGE("descriptor resources missing");
        return false;
    }

    vkResetDescriptorPool(device_, descriptorPool_, 0);
    runtime_.clear();
    runtime_.resize(passes_.size());

    for (size_t i = 0; i < passes_.size(); ++i) {
        const VkPassDesc& pass = passes_[i];
        PassRuntime& rt = runtime_[i];

        // 去重键：shader + 顶点布局 + 图元 + 混合 + push constant 大小
        std::string key = pass.vsPath + "|" + pass.fsPath + "|";
        for (uint32_t size : pass.attribSizes) key += std::to_string(size) + ",";
        key += std::to_string(pass.strideBytes) + "|" + std::to_string(pass.primitive) + "|"
             + std::to_string(pass.blendEnable ? 1 : 0) + ":"
             + std::to_string(pass.srcColor) + "," + std::to_string(pass.dstColor) + ","
             + std::to_string(pass.srcAlpha) + "," + std::to_string(pass.dstAlpha) + "|"
             + std::to_string(pass.pushConstantBytes);

        rt.layout = layoutCache_[pass.pushConstantBytes];
        auto cachedPipeline = pipelineCache_.find(key);
        if (cachedPipeline != pipelineCache_.end()) {
            rt.pipeline = cachedPipeline->second;
            VkDescriptorSetAllocateInfo alloc{};
            alloc.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
            alloc.descriptorPool = descriptorPool_;
            alloc.descriptorSetCount = 1;
            alloc.pSetLayouts = &descriptorSetLayout_;
            if (vkAllocateDescriptorSets(device_, &alloc, &rt.descriptors) != VK_SUCCESS) {
                LOGE("vkAllocateDescriptorSets failed");
            }
            continue;
        }

        VkShaderModule vs = createShaderModuleLocked({pass.vsPath.c_str()});
        VkShaderModule fs = createShaderModuleLocked({pass.fsPath.c_str()});
        if (vs == VK_NULL_HANDLE || fs == VK_NULL_HANDLE) {
            if (vs) vkDestroyShaderModule(device_, vs, nullptr);
            if (fs) vkDestroyShaderModule(device_, fs, nullptr);
            LOGE("missing shader %s / %s", pass.vsPath.c_str(), pass.fsPath.c_str());
            continue;
        }

        VkPipelineShaderStageCreateInfo stages[2]{};
        stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
        stages[0].module = vs;
        stages[0].pName = "main";
        stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
        stages[1].module = fs;
        stages[1].pName = "main";

        VkVertexInputBindingDescription binding{};
        binding.binding = 0;
        binding.stride = pass.strideBytes;
        binding.inputRate = VK_VERTEX_INPUT_RATE_VERTEX;

        std::vector<VkVertexInputAttributeDescription> attributes;
        uint32_t offset = 0;
        for (size_t a = 0; a < pass.attribSizes.size(); ++a) {
            VkVertexInputAttributeDescription attr{};
            attr.location = static_cast<uint32_t>(a);
            attr.binding = 0;
            attr.format = formatForComponents(pass.attribSizes[a]);
            attr.offset = offset;
            offset += pass.attribSizes[a] * sizeof(float);
            attributes.push_back(attr);
        }

        VkPipelineVertexInputStateCreateInfo vertexInput{};
        vertexInput.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
        // 空属性 = 不给顶点缓冲，几何体由 gl_VertexIndex 生成（galaxy 的 bg/light 就是这样）
        vertexInput.vertexBindingDescriptionCount = attributes.empty() ? 0 : 1;
        vertexInput.pVertexBindingDescriptions = attributes.empty() ? nullptr : &binding;
        vertexInput.vertexAttributeDescriptionCount =
                static_cast<uint32_t>(attributes.size());
        vertexInput.pVertexAttributeDescriptions =
                attributes.empty() ? nullptr : attributes.data();

        VkPipelineInputAssemblyStateCreateInfo assembly{};
        assembly.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
        if (pass.primitive == 2) {
            assembly.topology = VK_PRIMITIVE_TOPOLOGY_POINT_LIST;
        } else if (pass.triangleStrip) {
            assembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
        } else {
            assembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
        }

        VkViewport viewport{0.0f, 0.0f,
                            static_cast<float>(swapchainExtent_.width),
                            static_cast<float>(swapchainExtent_.height), 0.0f, 1.0f};
        VkRect2D scissor{{0, 0}, swapchainExtent_};
        VkPipelineViewportStateCreateInfo viewportState{};
        viewportState.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
        viewportState.viewportCount = 1;
        viewportState.pViewports = &viewport;
        viewportState.scissorCount = 1;
        viewportState.pScissors = &scissor;

        VkPipelineRasterizationStateCreateInfo raster{};
        raster.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
        raster.lineWidth = 1.0f;

        VkPipelineMultisampleStateCreateInfo multisample{};
        multisample.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
        multisample.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

        VkPipelineColorBlendAttachmentState blend{};
        blend.blendEnable = pass.blendEnable ? VK_TRUE : VK_FALSE;
        blend.srcColorBlendFactor = static_cast<VkBlendFactor>(pass.srcColor);
        blend.dstColorBlendFactor = static_cast<VkBlendFactor>(pass.dstColor);
        blend.srcAlphaBlendFactor = static_cast<VkBlendFactor>(pass.srcAlpha);
        blend.dstAlphaBlendFactor = static_cast<VkBlendFactor>(pass.dstAlpha);
        blend.colorWriteMask = 0xF;

        VkPipelineColorBlendStateCreateInfo blendState{};
        blendState.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
        blendState.attachmentCount = 1;
        blendState.pAttachments = &blend;

        VkPushConstantRange pushRange{};
        pushRange.stageFlags = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
        pushRange.size = pass.pushConstantBytes;

        if (rt.layout == VK_NULL_HANDLE) {
            VkPipelineLayoutCreateInfo layoutInfo{};
            layoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
            layoutInfo.setLayoutCount = 1;
            layoutInfo.pSetLayouts = &descriptorSetLayout_;
            if (pass.pushConstantBytes > 0) {
                layoutInfo.pushConstantRangeCount = 1;
                layoutInfo.pPushConstantRanges = &pushRange;
            }
            if (vkCreatePipelineLayout(device_, &layoutInfo, nullptr, &rt.layout) != VK_SUCCESS) {
                LOGE("vkCreatePipelineLayout failed");
                vkDestroyShaderModule(device_, vs, nullptr);
                vkDestroyShaderModule(device_, fs, nullptr);
                continue;
            }
            layoutCache_[pass.pushConstantBytes] = rt.layout;
        }

        VkGraphicsPipelineCreateInfo pipelineInfo{};
        pipelineInfo.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
        pipelineInfo.stageCount = 2;
        pipelineInfo.pStages = stages;
        pipelineInfo.pVertexInputState = &vertexInput;
        pipelineInfo.pInputAssemblyState = &assembly;
        pipelineInfo.pViewportState = &viewportState;
        pipelineInfo.pRasterizationState = &raster;
        pipelineInfo.pMultisampleState = &multisample;
        pipelineInfo.pColorBlendState = &blendState;
        pipelineInfo.layout = rt.layout;
        pipelineInfo.renderPass = renderPass_;
        pipelineInfo.subpass = 0;

        VkResult result = vkCreateGraphicsPipelines(
                device_, VK_NULL_HANDLE, 1, &pipelineInfo, nullptr, &rt.pipeline);
        vkDestroyShaderModule(device_, vs, nullptr);
        vkDestroyShaderModule(device_, fs, nullptr);
        if (result != VK_SUCCESS) {
            LOGE("vkCreateGraphicsPipelines failed: %d", result);
            continue;
        }
        pipelineCache_[key] = rt.pipeline;

        VkDescriptorSetAllocateInfo alloc{};
        alloc.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        alloc.descriptorPool = descriptorPool_;
        alloc.descriptorSetCount = 1;
        alloc.pSetLayouts = &descriptorSetLayout_;
        if (vkAllocateDescriptorSets(device_, &alloc, &rt.descriptors) != VK_SUCCESS) {
            LOGE("vkAllocateDescriptorSets failed");
        }
    }

    refreshDescriptorsLocked();
    return true;
}

bool VkPassRenderer::onSwapchainCreated() {
    if (commandPool_ != VK_NULL_HANDLE) {
        uploadPendingTexturesLocked();
    }
    refreshDescriptorsLocked();
    return true;
}

bool VkPassRenderer::isSceneReadyLocked() const {
    for (const PassRuntime& rt : runtime_) {
        if (rt.pipeline != VK_NULL_HANDLE) return true;
    }
    return false;
}

void VkPassRenderer::destroyTexturesLocked() {
    // 只销毁 GPU 资源；pending 像素保留，surface 重建后可以重新上传
    for (auto& entry : textures_) {
        TextureEntry& tex = entry.second;
        if (tex.view != VK_NULL_HANDLE) { vkDestroyImageView(device_, tex.view, nullptr); tex.view = VK_NULL_HANDLE; }
        if (tex.sampler != VK_NULL_HANDLE) { vkDestroySampler(device_, tex.sampler, nullptr); tex.sampler = VK_NULL_HANDLE; }
        if (tex.image != VK_NULL_HANDLE) { vkDestroyImage(device_, tex.image, nullptr); tex.image = VK_NULL_HANDLE; }
        if (tex.memory != VK_NULL_HANDLE) { vkFreeMemory(device_, tex.memory, nullptr); tex.memory = VK_NULL_HANDLE; }
    }
}

void VkPassRenderer::destroyPipelinesLocked() {
    runtime_.clear();
    for (auto& entry : pipelineCache_) {
        if (entry.second != VK_NULL_HANDLE) vkDestroyPipeline(device_, entry.second, nullptr);
    }
    pipelineCache_.clear();
    for (auto& entry : layoutCache_) {
        if (entry.second != VK_NULL_HANDLE) vkDestroyPipelineLayout(device_, entry.second, nullptr);
    }
    layoutCache_.clear();
}

// ─────────────────────────── resources ───────────────────────────

bool VkPassRenderer::createBufferLocked(const std::string& name, BufferEntry& entry) {
    if (entry.buffer != VK_NULL_HANDLE) return true;
    if (entry.size == 0) return false;

    VkBufferCreateInfo bi{};
    bi.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bi.size = entry.size;
    bi.usage = VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
    bi.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (vkCreateBuffer(device_, &bi, nullptr, &entry.buffer) != VK_SUCCESS) {
        LOGE("vkCreateBuffer(%s) failed", name.c_str());
        return false;
    }

    VkMemoryRequirements req{};
    vkGetBufferMemoryRequirements(device_, entry.buffer, &req);
    VkMemoryAllocateInfo ai{};
    ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    ai.allocationSize = req.size;
    ai.memoryTypeIndex = vkFindMemoryType(
            physicalDevice_, req.memoryTypeBits,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (vkAllocateMemory(device_, &ai, nullptr, &entry.memory) != VK_SUCCESS) {
        LOGE("vkAllocateMemory(%s) failed", name.c_str());
        vkDestroyBuffer(device_, entry.buffer, nullptr);
        entry.buffer = VK_NULL_HANDLE;
        return false;
    }
    vkBindBufferMemory(device_, entry.buffer, entry.memory, 0);
    if (vkMapMemory(device_, entry.memory, 0, entry.size, 0, &entry.mapped) != VK_SUCCESS) {
        entry.mapped = nullptr;
    }
    return true;
}

void VkPassRenderer::destroyBufferLocked(BufferEntry& entry) {
    if (entry.mapped != nullptr) { vkUnmapMemory(device_, entry.memory); entry.mapped = nullptr; }
    if (entry.buffer != VK_NULL_HANDLE) { vkDestroyBuffer(device_, entry.buffer, nullptr); entry.buffer = VK_NULL_HANDLE; }
    if (entry.memory != VK_NULL_HANDLE) { vkFreeMemory(device_, entry.memory, nullptr); entry.memory = VK_NULL_HANDLE; }
}

bool VkPassRenderer::uploadPendingTexturesLocked() {
    if (device_ == VK_NULL_HANDLE || commandPool_ == VK_NULL_HANDLE) return false;
    bool allOk = true;
    for (auto& entry : textures_) {
        TextureEntry& tex = entry.second;
        if (tex.pending.empty()) continue;
        uint32_t unused = 0;
        if (!uploadTextureLocked(
                    unused, tex.image, tex.memory, tex.view, tex.sampler,
                    VK_NULL_HANDLE, 0,
                    reinterpret_cast<const int32_t*>(tex.pending.data()),
                    tex.pendingWidth, tex.pendingHeight,
                    VK_FILTER_LINEAR, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)) {
            LOGE("uploadTexture(%s) failed", entry.first.c_str());
            allOk = false;
        }
    }
    return allOk;
}

void VkPassRenderer::refreshDescriptorsLocked() {
    if (device_ == VK_NULL_HANDLE) return;
    for (size_t i = 0; i < passes_.size() && i < runtime_.size(); ++i) {
        PassRuntime& rt = runtime_[i];
        if (rt.descriptors == VK_NULL_HANDLE) continue;
        for (const auto& binding : passes_[i].textures) {
            auto it = textures_.find(binding.second);
            if (it == textures_.end() || it->second.view == VK_NULL_HANDLE) continue;
            VkDescriptorImageInfo info{};
            info.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            info.imageView = it->second.view;
            info.sampler = it->second.sampler;
            VkWriteDescriptorSet write{};
            write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            write.dstSet = rt.descriptors;
            write.dstBinding = binding.first;
            write.descriptorCount = 1;
            write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            write.pImageInfo = &info;
            vkUpdateDescriptorSets(device_, 1, &write, 0, nullptr);
        }
    }
}

// ─────────────────────────── frame loop ───────────────────────────

void VkPassRenderer::recordFrameLocked(VkCommandBuffer cmd, uint32_t imageIndex) {
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    if (vkBeginCommandBuffer(cmd, &begin) != VK_SUCCESS) return;

    VkClearValue clear{};
    clear.color.float32[0] = clearColor_[0];
    clear.color.float32[1] = clearColor_[1];
    clear.color.float32[2] = clearColor_[2];
    clear.color.float32[3] = clearColor_[3];

    VkRenderPassBeginInfo passBegin{};
    passBegin.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
    passBegin.renderPass = renderPass_;
    passBegin.framebuffer = swapchainFramebuffers_[imageIndex];
    passBegin.renderArea = {{0, 0}, swapchainExtent_};
    passBegin.clearValueCount = 1;
    passBegin.pClearValues = &clear;
    vkCmdBeginRenderPass(cmd, &passBegin, VK_SUBPASS_CONTENTS_INLINE);

    for (size_t i = 0; i < passes_.size() && i < runtime_.size(); ++i) {
        const VkPassDesc& pass = passes_[i];
        const PassRuntime& rt = runtime_[i];
        if (rt.pipeline == VK_NULL_HANDLE || rt.layout == VK_NULL_HANDLE) continue;

        // bufferName 为空 = 不用顶点缓冲（几何体由 gl_VertexIndex 生成）
        VkBuffer vertexBuffer = VK_NULL_HANDLE;
        if (!pass.bufferName.empty()) {
            auto bufferIt = buffers_.find(pass.bufferName);
            if (bufferIt == buffers_.end() || bufferIt->second.buffer == VK_NULL_HANDLE) continue;
            vertexBuffer = bufferIt->second.buffer;
        }

        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, rt.pipeline);
        if (rt.descriptors != VK_NULL_HANDLE) {
            vkCmdBindDescriptorSets(
                    cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, rt.layout, 0, 1,
                    &rt.descriptors, 0, nullptr);
        }
        if (pass.pushConstantBytes > 0 && !rt.push.empty()) {
            const uint32_t bytes = std::min<uint32_t>(
                    pass.pushConstantBytes,
                    static_cast<uint32_t>(rt.push.size() * sizeof(float)));
            vkCmdPushConstants(
                    cmd, rt.layout,
                    VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                    0, bytes, rt.push.data());
        }
        if (vertexBuffer != VK_NULL_HANDLE) {
            VkDeviceSize offset = 0;
            vkCmdBindVertexBuffers(cmd, 0, 1, &vertexBuffer, &offset);
        }
        vkCmdDraw(cmd, pass.vertexCount, 1, 0, 0);
    }

    vkCmdEndRenderPass(cmd);
    vkEndCommandBuffer(cmd);
}

void VkPassRenderer::drawFrame() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!isReadyLocked()) return;

    vkWaitForFences(device_, 1, &inFlightFence_, VK_TRUE, UINT64_MAX);

    uint32_t imageIndex = 0;
    VkResult acquire = vkAcquireNextImageKHR(
            device_, swapchain_, UINT64_MAX, imageAvailableSemaphore_,
            VK_NULL_HANDLE, &imageIndex);
    if (acquire == VK_ERROR_SURFACE_LOST_KHR) {
        if (window_ != nullptr) createOrUpdateSurfaceLocked(window_, width_, height_);
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

    VkCommandBuffer cmd = swapchainCommandBuffers_[imageIndex];
    vkResetCommandBuffer(cmd, 0);
    recordFrameLocked(cmd, imageIndex);

    VkPipelineStageFlags waitStages[] = {VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT};
    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.waitSemaphoreCount = 1;
    submit.pWaitSemaphores = &imageAvailableSemaphore_;
    submit.pWaitDstStageMask = waitStages;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &cmd;
    submit.signalSemaphoreCount = 1;
    submit.pSignalSemaphores = &renderFinishedSemaphore_;

    vkResetFences(device_, 1, &inFlightFence_);
    if (vkQueueSubmit(graphicsQueue_, 1, &submit, inFlightFence_) != VK_SUCCESS) {
        LOGE("vkQueueSubmit failed");
        return;
    }

    VkPresentInfoKHR present{};
    present.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
    present.waitSemaphoreCount = 1;
    present.pWaitSemaphores = &renderFinishedSemaphore_;
    present.swapchainCount = 1;
    present.pSwapchains = &swapchain_;
    present.pImageIndices = &imageIndex;
    vkQueuePresentKHR(graphicsQueue_, &present);
}

// ─────────────────────────── lifecycle ───────────────────────────

bool VkPassRenderer::createOrUpdateSurface(JNIEnv* env, jobject surface,
                                           int width, int height) {
    if (env == nullptr || surface == nullptr) return false;
    std::lock_guard<std::mutex> lock(mutex_);
    width_ = width;
    height_ = height;

    if (!createInstanceLocked("VkPass")) return false;
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) return false;
    if (window_ != nullptr) ANativeWindow_release(window_);
    window_ = window;

    if (!createOrUpdateSurfaceLocked(window_, width, height)) {
        ANativeWindow_release(window_);
        window_ = nullptr;
        return false;
    }
    uploadPendingTexturesLocked();
    refreshDescriptorsLocked();
    return true;
}

void VkPassRenderer::onSurfaceDestroyed() {
    std::lock_guard<std::mutex> lock(mutex_);
    destroySurfaceLocked();
    if (window_ != nullptr) {
        ANativeWindow_release(window_);
        window_ = nullptr;
    }
}

void VkPassRenderer::destroy() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (device_ != VK_NULL_HANDLE) {
        vkDeviceWaitIdle(device_);
        for (auto& entry : buffers_) destroyBufferLocked(entry.second);
        buffers_.clear();
        for (auto& entry : textures_) {
            TextureEntry& tex = entry.second;
            if (tex.view) vkDestroyImageView(device_, tex.view, nullptr);
            if (tex.sampler) vkDestroySampler(device_, tex.sampler, nullptr);
            if (tex.image) vkDestroyImage(device_, tex.image, nullptr);
            if (tex.memory) vkFreeMemory(device_, tex.memory, nullptr);
        }
        textures_.clear();
        if (descriptorPool_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorPool(device_, descriptorPool_, nullptr);
            descriptorPool_ = VK_NULL_HANDLE;
        }
        if (descriptorSetLayout_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(device_, descriptorSetLayout_, nullptr);
            descriptorSetLayout_ = VK_NULL_HANDLE;
        }
    }
    if (window_ != nullptr) {
        ANativeWindow_release(window_);
        window_ = nullptr;
    }
    destroyDeviceLocked();
}

// ─────────────────────────── JNI ───────────────────────────

static jlong JNICALL nCreate(JNIEnv* env, jclass, jobject assetManager) {
    AAssetManager* assets = AAssetManager_fromJava(env, assetManager);
    if (assets == nullptr) return 0;
    return reinterpret_cast<jlong>(new VkPassRenderer(assets));
}

static void JNICALL nDestroy(JNIEnv*, jclass, jlong handle) {
    if (auto* renderer = asRenderer(handle)) {
        renderer->destroy();
        delete renderer;
    }
}

static void JNICALL nSetClearColor(
        JNIEnv*, jclass, jlong handle, jfloat r, jfloat g, jfloat b, jfloat a) {
    if (auto* renderer = asRenderer(handle)) renderer->setClearColor(r, g, b, a);
}

static jint JNICALL nAddPass(
        JNIEnv* env, jclass, jlong handle,
        jstring vs, jstring fs, jintArray attribSizes, jint stride, jint primitive,
        jboolean blend, jint srcColor, jint dstColor, jint srcAlpha, jint dstAlpha,
        jint pushBytes, jstring bufferName, jint vertexCount, jboolean clear) {
    auto* renderer = asRenderer(handle);
    if (renderer == nullptr) return -1;

    VkPassDesc desc;
    desc.vsPath = jstringToStd(env, vs);
    desc.fsPath = jstringToStd(env, fs);
    desc.strideBytes = static_cast<uint32_t>(stride);
    desc.primitive = static_cast<uint32_t>(primitive);
    desc.triangleStrip = primitive == 0;
    desc.blendEnable = blend == JNI_TRUE;
    desc.srcColor = static_cast<uint32_t>(srcColor);
    desc.dstColor = static_cast<uint32_t>(dstColor);
    desc.srcAlpha = static_cast<uint32_t>(srcAlpha);
    desc.dstAlpha = static_cast<uint32_t>(dstAlpha);
    desc.pushConstantBytes = static_cast<uint32_t>(pushBytes);
    desc.bufferName = jstringToStd(env, bufferName);
    desc.vertexCount = static_cast<uint32_t>(vertexCount);
    desc.clear = clear == JNI_TRUE;

    if (attribSizes != nullptr) {
        const jsize count = env->GetArrayLength(attribSizes);
        jint* values = env->GetIntArrayElements(attribSizes, nullptr);
        for (jsize i = 0; i < count; ++i) {
            desc.attribSizes.push_back(static_cast<uint32_t>(values[i]));
        }
        env->ReleaseIntArrayElements(attribSizes, values, JNI_ABORT);
    }
    return renderer->addPass(std::move(desc));
}

static void JNICALL nCreateBuffer(
        JNIEnv* env, jclass, jlong handle, jstring name, jint stride, jint capacity,
        jboolean dynamic) {
    if (auto* renderer = asRenderer(handle)) {
        renderer->createBuffer(
                jstringToStd(env, name), static_cast<uint32_t>(stride),
                static_cast<uint32_t>(capacity), dynamic == JNI_TRUE);
    }
}

static void JNICALL nCreateTexture(JNIEnv* env, jclass, jlong handle, jstring name) {
    if (auto* renderer = asRenderer(handle)) {
        renderer->createTexture(jstringToStd(env, name));
    }
}

static void JNICALL nBindTexture(
        JNIEnv* env, jclass, jlong handle, jint passIndex, jint binding, jstring name) {
    if (auto* renderer = asRenderer(handle)) {
        renderer->bindTexture(
                passIndex, static_cast<uint32_t>(binding), jstringToStd(env, name));
    }
}

static void JNICALL nSetTexture(
        JNIEnv* env, jclass, jlong handle, jstring name, jintArray pixels,
        jint width, jint height) {
    auto* renderer = asRenderer(handle);
    if (renderer == nullptr || pixels == nullptr || width <= 0 || height <= 0) return;
    const jsize count = env->GetArrayLength(pixels);
    if (count < width * height) return;
    jint* data = env->GetIntArrayElements(pixels, nullptr);
    if (data == nullptr) return;
    renderer->setTexturePixels(
            jstringToStd(env, name), data,
            static_cast<uint32_t>(width), static_cast<uint32_t>(height));
    env->ReleaseIntArrayElements(pixels, data, JNI_ABORT);
}

static void JNICALL nUploadBuffer(
        JNIEnv* env, jclass, jlong handle, jstring name, jobject data, jint bytes) {
    auto* renderer = asRenderer(handle);
    if (renderer == nullptr || data == nullptr || bytes <= 0) return;
    void* address = env->GetDirectBufferAddress(data);
    jlong capacity = env->GetDirectBufferCapacity(data);
    if (address == nullptr || capacity <= 0) return;
    const size_t length = std::min(static_cast<size_t>(bytes), static_cast<size_t>(capacity));
    renderer->uploadBuffer(jstringToStd(env, name), address, length);
}

static void JNICALL nSetPushConstants(
        JNIEnv* env, jclass, jlong handle, jint passIndex, jfloatArray values) {
    auto* renderer = asRenderer(handle);
    if (renderer == nullptr || values == nullptr) return;
    const jsize count = env->GetArrayLength(values);
    jfloat* data = env->GetFloatArrayElements(values, nullptr);
    if (data == nullptr) return;
    renderer->setPushConstants(passIndex, data, static_cast<size_t>(count));
    env->ReleaseFloatArrayElements(values, data, JNI_ABORT);
}

static void JNICALL nSetVertexCount(
        JNIEnv*, jclass, jlong handle, jint passIndex, jint vertexCount) {
    if (auto* renderer = asRenderer(handle)) {
        renderer->setVertexCount(passIndex, static_cast<uint32_t>(vertexCount));
    }
}

static jboolean JNICALL nSurfaceCreated(
        JNIEnv* env, jclass, jlong handle, jobject surface, jint width, jint height) {
    auto* renderer = asRenderer(handle);
    if (renderer == nullptr) return JNI_FALSE;
    return renderer->createOrUpdateSurface(env, surface, width, height)
            ? JNI_TRUE : JNI_FALSE;
}

static void JNICALL nSurfaceChanged(
        JNIEnv* env, jclass, jlong handle, jobject surface, jint width, jint height) {
    if (auto* renderer = asRenderer(handle)) {
        renderer->createOrUpdateSurface(env, surface, width, height);
    }
}

static void JNICALL nSurfaceDestroyed(JNIEnv*, jclass, jlong handle) {
    if (auto* renderer = asRenderer(handle)) renderer->onSurfaceDestroyed();
}

static void JNICALL nRenderFrame(JNIEnv*, jclass, jlong handle) {
    if (auto* renderer = asRenderer(handle)) renderer->drawFrame();
}

static jboolean JNICALL nIsSupported(JNIEnv*, jclass) {
    return vkIsVulkanSupported() ? JNI_TRUE : JNI_FALSE;
}

static const JNINativeMethod kMethods[] = {
    {"nCreate", "(Landroid/content/res/AssetManager;)J", reinterpret_cast<void*>(nCreate)},
    {"nDestroy", "(J)V", reinterpret_cast<void*>(nDestroy)},
    {"nSetClearColor", "(JFFFF)V", reinterpret_cast<void*>(nSetClearColor)},
    {"nAddPass",
     "(JLjava/lang/String;Ljava/lang/String;[IIIZIIIIILjava/lang/String;IZ)I",
     reinterpret_cast<void*>(nAddPass)},
    {"nCreateBuffer", "(JLjava/lang/String;IIZ)V", reinterpret_cast<void*>(nCreateBuffer)},
    {"nCreateTexture", "(JLjava/lang/String;)V", reinterpret_cast<void*>(nCreateTexture)},
    {"nBindTexture", "(JIILjava/lang/String;)V", reinterpret_cast<void*>(nBindTexture)},
    {"nSetTexture", "(JLjava/lang/String;[III)V", reinterpret_cast<void*>(nSetTexture)},
    {"nUploadBuffer", "(JLjava/lang/String;Ljava/nio/ByteBuffer;I)V",
     reinterpret_cast<void*>(nUploadBuffer)},
    {"nSetPushConstants", "(JI[F)V", reinterpret_cast<void*>(nSetPushConstants)},
    {"nSetVertexCount", "(JII)V", reinterpret_cast<void*>(nSetVertexCount)},
    {"nSurfaceCreated", "(JLandroid/view/Surface;II)Z",
     reinterpret_cast<void*>(nSurfaceCreated)},
    {"nSurfaceChanged", "(JLandroid/view/Surface;II)V",
     reinterpret_cast<void*>(nSurfaceChanged)},
    {"nSurfaceDestroyed", "(J)V", reinterpret_cast<void*>(nSurfaceDestroyed)},
    {"nRenderFrame", "(J)V", reinterpret_cast<void*>(nRenderFrame)},
    {"nIsSupported", "()Z", reinterpret_cast<void*>(nIsSupported)},
};

bool registerPassVk(JNIEnv* env) {
    jclass cls = env->FindClass("com/reandroid/plugin/VkPassNative");
    if (cls == nullptr) return false;
    const int count = sizeof(kMethods) / sizeof(kMethods[0]);
    return env->RegisterNatives(cls, kMethods, count) == JNI_OK;
}
