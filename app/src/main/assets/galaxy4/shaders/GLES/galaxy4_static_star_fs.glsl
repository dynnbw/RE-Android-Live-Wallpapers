#version 300 es
precision mediump float;
out vec4 fragColor;
in float pointSize;
uniform sampler2D uTexture1;
uniform sampler2D uTexture2;
void main() {
  if (pointSize > 4.0) {
    fragColor = texture(uTexture1, gl_PointCoord);
  } else {
    fragColor = texture(uTexture2, gl_PointCoord);
  }
}
