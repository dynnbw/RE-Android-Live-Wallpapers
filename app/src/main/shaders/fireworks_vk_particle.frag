#version 450

/*
 * 烟花粒子 pass —— 对应 GLES 的 fireworks_particle_fs.glsl：
 * 纹理的颜色与 alpha 分别乘逐顶点颜色，加法混合后就是发光效果。
 */

layout(set = 0, binding = 0) uniform sampler2D sTexture;

layout(location = 0) in vec2 vTexCoord;
layout(location = 1) in vec4 vColor;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 c = texture(sTexture, vTexCoord);
    fragColor = vec4(c.rgb * vColor.rgb, c.a * vColor.a);
}
