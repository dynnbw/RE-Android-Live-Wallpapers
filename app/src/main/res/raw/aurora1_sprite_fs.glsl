precision mediump float;

uniform sampler2D uTexture;
uniform vec4 uColor;
uniform vec2 uFlow;
uniform float uDistort;
uniform float uTime;

varying vec2 vTexCoord;

void main() {
    vec2 uv = vTexCoord;
    if (uDistort > 0.0) {
        float wobble = sin((uv.y + uTime * uFlow.y) * 6.2831853 + uFlow.x) * uDistort;
        uv.x += wobble;
    }
    vec4 color = texture2D(uTexture, uv) * uColor;
    gl_FragColor = color;
}