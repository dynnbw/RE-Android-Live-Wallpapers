#version 450

layout(set = 0, binding = 0) uniform sampler2D uBackgroundTex;

layout(push_constant) uniform PushConstants {
    mat4 uMvpMatrix;
    float uAlpha;
    float uLeafFrameIndex;
    float uLeafFrameInvCount;
    float u_glHeight;
    float u_bgScale;
    float u_meshScaleX;
    float u_meshScaleY;
    float u_dxMul;
    float u_xOffset;
    int u_rotate;
} pc;

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outColor;

void main() {
    vec4 texel = texture(uBackgroundTex, vUv);
    outColor = vec4(texel.rgb, texel.a * pc.uAlpha);
}
