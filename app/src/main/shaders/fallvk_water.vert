#version 450

layout(set = 0, binding = 1) uniform DropBlock {
    vec4 u_drop[80];
    int u_dropCount;
} dropBlock;

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

    float posScaledY = ((pos.y / (pc.u_glHeight * 0.5)) + 1.0) * pc.u_meshScaleY;

    float varU = pos.x + 1.0;
    float varV = pos.y + pc.u_glHeight * 0.5;

    if (pc.u_rotate < 1) {
        varU = varU * 0.25 + pc.u_xOffset * 0.5;
        varV *= 0.33 * (3.333 / pc.u_glHeight);
    } else {
        varU *= 0.5;
        varV *= 0.3125 * (3.333 / pc.u_glHeight);
    }

    varU = 0.5 + (varU - 0.5) * pc.u_bgScale;
    varV = 0.5 + (varV - 0.5) * pc.u_bgScale;

    // xOffset applied BEFORE scaleX to match addDrop() CPU math
    float pxAdj = pos.x;
    if (pc.u_rotate < 1) pxAdj += pc.u_xOffset * 2.0;
    float posScaledX = (pxAdj + 1.0) * pc.u_meshScaleX;

    vec2 ripplePos = vec2(posScaledX, posScaledY);
    vec2 texOffset = vec2(0.0);
    float dxMul = pc.u_dxMul;

    for (int i = 0; i < 80; i++) {
        if (i < dropBlock.u_dropCount) {
            texOffset += addDrop(dropBlock.u_drop[i], ripplePos, dxMul);
        }
    }

    varU += texOffset.x;
    varV += texOffset.y;

    vUv = vec2(varU, varV);
}
