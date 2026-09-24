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

/*
 * 夜里被搅动的水里，蓝藻发出的生物光。
 *
 * 依据的是实测的甲藻发光行为（见项目记忆里的文献），三条都反直觉：
 *
 *   1. **过阈值才闪，不是"有波纹就亮"。** 触发阈值比环境水流高好几个数量级，
 *      所以平静的水根本不发光 —— 只有真正被扰动的地方才闪。
 *      这里用 u_dropPower（这次扰动的强度）过 uAlgaeThreshold 来体现。
 *   2. **极短。** 实测闪光 50~150ms、衰减常数约 0.14s。所以光只贴在最前面那一段，
 *      波前过去就没了 —— 不是留下一片余晖。
 *   3. **是颗粒的，不是一道干净的环。** 每个细胞的阈值不同（文献里的 "cell anxiety"），
 *      真实的发光尾迹像碎钻。所以强度要乘一层固定的空间哈希。
 *
 * 颜色用 474~476nm 的**蓝**（实测发射峰），不是照片上那种青绿 ——
 * 青绿多半来自水色吸收与相机白平衡。
 */
uniform float uAlgaeAmount;
uniform float uAlgaeGain;
uniform float uAlgaeThreshold;
/** 闪光贴在波前后面多宽的一段（网格单位）。spread 每秒走 30 单位，所以宽度÷30 就是时长。 */
uniform float uAlgaeBand;
/**
 * 颗粒用的噪声贴图，以及它每铺满一次覆盖多少网格单位。
 *
 * <p>**不能用方格哈希做颗粒。** 一开始是 `hash21(floor(uv * 密度))` ——
 * 那是均匀的方格，上机看就是"一片细密的均匀点子糊在屏幕上"。
 * 真实的藻是一团一团的，要用**有机的团状噪声**。
 * 这张贴图取自本项目的 magicsmoke 壁纸（它自带的噪声图）。
 */
uniform sampler2D uAlgaeNoise;
uniform float uAlgaeNoiseTile;
uniform float uAlgaeNoiseGain;
/** 一团藻的颜色范围：疏的地方偏 uAlgaeLow、密的地方偏 uAlgaeHigh。 */
uniform vec3 uAlgaeLow;
uniform vec3 uAlgaeHigh;
/** 每次扰动的强度，与 u_drop 一一对应。 */
uniform float uAlgaePower[$DROP_SIZE];
/*
 * 波纹本身。顶点着色器已经声明过同名的 —— 同名 uniform 在两个阶段共享同一个值，
 * 所以这里再声明一次就能直接读到，**不需要多传一份**。
 *
 * ⚠ **必须显式写 highp。** 顶点着色器的 float 默认就是 highp，而片元是 mediump ——
 * 同一个 uniform 在两个阶段精度不一致，链接会直接失败：
 * "Precisions of uniform 'u_dxMul' differ between VERTEX and FRAGMENT shaders."
 * 症状是水面整个消失（程序没建出来），而且是**上机才报**的。
 */
uniform highp float u_dxMul;
uniform highp vec4 u_drop[$DROP_SIZE];
uniform highp float u_dropCount;

float hash21(highp vec2 p);   // 定义在下面，星空那一节

