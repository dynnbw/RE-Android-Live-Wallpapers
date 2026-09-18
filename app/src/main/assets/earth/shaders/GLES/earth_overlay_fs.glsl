precision mediump float;

uniform sampler2D uSampler;

varying highp vec2 vTexCoord;

void main() {
  gl_FragColor = texture2D(uSampler, vTexCoord);
}
