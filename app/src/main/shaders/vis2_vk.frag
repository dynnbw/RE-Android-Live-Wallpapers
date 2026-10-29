#version 450
// vis2（PCM 波形）的纹理路径 —— 与 GLES 版 musicvis_wave_fs.glsl 相同：直接取纹理。
layout(set = 0, binding = 0) uniform sampler2D sTexture;

layout(location = 0) in vec2 vTex;

layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = texture(sTexture, vTex);
}
