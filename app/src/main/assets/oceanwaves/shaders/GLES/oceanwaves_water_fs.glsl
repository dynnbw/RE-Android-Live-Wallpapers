#version 300 es
// 海床 * (焦散 + 底光) + 反射，照参考实现的 image.fragment。
precision highp float;

uniform sampler2D uSeabed;
uniform sampler2D uEnvmap;
uniform sampler2D uRipple;

in vec2 vSeabedUV;
in vec2 vCausticsUV;
in vec2 vReflectionUV;
in vec2 vRippleUV;
in float vCausticsIntensity;

out vec4 fragColor;

void main() {
    vec3 ripple = texture(uRipple, vRippleUV).rgb;
    vec2 micro = (ripple.rg - vec2(0.5)) * 0.2;

    vec4 seabed = texture(uSeabed, vSeabedUV - micro * 0.1);
    float caustics = texture(uEnvmap, vCausticsUV + micro).r;
    float reflection = texture(uEnvmap, vReflectionUV + micro).g;

    fragColor = vec4(seabed.rgb * (caustics * vCausticsIntensity + 0.85) + reflection, 1.0);
}
