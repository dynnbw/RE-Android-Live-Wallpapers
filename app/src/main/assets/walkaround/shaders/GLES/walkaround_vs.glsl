#version 300 es
in vec2 aPosition;
in vec2 aTexCoord;
uniform mat4 uMVP;
uniform mat4 uTexMatrix;
out vec2 vTex;
void main() {
  vTex = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
  gl_Position = uMVP * vec4(aPosition, 0.0, 1.0);
}
