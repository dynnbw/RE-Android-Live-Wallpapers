#version 300 es
in vec3 aPosition;
in vec2 aTexCoord;
uniform mat4 uMVP;
out vec2 vTex;
void main() {
  vTex = aTexCoord;
  gl_Position = uMVP * vec4(aPosition, 1.0);
}
