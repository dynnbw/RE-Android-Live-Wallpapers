#version 450

layout(set = 0, binding = 0) uniform sampler2D uTexture;

layout(push_constant) uniform PushConstants {
    mat4 uMvp;
} pc;

layout(location = 0) in vec2 vTexCoord;
layout(location = 1) in float vAlpha;

layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = texture(uTexture, vTexCoord) * vec4(1.0, 1.0, 1.0, vAlpha);
}
