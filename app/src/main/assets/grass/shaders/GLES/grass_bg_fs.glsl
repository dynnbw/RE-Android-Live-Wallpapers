precision mediump float;
uniform sampler2D uSampler;
uniform float uAlpha;
// Tint multiplier; defaults to (1,1,1). Used to darken the white cloud
// textures at night. Program-level state -- any draw that bypasses
// GrassSpriteRenderer must reset it to white itself.
uniform vec3 uTint;
varying vec2 vTexCoord;
varying float vAlpha;
void main() {
  vec4 c = texture2D(uSampler, vTexCoord);
  gl_FragColor = vec4(c.rgb * uTint, c.a * vAlpha * uAlpha);
}
