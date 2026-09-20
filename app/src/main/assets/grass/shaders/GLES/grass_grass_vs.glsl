#version 300 es
uniform mat4 uMVPMatrix;
in vec2 aPosition;
in vec4 aColor;
in vec2 aTexCoord;
out vec4 vColor;
out highp vec2 vTexCoord;
void main() {
  gl_Position = uMVPMatrix * vec4(aPosition, 0.0, 1.0);
  vColor = aColor;
  vTexCoord = aTexCoord;
}
