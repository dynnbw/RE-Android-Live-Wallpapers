#version 300 es
// 默认精度**保持 mediump，与加特效之前一致** —— 这样开关关掉时这条路径逐位不变，
// "关掉就等于今天"才是字面上成立的。
precision mediump float;

out vec4 fragColor;
uniform sampler2D uSampler;
in vec4 vColor;
in highp vec2 vTexCoord;
in highp vec2 vPos;
/**
 * 逐叶的萤火虫遮挡（1 = 不受影响，越小越暗）。
 *
 * <p>近处的草把影子投在远处的草上 —— 萤火虫画在草之后、是离镜头最近的一层，光从前方来。
 * 由 GrassFireflyShadow 在 Java 侧限频算好，见 GrassBladeLighting.occlusionFromFrontOf。
 */
in float vFireflyShadow;

// ---- 萤火虫照亮草叶 ----
//
// 挂在**特效**开关下（与 HDR 辉光、黄昏影调同进同出），没有自己的开关。
//
// **为什么不像逆光那样在 Java 侧算成一个标量：** 那条通道（vTexCoord.y）是逐叶的，
// 一个顶点只有 8 个 float 且已占满，而且与 Vulkan 共用同一份顶点格式。萤火虫的光
// 本来就是局域的、圆的，逐像素算既更准也更省事 —— 顶点位置已经在这儿了（vPos）。
//
// 每个光源是 (x, y, 亮度)：位置与亮度都由 GrassFireflyLight 从**已经画出去的**
// 萤火虫精灵批里取出，所以眨眼与昼夜淡入淡出自动跟着走，不可能与画面不一致。
#define FLY_MAX 16

uniform highp vec3 uFirefly[FLY_MAX];
uniform vec3  uFireflyTint;
/** 总强度。0 = 关 —— 此时 main 里那一行是恒等，逐位不变。 */
uniform float uFireflyGain;
uniform float uFireflyRadius;
/**
 * 把叶片颜色还原成**本色**的增益。
 *
 * <p>vColor.rgb 里装的是「本色 × 夜色系数」——夜里系数低到 0.1 甚至 0，整片草接近纯黑。
 * 而**黑底上叠什么都没用**：光加在黑上，画面上就只剩灯自己的颜色，草的本色一点也读不出来
 * （这正是逆光开关当年踩过的那个坑，见 GRASS_NIGHT_VALUE_FLOOR）。
 *
 * <p>所以萤火虫这一项不能是"往底色上加光"，得是"**让光把本色照出来**"：
 * {@code 本色 = vColor.rgb × uFireflyAlbedoBoost}，乘上光照就是
 * {@code 本色 × 光} —— 颜色跟着草走、亮度跟着光走。
 *
 * <p>增益由 CPU 按当帧的夜色系数取倒数算好（并封了上限），与顶点里那个系数是同一个来源。
 */
uniform float uFireflyAlbedoBoost;

/**
 * 各光源在 p 点处的叠加照度。
 *
 * <p>不用开方：{@code w = 1 - d²/r²}。再平方一次是为了让**半径处的一阶导也是 0** ——
 * 只取到 {@code 1-d²/r²} 的话圆边上还剩一个折角，画面上就是一个看得见的圈。
 */
highp float fireflyLight(highp vec2 p) {
  highp float r2 = max(uFireflyRadius * uFireflyRadius, 1.0);
  highp float invR2 = 1.0 / r2;
  highp float sum = 0.0;
  for (int i = 0; i < FLY_MAX; i++) {
    highp vec3 f = uFirefly[i];
    if (f.z <= 0.0) continue;          // 空槽位（Java 侧每帧清零）
    highp vec2 d = p - f.xy;
    highp float d2 = dot(d, d);
    if (d2 >= r2) continue;
    highp float w = 1.0 - d2 * invR2;
    sum += w * w * f.z;
  }
  return sum;
}

// ---- 草叶逆光（第二版）----
//
// uLight 是总强度：开关 × 太阳高度角曲线 × 天气。它同时是**总开关**。
//
// **这里没有光源位置。** 逐叶的受光已经在 Java 侧算进了 vTexCoord.y ——
// 第一版把"光落在哪里"做成以光源屏幕位置为中心的径向渐变，于是同一距离上每片叶
// 拿到的值数学上必然相等，整片草一起变金、没有个体差异。2D 光照的经典文章把这个
// 失败模式说得很直白：没有法线贴图时，光只是把精灵的整体形状均匀照亮。
uniform float uLight;
// 高光门控：驱动**透光与迎光边**。0 = 不要高光（正午），1 = 满（黄金时刻、月夜）。
//
// 与 uLight 分开是因为它们驱动的东西不同：uLight 管高度渐变与阴影（白天也有），
// uHighlight 管"逆光的那层金"（正午没有 —— 太阳在头顶，逆光不成立）。
uniform float uHighlight;
uniform float uCrossAngle;   // 横截面法线扫过的张角
uniform float uHeightDim;    // 叶根（厚）的明暗系数
uniform float uHeightGain;   // 叶尖（薄）的明暗系数
uniform vec3  uTransmit;     // 透过的光的颜色（**不是乘数**，见下面第 ② 条）
uniform vec3  uCool;         // 冷影（乘）
uniform vec3  uRimColor;     // 迎光边暖白（加）
uniform float uRimGain;
uniform float uShadowGain;

