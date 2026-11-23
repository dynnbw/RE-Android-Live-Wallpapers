#version 450

/*
 * 蒲公英的背景色带 —— 与 GLES 的 dandelion_sky_fs.glsl 同一条色带。
 *
 * GLES 把 11 个色标当 uniform 数组上传；push constant 只有 128 字节，装不下 11 个
 * vec3 加 11 个 float，所以这里直接写进着色器。色标本来就是参考实现那张图的常量。
 */

const vec3 STOPS[11] = vec3[11](
    vec3(0.290, 0.490, 1.000),
    vec3(0.322, 0.525, 0.969),
    vec3(0.353, 0.573, 0.906),
    vec3(0.420, 0.729, 0.808),
    vec3(0.451, 0.827, 0.776),
    vec3(0.518, 0.875, 0.741),
    vec3(0.612, 0.890, 0.678),
    vec3(0.741, 0.890, 0.612),
    vec3(0.871, 0.906, 0.549),
    vec3(0.937, 0.906, 0.549),
    vec3(0.969, 0.906, 0.549)
);

const float STOP_POS[11] = float[11](
    0.0, 40.0, 100.0, 200.0, 300.0, 400.0, 500.0, 600.0, 700.0, 760.0, 800.0
);

layout(location = 0) in float vDesignY;

layout(location = 0) out vec4 fragColor;

void main() {
    float y = clamp(vDesignY, STOP_POS[0], STOP_POS[10]);
    vec3 color = STOPS[0];
    for (int i = 0; i < 10; i++) {
        float f = clamp((y - STOP_POS[i]) / (STOP_POS[i + 1] - STOP_POS[i]), 0.0, 1.0);
        color = mix(color, STOPS[i + 1], f);
    }
    // 8 位色深下这种大跨度渐变会有色带，抖一点噪点把它打散
    float noise = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);
    fragColor = vec4(color + (noise - 0.5) / 255.0, 1.0);
}
