#version 450

// 原版片元着色器是 precision mediump float；这里保持同一精度等级。
precision mediump float;

/*
 * 按到圆心的距离同时画出填充与描边 —— 与 GLES 版 bokeh_fs.glsl 逐行一致。
 * 混合由 pass 的 SRC_ALPHA / ONE_MINUS_SRC_ALPHA 因子完成，着色器本身不做混合。
 */

layout(push_constant) uniform PushConstants {
    mat4 uMvp;
    float uRingPx;
} pc;

layout(location = 0) in vec2 vCorner;
layout(location = 1) in vec4 vFill;
layout(location = 2) in vec3 vRing;
layout(location = 3) in float vRadius;

layout(location = 0) out vec4 fragColor;

void main() {
    float d = length(vCorner) * vRadius;
    // 1px 抗锯齿，与原版 Canvas 的 drawCircle 边缘观感一致
    float edge = 1.0 - smoothstep(vRadius - 1.0, vRadius, d);
    if (edge <= 0.0) {
        discard;
    }
    float ring = smoothstep(vRadius - pc.uRingPx - 1.0, vRadius - pc.uRingPx, d);
    vec3 rgb = mix(vFill.rgb, vRing, ring);
    fragColor = vec4(rgb, vFill.a * edge);
}
