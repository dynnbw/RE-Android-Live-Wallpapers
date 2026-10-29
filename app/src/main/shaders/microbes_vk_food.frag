#version 450

layout(location = 0) out vec4 fragColor;

void main() {
    vec2 given = gl_PointCoord.xy - 0.5;
    float h = length(given) * 2.0;
    fragColor.rgb = vec3(1.0, 1.0, 1.0);
    fragColor.a = pow(2.81, -pow(h * 2.0, 2.0));
}
