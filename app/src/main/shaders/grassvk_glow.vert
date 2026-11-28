#version 450

// 全屏四边形：顶点已经就是 NDC，不接矩阵、不接顶点缓冲。
//
// 不做 v 翻转：Vulkan 里 NDC 的 y 朝下，纹理第 0 行也在画面上方，两边一致 ——
// 翻过来反而会上下颠倒。
layout(location = 0) out vec2 vUv;

void main() {
    vec2 pos[4] = vec2[](vec2(-1.0, -1.0), vec2(1.0, -1.0), vec2(-1.0, 1.0), vec2(1.0, 1.0));
    vec2 p = pos[gl_VertexIndex];
    vUv = p * 0.5 + 0.5;
    gl_Position = vec4(p, 0.0, 1.0);
}
