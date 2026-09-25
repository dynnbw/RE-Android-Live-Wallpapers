#version 300 es
precision mediump float;
out vec4 fragColor;
uniform sampler2D uScene;
uniform sampler2D uBloom;
uniform float uStrength;
// 黄昏影调：作用在**合成之后的整帧**上（0 = 不压，默认；不设它与加这个功能之前逐位相同）。
//
// 放在这里而不是各壁纸的着色器里，是因为要的是"整张画面"：草叶、天空、云、雨、水
// 都是分开画的，而且草叶自己还有大量半透明叠加 —— 逐层各压一次的话，叠得越多的地方
// 被压得越狠，画面会花。合成这一步之后，每个像素只经过一次。
uniform float uTone;
uniform float uToneGamma;
in vec2 vUv;
void main() {
  vec3 scene = texture(uScene, vUv).rgb;
  vec3 bloom = texture(uBloom, vUv).rgb;
  vec3 composed = scene + bloom * uStrength;

  // 曲线：pow 型，0 与 1 上都是恒等 —— 动的只有中间段，正是"中间灰偏暗部、黑白灰拉开"。
  //
  // **大于 1 的部分原样留住**（场景缓冲是 RGBA16F，逆光的溢光本来就超过 1）：
  // 压它等于把逆光那道金擦掉；留住它，中间调沉下去之后它反而更突出。
  //
  // 局部用 highp：片元默认 mediump（尾数约 10 位），而这条曲线把暗部再压一档，
  // 暗绿本来就在 0.1 附近 —— mediump 会在暗部压出条带。
  if (uTone > 0.0) {
    highp vec3 flattened = clamp(composed, 0.0, 1.0);
    highp vec3 shaped = pow(flattened, vec3(uToneGamma));
    shaped += max(composed - 1.0, 0.0);
    composed = mix(composed, shaped, uTone);
  }

  // **alpha 必须写 1。** 场景纹理带 alpha（草叶与精灵都是半透明的），
  // 照抄到屏幕会改变壁纸自身的合成方式。
  fragColor = vec4(composed, 1.0);
}
