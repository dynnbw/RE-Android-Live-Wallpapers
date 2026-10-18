#version 450

layout(push_constant) uniform PushConstants {
    mat4 uMvp;
} pc;

layout(location = 0) in vec2 aPosition;
layout(location = 1) in float aAlpha;

layout(location = 0) out float vAlpha;

void main() {
    vAlpha = aAlpha;
    gl_Position = pc.uMvp * vec4(aPosition, 0.0, 1.0);
}
