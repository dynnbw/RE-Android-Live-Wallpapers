precision mediump float;
uniform sampler2D uSampler;
varying vec4 vColor;
varying vec2 vTexCoord;
void main() {
  float a = texture2D(uSampler, vTexCoord).a;
  gl_FragColor = vec4(vColor.rgb, vColor.a * a);
}
