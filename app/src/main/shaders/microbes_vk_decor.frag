#version 450

layout(location = 0) in vec4 vColor;

layout(location = 0) out vec4 fragColor;

void main() {
    vec2 given = gl_PointCoord.xy - 0.5;
    float h = length(given) * 2.0;
    fragColor.rgb = vColor.rgb;
    fragColor.a = vColor.a * clamp((1.0 - h) * 2.0, 0.0, 2.0);
}
