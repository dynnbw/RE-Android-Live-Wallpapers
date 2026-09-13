// 星图必须全亮 —— 被 Lambert 照亮就毁了，所以单开一个 program 而不是复用 planet。
precision mediump float;

uniform sampler2D uSampler;

varying vec2 vTexCoord;

void main() {
  gl_FragColor = texture2D(uSampler, vTexCoord);
}
