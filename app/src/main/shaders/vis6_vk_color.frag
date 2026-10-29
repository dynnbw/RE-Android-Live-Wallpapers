#version 450

/*
 * vis6（环形频谱）的重着色圆环 —— 对应 GLES 版
 * musicvis/shaders/GLES/musicvis_wave_color_fs.glsl。
 *
 * GLES 的顶点着色器声明了 aTexCoord，但渲染器从未启用该属性，采样坐标恒为
 * 默认值 (0, 0)；这里同样固定采样该点，明度与原版一致。
 */

layout(set = 0, binding = 0) uniform sampler2D uTex;

layout(location = 0) in vec3 vAdjust;

layout(location = 0) out vec4 fragColor;

vec3 hsl2rgb(vec3 hsl) {
    const vec3 K1 = vec3(0.5, 1.0 / 3.0, 2.0 / 3.0);
    const vec3 K2 = vec3(1.0, -1.0, -1.0);
    const vec3 C = vec3(-1.0, 2.0, 2.0);
    vec3 rgb = clamp(abs(6.0 * (hsl.xxx - K1.xyz)) * K2 + C, 0.0, 1.0);
    float chroma = (1.0 - abs(2.0 * hsl.z - 1.0)) * hsl.y;
    return (rgb - 0.5) * chroma + hsl.z;
}

void main() {
    vec3 rgb = texture(uTex, vec2(0.0)).rgb;
    if (vAdjust.x >= 0.0) {
        vec3 hsl = vAdjust * vec3(1.0, 1.0, rgb.r);
        rgb = hsl2rgb(hsl);
    }
    fragColor = vec4(rgb, 1.0);
}
