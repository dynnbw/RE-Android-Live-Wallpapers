precision mediump float;

varying vec2 vUv;
uniform sampler2D uTex;

void main() {
    gl_FragColor = texture2D(uTex, vUv);
}
