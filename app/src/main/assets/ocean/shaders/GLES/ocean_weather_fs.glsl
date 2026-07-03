precision mediump float;
uniform sampler2D uTexture;
uniform vec4 uColor;
varying vec2 vTexCoord;
void main() {
  vec4 tex = texture2D(uTexture, vTexCoord);
  // Mimic original GLES 1.x fixed-function: GL_MODULATE (tex * color) with
  // glColor4f(r,g,b,a). RGB uses uColor.rgb at full strength; alpha is separate.
  gl_FragColor = vec4(tex.rgb * uColor.rgb, tex.a * uColor.a);
}
