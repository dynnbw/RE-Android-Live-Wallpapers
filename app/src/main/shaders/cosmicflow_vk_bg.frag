#version 450

/* Cosmic Flow background pass — Vulkan port of shaders/GLES/bg_fs.glsl. */

layout(set = 0, binding = 0) uniform sampler2D sTexture;

layout(location = 0) in vec3 vColor;
layout(location = 1) in vec2 vTexCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 texColor = texture(sTexture, vTexCoord);
    fragColor = vec4(texColor.xyz * vColor, texColor.a);
}
