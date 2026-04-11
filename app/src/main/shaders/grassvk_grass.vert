#version 450

layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec4 aColor;
layout(location = 2) in vec2 aTexCoord;

layout(location = 0) out vec4 vColor;
layout(location = 1) out vec2 vTexCoord;

layout(push_constant) uniform GrassPushConstants {
    mat4 uMVP;
} uPush;

void main() {
    gl_Position = uPush.uMVP * vec4(aPosition, 0.0, 1.0);
    gl_Position.y = -gl_Position.y;
    vColor    = aColor;
    vTexCoord = aTexCoord;
}
