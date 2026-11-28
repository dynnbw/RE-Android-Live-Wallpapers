#version 450

layout(location = 0) in vec2 vTexCoord;
layout(location = 1) in float vAlpha;
layout(location = 2) in vec4 vTint;
layout(location = 0) out vec4 fragColor;

layout(binding = 0) uniform sampler2D uSampler;

void main() {
    vec4 c = texture(uSampler, vTexCoord);
    // 与 GLES 的精灵着色器同一套：rgb 乘染色、alpha 乘整批透明度
    fragColor = vec4(c.rgb * vTint.rgb, c.a * vAlpha * vTint.a);
}
