precision mediump float;
uniform sampler2D uTexture;
varying vec4 vColor;
void main() {
  vec4 texColor = texture2D(uTexture, gl_PointCoord);
  gl_FragColor = vColor * texColor;
}
