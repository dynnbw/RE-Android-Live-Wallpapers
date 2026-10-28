#version 450

/* Holo Spiral 背景 pass —— GLES 的 holospiral_vertex_background.glsl。 */

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec4 aColor;

layout(location = 0) out vec4 vColor;

void main() {
    vColor = aColor;
    // GL 裁剪空间 → Vulkan：y 取反，z 从 [-w,w] 映射到 [0,w]
    gl_Position = vec4(aPosition.x, -aPosition.y, aPosition.z * 0.5 + 0.5, 1.0);
}
