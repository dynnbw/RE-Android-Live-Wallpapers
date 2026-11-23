#version 450

/*
 * GeekLog 的字符 quad 批量 —— 对应 GLES 版内联的 aPosition/aUV/aColor 顶点着色器。
 *
 * 顶点位置已经是 GL 裁剪空间坐标，GLES 版原样输出。这里只做 Vulkan 的裁剪空间修正：
 * y 取反，z 从 [-w, w] 映射到 [0, w]。图集坐标与颜色原样传给片元着色器。
 */

const mat4 CLIP_CORRECTION = mat4(
        1.0, 0.0, 0.0, 0.0,
        0.0, -1.0, 0.0, 0.0,
        0.0, 0.0, 0.5, 0.0,
        0.0, 0.0, 0.5, 1.0);

layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec2 aUV;
layout(location = 2) in vec3 aColor;

layout(location = 0) out vec2 vUV;
layout(location = 1) out vec3 vColor;

void main() {
    gl_Position = CLIP_CORRECTION * vec4(aPosition, 0.0, 1.0);
    vUV = aUV;
    vColor = aColor;
}
