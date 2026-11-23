#version 450

// 星点点精灵 —— 对应 GLES 的 nightsky_star_fs.glsl。
// 输出预乘颜色（rgb * a），pass 用 ONE/ONE 相加混合。

layout(location = 0) in vec4 vColor;

layout(location = 0) out vec4 fragColor;

void main() {
    vec2 d = gl_PointCoord - vec2(0.5);
    float r = length(d) * 2.0;
    float edge = smoothstep(1.0, 0.0, r);
    float alpha = vColor.a * edge * edge;
    fragColor = vec4(vColor.rgb * alpha, alpha);
}
