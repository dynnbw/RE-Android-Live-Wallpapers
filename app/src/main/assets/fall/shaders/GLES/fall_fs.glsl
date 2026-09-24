#version 300 es
precision mediump float;
out vec4 fragColor;
uniform sampler2D uSampler;
uniform float uAlpha;
uniform vec4 uColor;
/**
 * 落叶的时段染色：往 uTint 混 uTintAmount。
 *
 * **必须是混，不能是乘。** 枫叶是橙红的，蓝通道本来就低 —— 乘一个偏蓝的颜色
 * 只会把它压暗压灰，永远到不了"偏蓝白"。这与草叶透光那次是同一个教训：
 * 要往某个色相去，得往那个颜色插值，乘法只能保留原色相。
 *
 * uTintAmount = 0 时 mix 退化成原样，所以关掉开关时逐位不变。
 */
uniform vec3 uTint;
uniform float uTintAmount;
/**
 * 明度缩放（1 = 不变）。夜里的"变暗"靠它。
 *
 * **不能用 uTint 顺带变暗。** 往一个深色混会让叶子的明暗层次一起被压平
 * （混得越多越平），而夜里想要的是"叶子暗下去、但还看得出是片叶子"。
 * 乘一个系数只压明度、不动对比，两件事得分开。
 *
 * uValue = 1 且 uTintAmount = 0 时整条链路是恒等，所以关掉开关时逐位不变。
 */
uniform float uValue;

/*
 * 逐**像素**、逐**波纹**的蓝藻受光。
 *
 * 两个"不能"都踩过：
 *
 *   1. **不能逐叶算一个乘数。** 那样整片叶子一起亮，而光在水里是个局部的环，
 *      扫过叶子时本来就只该照亮它的一部分。（用户原话：「受光是整体的，
 *      但藻发光其实无法覆盖到整个叶子」。）
 *   2. **不能只挑一个波纹。** 那样多个波纹同时照到叶子时强度不叠加，
 *      而水面那边是累加的 —— 两边对不上。（用户原话：「多个水波纹发光
 *      不会叠加到叶子的发光强度」。）
 *
 * 所以这里和水面着色器做的是**同一件事**：遍历所有波纹，把各自的波带加起来。
 * `algaeGlowOnLeaf()` 与 fall_water_fs.glsl 里的 `algaeGlow()` 是同一段逻辑，
 * 改一处必须改另一处（GLSL 没有 include，只能各留一份）。
 */
uniform vec2 uLeafCenter;
/** 叶片在世界里的两个半轴向量（已含缩放与旋转）。 */
uniform vec2 uLeafAxisX;
uniform vec2 uLeafAxisY;

uniform highp vec4 u_drop[$DROP_SIZE];
uniform highp float u_dropCount;
uniform float uAlgaePower[$DROP_SIZE];
/** 每个波纹在**世界**坐标里的位置（叶子用的是世界坐标）。 */
uniform vec2 uAlgaeWorld[$DROP_SIZE];
uniform float uAlgaeThreshold;
uniform float uAlgaeBand;
/** 世界 → 网格度量的两个系数（scaleX·dxMul, scaleY/halfH）。 */
uniform vec2 uAlgaeMetric;
uniform float uAlgaeAmount;
uniform sampler2D uAlgaeNoise;
uniform float uAlgaeNoiseTile;
/** 亮弧场，与水面同一套常量与来源（见 fall_water_fs.glsl 里 uAlgaeArcScale 的说明）。 */
uniform float uAlgaeArcScale;
const float ALGAE_ARC_LO = 0.42;
const float ALGAE_ARC_HI = 0.62;
/** 场的分层参数，**两份着色器必须逐条相同**（有单测钉着）。 */
const float ALGAE_LAYER_COARSE = 0.25;
const float ALGAE_LAYER_FINE = 1.15;
const float ALGAE_LAYER_ROT1 = 2.399963;
const float ALGAE_LAYER_ROT2 = 4.799926;
const float ALGAE_LAYER_FALLOFF = 2.5;
/**
 * 藻的两端颜色。叶子只取场的覆盖度，用不到它们 —— 但 {@link algaeLayer} 要，
 * 而那个函数必须与水面**逐字相同**才能保证两边的场对得上，所以一并声明。
 */
uniform vec3 uAlgaeLow;
uniform vec3 uAlgaeHigh;

