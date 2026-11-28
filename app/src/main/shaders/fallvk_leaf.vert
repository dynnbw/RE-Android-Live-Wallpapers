#version 450

layout(push_constant) uniform PushConstants {
    mat4 uMvpMatrix;
    float uAlpha;
    float uLeafFrameIndex;
    float uLeafFrameInvCount;
    float uShadow;
    /*
     * 叶片的世界变换：中心与两个半轴（已含缩放与旋转）。
     *
     * 这几个槽在水面着色器里是涟漪参数，叶子用不到，于是片元那边逐像素的
     * 蓝藻受光可以在这里一次算好世界坐标、插值下去 —— push constant 里放不下一整块矩阵。
     */
    vec2 uLeafCenter;
    vec2 uLeafAxisX;
    vec2 uLeafAxisY;
} pc;

layout(location = 0) out vec2 vUv;
layout(location = 1) out vec2 vWorld;

void main() {
    vec2 pos[4] = vec2[](vec2(-0.55, -0.55), vec2(0.55, -0.55), vec2(-0.55, 0.55), vec2(0.55, 0.55));
    vec2 uv[4] = vec2[](vec2(0.0, 0.0), vec2(1.0, 0.0), vec2(0.0, 1.0), vec2(1.0, 1.0));

    vec2 local = pos[gl_VertexIndex] / 0.55;
    vec2 baseUv = uv[gl_VertexIndex];
    vUv = vec2((baseUv.x + pc.uLeafFrameIndex) * pc.uLeafFrameInvCount, baseUv.y);
    vWorld = pc.uLeafCenter + pc.uLeafAxisX * local.x + pc.uLeafAxisY * local.y;
    gl_Position = pc.uMvpMatrix * vec4(pos[gl_VertexIndex], 0.0, 1.0);
}
