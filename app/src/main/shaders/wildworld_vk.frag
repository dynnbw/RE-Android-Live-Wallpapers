#version 450

layout(set = 0, binding = 0) uniform sampler2D uTexture;

layout(location = 0) in vec2 vTexCoord;
layout(location = 1) in float vAlpha;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 c = texture(uTexture, vTexCoord);
    fragColor = vec4(c.rgb, c.a * vAlpha);
}
