precision mediump float;
uniform sampler2D uSampler;
uniform float uAlpha;
varying vec2 vTexCoord;
void main() {
  vec4 c = texture2D(uSampler, vTexCoord);
  gl_FragColor = vec4(c.rgb, c.a * uAlpha);
}
