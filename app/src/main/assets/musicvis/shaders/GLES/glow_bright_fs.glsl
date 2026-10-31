#version 300 es
precision mediump float;
out vec4 fragColor;
uniform sampler2D uScene;
uniform vec2  uTexel;       // 1.0 / 场景尺寸
uniform float uThreshold;   // 亮度阈值
uniform float uSoftKnee;    // 阈值之上的过渡宽度
in vec2 vUv;
void main() {
  // 5×5 高斯预采样（每格跨 2 个纹素，覆盖约 9 个全分辨率像素）。
  //
  // 谱线只有 1–2px 宽，直接降到半分辨率会把它们采样成断续的竖条；后面再怎么模糊，
  // 也只是把这些竖条晕开 —— 看起来就是光晕里的一道道竖纹。先做一次足够宽的预采样，
  // 把细条平均掉，外溢才是连续的雾。
  vec3 sum = vec3(0.0);
  float wsum = 0.0;
  for (int j = -2; j <= 2; j++) {
    for (int i = -2; i <= 2; i++) {
      float d2 = float(i * i + j * j);
      float w = exp(-0.5 * d2 / (1.5 * 1.5));
      vec2 offset = uTexel * (vec2(float(i), float(j)) * 2.0);
      sum += texture(uScene, vUv + offset).rgb * w;
      wsum += w;
    }
  }
  vec3 c = sum / wsum;

  float lum = dot(c, vec3(0.299, 0.587, 0.114));
  // 软阈值：超过阈值才留，且留一点过渡 —— 硬阈值会在光晕边缘留下可见的台阶。
  fragColor = vec4(c * smoothstep(uThreshold, uThreshold + uSoftKnee, lum), 1.0);
}
