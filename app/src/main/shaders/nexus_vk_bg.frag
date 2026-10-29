#version 450

/* Nexus 背景 pass —— GLES 版用白色 uColor 乘纹理，结果与直接采样相同。 */

layout(set = 0, binding = 0) uniform sampler2D sTexture;

layout(location = 0) in vec2 vTexCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = texture(sTexture, vTexCoord);
}
