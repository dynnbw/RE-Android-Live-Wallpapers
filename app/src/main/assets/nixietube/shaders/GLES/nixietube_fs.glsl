precision mediump float;
uniform sampler2D uTexture;
varying vec2 vTexCoord;
void main() {
  vec4 tex = texture2D(uTexture, vTexCoord);
  gl_FragColor = tex;
}
