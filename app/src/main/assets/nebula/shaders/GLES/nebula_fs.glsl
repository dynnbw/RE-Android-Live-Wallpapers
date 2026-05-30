#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform vec2 uResolution;
uniform float uTime;
uniform vec2 uOffset;
uniform float uBrightness;
uniform float uQuality;

varying vec2 vUv;

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float noise2(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);

    float a = hash21(i + vec2(0.0, 0.0));
    float b = hash21(i + vec2(1.0, 0.0));
    float c = hash21(i + vec2(0.0, 1.0));
    float d = hash21(i + vec2(1.0, 1.0));

    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float fbm2(vec2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 5; i++) {
        v += a * noise2(p);
        p = p * 2.03 + vec2(3.2, 7.1);
        a *= 0.55;
    }
    return v;
}

float starField(vec2 uv) {
    vec2 g = floor(uv * 220.0);
    float rnd = hash21(g);
    float star = step(0.997, rnd) * pow((rnd - 0.997) / 0.003, 2.0);
    float twinkle = 0.7 + 0.3 * sin(uTime * 1.6 + rnd * 40.0);
    return star * twinkle;
}

vec3 tonemap(vec3 c) {
    c = max(c, 0.0);
    c = c / (1.0 + c);
    return pow(c, vec3(1.0 / 2.2));
}

void main() {
    vec2 uv = vUv * 2.0 - 1.0;
    float aspect = uResolution.x / max(1.0, uResolution.y);
    uv.x *= aspect;

    float parallax = (uOffset.x - 0.5) * 0.35;
    // Orion Nebula (M42) structure: main core lower, wings, bar, shell, dust lane
    vec2 p = uv * 2.5;
    // 主核整体下移
    p.y += 0.22;
    p.x += parallax;
    p.y -= 0.03;

    vec2 flow = vec2(0.04 * uTime, -0.02 * uTime);
    vec2 warp = vec2(
        fbm2(p * 1.10 + flow),
        fbm2(p * 1.10 + vec2(4.2, -2.8) - flow)
    ) - 0.5;

    vec2 q = p + warp * 0.85;
    float f1 = fbm2(q * 1.25 + vec2(1.3, 2.1));
    float f2 = fbm2(q * 2.40 - vec2(3.7, 1.5));
    float f3 = fbm2(q * 4.10 + vec2(6.1, -4.3));

    // 主核更亮更集中，位置偏下
    float core = exp(-dot((p - vec2(0.0, -0.18)) * vec2(2.5, 3.2),
                          (p - vec2(0.0, -0.18)) * vec2(2.5, 3.2)));
    // 两翼对称展开，略带弧度
    float wingA = exp(-dot((p - vec2(0.55, 0.18)) * vec2(1.7, 3.8),
                           (p - vec2(0.55, 0.18)) * vec2(1.7, 3.8)));
    float wingB = exp(-dot((p - vec2(-0.55, 0.18)) * vec2(1.7, 3.8),
                           (p - vec2(-0.55, 0.18)) * vec2(1.7, 3.8)));
    // 上方弧形弥散
    float arcShell = exp(-pow(length((p - vec2(0.0, 0.38)) * vec2(1.2, 2.8)), 1.25) * 1.18);
    // 外壳包络
    // 更宽更软的外壳，边缘渐隐
    float shell = exp(-pow(length(p * vec2(1.15, 1.65)), 1.12) * 0.82);
    // 尘带横贯主核
    float dustLane = exp(-pow((p.y + 0.08 + 0.22 * sin((p.x) * 2.8)), 2.0) * 38.0) * (0.7 + 0.3 * fbm2(p * 6.0 + vec2(-2.0, 3.0)));
    float filaments = 0.55 * f1 + 0.30 * f2 + 0.15 * f3;

    float qualityBoost = mix(0.9, 1.2, clamp(uQuality, 0.0, 1.0));
    float density = shell * (0.55 + 0.75 * filaments);
    density += 1.85 * core + 0.92 * wingA + 0.92 * wingB;
    density += 0.55 * arcShell;
    density -= dustLane * 0.55;
    // 边缘渐隐，防止硬切
    float fade = smoothstep(1.0, 0.82, length(p));
    density *= fade;
    density = clamp(density * qualityBoost, 0.0, 2.0);

    float ion = clamp(core * 1.1 + 0.35 * f2, 0.0, 1.0);

    vec3 redZone = vec3(1.00, 0.28, 0.24);
    vec3 blueZone = vec3(0.28, 0.76, 1.00);
    vec3 warmDust = vec3(0.95, 0.62, 0.40);

    vec3 nebula = mix(redZone, blueZone, ion);
    nebula = mix(nebula, warmDust, clamp(dustLane * 0.55, 0.0, 1.0));
    nebula *= density * (1.70 * uBrightness);

    vec3 bg = mix(vec3(0.008, 0.008, 0.026), vec3(0.018, 0.010, 0.040), 1.0 - vUv.y);
    float stars = starField(vUv) + 0.55 * starField(vUv * 1.9 + 13.4);
    bg += stars * vec3(0.95, 0.97, 1.0);

    vec3 color = bg + nebula;
    gl_FragColor = vec4(tonemap(color), 1.0);
}