#version 450
// vis2（PCM 波形）的 HSL 重新着色路径 —— 对应 GLES 版 musicvis_wave_color_vs.glsl。
//
// 逐顶点的 hue / saturation / brightness 由 WaveScene.updateAdjustBuffer 填入；
// 着色器打开时 hue >= 0，关闭时 hue = -1（片元着色器据此跳过换算）。
layout(push_constant) uniform PushConstants {
    mat4 uMvp;
} pc;

layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec2 aTexCoord;
layout(location = 2) in vec3 aAdjust;

layout(location = 0) out vec2 vTex;
layout(location = 1) out vec3 vAdjust;

void main() {
    vTex = aTexCoord;
    vAdjust = aAdjust;
    gl_Position = pc.uMvp * vec4(aPosition, 0.0, 1.0);
}
