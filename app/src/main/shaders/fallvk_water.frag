#version 450

// binding 0: 河床的树遮罩（R8_UNORM，白=天空 黑=树）
// binding 2: 天空色带（24x64，纯竖直渐变）
layout(set = 0, binding = 0) uniform sampler2D uMaskTex;
layout(set = 0, binding = 2) uniform sampler2D uSkyTex;

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
    // 天空只取 v，并且和树用同一个 vUv —— 顶点着色器把 UV 按水面波纹扭曲过，
    // 天空跟着一起扭，与两者烤在一张贴图里时观感一致。
    float m = texture(uMaskTex, vUv).r;
    vec3 sky = texture(uSkyTex, vec2(0.5, vUv.y)).rgb;
    outColor = vec4(sky * m, pc.uAlpha);
}
