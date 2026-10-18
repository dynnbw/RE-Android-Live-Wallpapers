#version 450

layout(set = 0, binding = 0) uniform sampler2D uTexture0;
layout(set = 0, binding = 1) uniform sampler2D uTexture1;
layout(set = 0, binding = 2) uniform sampler2D uTexture2;
layout(set = 0, binding = 3) uniform sampler2D uTexture3;
layout(set = 0, binding = 4) uniform sampler2D uTexture4;
layout(set = 0, binding = 5) uniform sampler2D uTexture5;
layout(set = 0, binding = 6) uniform sampler2D uTexture6;
layout(set = 0, binding = 7) uniform sampler2D uTexture7;

layout(push_constant) uniform PushConstants {
    mat4 uMvp;
} pc;

layout(location = 0) in vec2 vTexCoord;
layout(location = 1) in vec4 vColor;
layout(location = 2) flat in int vTextureSlot;

layout(location = 0) out vec4 fragColor;

/*
 * GLES 的天气贴图统一用 GL_NEAREST + CLAMP_TO_EDGE 上传（GLTextureUtils
 * .loadTextureNearestPremult），而通用 pass 渲染器固定创建线性过滤的采样器。
 * 这里用 texelFetch 手工取最近纹素，把过滤方式拉回与 GLES 一致。
 * uv 落在 [0,1]，钳到最后一格即可复刻 CLAMP_TO_EDGE。
 */
vec4 sampleNearest(sampler2D tex, vec2 uv) {
    ivec2 size = textureSize(tex, 0);
    ivec2 pixel = clamp(ivec2(floor(uv * vec2(size))), ivec2(0), size - 1);
    return texelFetch(tex, pixel, 0);
}

void main() {
    // 一个 pass 里最多 8 张可替换贴图（白/夜天空、云 A/B、雨、闪电、地面昼夜……）。
    // 不做采样器数组动态下标：那需要 shaderSampledImageArrayDynamicIndexing，
    // 通用渲染器没有显式启用该特性。
    vec4 tex;
    if (vTextureSlot == 0) {
        tex = sampleNearest(uTexture0, vTexCoord);
    } else if (vTextureSlot == 1) {
        tex = sampleNearest(uTexture1, vTexCoord);
    } else if (vTextureSlot == 2) {
        tex = sampleNearest(uTexture2, vTexCoord);
    } else if (vTextureSlot == 3) {
        tex = sampleNearest(uTexture3, vTexCoord);
    } else if (vTextureSlot == 4) {
        tex = sampleNearest(uTexture4, vTexCoord);
    } else if (vTextureSlot == 5) {
        tex = sampleNearest(uTexture5, vTexCoord);
    } else if (vTextureSlot == 6) {
        tex = sampleNearest(uTexture6, vTexCoord);
    } else {
        tex = sampleNearest(uTexture7, vTexCoord);
    }

    // 与 GLES 的 GL_MODULATE 一致：贴图是预乘的，颜色再乘一次。
    fragColor = vec4(tex.rgb * vColor.rgb, tex.a * vColor.a);
}
