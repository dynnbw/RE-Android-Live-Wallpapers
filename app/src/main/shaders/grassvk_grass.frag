#version 450

layout(location = 0) in vec4 vColor;
layout(location = 1) in vec2 vTexCoord;
layout(location = 0) out vec4 fragColor;

layout(binding = 0) uniform sampler2D uAATexture;

void main() {
    // AA texture encodes edge softness in the R channel (uploaded as RGBA with R=alpha)
    float a = texture(uAATexture, vTexCoord).r;
    fragColor = vec4(vColor.rgb, vColor.a * a);
}
