uniform mat4 uMVPMatrix;
attribute vec3 aPosition;
attribute float aPointSize;
void main() {
  float angle = aPosition.x;
  float dist = aPosition.y;
  float z = aPosition.z;
  vec3 pos = vec3(cos(angle) * dist, sin(angle) * dist, z);
  gl_Position = uMVPMatrix * vec4(pos, 1.0);
  float pSize = (100.0 - (pos.y * pos.y * 1.44 + pos.x * pos.x) * 400.0) * aPointSize;
  gl_PointSize = max(pSize, 0.0);
}
