#version 450

layout(push_constant) uniform PushConstants {
    mat4 uMvpMatrix;
    float uAlphaMultiplier;
    float uTwist;
    float uLightScaleX;
    float uLightScaleY;
} pc;

layout(location = 0) out vec2 vUv;

void main() {
    vec2 quad[4] = vec2[](vec2(-1.0, -1.0), vec2(1.0, -1.0), vec2(-1.0, 1.0), vec2(1.0, 1.0));

    // 与 GLES 的 drawLights 一致：尺寸由 galaxy_radius 和屏幕宽度算出
    vec2 p = quad[gl_VertexIndex] * vec2(pc.uLightScaleX, pc.uLightScaleY);

    vUv = quad[gl_VertexIndex] * 0.5 + 0.5;
    gl_Position = pc.uMvpMatrix * vec4(p, 0.0, 1.0);
}