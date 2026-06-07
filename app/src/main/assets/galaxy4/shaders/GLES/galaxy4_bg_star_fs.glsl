precision mediump float;
uniform float uParticleOpacity;
void main() {
  gl_FragColor = vec4(1.0, 1.0, 1.0, 0.5 * uParticleOpacity);
}
