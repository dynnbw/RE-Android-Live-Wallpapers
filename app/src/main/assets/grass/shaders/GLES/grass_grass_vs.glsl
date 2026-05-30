uniform mat4 uMVPMatrix;
attribute vec2 aPosition;
attribute vec4 aColor;
attribute vec2 aTexCoord;
varying vec4 vColor;
varying vec2 vTexCoord;
void main() {
  gl_Position = uMVPMatrix * vec4(aPosition, 0.0, 1.0);
  vColor = aColor;
  vTexCoord = aTexCoord;
}
