#version 450

/* Holo Spiral 点阵 pass —— GLES 的 holospiral_vertex_geometry.glsl。 */

layout(push_constant) uniform PushConstants {
    mat4 uModelViewProj;
    float uMaxPointSize;
    float uFarPlane;
} pc;

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec4 aColor;

layout(location = 0) out vec4 vColor;
layout(location = 1) out float vFactor1;
layout(location = 2) out float vFactor2;

void main() {
    vec4 clip = pc.uModelViewProj * vec4(aPosition, 1.0);

    // 距离衰减按 GL 裁剪空间的 z 算，必须在 y 翻转之前做
    float factor = (pc.uFarPlane - abs(clip.z)) / pc.uFarPlane;
    gl_PointSize = factor * pc.uMaxPointSize;

    vColor = aColor;
    vColor.a = vColor.a * factor;
    vFactor2 = abs((factor * 2.0) - 1.0);
    vFactor1 = (1.0 - vFactor2) * 0.2;

    // GL 裁剪空间 → Vulkan：y 取反，z 从 [-w,w] 映射到 [0,w]
    gl_Position = vec4(clip.x, -clip.y, clip.z * 0.5 + clip.w * 0.5, clip.w);
}
