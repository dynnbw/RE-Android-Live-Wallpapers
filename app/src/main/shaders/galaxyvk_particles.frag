#version 450

layout(location = 0) in vec4 vColor;
layout(location = 0) out vec4 outColor;

void main() {
    vec2 p = gl_PointCoord * 2.0 - 1.0;
    float radius2 = dot(p, p);
    if (radius2 > 1.0) {
        discard;
    }

    float glow = exp(-3.5 * radius2);
    float edge = smoothstep(1.0, 0.0, radius2);
    outColor = vec4(vColor.rgb * (0.5 + glow), vColor.a * edge * glow);
}