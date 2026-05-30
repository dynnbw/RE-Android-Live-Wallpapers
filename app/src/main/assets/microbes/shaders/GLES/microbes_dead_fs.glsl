precision mediump float;
varying vec4 vColor;
void main() {
  vec2 given = vec2(gl_PointCoord.xy - 0.5);
  float h = length(given) * 2.0;
  gl_FragColor.rgb = vColor.rgb;
  gl_FragColor.a = vColor.a * clamp((1.0 - h) * 2.0, 0.0, 2.0);
}
