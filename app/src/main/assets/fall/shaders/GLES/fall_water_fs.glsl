#version 300 es
// 水面/河床：树（白底黑图的遮罩）乘以天空色带，而不是把两者烤在一张贴图里。
//
// 天空只取 v（纯竖直渐变），并且和树用同一个 vTexCoord —— 顶点着色器把 UV
// 按水面波纹扭曲过，天空跟着一起扭，和合并成一张贴图时的观感一致。
//
// 天空有四条色带（清晨/白日/黄昏/夜晚），按权重加权求和，和 grass 的
// grass_sky_fs.glsl 是同一套做法。权重之和恒为 1；日夜开关关掉时
// 只有黄昏那一条是 1，其余为 0，结果与单条色带逐位相同。
precision mediump float;
out vec4 fragColor;
uniform sampler2D uMask;
uniform sampler2D uSkyMorning;
uniform sampler2D uSkyDay;
uniform sampler2D uSkyDusk;
uniform sampler2D uSkyNight;
uniform float uWeightMorning;
uniform float uWeightDay;
uniform float uWeightDusk;
uniform float uWeightNight;
uniform float uAlpha;
uniform vec4 uColor;
in highp vec2 vTexCoord;
void main() {
  float m = texture(uMask, vTexCoord).r;
  vec2 skyUV = vec2(0.5, vTexCoord.y);
  vec3 sky = texture(uSkyMorning, skyUV).rgb * uWeightMorning
           + texture(uSkyDay, skyUV).rgb * uWeightDay
           + texture(uSkyDusk, skyUV).rgb * uWeightDusk
           + texture(uSkyNight, skyUV).rgb * uWeightNight;
  fragColor = vec4(sky * m, 1.0) * uColor;
  fragColor.a *= uAlpha;
}
