#version 450

layout(push_constant) uniform PushConstants {
    mat4 uMvp;
} pc;

layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec2 aTexCoord;
layout(location = 2) in float aAlpha;
layout(location = 3) in vec3 aAdjust;

layout(location = 0) out vec2 vTexCoord;
layout(location = 1) out float vAlpha;
layout(location = 2) out vec3 vAdjust;

void main() {
    vTexCoord = aTexCoord;
    vAlpha = aAlpha;
    vAdjust = aAdjust;
    gl_Position = pc.uMvp * vec4(aPosition, 0.0, 1.0);
}
