#version 450

// 全屏背景的透传片元着色器，与 GLES 版 silk_bg_fs.glsl 等价。

layout(set = 0, binding = 0) uniform sampler2D uTexture;

layout(location = 0) in vec2 vTexCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = texture(uTexture, vTexCoord);
}
