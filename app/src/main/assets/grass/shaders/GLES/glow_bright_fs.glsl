#version 300 es
precision mediump float;
out vec4 fragColor;
uniform sampler2D uScene;
uniform vec2  uTexel;       // 1.0 / 场景尺寸
uniform float uThreshold;   // 亮度阈值
uniform float uSoftKnee;    // 阈值之上的过渡宽度
in vec2 vUv;
void main() {
  // 4 抽头盒式降采样，顺带做亮度提取 —— 两件事一趟做完，省一遍全屏。
  vec3 c = texture(uScene, vUv + uTexel * vec2(-0.5, -0.5)).rgb
         + texture(uScene, vUv + uTexel * vec2( 0.5, -0.5)).rgb
         + texture(uScene, vUv + uTexel * vec2(-0.5,  0.5)).rgb
         + texture(uScene, vUv + uTexel * vec2( 0.5,  0.5)).rgb;
  c *= 0.25;
  float lum = dot(c, vec3(0.299, 0.587, 0.114));
  // 软阈值：超过阈值才留，且留一点过渡 —— 硬阈值会在光晕边缘留下可见的台阶。
  fragColor = vec4(c * smoothstep(uThreshold, uThreshold + uSoftKnee, lum), 1.0);
}
