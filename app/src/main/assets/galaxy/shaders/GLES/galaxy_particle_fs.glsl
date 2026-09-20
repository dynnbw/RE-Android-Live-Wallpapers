#version 300 es
precision mediump float;
out vec4 fragColor;
uniform sampler2D uTexture;
in vec4 vColor;
void main() {
  vec4 texColor = texture(uTexture, gl_PointCoord);
  fragColor = vColor * texColor;
}
