#version 450

/*
 * LuminousDots 网格（GLES 的 8 个 renderPass）。
 *
 * uMvp 已经乘过 CLIP_CORRECTION，所以 clip.y 是 GL 侧 gl_Position.y 的相反数；
 * 渐变阈值是原版按 GL clip y 定的，这里先把它还原再比较。
 */

layout(push_constant) uniform PushConstants {
    mat4 uMvp;
    vec4 uColor;
    float uAlpha;
    float uBatteryAlpha;
    float uGradDownStart;
    float uGradUpStart;
    float uGradGapUp;
    float uGradGapDown;
    float uTexIndex;
} pc;

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec2 aTexCoord;
layout(location = 2) in float aAlpha;
layout(location = 3) in vec4 aColor;

layout(location = 0) out vec2 vTexCoord;
layout(location = 1) out float vAlpha;
layout(location = 2) out vec4 vColor;

void main() {
    vTexCoord = aTexCoord;
    vColor = aColor;

    // 原版顶点着色器里 uIsGradiatObj 恒为 0，走的是 aColor 分支：
    // vColor.a = aColor.a * u_BatteryAlpha，vAlpha 也取这个值。
    vAlpha = aColor.a * pc.uBatteryAlpha;
    vColor.a = vAlpha;

    vec4 clip = pc.uMvp * vec4(aPosition, 1.0);
    float glY = -clip.y;

    float gradientAlpha = 1.0;
    if (glY < pc.uGradDownStart && glY < 0.0) {
        if (pc.uGradGapDown > (pc.uGradDownStart - glY)) {
            gradientAlpha = 1.0 - ((pc.uGradDownStart - glY) / pc.uGradGapDown);
        } else {
            gradientAlpha = 0.0;
        }
    }
    if (glY > pc.uGradUpStart && glY > 0.0) {
        if (pc.uGradGapUp > (glY - pc.uGradUpStart)) {
            gradientAlpha = 1.0 - ((glY - pc.uGradUpStart) / pc.uGradGapUp);
        } else {
            gradientAlpha = 0.0;
        }
    }
    vAlpha = vAlpha * gradientAlpha;

    gl_Position = clip;
}
