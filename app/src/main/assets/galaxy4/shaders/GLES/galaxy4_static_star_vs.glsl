#version 300 es
uniform mat4 uMVPMatrix;
in vec2 aPosition;
in float aPointSize;
out float pointSize;
void main() {
  gl_Position = uMVPMatrix * vec4(aPosition, 0.0, 1.0);
  pointSize = aPointSize;
  gl_PointSize = aPointSize;
}
