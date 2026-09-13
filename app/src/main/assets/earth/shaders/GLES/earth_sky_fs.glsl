// 星图必须全亮 —— 被 Lambert 照亮就毁了，所以单开一个 program 而不是复用 planet。
precision mediump float;

uniform sampler2D uSampler;

varying vec2 vTexCoord;

void main() {
  // 与 planet 用同一套网格，UV 同样偏 180° 经度，保持一致
  gl_FragColor = texture2D(uSampler, vec2(fract(vTexCoord.x + 0.5), vTexCoord.y));
}
