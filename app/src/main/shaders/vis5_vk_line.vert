#version 450
// vis5（Many）的波形纹理路径 —— 对应 GLES 版 musicvis_many_line_vs.glsl。
//
// 顶点是 ManyScene 每帧算好的波形采样（x, y）与纹理坐标（u, v），
// uMvp 已含 Vulkan 的裁剪空间修正（y 取反、z 映射到 [0, w]）。
layout(push_constant) uniform PushConstants {
    mat4 uMvp;
} pc;

layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec2 aTexCoord;

layout(location = 0) out vec2 vTex;

void main() {
    vTex = aTexCoord;
    gl_Position = pc.uMvp * vec4(aPosition, 0.0, 1.0);
}
