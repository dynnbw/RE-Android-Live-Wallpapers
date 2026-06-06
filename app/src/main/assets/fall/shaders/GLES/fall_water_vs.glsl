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
attribute vec4 aPosition;
varying vec2 vTexCoord;

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
    gl_Position = uMVPMatrix * aPosition;
    vec2 pos = aPosition.xy;

    float posScaledX = (pos.x + 1.0) * u_meshScaleX;
    float posScaledY = ((pos.y / (u_glHeight * 0.5)) + 1.0) * u_meshScaleY;

    float varU = pos.x + 1.0;
    float varV = pos.y + u_glHeight * 0.5;

    if (u_rotate < 0.5) {
        varU = varU * 0.25 + u_xOffset * 0.5;
        varV *= 0.33 * (3.333 / u_glHeight);
        posScaledX += u_xOffset * 2.0;
    } else {
        varU *= 0.5;
        varV *= 0.3125 * (3.333 / u_glHeight);
    }

    varU = 0.5 + (varU - 0.5) * u_bgScale;
    varV = 0.5 + (varV - 0.5) * u_bgScale;

    vec2 ripplePos = vec2(posScaledX, posScaledY);
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
