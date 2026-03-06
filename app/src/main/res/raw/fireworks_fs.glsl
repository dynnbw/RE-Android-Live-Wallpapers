precision mediump float;
uniform sampler2D uSampler;
uniform float uAlpha;
uniform vec3 uColor;
varying vec2 vTexCoord;
void main() {
  vec4 c = texture2D(uSampler, vTexCoord);
  gl_FragColor = vec4(c.rgb * uColor, c.a * uAlpha);
}
