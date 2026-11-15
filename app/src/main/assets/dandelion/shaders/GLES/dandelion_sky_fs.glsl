#version 300 es
// 背景是参考实现里那张纯渐变（蓝 → 青 → 绿 → 黄），没有可辨识的细节，
// 所以按几条色标用程序画出来：任何分辨率都是干净的，也不会像贴图那样被拉糊。
precision highp float;

in float vDesignY;

uniform vec3 uStops[11];
uniform float uStopPos[11];

out vec4 fragColor;

void main() {
    float y = clamp(vDesignY, uStopPos[0], uStopPos[10]);
    vec3 color = uStops[0];
    for (int i = 0; i < 10; i++) {
        float f = clamp((y - uStopPos[i]) / (uStopPos[i + 1] - uStopPos[i]), 0.0, 1.0);
        color = mix(color, uStops[i + 1], f);
    }
    // 8 位色深下这种大跨度渐变会有色带，抖一点噪点把它打散
    float noise = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);
    fragColor = vec4(color + (noise - 0.5) / 255.0, 1.0);
}
