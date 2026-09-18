uniform mat4 uMVPMatrix;
attribute vec2 aPosition;
attribute vec2 aTexCoord;
attribute vec4 aColor;
varying highp vec2 vTexCoord;
varying vec4 vColor;
void main() {
  gl_Position = uMVPMatrix * vec4(aPosition, 0.0, 1.0);
  vTexCoord = aTexCoord;
  vColor = aColor;
}
