#version 450

layout(set = 0, binding = 1) uniform DropBlock {
    vec4 u_drop[80];
    int u_dropCount;
    vec4 uSkyWeights;   // [夜,晨,昏,昼]；片元着色器混天空用
    vec4 uAlgae[80];    // (强度, 世界x, 世界y, 0)；片元着色器算蓝藻用
    float uAlgaeAmount;
    float uLeafAlgaeAmount;
    float uAlgaeMetricX;
    float uAlgaeMetricY;
    vec2 uAlgaeMeshScale;
    vec2 uAlgaeMeshOffset;
    float uAlgaeThreshold;
    float uAlgaeBand;
    float uAlgaeNoiseTile;
    float uAlgaeArcScale;
    float uAlgaeGain;
    vec4 uAlgaeLow;
    vec4 uAlgaeHigh;
    vec4 uLightColor;
    vec4 uEmitter;       // (屏幕 x, y, 横向半宽, 纵向半宽)
    vec4 uEmitterColor;  // rgb + 增益
} dropBlock;

layout(push_constant) uniform PushConstants {
    mat4 uMvpMatrix;
    float uAlpha;
    float uLeafFrameIndex;
    float uLeafFrameInvCount;
    float uShadow;      // 叶片影子标志；水面不用，只为与 C++ 结构体对齐
    float u_glHeight;
    float u_bgScale;
    float u_meshScaleX;
    float u_meshScaleY;
    float u_dxMul;
    float u_xOffset;
    int u_rotate;
    float uStarAmount;
    float uStarTime;
    float uStarAspect;
    float _pad0;
    float _pad1;
} pc;

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec2 aTexCoord;

layout(location = 0) out vec2 vUv;
layout(location = 1) out vec2 vScreenUv;
/** 网格坐标（和 UBO 里的 u_drop 同一套单位）：蓝藻要在片元里按同一套距离算光。 */
layout(location = 2) out vec2 vMeshPos;

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
    vScreenUv = gl_Position.xy / gl_Position.w * 0.5 + 0.5;
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
    vMeshPos = ripplePos;
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
