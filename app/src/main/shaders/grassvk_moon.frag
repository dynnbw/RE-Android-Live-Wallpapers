#version 450

layout(location = 0) in vec2 vTexCoord;
layout(location = 1) in float vAlpha;
layout(location = 0) out vec4 fragColor;

layout(binding = 0) uniform sampler2D uMoonBase;

layout(push_constant) uniform MoonPushConstants {
    mat4 uMVP;
    vec4 p0; // x=phaseDeg y=brightness z=moonAlpha w=isDaytime
    vec4 p1; // x=contrast y=saturation z=blueTint w=eclipseType
    vec4 p2; // x=eclipseFraction y=eclipsePhase z=shadowOffsetX w=shadowOffsetY
} uPush;

void main() {
    vec2 uv = vTexCoord * 2.0 - 1.0;
    float circle = smoothstep(1.0, 0.97, length(uv));
    float alphaMask = circle * vAlpha;
    if (alphaMask <= 0.001) {
        discard;
    }

    vec4 base = texture(uMoonBase, vTexCoord);

    float phaseRad = radians(uPush.p0.x);
    float dir = (sin(phaseRad) >= 0.0) ? 1.0 : -1.0;
    float z = sqrt(max(0.0, 1.0 - dot(uv, uv)));
    vec3 normal = normalize(vec3(uv, z));
    float sx = abs(sin(phaseRad));
    vec3 lightDir = normalize(vec3(dir * sx, 0.0, -cos(phaseRad)));
    float light = dot(normal, lightDir);
    float lightFactor = smoothstep(-0.12, 0.12, light);

    vec3 lit = base.rgb * uPush.p0.y;
    vec3 shadowTint = vec3(0.20, 0.18, 0.28);
    float phaseMix = mix(0.01, 1.0, lightFactor);
    vec3 color = mix(lit * shadowTint, lit, phaseMix);

    if (uPush.p0.w > 0.5) {
        float gray = dot(color, vec3(0.299, 0.587, 0.114));
        color = mix(vec3(gray), color, uPush.p1.y);
        color = (color - 0.5) * uPush.p1.x + 0.5;
        color = mix(color, vec3(0.8, 0.9, 1.0), uPush.p1.z);
    }

    if (uPush.p1.w > 0.5) {
        float p = clamp(uPush.p2.y, 0.0, 1.0);
        float frac = clamp(uPush.p2.x, 0.0, 1.0);
        float totalFrac = clamp(uPush.p2.x - 1.0, 0.0, 1.0);

        float penIn = smoothstep(0.0, 0.2, p);
        float penOut = 1.0 - smoothstep(0.8, 1.0, p);
        float penGate = penIn * penOut;
        float penStrength = mix(0.3, 1.0, penGate);
        float penDim = 0.12 * penStrength * mix(0.4, 1.0, frac);
        color *= (1.0 - penDim);

        vec2 shadowUv = uv + vec2(uPush.p2.z, uPush.p2.w);
        float shadowDistance = length(shadowUv);
        float shadowEdge = smoothstep(0.9, 0.7, shadowDistance);

        float partialIn = smoothstep(0.2, 0.4, p);
        float partialOut = 1.0 - smoothstep(0.6, 0.8, p);
        float partialGate = partialIn * partialOut;
        float partialStrength = mix(0.3, 1.0, partialGate);
        float umbra = clamp(shadowEdge * frac * partialStrength, 0.0, 1.0);
        vec3 shadowColor = vec3(0.6, 0.2, 0.1);
        color = mix(color, color * shadowColor, umbra);

        float totalIn = smoothstep(0.4, 0.55, p);
        float totalOut = 1.0 - smoothstep(0.65, 0.8, p);
        float totalGate = totalIn * totalOut;
        float totalStrength = mix(0.3, 1.0, totalGate);
        float total = clamp(totalStrength * (0.6 + 0.4 * totalFrac), 0.0, 1.0);
        vec3 deepTint = mix(shadowColor * 0.9 + vec3(0.1), shadowColor * 0.6 + vec3(0.08),
                smoothstep(0.55, 0.65, p));
        color = mix(color, deepTint, total);
        color *= (1.0 - total * 0.65);
    }

    fragColor = vec4(color, alphaMask * uPush.p0.z);
}
