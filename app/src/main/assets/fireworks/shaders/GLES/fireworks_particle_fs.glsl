#version 300 es
precision mediump float;
out vec4 fragColor;
uniform sampler2D uSampler;
in highp vec2 vTexCoord;
in vec4 vColor;
void main() {
  vec4 c = texture(uSampler, vTexCoord);
  fragColor = vec4(c.rgb * vColor.rgb, c.a * vColor.a);
}
