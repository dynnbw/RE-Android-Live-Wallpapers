precision mediump float;
uniform sampler2D uTexture1;
uniform sampler2D uTexture2;
uniform float uTime;
void main() {
  float blend = (sin(uTime * 2.0) + 1.0) * 0.5;
  vec4 color1 = texture2D(uTexture1, gl_PointCoord);
  vec4 color2 = texture2D(uTexture2, gl_PointCoord);
  gl_FragColor = mix(color1, color2, blend);
}
