#version 450

layout(set = 0, binding = 0) uniform sampler2D uSource;

layout(push_constant) uniform PushConstants {
    vec2 uStep;    // 已含半径：(r/w, 0) 横向、(0, r/h) 纵向
    float uSigma;  // 高斯 σ，单位是"采样步数"（固定 2：9 抽头覆盖 ±2σ）
    float _pad;
} pc;

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outColor;

/*
 * 可分离高斯：9 个抽头、权重现算再归一化 —— 与 GLES 的 grass 版 glow_blur_fs.glsl
 * 同一套（注意与 fall 用的固定权重 5 抽头不同）。
 *
 * 步长取 σ/2（半径的 1/4），抽头间距小于 σ，叠起来才是平滑衰减，而不是几个错位副本。
 */
const int TAPS = 4;

void main() {
    vec3 sum = vec3(0.0);
    float total = 0.0;
    float inv = 1.0 / (pc.uSigma * pc.uSigma);
    for (int i = -TAPS; i <= TAPS; i++) {
        float f = float(i);
        float w = exp(-0.5 * f * f * inv);
        sum += texture(uSource, vUv + pc.uStep * f).rgb * w;
        total += w;
    }
    outColor = vec4(sum / total, 1.0);
}
