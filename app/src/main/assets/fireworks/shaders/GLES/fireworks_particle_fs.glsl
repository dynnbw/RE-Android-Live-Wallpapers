precision mediump float;
uniform sampler2D uSampler;
varying vec2 vTexCoord;
varying vec4 vColor;
void main() {
  vec4 c = texture2D(uSampler, vTexCoord);
  gl_FragColor = vec4(c.rgb * vColor.rgb, c.a * vColor.a);
}
