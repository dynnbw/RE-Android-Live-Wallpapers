// 原版 DeadMicrobe_vert(逐字):尸体 = 白色细长点,size 取 aPosition.w(尸体槽 breed)
precision mediump float;
uniform vec4 uTrans;
uniform float time;
attribute vec4 aPosition;
varying vec2 vTransform;
varying float vWidthScale;
const float energy = 3.;
void main() {
  float angle = aPosition.z;
  float size = aPosition.w;
  gl_Position = vec4(aPosition.xy * uTrans.xy + uTrans.zw, 0, 1);
  gl_PointSize = 30.*size;
  vTransform = vec2(cos(angle), sin(angle));
  vWidthScale = 7./energy;
}
