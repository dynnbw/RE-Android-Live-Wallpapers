#version 300 es
precision mediump float;
out vec4 fragColor;
uniform float uParticleOpacity;
void main() {
  fragColor = vec4(1.0, 1.0, 1.0, 0.5 * uParticleOpacity);
}
