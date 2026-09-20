#version 300 es
in vec2 aPosition;
in vec2 aTexCoord;
uniform mat4 uMVP;
out vec2 vTex;
void main() {
  vTex = aTexCoord;
  gl_Position = uMVP * vec4(aPosition.xy, 0.0, 1.0);
}
