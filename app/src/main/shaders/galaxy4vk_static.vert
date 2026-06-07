#version 450

layout(push_constant) uniform PushConstants {
    mat4 uMvpMatrix;
    float uAlpha;
    float uParticleSize;
    float uParticleOpacity;
} pc;

layout(location = 0) in vec2 aPosition;
layout(location = 1) in float aPointSize;

layout(location = 0) out float pointSize;

void main() {
    gl_Position = pc.uMvpMatrix * vec4(aPosition, 0.0, 1.0);
    pointSize = aPointSize;
    gl_PointSize = aPointSize;
}
