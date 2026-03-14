#version 450

layout(set = 0, binding = 0) uniform sampler2D uBackgroundTex;

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outColor;

void main() {
    outColor = texture(uBackgroundTex, clamp(vUv, 0.0, 1.0));
}
