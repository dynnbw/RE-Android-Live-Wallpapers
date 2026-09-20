#version 300 es
precision mediump float;
out vec4 fragColor;
uniform sampler2D uSampler;
uniform float uAlpha;
// Tint multiplier; defaults to (1,1,1). Used to darken the white cloud
// textures at night. Program-level state -- any draw that bypasses
// GrassSpriteRenderer must reset it to white itself.
uniform vec3 uTint;
in highp vec2 vTexCoord;
in float vAlpha;
void main() {
  vec4 c = texture(uSampler, vTexCoord);
  fragColor = vec4(c.rgb * uTint, c.a * vAlpha * uAlpha);
}
