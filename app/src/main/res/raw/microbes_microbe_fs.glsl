precision mediump float;
varying vec3 vColor;
varying vec2 vTransform;
varying float vWidthScale;
void main() {
  vec2 given = vec2(gl_PointCoord.xy - 0.5);
  vec2 rotated = vec2(given.x * vTransform.x - given.y * vTransform.y, given.x * vTransform.y + given.y * vTransform.x);
  vec2 scaled = rotated * vec2(1.0, vWidthScale);
  float h = length(scaled) * 2.0;
  gl_FragColor.rgb = vColor.xyz;
  float hyperb = -pow(h - 0.4, 2.0);
  gl_FragColor.a = clamp(hyperb * 30.0 + 0.5, 0.0, 0.5) + clamp(-length(rotated * vec2(1.0, (vWidthScale - 1.0) * 0.2 + 1.0) * 2.0) + 1.0, 0.0, 0.5);
}
