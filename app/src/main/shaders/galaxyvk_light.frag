#version 450

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outColor;

layout(set = 0, binding = 0) uniform sampler2D uLightTex;

void main() {
    vec4 texel = texture(uLightTex, vUv);
    outColor = texel;
}