precision mediump float;
uniform vec4 uTrans;
uniform float time;
attribute vec3 aPosition;
attribute vec3 miscInfo;
attribute vec3 aColor;
varying vec3 vColor;
varying vec2 vTransform;
varying float vWidthScale;
void main() {
  float scale = miscInfo.x;
  float energy = miscInfo.y;
  float pulseProgress = (time - miscInfo.z);
  float pulse = clamp(min(pulseProgress * 2.0, -(pulseProgress - 1.0) * 0.5), 0.0, 1.0);
  gl_Position = vec4(aPosition.xy * uTrans.xy + uTrans.zw, 0.0, 1.0);
  gl_PointSize = 30.0 * scale;
  vTransform = vec2(cos(aPosition.z), sin(aPosition.z));
  vWidthScale = 1.0 / mix(0.5, 0.9, energy);
  vColor = aColor * 1.1 + pulse;
}
