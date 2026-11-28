#version 450

layout(set = 0, binding = 0) uniform sampler2D uLeafTex;

layout(push_constant) uniform PushConstants {
    mat4 uMvpMatrix;
    float uAlpha;
    float uLeafFrameIndex;
    float uLeafFrameInvCount;
    float uShadow;   // 1 = 影子（剪影），0 = 正常叶片
    float u_glHeight;
    float u_bgScale;
    float u_meshScaleX;
    float u_meshScaleY;
    float u_dxMul;
    float u_xOffset;
    int u_rotate;
    // 与水面共用最后 5 个 float：叶子这边是日夜染色的 rgb / amount / value
    float uTintR;
    float uTintG;
    float uTintB;
    float uTintAmount;
    float uTintValue;
} pc;

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outColor;

void main() {
    vec4 texel = texture(uLeafTex, vUv);
    // 与 GLES 一致：往目标色混（不是乘），再单独压明度
    vec3 rgb = mix(texel.rgb, vec3(pc.uTintR, pc.uTintG, pc.uTintB), pc.uTintAmount)
            * pc.uTintValue;
    // 影子只取叶形，颜色压成黑色，靠 alpha 混合把地面压暗
    rgb = mix(rgb, vec3(0.0), pc.uShadow);
    outColor = vec4(rgb, texel.a * pc.uAlpha);
}
