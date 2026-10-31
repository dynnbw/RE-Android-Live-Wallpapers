#version 450

layout(push_constant) uniform PushConstants {
    mat4 uMvp;
    float uAddAlpha;
    float uLight;
} pc;

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec2 aTexCoord;

layout(location = 0) out vec2 vTexCoord;

void main() {
    vec4 clip = pc.uMvp * vec4(aPosition, 1.0);
    gl_Position = vec4(clip.x, -clip.y, 0.5 * clip.z + 0.5 * clip.w, clip.w);
    vTexCoord = aTexCoord;
}
