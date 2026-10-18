#version 450

// 原版片元着色器是 precision mediump float；这里保持同一精度等级。
precision mediump float;

/*
 * 网格片元着色器。
 *
 * 原版每帧只绑定一张物体贴图；通用 pass 的贴图绑定是静态的，所以这里一次性绑定
 * 全部 6 张候选（box0_128 / box0_1_128 / box0_64 / box0_1_64 / round0 / dot0），
 * 由 push constant 里的 uTexIndex 选择。uTexIndex 来自 push constant，是 uniform，
 * 分支里每条采样都对应一个静态 binding。
 */

layout(set = 0, binding = 0) uniform sampler2D uTexture0;
layout(set = 0, binding = 1) uniform sampler2D uTexture1;
layout(set = 0, binding = 2) uniform sampler2D uTexture2;
layout(set = 0, binding = 3) uniform sampler2D uTexture3;
layout(set = 0, binding = 4) uniform sampler2D uTexture4;
layout(set = 0, binding = 5) uniform sampler2D uTexture5;

layout(push_constant) uniform PushConstants {
    mat4 uMvp;
    vec4 uColor;
    float uAlpha;
    float uBatteryAlpha;
    float uGradDownStart;
    float uGradUpStart;
    float uGradGapUp;
    float uGradGapDown;
    float uTexIndex;
} pc;

layout(location = 0) in vec2 vTexCoord;
layout(location = 1) in float vAlpha;
layout(location = 2) in vec4 vColor;

layout(location = 0) out vec4 fragColor;

void main() {
    float idx = pc.uTexIndex;
    vec4 baseColor;
    if (idx < 0.5) {
        baseColor = texture(uTexture0, vTexCoord);
    } else if (idx < 1.5) {
        baseColor = texture(uTexture1, vTexCoord);
    } else if (idx < 2.5) {
        baseColor = texture(uTexture2, vTexCoord);
    } else if (idx < 3.5) {
        baseColor = texture(uTexture3, vTexCoord);
    } else if (idx < 4.5) {
        baseColor = texture(uTexture4, vTexCoord);
    } else {
        baseColor = texture(uTexture5, vTexCoord);
    }

    // 原版：vIsGradiatObj >= 1 时才用 uColor；该值恒为 0，所以总是走 vColor。
    baseColor = baseColor * vColor;
    fragColor = baseColor * vAlpha * pc.uAlpha;
}
