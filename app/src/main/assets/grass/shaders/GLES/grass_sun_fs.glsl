#version 300 es
precision highp float;

out vec4 fragColor;

in highp vec2 vUv;
in highp vec4 vLight;
in highp float vCircleNumber;
in highp float vRandSeed;
in highp vec2 vSunPos;
in highp mat4 vWorldSpaceMat;

uniform float uOpacity;
uniform sampler2D uSunRamp;
uniform sampler2D uAnnulusRamp;
uniform sampler2D uRays;
uniform float uCircleAlpha;
uniform float uCircleOffset;
uniform float uCircleOffsetRatio;
uniform float uAnnulusAlpha;
uniform float uRayAlpha;
/** 动态射线（三相位弱光线）的强度。参考实现里是硬编码的 1.2。 */
uniform float uDynamicRayAlpha;
/** 成对光环的亮度。参考实现里是硬编码的 0.6。 */
uniform float uFlareBrightness;
uniform float uQuality;
uniform float u22Open;
uniform bool uCloseCircle;

#define uR 1.25
#define uG 1.61
#define uB 1.84

// 圆形光斑
#define CIRCLE_SIZE 0.95

#define DECAY 6.
#define PI 3.1415926

// 噪声图的替身：原来这里按 (value+seed)*1.5/256 采样一张 256² 噪声图，
// 只当"每个光斑一套确定的随机数"用。位混合哈希是同一角色，且不用带 400 KB 资源。
vec3 hash13(float p) {
    vec3 p3 = fract(vec3(p) * vec3(.1031, .11369, .13787));
    p3 += dot(p3, p3.yzx + 19.19);
    return fract(vec3((p3.x + p3.y) * p3.z, (p3.x + p3.z) * p3.y, (p3.y + p3.z) * p3.x));
}

vec3 getRandomLite(float value, float seed) {
    return hash13(value + seed * 137.0);
}

float perFromVal(float val, float from, float to) {
    return (val - from) / (to - from);
}

const vec3 colorLUT[] = vec3[](vec3(0.1686, 0.7412, 0.2353), //0
vec3(1.0, 0.3843, 0.0), //1
vec3(1.0, 0.5333, 0.0), //2
vec3(0.7804, 1.0, 0.3725), //3
vec3(1.0, 0.5333, 0.0), //4
vec3(1.0, 0.3843, 0.0), //5
vec3(1.0, 0.5333, 0.0), //6
vec3(1.0, 0.5333, 0.0), //7
vec3(1.0, 0.2588, 0.1294));//8

/*获得每个圆形的颜色*/
vec3 getColor(int index) {
    return colorLUT[index];
}

/* 把一维半径表当纹理采样：u 走半径，v 固定取行心。
   sun_ramp / sun_annulus_ramp 都是 540x1，v=0.5 正好是那一行。 */
vec4 circleTex(sampler2D tex, vec2 st) {
    return texture(tex, vec2(length(st * 2.0 - 1.0), 0.5));
}

vec3 ring(vec2 uv, vec2 pos, float dist) {
    vec2 uvd = uv * (length(uv));
    float r = max(2. / (1. + 32. * pow(length(uvd + (dist - .05) * pos), 2.)), 0.) * .25;
    float g = max(2. / (1. + 32. * pow(length(uvd + dist * pos), 2.)), 0.) * .23;
    float b = max(2. / (1. + 32. * pow(length(uvd + (dist + .05) * pos), 2.)), 0.) * .21;
    return vec3(r, g, b);
}

vec3 lensflare(vec2 uv, vec2 pos, float brightness, float size) {
    vec3 c = ring(uv, pos, -1.) * .5 * size;
    c += ring(uv, pos, 1.) * .5 * size;
    return c * brightness;
}

vec3 lensflareSimple(vec2 uv, vec2 pos, float brightness, float size) {
    vec2 uvd = uv * (length(uv));
    float g = max(2. / (1. + 32. * pow(length(uvd - pos), 2.)) + 2. / (1. + 32. * pow(length(uvd + pos), 2.)), 0.);
    return vec3(g, g, g) * .115 * size * brightness;
}

/* 绘制圆形光斑 */
vec3 getCircle(int index, vec2 uv, float size, float dist, vec2 sunPos, float blur) {
    blur = mix(0.45, 1., blur); // 将 0-1 的模糊值映射到 0.45-1
    vec3 color = getColor(index); // 获取预置的颜色
    vec3 f = vec3(0.); // 开始绘制
    // 计算中心圆形光斑
    float c = max(0.01 - pow(length(uv + sunPos * dist), size * 1.4), 0.) * 50.;
    float c1 = (c > 0.1) ? (0.1 - (c - 0.1) / 5.) : (c / 1.4) * blur * 2.;
    float c2 = mix(c1, c, (blur > 0.5) ? ((blur - 0.5) * 2.) : 0.);

    float alpha = uCircleAlpha * mix(1., 0.4, (size - 1.) * 3.) * ((blur >= 0.82) ? 0.5 : 1.);
    f += c2 * color * alpha;

    return f - 0.01;
}

