#version 450

// 星轨片元 —— 对应 GLES 的 nightsky_trail_fs.glsl。
// 输出预乘颜色，pass 用 ONE/ONE 相加混合。

layout(location = 0) in vec4 vColor;

layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = vec4(vColor.rgb * vColor.a, vColor.a);
}
