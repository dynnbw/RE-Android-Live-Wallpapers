precision mediump float;
uniform sampler2D uSampler;
uniform float uAlpha;
uniform vec4 uColor;
varying vec2 vTexCoord;
void main() {
  vec4 texColor = texture2D(uSampler, vTexCoord);
  gl_FragColor = texColor * uColor;
  gl_FragColor.a *= uAlpha;
}
