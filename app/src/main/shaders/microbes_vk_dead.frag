#version 450

layout(location = 0) in vec2 vTransform;
layout(location = 1) in float vWidthScale;

layout(location = 0) out vec4 fragColor;

void main() {
    vec2 given = gl_PointCoord.xy - 0.5;
    vec2 rotated = vec2(given.x * vTransform.x - given.y * vTransform.y,
                        given.x * vTransform.y + given.y * vTransform.x);
    vec2 scaled = rotated * vec2(1.0, vWidthScale);
    float h = length(scaled) * 2.0;
    fragColor.rgb = vec3(1.0, 1.0, 1.0);
    // 与 GLES 一样用乘法替代 pow(x, 2.0)：x < 0 时 pow 的结果未定义
    float hyperb = -(h - 0.4) * (h - 0.4);
    fragColor.a = clamp(hyperb * 30.0 + 0.5, 0.0, 0.5);
}