/* 计算天气效果 */
vec4 getWeatherEffect(vec2 uv) {
    float number = uCloseCircle ? 0.0 : vCircleNumber;
    // 开始绘制
    vec3 color = vec3(0.);

    // 太阳方向。**参考实现这里没有防零**：太阳正好落在屏幕正中时 vSunPos == 0，
    // normalize(0) 和 /0 都是 NaN。grass 的太阳位置是连续走过的、会真的经过中心，
    // 于是会闪一下 NaN（表现为一个黑块或整片花掉）。用 max() 兜住分母即可，
    // 代价是正中几个像素内的方向不可靠 —— 看不出来。
    float lsun = length(vSunPos);
    float invLsun = 1.0 / max(lsun, 1.0E-4);
    vec2 sunDir = vSunPos * invLsun;

    // 优化圆形光斑
    if (uQuality > 0.) {
        vec2 uvScaled = uv * 4.0;
        vec2 sunPosScaled = vSunPos.xy * 1.5;
        vec3 lensflareColor = vec3(1.4, 1.2, 1.);
        const float MAX_ITER = 10.;

        bool enableLensflare = u22Open > 0.5;
        float d = abs(vSunPos.x * uv.y - vSunPos.y * uv.x) * invLsun;

        bool mainCondition = d > 0.1;
        float l = dot(sunDir, uv);
        float threshL = length(clamp(vSunPos * uCircleOffset, -1.0, 0.0));
        if (mainCondition || (!mainCondition && (l > threshL + 0.1 || l < -0.8 * lsun + threshL - 0.1))) {
            color -= 0.01 * number;
            if (enableLensflare) {
                color += lensflareSimple(uvScaled, sunPosScaled, uFlareBrightness, 4.0) * lensflareColor;
            }
        } else {
            for (float i = 0.; i < MAX_ITER; i++) {
                if (i >= number) break;
                vec3 rg = getRandomLite(i, vRandSeed);
                float size = (rg.r * 0.33 + CIRCLE_SIZE) + vLight.a;
                float randFactor = rg.g * mix(0.5, 0.2, uCircleOffsetRatio);
                float dist = (uCircleOffset + randFactor + i / 30. + i * i * 0.01) + vLight.a;
                color += getCircle(int(i), uv, size, dist, vSunPos, rg.b);
            }

            if (enableLensflare) {
                color += lensflare(uvScaled, sunPosScaled, uFlareBrightness, 4.0) * lensflareColor;
            }
        }
    }

    // 环形光晕。st 是中心坐标系，再平移到太阳位置
    vec2 diff = uv - vSunPos;
    vec2 st = uv * 3.0;
    st -= vSunPos * 2.5;

    if (u22Open > 0.5) {
        vec4 clipSpacePos = vWorldSpaceMat * vec4(st, 0.0, 1.0);
        vec2 ndcSpacePos = clipSpacePos.xy / clipSpacePos.w + 0.5;
        color += circleTex(uAnnulusRamp, ndcSpacePos.xy).rgb * uAnnulusAlpha * 4.0;
    }

    // 射线
    // 静态部分
    vec2 st2 = clamp((diff) / 3.0 + 0.5, 0.0, 1.0);
    vec3 obviousLine = texture(uRays, st2).rgb;
    // 同一个防零：uv 正好等于 vSunPos 时（太阳正中心那一个像素）normalize 也是 0/0。
    vec2 toSun = vSunPos - uv;
    vec2 toSunDir = toSun / max(length(toSun), 1.0E-4);
    float fan = min(max(0.3, pow(perFromVal(dot(sunDir, toSunDir), -1.0, 1.0), 3.0)), 0.8);
    fan = max(pow(1.0 - length(toSun), 3.0), fan);
    color += obviousLine * fan * uRayAlpha;

    // 动态部分
    const vec3 deltas = vec3(0.0, PI / 5.0, PI / 2.0);
    const vec3 threeDeltas = 3.0 * deltas;
    const vec3 nineDeltas = 9.0 * deltas;
    const vec3 cos9delta = vec3(cos(nineDeltas.x), cos(nineDeltas.y), cos(nineDeltas.z));
    const vec3 sin9delta = vec3(sin(nineDeltas.x), sin(nineDeltas.y), sin(nineDeltas.z));
    float angle = atan(diff.y, diff.x);
    float threeAngle = 3.0 * angle;
    float nineAngle = 9.0 * angle;
    float cos9a = cos(nineAngle);
    float sin9a = sin(nineAngle);
    vec3 cos9 = cos9a * cos9delta - sin9a * sin9delta;
    vec3 sin9 = sin9a * cos9delta + cos9a * sin9delta;
    vec3 angle3 = threeAngle + threeDeltas;
    vec3 term1 = sin(angle3 + cos9);
    vec3 term2 = abs(sin9);
    vec3 dimLine = abs(term1) * term2 * vLight.rgb;
    color += (dimLine.x + dimLine.y + dimLine.z) * uDynamicRayAlpha * (1.0 - st2.x);

    // 太阳
    vec2 st3 = clamp(diff + 0.5, 0.0, 1.0);
    color += circleTex(uSunRamp, st3).rgb * vec3(uR, uG, uB) * 1.02;

    vec2 st4 = vec2(length(st3 * 2.0 - 1.0) * 2.8, 0.5);
    vec4 glow = vec4(0.8509803922, 0.6039215686, 0.3490196078, 0.5 / exp(st4.x * st4.x));
    color.rgb += glow.rgb * glow.a;

    // 亮度抑制
    color *= exp(1.0 - length(diff)) / DECAY;

    // 地平线淡出。grass 的 vUv.y 是**下正**（+0.5 = 屏幕底），参考实现是上正，
    // 所以要取负：太阳越低（vUv.y 越大）越淡。写反了太阳会整个不见。
    color = clamp(color, 0.0, 1.0) * smoothstep(-0.5, 0.1, -uv.y);

    return vec4(color, 1.);
}

void main() {
    vec4 effect = getWeatherEffect(vUv);
    effect.a *= uOpacity;
    fragColor = effect;
}
