#version 450

layout(set = 0, binding = 0) uniform sampler2D uTexture;

layout(location = 0) in vec2 vTexCoord;
layout(location = 1) in float vAlpha;
layout(location = 2) in vec3 vAdjust;

layout(location = 0) out vec4 fragColor;

// inspired by http://www.chilliant.com/rgb2hsv.html
vec3 hsl2rgb(vec3 hsl)
{
    const vec3 K1 = vec3(0.5, 1.0 / 3.0, 2.0 / 3.0);
    const vec3 K2 = vec3(1.0, -1.0, -1.0);
    const vec3 C = vec3(-1.0, 2.0, 2.0);

    vec3 rgb = clamp(abs(6.0 * (hsl.xxx - K1.xyz)) * K2 + C, 0.0, 1.0);
    float chroma = (1.0 - abs(2.0 * hsl.z - 1.0)) * hsl.y;
    return (rgb - 0.5) * chroma + hsl.z;
}

void main() {
    vec3 rgb = texture(uTexture, vTexCoord).rgb;

    if (vAdjust.x >= 0.0) {
        vec3 hsl = vAdjust * vec3(1.0, 1.0, rgb.r);
        rgb = hsl2rgb(hsl);
    }

    fragColor = vec4(rgb, vAlpha);
}
