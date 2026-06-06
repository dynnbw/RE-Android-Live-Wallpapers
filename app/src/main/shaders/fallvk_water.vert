#version 450

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
    int u_dropCount;
    vec4 u_drop[8];
} pc;

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec2 aTexCoord;

layout(location = 0) out vec2 vUv;

vec2 addDrop(vec4 d, vec2 ripplePos, float dxMul) {
    vec2 delta = vec2((d.x - ripplePos.x) * dxMul, d.y - ripplePos.y);
    float dist = length(delta);
    if (dist >= d.w) return vec2(0.0);
    float amp = d.z * 0.12 * dist / (d.w * d.w) * sin(d.w - dist);
    vec2 ret = delta * amp;
    ret.x /= dxMul;
    return ret;
}

void main() {
    gl_Position = pc.uMvpMatrix * vec4(aPosition, 1.0);
    vec2 pos = aPosition.xy;

    float posScaledX = (pos.x + 1.0) * pc.u_meshScaleX;
    float posScaledY = ((pos.y / (pc.u_glHeight * 0.5)) + 1.0) * pc.u_meshScaleY;

    float varU = pos.x + 1.0;
    float varV = pos.y + pc.u_glHeight * 0.5;

    if (pc.u_rotate < 1) {
        varU = varU * 0.25 + pc.u_xOffset * 0.5;
        varV *= 0.33 * (3.333 / pc.u_glHeight);
        posScaledX += pc.u_xOffset * 2.0;
    } else {
        varU *= 0.5;
        varV *= 0.3125 * (3.333 / pc.u_glHeight);
    }

    varU = 0.5 + (varU - 0.5) * pc.u_bgScale;
    varV = 0.5 + (varV - 0.5) * pc.u_bgScale;

    vec2 ripplePos = vec2(posScaledX, posScaledY);
    vec2 texOffset = vec2(0.0);
    float dxMul = pc.u_dxMul;

    if (pc.u_dropCount > 0) texOffset += addDrop(pc.u_drop[0], ripplePos, dxMul);
    if (pc.u_dropCount > 1) texOffset += addDrop(pc.u_drop[1], ripplePos, dxMul);
    if (pc.u_dropCount > 2) texOffset += addDrop(pc.u_drop[2], ripplePos, dxMul);
    if (pc.u_dropCount > 3) texOffset += addDrop(pc.u_drop[3], ripplePos, dxMul);
    if (pc.u_dropCount > 4) texOffset += addDrop(pc.u_drop[4], ripplePos, dxMul);
    if (pc.u_dropCount > 5) texOffset += addDrop(pc.u_drop[5], ripplePos, dxMul);
    if (pc.u_dropCount > 6) texOffset += addDrop(pc.u_drop[6], ripplePos, dxMul);
    if (pc.u_dropCount > 7) texOffset += addDrop(pc.u_drop[7], ripplePos, dxMul);

    varU += texOffset.x;
    varV += texOffset.y;

    vUv = vec2(varU, varV);
}
