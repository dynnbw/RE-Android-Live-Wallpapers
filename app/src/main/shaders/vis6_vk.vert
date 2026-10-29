#version 450

/*
 * vis6（环形频谱）的纯色圆环 —— 对应 GLES 版 MusicVisCircleGL 内联的
 * aPosition + uMVP 顶点着色器。
 *
 * 顶点是场景坐标 (x, y)；uMvp 已经乘过 GL → Vulkan 的裁剪空间修正
 * （y 取反、z 从 [-1, 1] 映射到 [0, 1]），所以这里与 GLES 一样把坐标交给矩阵。
 */

layout(push_constant) uniform PushConstants {
    mat4 uMvp;
} pc;

layout(location = 0) in vec2 aPosition;

void main() {
    gl_Position = pc.uMvp * vec4(aPosition, 0.0, 1.0);
}
