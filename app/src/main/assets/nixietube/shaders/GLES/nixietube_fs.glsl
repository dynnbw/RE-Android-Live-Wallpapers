#version 300 es
precision mediump float;
out vec4 fragColor;
uniform sampler2D uTexture;
in vec2 vTexCoord;
void main() {
  vec4 tex = texture(uTexture, vTexCoord);
  fragColor = tex;
}
