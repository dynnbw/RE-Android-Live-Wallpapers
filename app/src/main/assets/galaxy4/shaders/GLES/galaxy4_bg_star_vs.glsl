uniform mat4 uMVPMatrix;
attribute vec3 aPosition;
void main() {
  float angle = aPosition.x;
  float dist = aPosition.y;
  float z = aPosition.z;
  vec3 pos = vec3(cos(angle) * dist, sin(angle) * dist, z);
  gl_Position = uMVPMatrix * vec4(pos, 1.0);
  gl_PointSize = 2.5;
}
