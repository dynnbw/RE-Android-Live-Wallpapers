uniform mat4 uMVPMatrix;
uniform float uParticleSize;
attribute vec3 aPosition;
void main() {
  float dist = aPosition.y;
  float angle = aPosition.x;
  float z = aPosition.z;

  // Match original RenderScript: sin→X, cos→Y with 0.8 aspect
  float x = dist * sin(angle);
  float y = dist * cos(angle) * 0.8;

  // Spiral twist: p = dist * 7.5
  float p = dist * 7.5;
  float s = cos(p);
  float t = sin(p);

  vec4 pos;
  pos.x = t * x + s * y;
  pos.y = s * x - t * y;
  pos.z = z;
  pos.w = 1.0;

  pos.y = pos.y * 0.5;
  gl_Position = uMVPMatrix * pos;
  gl_PointSize = 1.0 * uParticleSize;
}