void main() {
  float a = texture(uSampler, vTexCoord).r;

  vec3 color = vColor.rgb;

  // 萤火虫照明加在**逆光那段之前**：逆光有自己的开关，萤火虫挂在特效开关下，
  // 两者互不相干 —— 放到提前返回之后的话，逆光一关萤火虫的光也跟着没了。
  // 关掉时 uFireflyGain 是 0：这个分支不成立，color 就是 vColor.rgb，逐位不变。
  if (uFireflyGain > 0.0) {
    // **照出本色**，不是往底色上加光：见 uFireflyAlbedoBoost 的说明。
    color += vColor.rgb * uFireflyAlbedoBoost
             * uFireflyTint * uFireflyGain * fireflyLight(vPos) * vFireflyShadow;
  }

  // 提前返回保证"逆光关掉时逐像素一致"，而不是指望下面每个式子各自退化成恒等
  // —— 写漏一处就静默失效，而且不会报错。
  if (uLight <= 0.0) {
    fragColor = vec4(color, a);
    return;
  }

  float u    = vTexCoord.x;        // 横截面位置：0/1 是叶片两条边
  float tip  = vColor.a;           // 0 叶根 → 1 叶尖，同时是厚度代理
  float beam = vTexCoord.y;        // 带符号的逐叶受光

  float m = abs(beam);             // 强弱
  float s = sign(beam);            // 光在叶片的哪一侧

  // 叶片横截面是个浅弧，法线沿宽度扫过 —— 这就是解析法线的来源，
  // 不需要给程序生成的叶片做贴图（那正是 2D 光照里真正麻烦的一步）。
  float theta = (u - 0.5) * uCrossAngle;
  float edge  = sin(theta) * s;        // 正 = 迎光侧
  // 轮廓要用 alpha 本身，不能用 abs(vTexCoord.x - 0.5)：后者是叶片**几何**的两端，
  // 恰好是 AA 贴图把叶片淡出的地方 —— 两者错开，乘积峰值只有 0.055，等于没画。
  float rim   = 4.0 * a * (1.0 - a);

  // ① 高度明暗：根厚而暗、尖薄而亮。按总强度淡入，uLight=0 时是恒等。
  // 分两句写：内层两个操作数都是 float，混出来是 float，套不进 mix(vec3, vec3, float)。
  float height = mix(uHeightDim, uHeightGain, tip);
  vec3 heightTint = mix(vec3(1.0), vec3(height), uLight);
  color *= heightTint;

  // ② 透射：薄 + 受光 + 偏背光侧（光穿过叶肉的那一侧）
  //
  //    **往"光的颜色"插值，而不是往 color × 暖金插值。**
  //    后者是给绿草染色 —— 乘数永远保留着叶色，所以再亮也读不出"光是穿透过来的"。
  //    物理上透射光是「光的颜色 × 叶肉的透射率」，与叶片自身的反射色无关。
  //    实测这一改让中间档从 +13% 跳到 +50%，那正是"透"的来源。
  //
  //    **系数用 sqrt(m) 而不是 m。** 高光走 m、阴影走 (1 - m)，两路是分开的；
  //    实测 m 的均值只有 0.22（可见度和遮挡各削掉一半），线性用 m 时混合系数太小。
  float transmit = sqrt(m) * tip * mix(0.85, 1.10, max(-edge, 0.0)) * uHighlight;
  vec3 transmitted = uTransmit * mix(0.90, 1.15, tip);   // 薄处透得多、也更亮
  color = mix(color, transmitted, transmit);

  // ③ 迎光边：**只在迎光那一侧**，且随受光缩放 —— 不是每片叶一圈相同的描边
  color += uRimColor * rim * m * max(edge, 0.0) * uRimGain * uHighlight;

  // ④ 暖光冷影：不受光处压暗**并偏冷**。只把亮部染暖、暗部不动的话，
  //    读起来就是"同一种颜料被调亮调暗"。
  // 阴影同样跟着 uLight 缩放 —— 夜里总强度是白天的一半，阴影也该淡一半
  color = mix(color, color * uCool, (1.0 - m) * uShadowGain * uLight);

  fragColor = vec4(color, a);
}
