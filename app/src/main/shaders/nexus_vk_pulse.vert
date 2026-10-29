#version 450

/*
 * Nexus 脉冲/光晕 pass —— 对应 GLES 的 nexus_vs.glsl。
 *
 * 顶点已在 Java 侧按每个脉冲各自的模型矩阵与正交投影算成裁剪坐标，
 * 颜色与 UV 都是逐顶点的；这里只做 Vulkan 的裁剪空间修正。
 */

const mat4 CLIP_CORRECTION = mat4(
        1.0, 0.0, 0.0, 0.0,
        0.0, -1.0, 0.0, 0.0,
        0.0, 0.0, 0.5, 0.0,
        0.0, 0.0, 0.5, 1.0);

layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec2 aTexCoord;
layout(location = 2) in vec4 aColor;

layout(location = 0) out vec2 vTexCoord;
layout(location = 1) out vec4 vColor;

void main() {
    gl_Position = CLIP_CORRECTION * vec4(aPosition, 0.0, 1.0);
    vTexCoord = aTexCoord;
    vColor = aColor;
}
