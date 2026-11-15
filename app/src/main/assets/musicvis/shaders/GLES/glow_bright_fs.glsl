#version 300 es
precision mediump float;
out vec4 fragColor;
uniform sampler2D uScene;
uniform vec2  uTexel;       // 1.0 / 场景尺寸
uniform float uThreshold;   // 亮度阈值
uniform float uSoftKnee;    // 阈值之上的过渡宽度
in vec2 vUv;
void main() {
  // 3×3 预采样（每格跨 2 个纹素，覆盖约 9 个全分辨率像素）。
  //
  // 目的是把谱线这类 1–2px 的细亮条平均掉，否则降到低分辨率时会采样成断续的竖条，
  // 模糊之后就是光晕里的一道道竖纹。用 3×3 而不是 5×5：覆盖范围一样，采样少 3 倍。
  vec3 sum = texture(uScene, vUv).rgb * 4.0;
  float wsum = 4.0;
  for (int j = -1; j <= 1; j++) {
    for (int i = -1; i <= 1; i++) {
      if (i == 0 && j == 0) {
        continue;
      }
      vec2 offset = uTexel * (vec2(float(i), float(j)) * 2.0);
      sum += texture(uScene, vUv + offset).rgb;
      wsum += 1.0;
    }
  }
  vec3 c = sum / wsum;

  float lum = dot(c, vec3(0.299, 0.587, 0.114));
  // 软阈值：超过阈值才留，且留一点过渡 —— 硬阈值会在光晕边缘留下可见的台阶。
  fragColor = vec4(c * smoothstep(uThreshold, uThreshold + uSoftKnee, lum), 1.0);
}
