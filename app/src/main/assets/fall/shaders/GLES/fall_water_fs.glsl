#version 300 es
// 水面/河床：树（白底黑图的遮罩）乘以天空色带，而不是把两者烤在一张贴图里。
//
// 天空只取 v（纯竖直渐变），并且和树用同一个 vTexCoord —— 顶点着色器把 UV
// 按水面波纹扭曲过，天空跟着一起扭，和合并成一张贴图时的观感一致。
precision mediump float;
out vec4 fragColor;
uniform sampler2D uMask;
uniform sampler2D uSky;
uniform float uAlpha;
uniform vec4 uColor;
in highp vec2 vTexCoord;
void main() {
  float m = texture(uMask, vTexCoord).r;
  vec3 sky = texture(uSky, vec2(0.5, vTexCoord.y)).rgb;
  fragColor = vec4(sky * m, 1.0) * uColor;
  fragColor.a *= uAlpha;
}
