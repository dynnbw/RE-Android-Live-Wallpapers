#version 450

/*
 * 坠落机器人精灵 pass —— 对应 GLES 的 droid_sprite_vs.glsl。
 *
 * 顶点已在 Java 侧按屏幕坐标与正交投影算成裁剪坐标，这里只做 Vulkan 的裁剪空间修正。
 */

const mat4 CLIP_CORRECTION = mat4(
        1.0, 0.0, 0.0, 0.0,
        0.0, -1.0, 0.0, 0.0,
        0.0, 0.0, 0.5, 0.0,
        0.0, 0.0, 0.5, 1.0);

layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec2 aTexCoord;

layout(location = 0) out vec2 vTexCoord;

void main() {
    gl_Position = CLIP_CORRECTION * vec4(aPosition, 0.0, 1.0);
    vTexCoord = aTexCoord;
}
