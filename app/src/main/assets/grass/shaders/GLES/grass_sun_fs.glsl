precision mediump float;

uniform float uTime;
uniform float uOpacity;
uniform float uLineAlpha;

varying vec2 vUv;
varying vec2 vSunPos;

#define PI 3.1415926

void main() {
    vec2 diff = vUv - vSunPos;
    float dist = length(diff);

    // 太阳光盘
    float disc = 1.0 - smoothstep(0.05, 0.08, dist);

    // 辉光
    float glowDist = dist * 3.5;
    float glow = 0.5 / exp(glowDist * glowDist);

    // 颜色
    vec3 coreColor  = vec3(1.25, 1.61, 1.84);
    vec3 glowColor  = vec3(0.851, 0.604, 0.349);

    vec3 color = coreColor * disc * 0.85;
    color += glowColor * glow * 0.7;

    float angle = atan(diff.y, diff.x);

    // 光线遮罩：光盘外，短距离渐隐
    float rayMask = (1.0 - disc) * (1.0 - smoothstep(0.08, 0.18, dist));

    // 静态光线
    float l1 = abs(sin(angle * 5.0));
    float l2 = abs(sin(angle * 7.0 + 1.2));
    float l3 = abs(sin(angle * 11.0 + 2.5));
    float lines = pow(max(max(l1, l2), l3), 4.0) * rayMask;
    color += vec3(1.0, 0.85, 0.55) * lines * 0.15;

    // 动态射线
    color += abs(sin(12.0 * angle)) * vec3(1.0, 0.808, 0.392) * 0.003 * rayMask;

    // 亮度衰减
    color *= exp(1.0 - dist) / 4.0;
    color = clamp(color, 0.0, 1.0);

    // alpha
    float alpha = clamp(disc * 0.7 + glow * 2.0, 0.0, 1.0) * uOpacity;

    gl_FragColor = vec4(color, alpha);
}
