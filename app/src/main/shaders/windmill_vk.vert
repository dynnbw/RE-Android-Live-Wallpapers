#version 450

layout(push_constant) uniform PushConstants {
    mat4 uMvp;
} pc;

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec2 aTexCoord;
layout(location = 2) in vec4 aColor;
layout(location = 3) in float aTextureSlot;

layout(location = 0) out vec2 vTexCoord;
layout(location = 1) out vec4 vColor;
layout(location = 2) flat out int vTextureSlot;

void main() {
    vTexCoord = aTexCoord;
    vColor = aColor;
    vTextureSlot = int(aTextureSlot + 0.5);
    gl_Position = pc.uMvp * vec4(aPosition, 1.0);
}
