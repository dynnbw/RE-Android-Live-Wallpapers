#version 300 es
precision mediump float;

out vec4 fragColor;
uniform sampler2D uSampler;

in highp vec2 vTexCoord;

void main() {
  fragColor = texture(uSampler, vTexCoord);
}
