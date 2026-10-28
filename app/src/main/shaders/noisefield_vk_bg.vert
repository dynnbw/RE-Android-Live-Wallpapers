#version 450

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec4 aColor;

layout(location = 0) out vec4 vColor;

void main() {
    vColor = aColor;
    // GLES 直接写裁剪坐标 (x, y, 0, 1)；Vulkan 这里 y 取反，z 由 0 映射到 0.5
    gl_Position = vec4(aPosition.x, -aPosition.y, 0.5, 1.0);
}
