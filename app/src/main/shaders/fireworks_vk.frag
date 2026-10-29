#version 450

/*
 * 烟花背景 pass —— 对应 GLES 的 fireworks_fs.glsl。
 *
 * GLES 绘制背景时把 uColor 设成白色、uAlpha 设成 1，所以这里直接输出纹理，
 * 与 vec4(c.rgb * 1.0, c.a * 1.0) 相同。
 */

layout(set = 0, binding = 0) uniform sampler2D sTexture;

layout(location = 0) in vec2 vTexCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = texture(sTexture, vTexCoord);
}
