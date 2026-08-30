precision mediump float;
uniform vec4 uTrans;
uniform float time;
attribute vec3 aPosition;
void main() {
  gl_Position = vec4(aPosition.xy * uTrans.xy + uTrans.zw, 0.0, 1.0);
  float scale = cos(time * 2.0 + aPosition.z * 10.0) * 0.2 + 0.7;
  gl_PointSize = 20.0 * scale;
}
