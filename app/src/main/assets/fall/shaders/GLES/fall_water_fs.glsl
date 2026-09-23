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

/*
 * 夜空星星：片元里程序化生成，不占贴图、不占顶点、不需要任何批次。
 *
 * **UV 用 vScreenUv，不跟水面波纹走。** 一开始是跟着走的（星星是水里的倒影嘛），
 * 上机一测，那正是"闪烁突变"的根因：波纹位移 texOffset 是**遮罩 UV** 空间的量
 * （横向只铺 0~0.5），加到**屏幕 UV**（铺 0~1）上等于放大约两倍，实测能挪 17px ——
 * 而星点只有 4px 大，被挪一格就是从"在"跳到"不在"，整片星空在抖。
 * 星星相当于无穷远的光源，本来就不该随水面动。
 *
 * **软、冷、慢。** 核心用大范围的 smoothstep 化开（小到亚像素的亮点会闪成噪点），
 * 色调整体偏冷白（与 NIGHT 色带同族），闪烁周期拉到几秒到十几秒。
 *
 * 两层不同密度的格子，每格至多一颗；位置、亮度、色调、闪烁全由格子坐标哈希出来。
 *
 * uStarAmount 由夜那一档的权重算出来（见 FallDayNightSystem.computeStarAmount），
 * 天没黑透时是 0，整个星空连算都不算。
 */
uniform float uStarAmount;
/**
 * 屏幕高宽比（height / width，竖屏 > 1）。
 *
 * <p>**星点的形状必须靠它修正。** UV 是逐轴归一化的：1 个 UV 单位在竖直方向
 * 是横向的 height/width 倍像素（1080×2400 上是 2.22 倍），
 * 所以在 UV 里算出来的"圆"到屏幕上是个**竖椭圆** —— 星点会被上下拉长。
 * 距离度量里把竖直分量乘上它，星的圆才算在屏幕上圆。
 */
uniform float uStarAspect;
/**
 * 闪烁用的时间。**必须是"回绕后的小数"，不能是绝对时间。**
 *
 * <p>一开始这里传的是 uptime 的秒数（上机时约 2956）。那样正弦的自变量是
 * {@code 2956 × 1.25 ≈ 3700} 这个量级，低位精度一丢，自变量就被量化成
 * **~2.3 弧度的台阶** —— 除以最快的星 0.8 rad/s，正好是**每两三秒跳一次**。
 * 上机用 {@code glReadPixels} 回读证实了：最亮值连续 48 帧纹丝不动、然后一步跳 80%。
 *
 * <p>现在 Java 侧按 {@link #STAR_WRAP_S} 回绕，值恒在 [0, 30) —— 自变量最大约 38，
 * 量化台阶降到 0.04 rad 以下，肉眼不可见。
 */
uniform highp float uStarTime;

/** 回绕周期（秒），必须与 Java 侧一致。 */
const float STAR_WRAP_S = 30.0;

/**
 * 闪烁基频。每颗星取它的**整数倍**。
 *
 * <p>取整不是为了好看，是为了回绕处**无缝**：t 从 30 回到 0 时，
 * 相位正好前进 {@code 2π × 整数}，正弦值是连续的。取任意实数速度的话，
 * 回绕那一刻全天的星星会一起跳一下。
 */
const float STAR_BASE_W = 6.2831853 / STAR_WRAP_S;

/** 精度友好的哈希。经典的 fract(sin(x)*43758) 在格子坐标变大之后会失真。 */
float hash21(highp vec2 p) {
  p = fract(p * vec2(123.34, 456.21));
  p += dot(p, p + 45.32);
  return fract(p.x * p.y);
}

/**
 * 一层星：返回该像素上这一层的贡献。
 *
 * <p>⚠ **每个属性用各自的哈希，绝不共用。** 这是踩过的坑：
 * 一开始只有一个 {@code h}，既拿它过阈值、又拿它算速度与相位。
 * 但能活下来的格子是 {@code h >= thresh} 的那些 —— 它们的哈希值全挤在
 * [0.9, 1.0] 这条窄带里，于是**所有星拿到同一个速度、几乎同一个相位，
 * 整片星空一起亮一起暗**。CPU 仿真过：速度倍率只取到 6 这一个值，
 * 相位只覆盖一整圈的 10%。
 */
vec3 starLayer(highp vec2 uv, float scale, float seed, float thresh) {
  highp vec2 gv = fract(uv * scale) - 0.5;
  highp vec2 id = floor(uv * scale);

  float present = hash21(id + seed);
  if (present < thresh) return vec3(0.0);
  // 上面那个 present 只用来决定"这一格有没有星"，后面每个属性各取各的
  float hBright = hash21(id + 11.3);
  float hSpeed = hash21(id + 27.9);
  float hPhase = hash21(id + 43.1);
  float hTint = hash21(id + 61.7);

  // 星在格子内的位置也随机，否则会排成整齐的方阵
  highp vec2 off = (vec2(hash21(id + 1.7), hash21(id + 9.3)) - 0.5) * 0.8;
  highp vec2 delta = gv - off;
  delta.y *= uStarAspect;   // 见 uStarAspect：不修正的话星点是竖椭圆
  float d = length(delta);

  // 速度取基频的整数倍（2~6 → 周期 15~5 秒），回绕才不会跳，见 STAR_BASE_W
  float k = 2.0 + floor(hSpeed * 5.0);
  float twinkle = 0.70 + 0.30 * sin(uStarTime * (STAR_BASE_W * k) + hPhase * 6.2831853);
  // 核心范围刻意给得大：小到亚像素的亮点会在水面扰动下闪成噪点
  float core = smoothstep(0.17, 0.0, d);
  // 色调不做四色表，只在"冷白 → 白"之间过渡，与夜色带同族
  vec3 tint = mix(vec3(0.86, 0.91, 1.00), vec3(1.0), hTint);
  return tint * (core * twinkle * (0.30 + hBright * 0.70));
}

vec3 starField(highp vec2 uv) {
  return starLayer(uv,  50.0, 0.0, 0.90)
       + starLayer(uv, 100.0, 3.1, 0.94);
}

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

  // 星空同样加在乘遮罩之前：树天然挡在星星前面，不必另做遮挡
  if (uStarAmount > 0.001) {
    sky += starField(vScreenUv) * uStarAmount;
  }

  fragColor = vec4(sky * m, 1.0) * uColor;
  fragColor.a *= uAlpha;
}
