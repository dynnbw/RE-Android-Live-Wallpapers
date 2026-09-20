#version 300 es
uniform mat4 uMVPMatrix;
in vec2 aPosition;
in vec2 aTexCoord;
// Per-vertex alpha, multiplied with the uAlpha uniform in the fragment stage.
// Producers that need no per-vertex variation simply write 1.0.
in float aAlpha;
out highp vec2 vTexCoord;
out float vAlpha;
void main() {
  gl_Position = uMVPMatrix * vec4(aPosition, 0.0, 1.0);
  vTexCoord = aTexCoord;
  vAlpha = aAlpha;
}
