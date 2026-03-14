#version 450

layout(push_constant) uniform PushConstants {
    mat4 uMvpMatrix;
    float uAlpha;
    float uLeafFrameIndex;
    float uLeafFrameInvCount;
} pc;

layout(location = 0) out vec2 vUv;

void main() {
    vec2 pos[4] = vec2[](vec2(-0.55, -0.55), vec2(0.55, -0.55), vec2(-0.55, 0.55), vec2(0.55, 0.55));
    vec2 uv[4] = vec2[](vec2(0.0, 0.0), vec2(1.0, 0.0), vec2(0.0, 1.0), vec2(1.0, 1.0));

    vec2 baseUv = uv[gl_VertexIndex];
    vUv = vec2((baseUv.x + pc.uLeafFrameIndex) * pc.uLeafFrameInvCount, baseUv.y);
    gl_Position = pc.uMvpMatrix * vec4(pos[gl_VertexIndex], 0.0, 1.0);
}
