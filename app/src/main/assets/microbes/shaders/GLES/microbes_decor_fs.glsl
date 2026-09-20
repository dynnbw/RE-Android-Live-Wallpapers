#version 300 es
// 原版 Decoration_frag(逐字)
precision mediump float;
out vec4 fragColor;
in vec4 vColor;
void main() {
  vec2 given = vec2(gl_PointCoord.xy-.5);
  float h = length(given)*2.;
  fragColor.rgb = vColor.rgb;
  fragColor.a = vColor.a*clamp((1.-h)*2., 0.,2.);
}
