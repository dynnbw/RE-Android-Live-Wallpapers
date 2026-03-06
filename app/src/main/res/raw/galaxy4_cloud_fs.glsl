precision mediump float;
uniform sampler2D uTexture;
void main() {
  gl_FragColor = texture2D(uTexture, gl_PointCoord);
}
