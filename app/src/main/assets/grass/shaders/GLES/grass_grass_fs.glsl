#version 300 es
precision mediump float;
out vec4 fragColor;
uniform sampler2D uSampler;
in vec4 vColor;
in highp vec2 vTexCoord;
void main() {
  float a = texture(uSampler, vTexCoord).r;
  fragColor = vec4(vColor.rgb, vColor.a * a);
}
