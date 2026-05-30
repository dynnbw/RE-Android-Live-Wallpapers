precision mediump float;
uniform vec4 uTrans;
uniform float time;
attribute vec4 pos;
varying vec4 vColor;
void main() {
  vec2 offset = vec2(sin((time + pos.x) * 0.1), cos((time + pos.y) * 0.1)) * mix(50.0, 200.0, pos.z);
  gl_Position.xy = ((pos.xy + offset) * uTrans.xy + uTrans.zw) * mix(0.1, 0.9, pos.z);
  gl_Position.zw = vec2(0.0, 1.0);
  gl_PointSize = 600.0 * mix(0.5, 1.0, pos.z);
  vColor = vec4(0.60, 0.6, 1.0, mix(0.03, 0.08, pos.z));
}
