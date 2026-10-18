#version 450

// 原版片元着色器是 precision mediump float；这里保持同一精度等级。
precision mediump float;

/*
 * Glow 片元着色器。
 *
 * 原版按 shape 绑定 glowbox / glowround / glowdot 之一；这里一次绑定三张，
 * 由 push constant 里的 uTexIndex 选择。
 */

layout(set = 0, binding = 0) uniform sampler2D uTexture0;
layout(set = 0, binding = 1) uniform sampler2D uTexture1;
layout(set = 0, binding = 2) uniform sampler2D uTexture2;

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
    } else {
        baseColor = texture(uTexture2, vTexCoord);
    }

    // 原版：vIsGradiatObj >= 1 时才用 uColor；该值恒为 0，所以总是走 vColor。
    baseColor = baseColor * vColor;
    fragColor = baseColor * vAlpha * pc.uAlpha;
}
