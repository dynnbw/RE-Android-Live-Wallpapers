uniform mat4 uMVPMatrix;
uniform float uAlphaMultiplier;
attribute vec3 aPosition;
attribute vec4 aColor;
varying vec4 vColor;
void main() {
  float dist = aPosition.y;
  float angle = aPosition.x;
  float x = dist * sin(angle);
  float y = dist * cos(angle) * 0.892;
  float p = dist * 5.5;
  float s = cos(p);
  float t = sin(p);
  vec4 pos;
  pos.x = t * x + s * y;
  pos.y = s * x - t * y;
  pos.z = aPosition.z;
  pos.w = 1.0;
  gl_Position = uMVPMatrix * pos;
  gl_PointSize = aColor.a;
  vColor.rgb = aColor.rgb / 255.0;
  vColor.a = uAlphaMultiplier;
}
