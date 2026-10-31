#version 450

layout(set = 0, binding = 0) uniform sampler2D uTexture;

layout(push_constant) uniform PushConstants {
    mat4 uMvp;
    float uBrightness;
} pc;

layout(location = 0) in vec2 vTexCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    float val = pc.uBrightness;
    fragColor = texture(uTexture, vTexCoord) + vec4(val, val, val, 0.0);
}
