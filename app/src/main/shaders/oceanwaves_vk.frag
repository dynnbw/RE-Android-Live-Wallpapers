#version 450

// 海床 * (焦散 + 底光) + 反射，与 GLES 版逐行对应。

layout(set = 0, binding = 0) uniform sampler2D uSeabed;
layout(set = 0, binding = 1) uniform sampler2D uEnvmap;
layout(set = 0, binding = 2) uniform sampler2D uRipple;

layout(location = 0) in vec2 vSeabedUV;
layout(location = 1) in vec2 vCausticsUV;
layout(location = 2) in vec2 vReflectionUV;
layout(location = 3) in vec2 vRippleUV;
layout(location = 4) in float vCausticsIntensity;

layout(location = 0) out vec4 fragColor;

void main() {
    vec3 ripple = texture(uRipple, vRippleUV).rgb;
    vec2 micro = (ripple.rg - vec2(0.5)) * 0.2;

    vec4 seabed = texture(uSeabed, vSeabedUV - micro * 0.1);
    float caustics = texture(uEnvmap, vCausticsUV + micro).r;
    float reflection = texture(uEnvmap, vReflectionUV + micro).g;

    fragColor = vec4(seabed.rgb * (caustics * vCausticsIntensity + 0.85) + reflection, 1.0);
}
