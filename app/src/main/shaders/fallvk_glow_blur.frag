#version 450

layout(set = 0, binding = 0) uniform sampler2D uSource;

layout(push_constant) uniform PushConstants {
    vec2 uStep;    // 已含半径：(r/w, 0) 横向、(0, r/h) 纵向
    float uSigma;  // 保留字段，与 GLES 那边同布局
    float _pad;
} pc;

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outColor;

void main() {
    // 线性采样的 5 抽头近似，等效 9 抽头 —— 权重是固定的一套常用值，与 GLES 同源
    vec3 c = texture(uSource, vUv).rgb * 0.2270270270;
    c += texture(uSource, vUv + pc.uStep * 1.3846153846).rgb * 0.3162162162;
    c += texture(uSource, vUv - pc.uStep * 1.3846153846).rgb * 0.3162162162;
    c += texture(uSource, vUv + pc.uStep * 3.2307692308).rgb * 0.0702702703;
    c += texture(uSource, vUv - pc.uStep * 3.2307692308).rgb * 0.0702702703;
    outColor = vec4(c, 1.0);
}
