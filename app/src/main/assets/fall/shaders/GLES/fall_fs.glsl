#version 300 es
precision mediump float;
out vec4 fragColor;
uniform sampler2D uSampler;
uniform float uAlpha;
uniform vec4 uColor;
/**
 * 落叶的时段染色：往 uTint 混 uTintAmount。
 *
 * <p>**必须是混，不能是乘。** 枫叶是橙红的，蓝通道本来就低 —— 乘一个偏蓝的颜色
 * 只会把它压暗压灰，永远到不了"偏蓝白"。这与草叶透光那次是同一个教训：
 * 要往某个色相去，得往那个颜色插值，乘法只能保留原色相。
 *
 * <p>uTintAmount = 0 时 mix 退化成原样，所以关掉开关时逐位不变。
 */
uniform vec3 uTint;
uniform float uTintAmount;
/**
 * 明度缩放（1 = 不变）。夜里的"变暗"靠它。
 *
 * <p>**不能用 uTint 顺带变暗。** 往一个深色混会让叶子的明暗层次一起被压平
 * （混得越多越平），而夜里想要的是"叶子暗下去、但还看得出是片叶子"。
 * 乘一个系数只压明度、不动对比，两件事得分开。
 *
 * <p>uValue = 1 且 uTintAmount = 0 时整条链路是恒等，所以关掉开关时逐位不变。
 */
uniform float uValue;
in highp vec2 vTexCoord;
void main() {
  vec4 texColor = texture(uSampler, vTexCoord);
  vec3 rgb = mix(texColor.rgb, uTint, uTintAmount) * uValue;
  fragColor = vec4(rgb, texColor.a) * uColor;
  fragColor.a *= uAlpha;
}
