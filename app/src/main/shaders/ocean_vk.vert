#version 450

/*
 * Ocean weather sprite vertex shader.
 *
 * Positions are baked on the CPU in the exact GL clip space produced by the
 * GLES path (projection * translate * rotateZ * scale). The push constant then
 * applies the same GL -> Vulkan clip correction used by the other VK ports:
 * y is flipped and z is remapped from [-w, w] to [0, w].
 */

layout(push_constant) uniform PushConstants {
    mat4 uClip;
} pc;

layout(location = 0) in vec4 aClipPosition; // GL clip space: x, y, z, w
layout(location = 1) in vec2 aTexCoord;
layout(location = 2) in vec4 aColor;

layout(location = 0) out vec2 vTexCoord;
layout(location = 1) out vec4 vColor;

void main() {
    vTexCoord = aTexCoord;
    vColor = aColor;
    gl_Position = pc.uClip * aClipPosition;
}
