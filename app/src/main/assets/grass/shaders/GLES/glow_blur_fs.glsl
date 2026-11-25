#version 300 es
precision mediump float;
out vec4 fragColor;
uniform sampler2D uSource;
uniform vec2 uStep;    // 每次采样的步长（纹理坐标）
uniform float uSigma;  // 高斯 σ，单位是"采样步数"（这里固定 2：9 抽头覆盖 ±2σ）
in vec2 vUv;

/*
 * 可分离高斯：9 个抽头、权重在着色器里按 exp(-x²/2σ²) 现算再归一化。
 *
 * 之前是单遍 5 抽头、步长直接等于半径：半径 8（半分辨率下就是 8 个纹素）时，
 * 采样点落在中心、±11、±26 纹素上，看起来是几个错位的副本而不是连续的晕。
 * 现在步长取 σ/2（半径的 1/4），抽头间距减半、数量翻倍，采样点之间的空隙
 * 小于 σ，叠起来就是平滑衰减。
 */
const int TAPS = 4;

void main() {
  vec3 sum = vec3(0.0);
  float total = 0.0;
  float inv = 1.0 / (uSigma * uSigma);
  for (int i = -TAPS; i <= TAPS; i++) {
    float f = float(i);
    float w = exp(-0.5 * f * f * inv);
    sum += texture(uSource, vUv + uStep * f).rgb * w;
    total += w;
  }
  fragColor = vec4(sum / total, 1.0);
}
