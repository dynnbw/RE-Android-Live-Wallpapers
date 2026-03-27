#version 450

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 fragColor;

layout(binding = 0) uniform sampler2D uTexNight;
layout(binding = 1) uniform sampler2D uTexSunrise;
layout(binding = 2) uniform sampler2D uTexSunset;
layout(binding = 3) uniform sampler2D uTexSky;
layout(binding = 4) uniform sampler2D uTexSolarEclipse;

layout(push_constant) uniform SkyPushConstants {
    float weightNight;
    float weightSunrise;
    float weightSunset;
    float weightSky;
    float weightSolarEclipse;
    float nightInvert;
} uPush;

void main() {
    // nightInvert: 0=normal, 1=flip UV vertically for night texture
    vec2 nightUV = mix(vUv, vec2(vUv.x, 1.0 - vUv.y), uPush.nightInvert);
    vec4 night   = texture(uTexNight,   nightUV);
    vec4 sunrise = texture(uTexSunrise, vUv);
    vec4 sunset  = texture(uTexSunset,  vUv);
    vec4 sky     = texture(uTexSky,     vUv);
    vec4 eclipse = texture(uTexSolarEclipse, vUv);

    vec3 rgb = night.rgb   * uPush.weightNight   +
               sunrise.rgb * uPush.weightSunrise +
               sunset.rgb  * uPush.weightSunset  +
               sky.rgb     * uPush.weightSky;

    float eclipseWeight = clamp(uPush.weightSolarEclipse, 0.0, 1.0);
    rgb = mix(rgb, eclipse.rgb, eclipseWeight);
    rgb *= (1.0 - 0.55 * eclipseWeight);

    float a = max(max(night.a, sunrise.a), max(sunset.a, sky.a));
    fragColor = vec4(rgb, a);
}
