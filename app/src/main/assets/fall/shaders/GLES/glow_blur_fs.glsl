#version 300 es
precision mediump float;
out vec4 fragColor;
uniform sampler2D uSource;
uniform vec2 uStep;   // 已含半径：(r/w, 0) 横向、(0, r/h) 纵向
in vec2 vUv;
void main() {
  // 线性采样的 5 抽头近似，等效 9 抽头 —— 权重是固定的一套常用值，不是现编的。
  vec3 c  = texture(uSource, vUv).rgb * 0.2270270270;
  c += texture(uSource, vUv + uStep * 1.3846153846).rgb * 0.3162162162;
  c += texture(uSource, vUv - uStep * 1.3846153846).rgb * 0.3162162162;
  c += texture(uSource, vUv + uStep * 3.2307692308).rgb * 0.0702702703;
  c += texture(uSource, vUv - uStep * 3.2307692308).rgb * 0.0702702703;
  fragColor = vec4(c, 1.0);
}