/**
 * 一层噪声 → **预乘**的颜色（rgb 已经乘过 a）。
 *
 * <p>暗的一半走 uAlgaeLow、亮的一半走 uAlgaeHigh，而 alpha 在**两端最强、中灰处归零** ——
 * 一团藻读作"亮核 + 一圈色 + 中间的空隙"，不是一条连续的渐变。
 * 这正是 magicsmoke 预设「绿色光晕」的 mode 1（low 0x00ff00、high 0xffffff，中灰透明），
 * 也就是那个造型的来源：光晕是**围绕亮核的一圈色**，而不是整片都亮。
 */
vec4 algaeLayer(float lum, float weight) {
  float a = abs(lum * 2.0 - 1.0) * weight;
  vec3 c = lum < 0.5 ? uAlgaeLow : uAlgaeHigh;
  return vec4(c * a, a);
}

/** 旋转矩阵。每层转一个角度，是打散同形重复的主要手段。 */
mat2 rot2(float a) {
  float c = cos(a);
  float s = sin(a);
  return mat2(c, -s, s, c);
}

/**
 * 藻的场：返回**预乘的颜色与覆盖度**（rgb 已乘过 a；叶子只取 a）。
 *
 * <p>层数、层间的关系全部照搬 magicsmoke 的「绿色光晕」：
 *
 *   - 一层很粗的底（它用 0.0875，即整屏还铺不满一次）压住大尺度，
 *     上面叠几层**尺度几乎相等**的细节层（它用 1.05/1.19/1.33/1.47，
 *     层间只差 4%），全靠**不同的旋转**把同形重复抹掉。
 *   - 每深一层再乘一个 {@code 1/ALGAE_LAYER_FALLOFF}（它的 alphaMul 是 2.5）。
 *
 * <p>⚠ 这个函数与 fall_water_fs.glsl 里那份**逐字相同**（GLSL 没有 include），
 * 改一处必须改另一处 —— 两边一旦走样，叶子上的光就跟水里那圈对不上。
 */
vec4 algaeField(highp vec2 meshPos) {
  highp vec2 p = meshPos / max(uAlgaeNoiseTile, 1e-4);
  vec4 acc = algaeLayer(texture(uAlgaeNoise, p * ALGAE_LAYER_COARSE).r, 1.0);
  acc += algaeLayer(texture(uAlgaeNoise,
          rot2(ALGAE_LAYER_ROT1) * p + vec2(0.31, 0.77)).r, 1.0 / ALGAE_LAYER_FALLOFF);
  acc += algaeLayer(texture(uAlgaeNoise,
          rot2(ALGAE_LAYER_ROT2) * (p * ALGAE_LAYER_FINE) + vec2(0.63, 0.19)).r,
          1.0 / (ALGAE_LAYER_FALLOFF * ALGAE_LAYER_FALLOFF));
  // 累加会超过 1（三层权重是 1+0.4+0.16）。夹一下，两边才都还在 0..1 里
  return min(acc, vec4(1.0));
}
/**
 * 世界 → 网格**坐标**的系数（scaleX, scaleY/halfH）与偏移。
 *
 * <p>**不能用 uAlgaeMetric**：那个的第一项含 dxMul，是给**距离**用的度量，
 * 拿它换算坐标会把噪声在 X 方向拉长，于是叶子和水面采到的颗粒对不上。
 *
 * <p>偏移也不能省 —— 网格坐标里含 xOffset*2 那一项（addDrop 加的、叶子画的时候减的），
 * 少了它两边的噪声差一个常量，环的形状就对不齐。
 */
uniform vec2 uAlgaeMeshScale;
uniform vec2 uAlgaeMeshOffset;
/** 蓝藻打在叶子上的光色（增益已含）。 */
uniform vec3 uLightColor;

in highp vec2 vTexCoord;

/**
 * 返回这片叶子受到的**光强**（0..1），不是颜色。
 *
 * <p>颜色由 {@link uLightColor} 给 —— **不要把水面那套 blob（藻自己的颜色）
 * 乘进来**：那是"水里那团藻是什么颜色"，而叶子要的是"有多少光打上来"。
 * 乘进去会把强度再压掉一大截（blob 平均约 0.3），实测从 1.9× 掉到 1.12×，
 * 看上去就是"叶子不发光"。
 *
 * <p>保留 density（噪声）—— 那是颗粒，是"这片水里密度如何"，与颜色无关。
 */
