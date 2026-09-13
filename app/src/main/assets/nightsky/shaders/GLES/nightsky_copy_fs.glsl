#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

varying highp vec2 vUv;
uniform sampler2D uTex;

void main() {
    gl_FragColor = texture2D(uTex, vUv);
}
