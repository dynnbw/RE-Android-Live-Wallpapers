#version 300 es
precision mediump float;
out vec4 fragColor;
in vec3 vColor;
in vec2 vTransform;
in float vWidthScale;
void main() {
  vec2 given = vec2(gl_PointCoord.xy - 0.5);
  vec2 rotated = vec2(given.x * vTransform.x - given.y * vTransform.y, given.x * vTransform.y + given.y * vTransform.x);
  vec2 scaled = rotated * vec2(1.0, vWidthScale);
  float h = length(scaled) * 2.0;
  fragColor.rgb = vColor.xyz;
  // GLSL leaves pow(x, 2.0) undefined for x < 0, and drivers differ: some fold it to
  // x*x, others return NaN (which would make gl_FragColor.a NaN and take the sprite
  // with it). h is the radial distance from the point centre, so (h - 0.4) is negative
  // over the whole inner body of the sprite -- most of its area, not an edge case.
  // x*x is mathematically identical and well-defined for negatives. Same reasoning as
  // silk_fs.glsl; verified not to change the render on Adreno 730, where pow() happens
  // to be folded already -- this is about the drivers that do not fold it.
  float hyperb = -(h - 0.4) * (h - 0.4);
  fragColor.a = clamp(hyperb * 30.0 + 0.5, 0.0, 0.5) + clamp(-length(rotated * vec2(1.0, (vWidthScale - 1.0) * 0.2 + 1.0) * 2.0) + 1.0, 0.0, 0.5);
}
