precision mediump float;
void main() {
  vec2 given = vec2(gl_PointCoord.xy - 0.5);
  float h = length(given) * 2.0;
  gl_FragColor.rgb = vec3(1.0, 1.0, 1.0);
  gl_FragColor.a = pow(2.81, -pow(h * 2.0, 2.0));
}
