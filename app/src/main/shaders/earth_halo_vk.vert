#version 450

/*
 * 大气光晕：屏幕空间四边形，对应 GLES 的 earth_overlay_vs.glsl。
 * 顶点已是像素坐标，uOrtho 在 Java 侧左乘了 GL → Vulkan 的裁剪空间修正。
 */

layout(push_constant) uniform PushConstants {
    mat4 uOrtho;
} pc;

layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec2 aTexCoord;

layout(location = 0) out vec2 vTexCoord;

void main() {
    vTexCoord = aTexCoord;
    gl_Position = pc.uOrtho * vec4(aPosition, 0.0, 1.0);
}
