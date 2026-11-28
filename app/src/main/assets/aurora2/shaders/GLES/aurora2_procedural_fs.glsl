#version 300 es
// 程序化极光: 伪体积帘幕. 颜色按帘内高度分层(低缘蓝紫/主体绿/顶部暗红),
// 色相档只旋转色相, 不改变分层结构.
precision highp float;

out vec4 fragColor;

uniform vec2 uResolution;
uniform float uTime;
uniform float uEnvelope; // 0..1 亚暴增亮
uniform float uPalette;  // 0 物理绿 / 1 青绿 / 2 冷紫 / 3 暖红

in vec2 vUv;

const vec3 AURORA_BLUE = vec3(0.10, 0.25, 1.00);
const vec3 AURORA_GREEN = vec3(0.10, 1.00, 0.30);
const vec3 AURORA_RED = vec3(1.00, 0.15, 0.10);

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(hash21(i), hash21(i + vec2(1.0, 0.0)), u.x),
        mix(hash21(i + vec2(0.0, 1.0)), hash21(i + vec2(1.0, 1.0)), u.x),
        u.y);
}

float fbm2(vec2 p) {
    return 0.6 * noise(p) + 0.4 * noise(p * 2.0 + 7.3);
}

/** 绕 RGB 灰轴旋转, 只改色相. */
vec3 hueRotate(vec3 c, float angle) {
    const vec3 k = vec3(0.57735027);
    float cosA = cos(angle);
    return c * cosA + cross(k, c) * sin(angle) + k * dot(k, c) * (1.0 - cosA);
}

/** 帘内高度决定颜色: 低缘蓝紫, 主体绿, 顶部暗红只在亮处出现. */
vec3 paletteColor(float h, float shape) {
    vec3 c = mix(AURORA_BLUE, AURORA_GREEN, smoothstep(0.0, 0.10, h));
    float redMix = smoothstep(0.72, 0.98, h) * smoothstep(0.45, 0.85, shape);
    c = mix(c, AURORA_RED, redMix * 0.22);
    if (uPalette > 2.5) {
        c = hueRotate(c, -2.2);
    } else if (uPalette > 1.5) {
        c = hueRotate(c, 2.1);
    } else if (uPalette > 0.5) {
        c = hueRotate(c, 0.5);
    }
    return c;
}

void main() {
    float t = uTime;

    // 帘面折皱: 整条帘在横向被缓慢推来推去
    float warp = (fbm2(vec2(vUv.x * 3.0 + t * 0.02, 11.0)) - 0.5) * 0.18;
    float x = vUv.x + warp;

    // 丝缕在竖直方向被折皱带偏, 不是笔直的栅栏
    float vvMid = (vUv.y - 0.16) / 0.78;
    float xf = x + 0.05 * (noise(vec2(x * 4.0, vvMid * 2.5 - t * 0.04)) - 0.5);

    // 帘面自身的疏密与丝缕: 四层共用同一份, 只在帘底高度上错开
    float density = smoothstep(0.28, 0.75, fbm2(vec2(xf * 2.6 + t * 0.02, vvMid * 1.4 + 2.0)));
    float ra = noise(vec2(xf * 95.0, vvMid * 1.6 + t * 0.8));
    float rb = noise(vec2(xf * 21.0 - 4.0, vvMid * 0.8 + t * 0.35));
    float rays = smoothstep(0.22, 0.78, ra * 0.62 + rb * 0.52);
    float topJitter = noise(vec2(x * 5.0 - t * 0.05, 9.0));

    // 大尺度分片: 全屏两三条主帘, 之间留暗
    float lobes = 0.30 + 0.70 * smoothstep(0.34, 0.62, noise(vec2(vUv.x * 2.4 + t * 0.008, 2.7)));

    vec3 color = vec3(0.0);
    float energySum = 0.0;
    const float depths[4] = float[4](0.6, 0.8, 1.0, 1.25);
    for (int i = 0; i < 4; i++) {
        float depth = depths[i];
        // 每条帘的下缘都不一样高, 高频抖动让边缘参差
        float bottom =
                0.16
                + (depth - 1.0) * 0.05
                + 0.13 * (fbm2(vec2(x * 1.3, t * 0.008 + depth * 3.0)) - 0.5)
                + 0.035 * (noise(vec2(x * 30.0, t * 0.2 + depth * 2.0)) - 0.5);
        float height = 0.72 + 0.06 * depth;
        float vv = (vUv.y - bottom) / height;
        float h = clamp(vv, 0.0, 1.0);

        // 上缘被噪声溶解, 不是整齐的横切
        float top = 1.0 - smoothstep(0.45, 1.05, vv + 0.35 * (topJitter - 0.5));
        float band = smoothstep(-0.03, 0.03, vv) * top;
        float raysLayer = rays * (1.0 - 0.6 * smoothstep(0.15, 0.95, vv));
        // 帘底亮边
        float rim = 1.0 - smoothstep(0.0, 0.10, abs(vv - 0.02));

        float energy =
                band * (0.10 + 0.9 * density) * (0.30 + 0.9 * raysLayer) * lobes
                * (1.0 + 0.3 * rim);
        float brightness = 0.55 + 0.45 * depth;
        color += paletteColor(h, energy) * energy * brightness;
        energySum += energy * brightness;
    }

    // 色调映射: 亮帘不至于削顶成白色
    color = 1.0 - exp(-color * (0.9 + 0.6 * uEnvelope) * 1.6);

    // 贴近地面的一侧淡出, 极光只留在天空里
    float horizon = smoothstep(0.03, 0.14, vUv.y);
    color *= horizon;

    // 加色叠加(SRC_ALPHA, ONE): alpha 作为极光浓淡, 天空与星星不受影响
    float coverage = clamp(0.10 + energySum * 1.0, 0.0, 0.9) * horizon;
    fragColor = vec4(color, coverage);
}