vec3 algaeGlow(highp vec2 meshPos) {
  /*
   * 噪声**在循环外只取一次** —— 它是水本身的属性（这片水里有多少藻、
   * 波前在这里鼓出来多少），与是哪个波纹无关。放进循环里等于每个波纹都采一次贴图，
   * 而那是这里最贵的一项（上机实测帧时间因此涨到几百毫秒）。
   */
  highp vec2 nuv = meshPos / max(uAlgaeNoiseTile, 1e-4);

  /*
   * 密度用**两级噪声相乘**。单级时 `clamp(n * 1.8, 0, 1)` 大部分格子都到 1，
   * 于是整条环是均匀的 —— 用户的原话是「看起来很像普通的光带」。
   * 两级相乘才出颗粒。
   */
  float n1 = texture(uAlgaeNoise, nuv).r;
  float n2 = texture(uAlgaeNoise, nuv * 3.7 + vec2(0.13, 0.57)).r;
  float density = smoothstep(0.20, 0.80, n1) * (0.30 + 0.70 * smoothstep(0.30, 0.85, n2));
  if (density <= 0.004) return vec3(0.0);
  float crowded = clamp(n1 * 0.65 + n2 * 0.35, 0.0, 1.0);
  vec3 blob = mix(uAlgaeLow, uAlgaeHigh, crowded);   // 不要叫 patch：GLSL ES 3.00 的保留字

  /*
   * 波前的"不完美"：用一个粗尺度的空间噪声把半径顶出去/收回来一点。
   *
   * 用**位置**而不是角度 —— 按角度采样会在 ±π 处留下一道接缝（噪声在角度上不周期）。
   */
  float wobble = texture(uAlgaeNoise, meshPos * 0.04 + vec2(7.3, 1.9)).r;
  float wobbleScale = 0.90 + 0.20 * wobble;

  float acc = 0.0;
  for (int i = 0; i < $DROP_SIZE; i++) {
    if (float(i) >= u_dropCount) break;
    float power = uAlgaePower[i];
    if (power < uAlgaeThreshold) continue;      // 没惊动这片水
    vec4 d = u_drop[i];
    // 与顶点着色器 addDrop() 同一套距离度量（X 方向同样乘 dxMul / u_dxMul）
    highp vec2 delta = vec2((meshPos.x - d.x) * u_dxMul, meshPos.y - d.y);
    float dist = length(delta);
    if (dist > d.w * wobbleScale + uAlgaeBand * 0.30) continue;   // 还没扫到 / 已经过去

    /*
     * 闪光带：**峰值靠外沿**，两头归零。
     *
     * 外沿（dist ≈ 波前）是刚被搅动的地方，本来就该最亮 —— 之前把峰值放在
     * 带子正中是错的。但也不能直接取到波前处才截断：那样接缝是一道硬边，
     * 上机看就是个边缘清晰的"圆盘"。所以峰值落在 0.75，外侧再留 0.25 淡出。
     */
    float t = clamp((dist - (d.w * wobbleScale - uAlgaeBand))
            / (uAlgaeBand * 1.30), 0.0, 1.0);
    float band = smoothstep(0.0, 0.75, t) * (1.0 - smoothstep(0.75, 1.0, t));

    // 过阈值之后的响应是超线性的（实测约 1.9 次方），这里用一条平滑的 S 曲线
    float above = smoothstep(uAlgaeThreshold, 1.0, power);
    acc += band * above;
  }
  // 颜色与密度在循环外统一乘一次
  return blob * (acc / (1.0 + acc)) * density;
}

uniform float uAlpha;
uniform vec4 uColor;
in highp vec2 vTexCoord;
in highp vec2 vScreenUv;
in highp vec2 vMeshPos;
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



  vec3 color = sky * m;

  /*
   * 蓝藻**加在乘遮罩之后**，与星空正好相反。
   *
   * 区别在于它们各自"在哪一层"：
   *   - **星星**是被反射的夜空的一部分 → 树的倒影该挡住它们 → 乘遮罩之前 ✓
   *   - **蓝藻**是水面自己发出来的光，和水面的倒影在同一个平面、而且在倒影前面
   *     → 树不该挡住它 → 乘遮罩之后 ✓
   *
   * （用户的原话：「甲藻发光似乎会被树的遮罩遮盖，可能是绘制顺序问题」。）
   */
  if (uAlgaeAmount > 0.001) {
    // 颜色由 low/high 两端给出（见 algaeGlow），这里只负责整体亮度
    color += algaeGlow(vMeshPos) * (uAlgaeGain * uAlgaeAmount);
  }

  fragColor = vec4(color, 1.0) * uColor;
  fragColor.a *= uAlpha;
}
