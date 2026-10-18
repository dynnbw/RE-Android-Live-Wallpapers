#version 450

layout(push_constant) uniform PushConstants {
    mat4 uMvp;
} pc;

layout(location = 0) in vec3 aPosition;
layout(location = 1) in float aOffsetX;
layout(location = 2) in vec4 aRealColor;
layout(location = 3) in vec3 aAdjust;

layout(location = 0) out vec4 vColor;
layout(location = 1) out vec3 vAdjust;

void main() {
    vColor = aRealColor;
    vAdjust = aAdjust;
    gl_Position = pc.uMvp * vec4(aPosition.x + aOffsetX / 3.5, aPosition.y, 0.0, 1.0);
}
