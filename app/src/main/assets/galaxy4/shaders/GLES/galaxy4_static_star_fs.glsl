precision mediump float;
varying float pointSize;
uniform sampler2D uTexture1;
uniform sampler2D uTexture2;
void main() {
  if (pointSize > 4.0) {
    gl_FragColor = texture2D(uTexture1, gl_PointCoord);
  } else {
    gl_FragColor = texture2D(uTexture2, gl_PointCoord);
  }
}
