#version 450

layout(push_constant) uniform PushConstants {
    mat4 uMvpMatrix;
    float uAlphaMultiplier;
} pc;

layout(location = 0) out vec2 vUv;

void main() {
    vec2 quad[4] = vec2[](vec2(-1.0, -1.0), vec2(1.0, -1.0), vec2(-1.0, 1.0), vec2(1.0, 1.0));

    // Match GLES light1 quad size: approximately 512px based sprite in galaxy center.
    vec2 scale = vec2(0.56, 0.61);
    vec2 p = quad[gl_VertexIndex] * scale;

    vUv = quad[gl_VertexIndex] * 0.5 + 0.5;
    gl_Position = pc.uMvpMatrix * vec4(p, 0.0, 1.0);
}