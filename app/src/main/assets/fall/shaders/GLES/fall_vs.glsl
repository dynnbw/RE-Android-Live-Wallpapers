#version 300 es
uniform mat4 uMVPMatrix;
in vec4 aPosition;
in vec2 aTexCoord;
out highp vec2 vTexCoord;
void main() {
  gl_Position = uMVPMatrix * aPosition;
  vTexCoord = aTexCoord;
}
