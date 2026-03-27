#version 450

layout(location = 0) in vec2 vTexCoord;
layout(location = 1) in float vAlpha;
layout(location = 0) out vec4 fragColor;

layout(binding = 0) uniform sampler2D uSampler;

void main() {
    vec4 c = texture(uSampler, vTexCoord);
    fragColor = vec4(c.rgb, c.a * vAlpha);
}
