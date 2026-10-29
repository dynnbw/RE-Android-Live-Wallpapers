#version 450
// vis1（Visualizer）的波形点 —— 对应 GLES 版 musicvis_line_vs.glsl。
//
// 顶点是屏幕像素坐标（x, y）+ 四边形角坐标（角 x, 角 y）。uMvp 是像素正交投影，
// 再拼上 GL → Vulkan 的裁剪空间修正（y 取反、z 从 [-1, 1] 映射到 [0, 1]），
// 所以这里与 GLES 一样直接把像素坐标交给矩阵。
layout(push_constant) uniform PushConstants {
    mat4 uMvp;
    vec4 uColor;
    float uFeather;
} pc;

layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec2 aCorner;

layout(location = 0) out vec2 vCorner;

void main() {
    vCorner = aCorner;
    gl_Position = pc.uMvp * vec4(aPosition, 0.0, 1.0);
}
