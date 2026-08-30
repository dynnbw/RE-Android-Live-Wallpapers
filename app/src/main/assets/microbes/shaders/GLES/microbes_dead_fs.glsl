// 原版 DeadMicrobe_frag(逐字)
precision mediump float;
varying vec2 vTransform;
varying float vWidthScale;
void main() {
  vec2 given = vec2(gl_PointCoord.xy-.5);
  vec2 rotated = vec2(given.x*vTransform.x - given.y*vTransform.y,
                      given.x*vTransform.y + given.y*vTransform.x);
  vec2 scaled = rotated*vec2(1., vWidthScale);
  float h = length(scaled)*2.;
  gl_FragColor.rgb = vec3(1.,1.,1.);
  float hyperb = -pow(h-.4, 2.);
  gl_FragColor.a = clamp(hyperb*30.+.5, 0., .5);
}
