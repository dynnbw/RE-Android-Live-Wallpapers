#version 450

layout(push_constant) uniform PushConstants {
    mat4 uMvpMatrix;
    float uAlpha;
    float uLeafFrameIndex;
    float uLeafFrameInvCount;
} pc;

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec2 aTexCoord;

layout(location = 0) out vec2 vUv;

void main() {
    gl_Position = pc.uMvpMatrix * vec4(aPosition, 1.0);
    vUv = aTexCoord;
}
