precision mediump float;
varying vec2 vTex;
uniform sampler2D uTex;
void main() {
  gl_FragColor = texture2D(uTex, vTex);
}
