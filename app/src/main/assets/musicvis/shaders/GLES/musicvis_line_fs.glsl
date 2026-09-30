#version 300 es
// vis1：原版是一条纯白的线（paint 只有颜色、没有贴图），所以这里只需要一个颜色。
//
// 每个点画成一个**边缘柔化的圆**：原版是 drawPoint + ROUND cap + setAntiAlias(true)，
// 也就是一个直径 2 像素的抗锯齿圆点。四边形本身是方的、边是硬的，所以这里拿顶点的
// **角坐标**（±1，半径 = 1）算覆盖：圆外全透明，圆边一像素内渐隐。
//
// 相邻点只隔一个采样（约 1 像素）而直径是 2，所以点会互相重叠 —— 画面上是一条线，
// 而柔边正是这条线上下轮廓不出现锯齿台阶的原因。
precision mediump float;
in vec2 vCorner;
uniform vec4 uColor;
uniform float uFeather;
out vec4 fragColor;
void main() {
  float r = length(vCorner);
  float coverage = 1.0 - smoothstep(1.0 - uFeather, 1.0, r);
  if (coverage <= 0.0) discard;
  fragColor = vec4(uColor.rgb, uColor.a * coverage);
}
