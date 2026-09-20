#version 300 es
precision mediump float;
out vec4 fragColor;
uniform sampler2D uSampler;
uniform float uAlpha;
uniform vec4 uColor;
in highp vec2 vTexCoord;
void main() {
  vec4 texColor = texture(uSampler, vTexCoord);
  fragColor = texColor * uColor;
  fragColor.a *= uAlpha;
}
