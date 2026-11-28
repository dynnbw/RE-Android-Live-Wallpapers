#version 450

layout(set = 0, binding = 0) uniform sampler2D uScene;
layout(set = 0, binding = 1) uniform sampler2D uBloom;

layout(push_constant) uniform PushConstants {
    float uStrength;
    float _pad0;
    float _pad1;
    float _pad2;
} pc;

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outColor;

void main() {
    vec3 scene = texture(uScene, vUv).rgb;
    vec3 bloom = texture(uBloom, vUv).rgb;
    // alpha 必须写 1：场景纹理带 alpha，照抄到屏幕会改变壁纸自身的合成方式
    outColor = vec4(scene + bloom * pc.uStrength, 1.0);
}
