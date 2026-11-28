#version 450

layout(set = 0, binding = 0) uniform sampler2D uScene;

layout(push_constant) uniform PushConstants {
    vec2 uTexel;        // 1.0 / 场景尺寸
    float uThreshold;   // 亮度阈值
    float uSoftKnee;    // 阈值之上的过渡宽度
} pc;

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outColor;

void main() {
    // 4 抽头盒式降采样，顺带做亮度提取 —— 与 GLES 的 glow_bright_fs 同一套
    vec3 c = texture(uScene, vUv + pc.uTexel * vec2(-0.5, -0.5)).rgb
           + texture(uScene, vUv + pc.uTexel * vec2(0.5, -0.5)).rgb
           + texture(uScene, vUv + pc.uTexel * vec2(-0.5, 0.5)).rgb
           + texture(uScene, vUv + pc.uTexel * vec2(0.5, 0.5)).rgb;
    c *= 0.25;
    float lum = dot(c, vec3(0.299, 0.587, 0.114));
    // 软阈值：硬阈值的台阶在光晕边缘看得见
    outColor = vec4(c * smoothstep(pc.uThreshold, pc.uThreshold + pc.uSoftKnee, lum), 1.0);
}
