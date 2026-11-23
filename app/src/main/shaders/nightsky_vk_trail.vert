#version 450

/*
 * 星轨的顶点着色器 —— 对应 GLES 的 nightsky_trail_vs.glsl。
 *
 * 顶点位置已经是 NDC（CPU 完成投影与透视除法），这里只做 GL → Vulkan 的转换：
 * y 取反，GL 的 z=0 落到 Vulkan 的 0.5。
 */

layout(location = 0) in vec2 aPos;
layout(location = 1) in vec4 aColor;

layout(location = 0) out vec4 vColor;

void main() {
    vColor = aColor;
    gl_Position = vec4(aPos.x, -aPos.y, 0.5, 1.0);
}
