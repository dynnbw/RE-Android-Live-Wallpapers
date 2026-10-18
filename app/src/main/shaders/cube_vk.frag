#version 450

layout(location = 0) in float vAlpha;

layout(location = 0) out vec4 fragColor;

void main() {
    // 与 GLES 一致：颜色固定为白，alpha 由顶点给出（线段 1.0，触摸圆圈 0.6）。
    fragColor = vec4(1.0, 1.0, 1.0, vAlpha);
}
