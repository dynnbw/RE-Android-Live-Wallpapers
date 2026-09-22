#version 300 es
// 默认精度**保持 mediump，与加特效之前一致** —— 这样开关关掉时这条路径逐位不变，
// "关掉就等于今天"才是字面上成立的。
precision mediump float;

out vec4 fragColor;
uniform sampler2D uSampler;
in vec4 vColor;
in highp vec2 vTexCoord;

// ---- 草叶逆光（第二版）----
//
// uLight 是总强度：开关 × 太阳高度角曲线 × 天气。它同时是**总开关**。
//
// **这里没有光源位置。** 逐叶的受光已经在 Java 侧算进了 vTexCoord.y ——
// 第一版把"光落在哪里"做成以光源屏幕位置为中心的径向渐变，于是同一距离上每片叶
// 拿到的值数学上必然相等，整片草一起变金、没有个体差异。2D 光照的经典文章把这个
// 失败模式说得很直白：没有法线贴图时，光只是把精灵的整体形状均匀照亮。
uniform float uLight;
uniform float uCrossAngle;   // 横截面法线扫过的张角
uniform float uHeightDim;    // 叶根（厚）的明暗系数
uniform float uHeightGain;   // 叶尖（薄）的明暗系数
uniform vec3  uWarm;         // 透射暖金（乘）
uniform vec3  uCool;         // 冷影（乘）
uniform vec3  uRimColor;     // 迎光边暖白（加）
uniform float uRimGain;
uniform float uShadowGain;

void main() {
  float a = texture(uSampler, vTexCoord).r;

  // 提前返回保证"关掉时逐像素一致"，而不是指望下面每个式子各自退化成恒等
  // —— 写漏一处就静默失效，而且不会报错。
  if (uLight <= 0.0) {
    fragColor = vec4(vColor.rgb, a);
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
  vec3 color = vColor.rgb * heightTint;

  // ② 透射：薄 + 受光 + 偏背光侧（光穿过叶肉的那一侧）
  color = mix(color, color * uWarm, m * tip * mix(0.65, 1.0, max(-edge, 0.0)));

  // ③ 迎光边：**只在迎光那一侧**，且随受光缩放 —— 不是每片叶一圈相同的描边
  color += uRimColor * rim * m * max(edge, 0.0) * uRimGain;

  // ④ 暖光冷影：不受光处压暗**并偏冷**。只把亮部染暖、暗部不动的话，
  //    读起来就是"同一种颜料被调亮调暗"。
  color = mix(color, color * uCool, (1.0 - m) * uShadowGain);

  fragColor = vec4(color, a);
}
