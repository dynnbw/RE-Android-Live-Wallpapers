#version 300 es
uniform mat4 uMVPMatrix;
uniform float u_glHeight;
uniform float u_bgScale;
uniform float u_meshScaleX;
uniform float u_meshScaleY;
uniform float u_dxMul;
uniform float u_xOffset;
uniform float u_rotate;
uniform vec4 u_drop[$DROP_SIZE];
uniform float u_dropCount;
in vec4 aPosition;
out highp vec2 vTexCoord;
/**
 * 屏幕 UV（0..1）。天空里的发光体按屏幕位置定位，所以需要它。
 *
 * 不能用 vTexCoord 顶替：那个被 bgScale 缩过、还带波纹位移，
 * 拿它当屏幕坐标会把发光体一起缩放/摇晃。
 */
out highp vec2 vScreenUv;

vec2 addDrop(vec4 d, vec2 ripplePos, float dxMul) {
    vec2 delta = vec2((d.x - ripplePos.x) * dxMul, d.y - ripplePos.y);
    float dist = length(delta);
    if (dist >= d.w) return vec2(0.0);
    float amp = d.z * 0.12 * dist / (d.w * d.w) * sin(d.w - dist);
    vec2 ret = delta * amp;
    ret.x /= dxMul;
    return ret;
}

out highp vec2 vMeshPos;

void main() {
    gl_Position = uMVPMatrix * aPosition;
    // 正交投影下 w 恒为 1，除它是个恒等操作；留着是为了将来换成透视投影时不会静默错位
    vScreenUv = gl_Position.xy / gl_Position.w * 0.5 + 0.5;
    vec2 pos = aPosition.xy;

    float posScaledY = ((pos.y / (u_glHeight * 0.5)) + 1.0) * u_meshScaleY;

    float varU = pos.x + 1.0;
    float varV = pos.y + u_glHeight * 0.5;

    if (u_rotate < 0.5) {
        varU = varU * 0.25 + u_xOffset * 0.5;
        varV *= 0.33 * (3.333 / u_glHeight);
    } else {
        varU *= 0.5;
        varV *= 0.3125 * (3.333 / u_glHeight);
    }

    varU = 0.5 + (varU - 0.5) * u_bgScale;
    varV = 0.5 + (varV - 0.5) * u_bgScale;

    // xOffset applied BEFORE scaleX to match addDrop() CPU math
    float pxAdj = pos.x;
    if (u_rotate < 0.5) pxAdj += u_xOffset * 2.0;
    float posScaledX = (pxAdj + 1.0) * u_meshScaleX;

    vec2 ripplePos = vec2(posScaledX, posScaledY);
    // 网格坐标（和 u_drop 同一套单位）交给片元 —— 蓝藻要在片元里按同一套距离算光
    vMeshPos = ripplePos;
    vec2 texOffset = vec2(0.0);
    float dxMul = u_dxMul;

    for (int i = 0; i < $DROP_SIZE; i++) {
        if (float(i) < u_dropCount) {
            texOffset += addDrop(u_drop[i], ripplePos, dxMul);
        }
    }

    varU += texOffset.x;
    varV += texOffset.y;

    vTexCoord = vec2(varU, varV);
}
