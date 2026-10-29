#version 450

/* Nexus 脉冲/光晕 pass —— 与 GLES 的 nexus_fs.glsl 相同：纹理乘逐顶点颜色。 */

layout(set = 0, binding = 0) uniform sampler2D sTexture;

layout(location = 0) in vec2 vTexCoord;
layout(location = 1) in vec4 vColor;

layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = texture(sTexture, vTexCoord) * vColor;
}
