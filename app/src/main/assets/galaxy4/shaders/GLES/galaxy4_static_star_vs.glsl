uniform mat4 uMVPMatrix;
attribute vec2 aPosition;
attribute float aPointSize;
void main() {
  gl_Position = uMVPMatrix * vec4(aPosition, 0.0, 1.0);
  gl_PointSize = aPointSize;
}
