#version 300 es
precision mediump float;
out vec4 fragColor;
uniform sampler2D uMoonBase;
uniform sampler2D uMoonMask;
uniform float uPhaseAngle;
// Disc rotation in degrees (parallactic angle). Rotates the WHOLE moon --
// both the surface texture and the terminator -- not just the phase mask.
uniform float uRotation;
uniform float uBrightness;
uniform float uMoonAlpha;
// 白天权重：1 是白天、0 是夜里。**连续量，不许拿它做真假判断** ——
// 它原来是 `uniform int uIsDaytime`，配合渲染器里二选一的 setBlendFunc，
// 太阳高度角一过 0 月面暗部就"啪"地变实。详见文件末尾输出那一段。
uniform float uDayWeight;
uniform float uContrast;
uniform float uSaturation;
uniform float uBlueTint;
uniform int uEclipseType;
uniform float uEclipseFraction;
uniform float uEclipsePhase;
uniform vec2 uShadowOffset;
uniform vec3 uShadowColor;
uniform vec3 uPenumbraColor;
uniform float uSolarOcclusion;
in highp vec2 vTexCoord;
void main() {
  vec2 uv = vTexCoord * 2.0 - 1.0;

  // Whole-disc rotation about the centre. Sampling at R(rot)*uv rotates the
  // rendered disc by -rot, and because uv is what the lighting below works
  // in, the terminator turns with the surface instead of staying upright.
  // Negated because this pass' quad puts v=0 at the top while the Vulkan one
  // puts v=1 there, so the same rotation formula comes out mirrored. Done this
  // way around so the two renderers agree; the angle itself is unchanged.
  float rot = radians(-uRotation);
  float cr = cos(rot);
  float sr = sin(rot);
  uv = vec2(uv.x * cr - uv.y * sr, uv.x * sr + uv.y * cr);
  vec2 discUv = uv * 0.5 + 0.5;

  float mask = texture(uMoonMask, discUv).r;
  float circle = smoothstep(1.0, 0.97, length(uv));
  float alphaMask = mask * circle;
  if (alphaMask <= 0.001) discard;

  if (uSolarOcclusion > 0.5) {
    fragColor = vec4(0.0, 0.0, 0.0, alphaMask * uMoonAlpha);
    return;
  }

  vec4 base = texture(uMoonBase, discUv);

  float phaseRad = radians(uPhaseAngle);
  float dir = (sin(phaseRad) >= 0.0) ? 1.0 : -1.0;
  float z = sqrt(max(0.0, 1.0 - dot(uv, uv)));
  vec3 normal = normalize(vec3(uv, z));
  float sx = abs(sin(phaseRad));
  vec3 lightDir = normalize(vec3(dir * sx, 0.0, -cos(phaseRad)));
  float light = dot(normal, lightDir);
  float lightFactor = smoothstep(-0.12, 0.12, light);

  vec3 lit = base.rgb * uBrightness;
  vec3 shadowTint = vec3(0.20, 0.18, 0.28);
  float phaseMix = mix(0.01, 1.0, lightFactor);
  vec3 color = mix(lit * shadowTint, lit, phaseMix);

  // 白天的调色（去饱和 / 对比 / 偏蓝），**连续加权**而不是 if。
  // 这三个量目前都是中性值（1 / 1 / 0），看不出差别；但只要有人把它们调活，
  // 那句 if 就会以和混合函数一样的方式在日出日落时跳一下。
  vec3 graded = color;
  float gray = dot(graded, vec3(0.299, 0.587, 0.114));
  graded = mix(vec3(gray), graded, uSaturation);
  graded = (graded - 0.5) * uContrast + 0.5;
  graded = mix(graded, vec3(0.8, 0.9, 1.0), uBlueTint);
  color = mix(color, graded, uDayWeight);

  if (uEclipseType != 0) {
    float p = clamp(uEclipsePhase, 0.0, 1.0);
    float frac = clamp(uEclipseFraction, 0.0, 1.0);
    float totalFrac = clamp(uEclipseFraction - 1.0, 0.0, 1.0);

    float penIn = smoothstep(0.0, 0.2, p);
    float penOut = 1.0 - smoothstep(0.8, 1.0, p);
    float penGate = penIn * penOut;
    float penStrength = mix(0.3, 1.0, penGate);
    float penDim = 0.12 * penStrength * mix(0.4, 1.0, frac);
    color *= (1.0 - penDim);

    vec2 shadowUv = uv + uShadowOffset;
    float shadowDistance = length(shadowUv);
    float shadowEdge = smoothstep(0.9, 0.7, shadowDistance);

    float partialIn = smoothstep(0.2, 0.4, p);
    float partialOut = 1.0 - smoothstep(0.6, 0.8, p);
    float partialGate = partialIn * partialOut;
    float partialStrength = mix(0.3, 1.0, partialGate);
    float umbra = clamp(shadowEdge * frac * partialStrength, 0.0, 1.0);
    color = mix(color, color * uShadowColor, umbra);

    float totalIn = smoothstep(0.4, 0.55, p);
    float totalOut = 1.0 - smoothstep(0.65, 0.8, p);
    float totalGate = totalIn * totalOut;
    float totalStrength = mix(0.3, 1.0, totalGate);
    float total = clamp(totalStrength * (0.6 + 0.4 * totalFrac), 0.0, 1.0);
    vec3 deepTint = mix(uShadowColor * 0.9 + vec3(0.1), uShadowColor * 0.6 + vec3(0.08),
      smoothstep(0.55, 0.65, p));
    color = mix(color, deepTint, total);
    color *= (1.0 - total * 0.65);
  }

  // ---- 输出：**预乘 alpha**，昼夜只是在插值 alpha ----
  //
  // 白天要的是滤色（GL_ONE, ONE_MINUS_SRC_COLOR）：月面暗部 src≈0，结果≈背景色，
  // 于是暗部融进天空、看不见。夜里要的是普通混合：暗部实心画出来。
  //
  // 这两种观感没法用 glBlendFunc 插值 —— 它是二选一的。但换成**预乘 alpha** 的
  // GL_ONE, ONE_MINUS_SRC_ALPHA 之后（见 GrassGL.drawMoon），
  // 两者的差别就**只剩输出 alpha 这一个标量**：
  //
  //   白天 a = 亮度的加权和：亮的地方 a≈1 → 结果≈自身，与滤色等价；
  //                          暗的地方 a≈0 → 结果≈背景，暗部因此看不见 ✓
  //   夜里 a = 圆盘覆盖率：就是普通 alpha 混合，与改之前**逐像素相同** ✓
  //
  // 于是昼夜之间只是这个 alpha 在动，天然连续，没有可跳的地方。
  // 用亮度而不是逐通道的 src，是因为 alpha 是标量、而 src 是 vec3；
  // 月亮本来就近似中性色，这个近似看不出来。
  float coverage = alphaMask * uMoonAlpha;
  vec3 premul = color * coverage;
  float alpha = clamp(mix(coverage, dot(premul, vec3(0.299, 0.587, 0.114)), uDayWeight),
      0.0, 1.0);
  fragColor = vec4(premul, alpha);
}
