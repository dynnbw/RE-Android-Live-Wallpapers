#version 300 es
precision mediump float;
out vec4 fragColor;
uniform sampler2D uSampler;
uniform float uAlpha;
in highp vec2 vTexCoord;
void main() {
  vec4 c = texture(uSampler, vTexCoord);
  fragColor = vec4(c.rgb, c.a * uAlpha);
}
