#version 450

layout(set = 0, binding = 0) uniform sampler2D uTexture;

layout(push_constant) uniform PushConstants {
    mat4 uClip;
    float uTime;
} pc;

layout(location = 0) in vec2 vTexCoord;
layout(location = 1) flat in vec4 vColor;
layout(location = 2) flat in vec2 vFlow;
layout(location = 3) flat in float vDistort;

layout(location = 0) out vec4 fragColor;

void main() {
    vec2 uv = vTexCoord;
    if (vDistort > 0.0) {
        float wobble = sin((uv.y + pc.uTime * vFlow.y) * 6.2831853 + vFlow.x) * vDistort;
        uv.x += wobble;
    }
    fragColor = texture(uTexture, uv) * vColor;
}
