#version 450

/*
 * 散景彩虹的四边形顶点 —— 对应 GLES 版 bokeh_vs.glsl。
 *
 * 顶点是屏幕像素坐标：corner 在 [-1, 1]，position = center + corner * radius。
 * uMvp 已经乘过 GL → Vulkan 的裁剪空间修正（y 取反、z 从 [-1, 1] 映射到 [0, 1]），
 * 所以位置与 GLES 逐字一致。
 */

layout(push_constant) uniform PushConstants {
    mat4 uMvp;
    float uRingPx;
} pc;

layout(location = 0) in vec2 aCorner;
layout(location = 1) in vec2 aCenter;
layout(location = 2) in float aRadius;
layout(location = 3) in vec4 aFill;
layout(location = 4) in vec3 aRing;

layout(location = 0) out vec2 vCorner;
layout(location = 1) out vec4 vFill;
layout(location = 2) out vec3 vRing;
layout(location = 3) out float vRadius;

void main() {
    vec2 pos = aCenter + aCorner * aRadius;
    gl_Position = pc.uMvp * vec4(pos, 0.0, 1.0);
    vCorner = aCorner;
    vFill = aFill;
    vRing = aRing;
    vRadius = aRadius;
}
