#version 300 es
precision mediump float;
in vec2 vCorner;
in vec4 vFill;
in vec3 vRing;
in float vRadius;

// 描边宽度（像素，随屏等比放大）
uniform float uRingPx;

out vec4 fragColor;

void main() {
    float d = length(vCorner) * vRadius;
    // 1px 抗锯齿，与原版 Canvas 的 drawCircle 边缘观感一致
    float edge = 1.0 - smoothstep(vRadius - 1.0, vRadius, d);
    if (edge <= 0.0) {
        discard;
    }
    float ring = smoothstep(vRadius - uRingPx - 1.0, vRadius - uRingPx, d);
    vec3 rgb = mix(vFill.rgb, vRing, ring);
    fragColor = vec4(rgb, vFill.a * edge);
}
