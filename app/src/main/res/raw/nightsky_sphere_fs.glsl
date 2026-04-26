precision mediump float;

varying vec2 vUv;

uniform sampler2D uMilkyTex;
uniform float uMilkyBrightness;

void main() {
    // Align Milky Way map longitude with the sky model (summer/winter side fix).
    vec2 uv = vec2(fract(vUv.x + 0.5), clamp(vUv.y, 0.0, 1.0));
    vec3 color = texture2D(uMilkyTex, uv).rgb * uMilkyBrightness;
    gl_FragColor = vec4(color, 1.0);
}
