#version 450

/*
 * 蒲公英的天空四边形 —— 对应 GLES 的 dandelion_sky_vs.glsl。
 *
 * 顶点已经在 CPU 上算成屏幕像素；uMvp 是正交投影乘过 GL → Vulkan 裁剪空间修正，
 * 所以位置与 GLES 逐字一致。每个顶点顺带带上它在设计稿里的纵坐标，片元按它查色带。
 */

layout(push_constant) uniform PushConstants {
    mat4 uMvp;
} pc;

layout(location = 0) in vec2 aPos;
layout(location = 1) in float aDesignY;

layout(location = 0) out float vDesignY;

void main() {
    gl_Position = pc.uMvp * vec4(aPos, 0.0, 1.0);
    vDesignY = aDesignY;
}
