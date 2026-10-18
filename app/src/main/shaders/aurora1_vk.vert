#version 450

layout(push_constant) uniform PushConstants {
    mat4 uClip;   // GL NDC -> Vulkan clip（y 翻转、z 0..1）
    float uTime;
} pc;

layout(location = 0) in vec2 aPosition;   // GL NDC
layout(location = 1) in vec2 aTexCoord;
layout(location = 2) in vec4 aColor;
layout(location = 3) in vec2 aFlow;
layout(location = 4) in float aDistort;

layout(location = 0) out vec2 vTexCoord;
layout(location = 1) flat out vec4 vColor;
layout(location = 2) flat out vec2 vFlow;
layout(location = 3) flat out float vDistort;

void main() {
    vTexCoord = aTexCoord;
    vColor = aColor;
    vFlow = aFlow;
    vDistort = aDistort;
    gl_Position = pc.uClip * vec4(aPosition, 0.0, 1.0);
}
