// 复刻原版 GLES1 固定管线的光照。
//
// 原版全篇没有 glMaterial / GL_COLOR_MATERIAL → 默认材质 ambient=0.2 / diffuse=0.8;
// 光源是无衰减点光源 (0,3,10)，且 `glLightfv(GL_POSITION)` 是在 modelview 为单位矩阵时
// 调用的 —— 原版把相机变换放在**投影矩阵**里，所以那次的 modelview 不含相机，
// 存下来的眼坐标等价于**世界空间**的固定点 (0,3,10)。
//
// 这一点很关键：若把光源当成"相机空间的 (0,3,10)"，光就永远在相机背后，
// 永远只看得到全亮面，晨昏线与城市灯光就整个失效了。
precision mediump float;

uniform sampler2D uDay;
uniform sampler2D uNight;
uniform vec3  uLightPosWorld;
uniform vec3  uChannelDelta;
uniform float uAmbient;
uniform float uNightGain;
uniform float uUseNight;

varying vec3 vWorldPos;
varying vec3 vNormal;
varying vec2 vTexCoord;

void main() {
  vec3 n = normalize(vNormal);
  vec3 l = normalize(uLightPosWorld - vWorldPos);
  float nl = dot(n, l);

  // 网格的 UV 与标准等距圆柱贴图之间**差了 180° 经度**（不是镜像）：
  // 直接采样会把大陆放到地球的另一面 —— 表现为"该亮的地方亮的是别的大陆"，
  // 例如下午四点时亚洲却是夜里。U 平移 0.5 即可（右半边采样到左半边）。
  vec2 uv = vec2(fract(vTexCoord.x + 0.5), vTexCoord.y);

  vec4 dayTex = texture2D(uDay, uv);
  vec3 dayCol = dayTex.rgb * (uAmbient + (1.0 - uAmbient) * max(nl, 0.0)) + uChannelDelta;

  vec3 col = dayCol;
  if (uUseNight > 0.5) {
    // 城市灯光是自发光，不参与光照调制；晨昏线用 smoothstep 柔化
    vec3 nightCol = texture2D(uNight, uv).rgb * uNightGain;
    col = mix(nightCol, dayCol, smoothstep(-0.15, 0.25, nl));
  }
  gl_FragColor = vec4(col, dayTex.a);
}
