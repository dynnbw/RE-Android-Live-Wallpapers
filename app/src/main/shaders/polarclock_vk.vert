#version 450

/*
 * 极坐标时钟的弧带与圆帽 —— 对应 GLES 版内联的 aPosition + uMVPMatrix 顶点着色器。
 *
 * 顶点是模型空间的 (x, y)，单位就是屏幕像素；uMvp 完成正交投影、平移到表盘中心、
 * 旋转 -90° 与横屏时的 0.9 缩放。矩阵已经乘过 GL → Vulkan 的裁剪空间修正
 *（y 取反、z 从 [-1, 1] 映射到 [0, 1]），所以位置与 GLES 逐字一致。
 */

layout(push_constant) uniform PushConstants {
    mat4 uMvp;
    vec4 uColor;
} pc;

layout(location = 0) in vec2 aPosition;

void main() {
    gl_Position = pc.uMvp * vec4(aPosition, 0.0, 1.0);
}
