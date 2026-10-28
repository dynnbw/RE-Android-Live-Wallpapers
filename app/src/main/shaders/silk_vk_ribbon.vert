#version 450

/*
 * Silk 丝带的顶点着色器，与 GLES 版 silk_vs.glsl 等价。
 *
 * 原版把世界坐标 0..1080 x 0..1920 硬编码进 CC_MVPMatrix，这里原样保留；
 * GL → Vulkan 的裁剪空间修正（y 取反、z 映射到 [0,1]）放在 uClip 里左乘。
 *
 * 顶点按三角形条带交错排列：A0, B0, A1, B1 ... A64, B64，其中 A 是 y1 行、
 * B 是 y2 行。条带划出的三角形与 GLES 的索引表 [i, i+1, i+65] /
 * [i+65, i+66, i+1] 是同一组，只是对角线的两个顶点顺序不同（不背面剔除）。
 */

layout(push_constant) uniform PushConstants {
    mat4 uClip;
    vec4 uRotateFlash;  // (sin(rot), cos(rot), flash, 未用)
    vec4 uNewPos;
    vec4 uOriginColor;  // rgb = 主题色，a = 1
    vec4 uParams;       // (originAlpha, decayLen, divFactor, decay)
} pc;

layout(location = 0) in vec2 aPosition;
layout(location = 1) in float aColor;
layout(location = 2) in vec2 aCoord;

layout(location = 0) out float vFragmentColor;
layout(location = 1) out vec2 vCoord;
layout(location = 2) out vec2 vLocalPos;
layout(location = 3) out float vFlash;

// 原版 MVPMatrix0..3，列主序；与 silk_vs.glsl 的 #define 逐字一致。
const mat4 CC_MVP = mat4(
        vec4(3.0792017, 0.0, 0.0, 0.0),
        vec4(0.0, 1.7320509, 0.0, 0.0),
        vec4(0.0, 0.0, -1.00766277, -1.0),
        vec4(-1662.76892, -1662.7688, 1652.68188, 1660.03809));

void main() {
    // 原版 rotation_matrix：第 0 列 (cos, -sin, 0, 0)，第 1 列 (sin, cos, 0, 0)。
    mat4 rotation = mat4(
            vec4(pc.uRotateFlash.y, -pc.uRotateFlash.x, 0.0, 0.0),
            vec4(pc.uRotateFlash.x, pc.uRotateFlash.y, 0.0, 0.0),
            vec4(0.0, 0.0, 1.0, 0.0),
            vec4(0.0, 0.0, 0.0, 1.0));
    // a_position 在 GLES 里是 vec4，只填了 x、y，z=0、w=1。
    vec4 position = rotation * vec4(aPosition, 0.0, 1.0) + pc.uNewPos;
    gl_Position = pc.uClip * (CC_MVP * position);

    vFragmentColor = aColor;
    vCoord = aCoord;
    // 原版 localPos = a_position，片元里只用到 x。
    vLocalPos = aPosition;
    vFlash = pc.uRotateFlash.z;
}
