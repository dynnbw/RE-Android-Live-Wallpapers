#version 450

/*
 * 孢子的片元着色器：七张姿态各异的种子图。
 *
 * 绑定顺序与 DandelionVkDelegate 里的全局贴图下标一致：7..13 对应 spore_1..spore_7，
 * 这里减去 7 换成 0..6。贴图是预乘 alpha 的，混合由 pass 的
 * ONE / ONE_MINUS_SRC_ALPHA 因子完成。
 */

layout(set = 0, binding = 0) uniform sampler2D uTexture0;
layout(set = 0, binding = 1) uniform sampler2D uTexture1;
layout(set = 0, binding = 2) uniform sampler2D uTexture2;
layout(set = 0, binding = 3) uniform sampler2D uTexture3;
layout(set = 0, binding = 4) uniform sampler2D uTexture4;
layout(set = 0, binding = 5) uniform sampler2D uTexture5;
layout(set = 0, binding = 6) uniform sampler2D uTexture6;

layout(location = 0) in vec2 vUV;
layout(location = 1) in float vAlpha;
layout(location = 2) flat in float vTexIndex;

layout(location = 0) out vec4 fragColor;

void main() {
    int index = int(vTexIndex + 0.5) - 7;
    vec4 color;
    if (index < 1) {
        color = textureLod(uTexture0, vUV, 0.0);
    } else if (index < 2) {
        color = textureLod(uTexture1, vUV, 0.0);
    } else if (index < 3) {
        color = textureLod(uTexture2, vUV, 0.0);
    } else if (index < 4) {
        color = textureLod(uTexture3, vUV, 0.0);
    } else if (index < 5) {
        color = textureLod(uTexture4, vUV, 0.0);
    } else if (index < 6) {
        color = textureLod(uTexture5, vUV, 0.0);
    } else {
        color = textureLod(uTexture6, vUV, 0.0);
    }
    fragColor = color * vAlpha;
}
