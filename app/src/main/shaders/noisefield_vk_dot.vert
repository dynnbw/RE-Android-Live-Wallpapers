#version 450

layout(push_constant) uniform PushConstants {
    mat4 uMvp;
    float uScaleSize;
} pc;

layout(location = 0) in vec3 aPosition;
layout(location = 1) in float aSpeed;
layout(location = 2) in float aAlpha;

layout(location = 0) out float vAlpha;

void main() {
    gl_Position = pc.uMvp * vec4(aPosition, 1.0);
    // 与 GLES 同一个公式：速度越快点越大
    gl_PointSize = 1.0 + aSpeed * pc.uScaleSize * 2500.0;
    vAlpha = aAlpha;
}