float algaeGlowOnLeaf(highp vec2 world) {
  /*
   * 先把世界坐标换成**与水面对齐的网格坐标**，再采样噪声。
   * 两边必须采在同一个坐标上，否则环的形状与颗粒对不上。
   */
  highp vec2 meshPos = world * uAlgaeMeshScale + uAlgaeMeshOffset;

  /*
   * 场与波前的 wobble 都在循环外各采一次 —— 它们是水的属性，与是哪个波纹无关。
   *
   * 这里用的是与水面上**同一个** {@link algaeField}，只是只要它的覆盖度 a
   * （叶子要的是"有多少光打上来"，颜色由 uLightColor 给）——
   * 函数体逐字相同，就是为了两边不会各走各的。
   */
  float density = algaeField(meshPos).a;
  if (density <= 0.004) return 0.0;

  float wobble = texture(uAlgaeNoise, meshPos * 0.04 + vec2(7.3, 1.9)).r;
  float wobbleScale = 0.90 + 0.20 * wobble;

  float acc = 0.0;
  for (int i = 0; i < $DROP_SIZE; i++) {
    if (float(i) >= u_dropCount) break;
    float power = uAlgaePower[i];
    if (power < uAlgaeThreshold) continue;      // 没惊动这片水
    vec4 d4 = u_drop[i];
    highp vec2 delta = (world - uAlgaeWorld[i]) * uAlgaeMetric;
    float dist = length(delta);
    if (dist > d4.w * wobbleScale + uAlgaeBand * 0.30) continue;

    /*
     * 亮弧：与水面同一套，连散列偏移用的都是**波纹序号** —— 位置在这边是世界坐标、
     * 在水面那边是网格坐标，拿位置做散列会算出两套断口，叶子上的光就和水里对不上。
     */
    highp vec2 dir = delta / max(dist, 1e-4);
    vec2 seed = fract(vec2(float(i) * 0.7548, float(i) * 0.5693));
    float arcGate = smoothstep(ALGAE_ARC_LO, ALGAE_ARC_HI,
            texture(uAlgaeNoise, dir * uAlgaeArcScale + seed).r);
    if (arcGate <= 0.004) continue;

    // 与水面同一条带子：**峰值靠外沿**（刚被搅动的地方最亮），两头归零
    float t = clamp((dist - (d4.w * wobbleScale - uAlgaeBand))
            / (uAlgaeBand * 1.30), 0.0, 1.0);
    float band = smoothstep(0.0, 0.75, t) * (1.0 - smoothstep(0.75, 1.0, t));

    acc += band * smoothstep(uAlgaeThreshold, 1.0, power) * arcGate;
  }
  return acc / (1.0 + acc);                     // 与水面对称的软饱和
}

void main() {
  vec4 texColor = texture(uSampler, vTexCoord);
  vec3 rgb = mix(texColor.rgb, uTint, uTintAmount) * uValue;

  /*
   * ⚠ **v 在叶片局部是朝下的**，所以 y 要反过来。
   *
   * 依据在 drawQuad：形参是 (left, top, right, bottom)，而调用传的是
   * (-LEAF_SIZE, -LEAF_SIZE, LEAF_SIZE, LEAF_SIZE) —— top 拿到 -0.55、
   * bottom 拿到 +0.55，于是 uv(0,0) 落在**上**左角、uv(0,1) 落在**下**左角。
   *
   * 这个上下颠倒是故意的：Android 的位图上传本身也是 v 翻转的，两者抵消，
   * 所以叶子看起来是对的。但这里的局部坐标必须跟着它，不能想当然按 v 朝上算 ——
   * 否则受光会在 Y 方向整个镜像（左下点击、光却从左上过来）。
   */
  if (uAlgaeAmount > 0.001) {
    vec2 local = vec2(vTexCoord.x * 2.0 - 1.0, 1.0 - vTexCoord.y * 2.0);
    vec2 world = uLeafCenter + uLeafAxisX * local.x + uLeafAxisY * local.y;
    // **乘**在叶片本色上：亮部提亮、暗部保持暗，才读作"被照亮"
    rgb *= (vec3(1.0) + uLightColor * algaeGlowOnLeaf(world));
  }

  fragColor = vec4(rgb, texColor.a) * uColor;
  fragColor.a *= uAlpha;
}
