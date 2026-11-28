#version 300 es
// 程序化极光: 参考实现(SurAurora)的低步数版本. 30 步 raymarch 降到 12 步,
// 每步 5 次三角域扭曲降到 4 次; 步距、权重与逐层配色都是预计算常量.
precision highp float;

out vec4 fragColor;

uniform vec2 uResolution;
uniform float uTime;
uniform float uEnvelope; // 0..1 亚暴增亮
uniform float uPalette;  // 0 物理绿 / 1 青绿 / 2 冷紫 / 3 暖红

in vec2 vUv;

// 0.8 + i^1.4 * 0.002
const float STEP_PT[12] = float[12](
        0.80000, 0.80200, 0.80528, 0.80931, 0.81393, 0.81904, 0.82457, 0.83049, 0.83676,
        0.84335, 0.85024, 0.85741);
// exp2(-i*0.12 - 2.2) * smoothstep(0, 5, i)
const float STEP_W[12] = float[12](
        0.000000, 0.020828, 0.064868, 0.109885, 0.139813, 0.143587, 0.132127, 0.121582,
        0.111878, 0.102949, 0.094732, 0.087171);
// sin(1 - vec3(2.15,-0.5,1.2) + i*0.2251) * 0.8 + 0.5, 12 步铺满原版 30 步的色相行程
const vec3 STEP_TINT[12] = vec3[12](
        vec3(0.0000, 1.2980, 0.3411),
        vec3(0.0000, 1.2905, 0.5201),
        vec3(0.0000, 1.2431, 0.6981),
        vec3(0.1344, 1.1582, 0.8662),
        vec3(0.3025, 1.0400, 1.0157),
        vec3(0.4806, 0.8946, 1.1392),
        vec3(0.6596, 0.7293, 1.2305),
        vec3(0.8306, 0.5524, 1.2849),
        vec3(0.9849, 0.3729, 1.2996),
        vec3(1.1147, 0.1998, 1.2740),
        vec3(1.2135, 0.0418, 1.2094),
        vec3(1.2763, 0.0000, 1.1089));

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float tri(float x) {
    return clamp(abs(fract(x) - 0.5), 0.01, 0.49);
}

vec2 tri2(vec2 p) {
    return vec2(tri(p.x) + tri(p.y), tri(p.y + tri(p.x)));
}

/** 绕 RGB 灰轴旋转, 只改色相. */
vec3 hueRotate(vec3 c, float angle) {
    const vec3 k = vec3(0.57735027);
    float cosA = cos(angle);
    return c * cosA + cross(k, c) * sin(angle) + k * dot(k, c) * (1.0 - cosA);
}

/** 参考实现的三角域扭曲噪声; 迭代次数从 5 降到 4. */
float surNoise(vec2 p, float timeCos, float timeSin) {
    float intensity = 1.8;
    float size = 2.5;
    float rz = 0.0;
    // 起始按 p.x*0.06 旋转, 角度很小, 用泰勒展开免掉三角函数
    float a = p.x * 0.06;
    float ca = 1.0 - a * a * 0.5;
    float sa = a - a * a * a * 0.1666667;
    p = vec2(ca * p.x - sa * p.y, sa * p.x + ca * p.y);
    vec2 bp = p;
    for (int i = 0; i < 3; i++) {
        vec2 dg = tri2(bp * 1.85) * 0.75;
        dg = vec2(timeCos * dg.x - timeSin * dg.y, timeSin * dg.x + timeCos * dg.y);
        p -= dg / size;
        bp *= 1.3;
        size *= 0.45;
        intensity *= 0.42;
        p *= 1.21 + (rz - 1.0) * 0.02;
        rz += tri(p.x + tri(p.y)) * intensity;
        // 固定 -0.3 弧度旋转
        p = vec2(0.95534 * p.x + 0.29552 * p.y, -0.29552 * p.x + 0.95534 * p.y);
    }
    return clamp(1.0 / pow(max(rz * 29.0, 1e-6), 1.3), 0.0, 0.55);
}

void main() {
    float aspect = uResolution.x / max(uResolution.y, 1.0);
    vec3 dir = normalize(vec3((vUv.x * 2.0 - 1.0) * aspect, vUv.y, -1.0));
    // 漂移速度: 参考实现是 1 rad/s, 这里压到 0.018 rad/s; +5 rad 让启动时正对一条帘幕
    float ang = uTime * 0.018 + 5.0;
    float ct = cos(ang);
    float st = sin(ang);

    vec3 color = vec3(0.0);
    vec3 avg = vec3(0.0);
    for (int i = 0; i < 12; i++) {
        float fi = float(i);
        float of = 0.006 * hash21(vec2(dir.x, dir.y) * 64.0) * smoothstep(0.0, 5.0, fi);
        // 视线到第 i 层帘幕的交点, p = bpos.zx
        float pt = STEP_PT[i] / max(dir.y * 2.0 + 0.8, 0.8) - of;
        // 横向放大采样频率: 单场在高频下会分成多条帘, 左右更均匀
        vec2 p = vec2(-6.7 + pt * dir.z, pt * dir.x * 2.0);
        float n = surNoise(p, ct, st);
        avg = mix(avg, STEP_TINT[i] * n, 0.5);
        color += avg * STEP_W[i];
    }
    color = max(color, vec3(0.0)) * (0.9 + 0.5 * uEnvelope) * 2.0;

    if (uPalette > 3.5) {
        // 青色: 参考实现的原始配色, 不做任何偏移
    } else if (uPalette > 2.5) {
        color = hueRotate(color, -2.2);
    } else if (uPalette > 1.5) {
        color = hueRotate(color, 2.1);
    } else if (uPalette > 0.5) {
        color = hueRotate(color, 0.5);
    } else {
        // 物理绿: 压青蓝, 抬绿, 更接近 557.7nm 的氧原子绿
        color *= vec3(1.0, 1.3, 0.55);
    }

    // 极光占天幕约四分之三, 靠近地面淡出
    float mask = smoothstep(0.03, 0.15, vUv.y);
    color *= mask;

    // 加色叠加(SRC_ALPHA, ONE): alpha 作为极光浓淡, 天空与星星不受影响
    float peak = max(max(color.r, color.g), color.b);
    float coverage = clamp(0.06 + peak * 1.9, 0.0, 0.9) * mask;
    fragColor = vec4(color, coverage);
}
