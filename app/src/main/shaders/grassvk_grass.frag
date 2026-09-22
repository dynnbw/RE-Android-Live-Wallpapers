#version 450

layout(location = 0) in vec4 vColor;
layout(location = 1) in vec2 vTexCoord;
layout(location = 0) out vec4 fragColor;

layout(binding = 0) uniform sampler2D uAATexture;

void main() {
    // AA texture encodes edge softness in the R channel (uploaded as RGBA with R=alpha)
    float a = texture(uAATexture, vTexCoord).r;
    // 与 GLES 那条同步：vColor.a 现在装叶尖位置，不能再乘进输出 alpha。
    // 顶点数据是两条渲染路径共用的（GrassRenderDataBuilder），只改一条会静默变样。
    fragColor = vec4(vColor.rgb, a);
}
