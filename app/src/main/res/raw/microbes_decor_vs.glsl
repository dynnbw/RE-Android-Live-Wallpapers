precision mediump float;
uniform vec4 uTrans;
uniform float time;
attribute vec4 aPosition;
varying vec2 vTransform;
varying float vWidthScale;
const float energy = 3.0;
void main() {
  float angle = aPosition.z;
  float size = aPosition.w;
  gl_Position = vec4(aPosition.xy * uTrans.xy + uTrans.zw, 0.0, 1.0);
  gl_PointSize = 30.0 * size;
  vTransform = vec2(cos(angle), sin(angle));
  vWidthScale = 7.0 / energy;
}
