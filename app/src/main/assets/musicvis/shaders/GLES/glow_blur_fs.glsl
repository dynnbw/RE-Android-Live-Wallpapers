#version 300 es
precision mediump float;
out vec4 fragColor;
uniform sampler2D uSource;
uniform vec2 uStep;   // 已含半径：(r/w, 0) 横向、(0, r/h) 纵向
in vec2 vUv;
void main() {
  // 可分离高斯：步距 1 采样 ±RADIUS，权重 exp(-i² / 2σ²)，最后归一化。
  //
  // 覆盖范围要明显大于波形本身的台阶宽度，否则模糊后会保留源图的段落感
  // （看起来就是一道道条纹）。σ=3.5 步、±12 步约覆盖 3.4σ。
  const int RADIUS = 12;
  const float SIGMA = 3.5;
  vec3 sum = texture(uSource, vUv).rgb;
  float wsum = 1.0;
  for (int i = 1; i <= RADIUS; i++) {
    float fi = float(i);
    float w = exp(-0.5 * fi * fi / (SIGMA * SIGMA));
    sum += (texture(uSource, vUv + uStep * fi).rgb
          + texture(uSource, vUv - uStep * fi).rgb) * w;
    wsum += 2.0 * w;
  }
  fragColor = vec4(sum / wsum, 1.0);
}
