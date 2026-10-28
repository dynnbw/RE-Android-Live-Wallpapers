#version 450

/*
 * 全屏背景四边形，与 GLES 版 silk_bg_vs.glsl 等价。
 * uClip 是 GL → Vulkan 的裁剪空间修正（y 取反、z 映射到 [0,1]）。
 */

layout(push_constant) uniform PushConstants {
    mat4 uClip;
} pc;

layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec2 aTexCoord;

layout(location = 0) out vec2 vTexCoord;

void main() {
    vTexCoord = aTexCoord;
    gl_Position = pc.uClip * vec4(aPosition, 0.0, 1.0);
}
