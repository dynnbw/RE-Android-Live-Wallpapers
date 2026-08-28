// Port of the original vivo CSilk fragment shader, with the per-ribbon
// #define constants (originColor/length/DIVISION_FACTOR/decay) promoted to uniforms.
// alpha is clamped to [0,1]: the original could go negative (ribbon 2's decay band),
// which inverted RGB (black flashes) and blew up the GL_ONE blend (white flashes).
precision lowp float;
uniform sampler2D CC_Texture0;
uniform vec4 uOriginColor;   // rgb = originColor, a = originAlpha
uniform float uOriginAlpha;
uniform float uDecayLen;     // 'length'
uniform float uDivFactor;    // 'DIVISION_FACTOR'
uniform float uDecay;        // 1.0 / 0.0
varying float v_fragmentColor;
varying vec2 v_coord;
varying vec4 localPos;
varying float flash;
void main() {
    // pow(x, 2.0) is undefined for x < 0 in GLSL (and the original's decay band
    // is negative over most of the ribbon); old drivers optimized it to x*x,
    // modern Adreno returns NaN. Use x*x — mathematically identical, safe for negatives.
    float diff = (2.0 * localPos.x - uDecayLen) * uDivFactor;
    float alpha = (uDecay < 0.5) ? 1.0 : 1.0 - diff * diff;
    alpha = clamp(alpha, 0.0, 1.0);
    vec4 dot = texture2D(CC_Texture0, v_coord);
    gl_FragColor = (v_fragmentColor * dot + (1.0 - v_fragmentColor) * uOriginColor) * uOriginAlpha * alpha * flash;
    // NaN guard: at wave troughs the ribbon collapses (y1~y2) and the triangles
    // become degenerate; some GPUs (Adreno) then produce NaN interpolants that
    // render as black or saturated (magenta/green/gold) flicker. step() returns 0
    // for any NaN comparison. Must REPLACE the value, not multiply by 0:
    // NaN * 0.0 is still NaN under IEEE 754.
    float safe = step(0.0, v_fragmentColor) * step(v_fragmentColor, 1.0);
    safe *= step(0.0, v_coord.x) * step(v_coord.x, 1.0);
    safe *= step(0.0, v_coord.y) * step(v_coord.y, 1.0);
    // Final sweep on the computed result itself (NaN can also enter via localPos/flash).
    safe *= step(0.0, gl_FragColor.r) * step(0.0, gl_FragColor.g);
    safe *= step(0.0, gl_FragColor.b) * step(0.0, gl_FragColor.a);
    if (safe < 0.5) {
        gl_FragColor = vec4(0.0);
    }
}
