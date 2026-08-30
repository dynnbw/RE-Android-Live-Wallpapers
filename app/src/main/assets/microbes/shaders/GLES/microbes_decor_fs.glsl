// 原版 Decoration_frag(逐字)
precision mediump float;
varying vec4 vColor;
void main() {
  vec2 given = vec2(gl_PointCoord.xy-.5);
  float h = length(given)*2.;
  gl_FragColor.rgb = vColor.rgb;
  gl_FragColor.a = vColor.a*clamp((1.-h)*2., 0.,2.);
}
