attribute vec2 aPosition;
attribute vec2 aTexCoord;
uniform mat4 uMVP;
varying vec2 vTex;
void main() {
  vTex = aTexCoord;
  gl_Position = uMVP * vec4(aPosition.xy, 0.0, 1.0);
}
