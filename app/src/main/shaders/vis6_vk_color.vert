#version 450

/*
 * vis6（环形频谱）的重着色圆环 —— 对应 GLES 版
 * musicvis/shaders/GLES/musicvis_wave_color_vs.glsl。
 */

layout(push_constant) uniform PushConstants {
    mat4 uMvp;
} pc;

layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec3 aAdjust;

layout(location = 0) out vec3 vAdjust;

void main() {
    vAdjust = aAdjust;
    gl_Position = pc.uMvp * vec4(aPosition, 0.0, 1.0);
}
