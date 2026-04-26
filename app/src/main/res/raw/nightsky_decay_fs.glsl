precision mediump float;

varying vec2 vUv;
uniform sampler2D uTex;
uniform float uDecay;

void main() {
    vec4 c = texture2D(uTex, vUv);
    gl_FragColor = vec4(c.rgb * uDecay, c.a * uDecay);
}
