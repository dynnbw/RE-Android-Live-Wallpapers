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
 * .loadTextureNearestPremult）。采样器在 WindmillVkDelegate 里按同样的参数创建，
 * 所以这里直接用 texture()，不再手工取纹素。
 */
void main() {
    // 一个 pass 里最多 8 张可替换贴图（白/夜天空、云 A/B、雨、闪电、地面昼夜……）。
    // 不做采样器数组动态下标：那需要 shaderSampledImageArrayDynamicIndexing，
    // 通用渲染器没有显式启用该特性。
    vec4 tex;
    if (vTextureSlot == 0) {
        tex = texture(uTexture0, vTexCoord);
    } else if (vTextureSlot == 1) {
        tex = texture(uTexture1, vTexCoord);
    } else if (vTextureSlot == 2) {
        tex = texture(uTexture2, vTexCoord);
    } else if (vTextureSlot == 3) {
        tex = texture(uTexture3, vTexCoord);
    } else if (vTextureSlot == 4) {
        tex = texture(uTexture4, vTexCoord);
    } else if (vTextureSlot == 5) {
        tex = texture(uTexture5, vTexCoord);
    } else if (vTextureSlot == 6) {
        tex = texture(uTexture6, vTexCoord);
    } else {
        tex = texture(uTexture7, vTexCoord);
    }

    // 与 GLES 的 GL_MODULATE 一致：贴图是预乘的，颜色再乘一次。
    fragColor = vec4(tex.rgb * vColor.rgb, tex.a * vColor.a);
}
