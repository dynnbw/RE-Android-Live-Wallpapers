#version 300 es
precision mediump float;
out vec4 fragColor;
uniform sampler2D uTexture;
void main() {
  vec4 texColor = texture(uTexture, gl_PointCoord);
  fragColor.rgb = texColor.rgb;
  fragColor.a = 0.1;
}
