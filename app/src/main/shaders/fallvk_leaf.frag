#version 450

layout(set = 0, binding = 0) uniform sampler2D uLeafTex;

layout(push_constant) uniform PushConstants {
    mat4 uMvpMatrix;
    float uAlpha;
    float uLeafFrameIndex;
    float uLeafFrameInvCount;
    float uShadow;   // 1 = 影子（剪影），0 = 正常叶片
} pc;

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outColor;

void main() {
    vec4 texel = texture(uLeafTex, vUv);
    // 影子只取叶形，颜色压成黑色，靠 alpha 混合把地面压暗
    vec3 rgb = mix(texel.rgb, vec3(0.0), pc.uShadow);
    outColor = vec4(rgb, texel.a * pc.uAlpha);
}
