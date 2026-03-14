#version 450

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outColor;

layout(set = 0, binding = 0) uniform sampler2D uBackgroundTex;

void main() {
    vec2 uv = clamp(vUv, 0.0, 1.0);
    outColor = texture(uBackgroundTex, uv);
}