#version 450

layout(push_constant) uniform PushConstants {
    mat4 uMvp;
    float uBrightness;
} pc;

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec2 aTexCoord;

layout(location = 0) out vec2 vTexCoord;

void main() {
    vec4 clip = pc.uMvp * vec4(aPosition, 1.0);
    // GL 裁剪空间 → Vulkan：y 取反，z 从 [-w, w] 映射到 [0, w]
    gl_Position = vec4(clip.x, -clip.y, 0.5 * clip.z + 0.5 * clip.w, clip.w);
    vTexCoord = aTexCoord;
}
