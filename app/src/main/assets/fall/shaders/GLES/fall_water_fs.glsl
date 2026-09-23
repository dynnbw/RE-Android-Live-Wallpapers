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
/*
 * 天空里的发光体：固定屏幕位置上的一团黄白色亮斑。
 *
 * 它的作用是"替树叶的黑剪影作解释"——树是逆着光看的，所以是黑的。
 * 亮斑的强度刻意开到**远大于 1**：它自己会被辉光糊掉，看不到本体的边界，
 * 正是参考图那个样子（参考图里根本没有能辨认的太阳圆面）。
 *
 * 加在天空里（乘遮罩之前），于是树叶天然挡在它前面：m=0 处不管加多少都会被乘成 0。
 */
uniform vec2  uEmitterPos;
uniform vec2  uEmitterRadius;
uniform vec3  uEmitterColor;
uniform float uEmitterGain;
uniform float uAlpha;
uniform vec4 uColor;
in highp vec2 vTexCoord;
in highp vec2 vScreenUv;
void main() {
  float m = texture(uMask, vTexCoord).r;
  vec2 skyUV = vec2(0.5, vTexCoord.y);
  vec3 sky = texture(uSkyMorning, skyUV).rgb * uWeightMorning
           + texture(uSkyDay, skyUV).rgb * uWeightDay
           + texture(uSkyDusk, skyUV).rgb * uWeightDusk
           + texture(uSkyNight, skyUV).rgb * uWeightNight;

  // 半径分两轴：屏幕不是方的，正圆在 UV 里会变成椭圆，
  // 而这一团本来就该是横向宽、纵向扁的（参考图里横跨小半个屏幕、只占上面一条）
  vec2 delta = (vScreenUv - uEmitterPos) / max(uEmitterRadius, vec2(1e-4));
  float d = length(delta);
  sky += uEmitterColor * (uEmitterGain * exp(-d * d * 2.0));

  fragColor = vec4(sky * m, 1.0) * uColor;
  fragColor.a *= uAlpha;
}
