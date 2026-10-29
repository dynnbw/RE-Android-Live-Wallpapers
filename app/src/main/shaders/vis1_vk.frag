#version 450
// vis1（Visualizer）的波形点 —— 对应 GLES 版 musicvis_line_fs.glsl。
//
// 原版是 drawPoint + ROUND cap + setAntiAlias(true)：一个直径 2 像素的抗锯齿圆点。
// 四边形本身是方的、边是硬的，所以拿顶点的角坐标（±1，半径 = 1）算覆盖：
// 圆外全透明，圆边一像素内渐隐。相邻点只隔一个采样、而直径是 2，点会互相重叠
// 成一条线，柔边正是这条线上下轮廓不出现锯齿台阶的原因。
layout(push_constant) uniform PushConstants {
    mat4 uMvp;
    vec4 uColor;
    float uFeather;
} pc;

layout(location = 0) in vec2 vCorner;

layout(location = 0) out vec4 fragColor;

void main() {
    float r = length(vCorner);
    float coverage = 1.0 - smoothstep(1.0 - pc.uFeather, 1.0, r);
    if (coverage <= 0.0) discard;
    fragColor = vec4(pc.uColor.rgb, pc.uColor.a * coverage);
}
