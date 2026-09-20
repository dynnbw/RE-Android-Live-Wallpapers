#version 300 es
uniform mat4 uMVPMatrix;
in vec4 aPosition;
in vec2 aTexCoord;
out vec2 vTexCoord;
void main() {
  vTexCoord = aTexCoord;
  gl_Position = uMVPMatrix * aPosition;
}
