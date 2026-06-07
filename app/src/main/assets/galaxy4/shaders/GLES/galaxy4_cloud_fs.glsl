precision mediump float;
uniform sampler2D uTexture;
void main() {
  vec4 texColor = texture2D(uTexture, gl_PointCoord);
  gl_FragColor.rgb = texColor.rgb;
  gl_FragColor.a = 0.1;
}
