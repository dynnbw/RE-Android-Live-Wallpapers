#version 450

layout(set = 0, binding = 0) uniform sampler2D uTexture;
layout(set = 0, binding = 1) uniform sampler2D uAlphaTexture;

layout(location = 0) in vec2 vTexCoord;
layout(location = 1) in float vAddAlpha;
layout(location = 2) in vec3 vAddColor;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 texel = texture(uTexture, vTexCoord);
    vec4 alphaTexture = texture(uAlphaTexture, vTexCoord);
    texel.a = alphaTexture.r;
    fragColor = texel + vec4(vAddColor, 0.0) + vec4(0.0, 0.0, 0.0, vAddAlpha);
}
