uniform mat4 uMVPMatrix;
attribute vec2 aPosition;
attribute vec2 aTexCoord;
// Per-vertex alpha, multiplied with the uAlpha uniform in the fragment stage.
// Producers that need no per-vertex variation simply write 1.0.
attribute float aAlpha;
varying highp vec2 vTexCoord;
varying float vAlpha;
void main() {
  gl_Position = uMVPMatrix * vec4(aPosition, 0.0, 1.0);
  vTexCoord = aTexCoord;
  vAlpha = aAlpha;
}
