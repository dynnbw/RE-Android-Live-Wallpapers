#version 300 es
precision highp float;

out vec4 fragColor;

in highp vec2 vUv;

uniform float uTime;
uniform float uOpacity;
/** 0..5，见 GrassWeatherSystem.rainIntensity。 */
uniform float uIntensity;
/** 竖轨道数（参考实现的 SCALE_X）。 */
uniform float uTrackCount;
/** 下落速度。参考实现里是个 uniform，取值没能提取出来，上机调。 */
uniform float uSpeedY;
uniform float uAspect;
/** 最靠近镜头那一层（参考实现里单独处理的那层）。 */
uniform float uBaseAlpha;
uniform float uBaseScale;
/** 其余各层；由 GrassRainStreakLayers 在 Java 侧算好。 */
uniform float uLayerAlpha[5];
uniform float uLayerScale[5];

#define SCALE 5.
#define BASE_TRACKS 10.
#define PI 3.1415926

/** 位混合哈希。 */
vec3 hash13(float p) {
  vec3 p3 = fract(vec3(p) * vec3(.1031, .11369, .13787));
  p3 += dot(p3, p3.yzx + 19.19);
  return fract(vec3((p3.x + p3.y) * p3.z, (p3.x + p3.z) * p3.y, (p3.y + p3.z) * p3.x));
}

/**
 * 每条竖轨道一套固定的随机数。
 *
 * <p>参考实现这里是按 (value + seed) * 1.5/256 采样一张 256² 噪声图；它只当随机源用，
 * 所以换成哈希 —— 同一角色，且不用背 400 KB 资源。
 */
vec3 getRandomLite(float value, float index) {
  return hash13(value + index * 137.0);
}

/**
 * 一层雨丝。
 *
 * <p>竖轨道栅格（uv.x 乘上轨道数后 floor 成整数轨道，fract 留作轨道内的横向位置），
 * 每条轨道由哈希给出**统一**的速度与偏移，所以同一条轨道上的雨丝速度一致 ——
 * 这正是"一条条往下刷"而不是"一屏杂乱点"的原因。
 *
 * <p>yv 那段是彗星头：{@code 1.0/fract(...)} 把相位变成向下拖长的尾巴，
 * 再乘 {@code sin(dx*PI)²} 收成两端渐隐的窄条。
 */
vec4 drawDropLayer(vec2 uv, float scale, float alpha, float index) {
  uv *= scale;
  uv = (uv - 0.5) * vec2(uAspect, 1.0);

  uv.x = uv.x * uTrackCount;
  float dx = fract(uv.x);
  uv.x = floor(uv.x);

  float dropTime = (uTime + 100.0) * uSpeedY;
  uv.y *= mix(0.028 * 1.6, 0.028, uIntensity / 5.0);

  vec3 rg = getRandomLite(uv.x, index);
  float offset = (rg.x + rg.y - 1.5) * 3.0;
  float speed = offset * 0.033 + 0.7;

  float yv = fract(uv.y + dropTime * speed
      - 0.1 * uSpeedY * mix(1.3, 1.0, uIntensity) + offset) * 95.0;
  // 参考实现这里直接 1.0/yv：yv 恰好为 0 时是 inf，inf*0 会产生 NaN 并糊满一屏。
  yv = 1.0 / max(yv, 1.0E-4);
  yv = smoothstep(0.0, 1.0, yv * yv);
  float alphaGradient = clamp(yv + 0.5, 0.0, 1.0);
  yv = sin(yv * PI) * (speed * 5.0);

  // 圆形头：横向两端渐隐，雨丝才不是硬边矩形
  float d2 = sin(dx * PI);
  yv *= d2 * d2;

  float a = smoothstep(0.0, 1.0, yv) * alphaGradient * alpha;

  // 最靠近镜头那层只在真正的大雨里出现（参考实现的条件）
  if (index < 0.5) {
    return (rg.b > 0.4 && uIntensity > 4.5) ? vec4(1.0, 1.0, 1.0, a) : vec4(0.0);
  }
  // 其余各层按轨道偏移量筛掉一部分，避免每层每轨都长雨丝
  float threshold = mix(-2.0, -5.0, uIntensity / 5.0);
  return offset > threshold ? vec4(1.0, 1.0, 1.0, a) : vec4(0.0);
}

void main() {
  // 参考实现的全屏四边形 aUv 是**上正**（v 在顶部为 1），而 grass 的 vUv.y 是**下正**
  // （v 在屏幕底为 1，见 GrassScene 的 orthoM(0, w, h, 0, ...)）。
  //
  // 这一处不能省：drawDropLayer 里是 fract(uv.y + time*speed + ...)，时间增大时
  // 特征往 **-y** 走。参考实现上正，于是 -y 就是向下 ✓；grass 下正，同一个式子
  // 就变成雨往**上**飞了。翻了这一次之后，下面所有对 uv.y 的用法才和参考实现同义。
  //
  // 旁证：同一批参考着色器里 rain_drop_new.glsl 有 `uv.y = 1.0 - uv.y;`，
  // 而 rain_screen 没有 —— 正是因为 rain_screen 的输入本来就是上正。
  vec2 uv = vec2(vUv.x, 1.0 - vUv.y);
  vec4 color = vec4(0.0);

  // 最近的一层：单独处理，最粗最亮
  color += drawDropLayer(uv, BASE_TRACKS + uBaseScale * SCALE, uOpacity * uBaseAlpha, 0.0);
  color = clamp(color, 0.0, 1.0);
  color.a *= mix(1.0, 0.7, smoothstep(0.1, 1.0, uv.y));

  // 其余各层：越远越细越淡
  for (int i = 0; i < 5; i++) {
    if (uLayerScale[i] <= 0.0) continue;
    color += drawDropLayer(uv, BASE_TRACKS + uLayerScale[i] * SCALE,
        uOpacity * uLayerAlpha[i], float(i + 1));
    color = clamp(color, 0.0, 1.0);
    color.a *= mix(1.0, 0.5, smoothstep(0.1, 1.0, uv.y));
  }

  fragColor = color;
}
