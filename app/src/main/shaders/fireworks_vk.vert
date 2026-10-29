#version 450

/*
 * 烟花背景 pass —— 对应 GLES 的 fireworks_vs.glsl。
 *
 * 顶点已在 Java 侧按 GLES 的正交投影算成裁剪坐标（背景两张图各自的位置与 UV
 * 都在那里定），这里只做 Vulkan 的裁剪空间修正：y 取反，z 从 [-w, w] 映射到 [0, w]。
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
