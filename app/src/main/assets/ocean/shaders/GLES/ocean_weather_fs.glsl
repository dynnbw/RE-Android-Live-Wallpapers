#version 300 es
precision mediump float;
out vec4 fragColor;
uniform sampler2D uTexture;
uniform vec4 uColor;
in vec2 vTexCoord;
void main() {
  vec4 tex = texture(uTexture, vTexCoord);
  // Mimic original GLES 1.x fixed-function: GL_MODULATE (tex * color) with
  // glColor4f(r,g,b,a). RGB uses uColor.rgb at full strength; alpha is separate.
  fragColor = vec4(tex.rgb * uColor.rgb, tex.a * uColor.a);
}
