#version 450
// vis5（Many）的波形纹理路径 —— 与 GLES 版 musicvis_many_line_fs.glsl 相同：直接取纹理。
//
// 两条路径的纹理分别来自 vis2 的火线与 vis3 的冰线，由 Java 侧按波形模式绑定。
layout(set = 0, binding = 0) uniform sampler2D sTexture;

layout(location = 0) in vec2 vTex;

layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = texture(sTexture, vTex);
}
