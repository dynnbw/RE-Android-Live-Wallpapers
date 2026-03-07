precision mediump float;
uniform sampler2D uMoonBase;
uniform sampler2D uMoonMask;
uniform float uPhaseAngle;
uniform float uBrightness;
uniform float uMoonAlpha;
uniform int uIsDaytime;
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
varying vec2 vTexCoord;
void main() {
  vec2 uv = vTexCoord * 2.0 - 1.0;
  float mask = texture2D(uMoonMask, vTexCoord).a;
  float circle = smoothstep(1.0, 0.97, length(uv));
  float alphaMask = mask * circle;
  if (alphaMask <= 0.001) discard;

  if (uSolarOcclusion > 0.5) {
    gl_FragColor = vec4(0.0, 0.0, 0.0, alphaMask * uMoonAlpha);
    return;
  }

  vec4 base = texture2D(uMoonBase, vTexCoord);

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

  if (uIsDaytime == 1) {
    float gray = dot(color, vec3(0.299, 0.587, 0.114));
    color = mix(vec3(gray), color, uSaturation);
    color = (color - 0.5) * uContrast + 0.5;
    color = mix(color, vec3(0.8, 0.9, 1.0), uBlueTint);
  }

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

  gl_FragColor = vec4(color, alphaMask * uMoonAlpha);
}
