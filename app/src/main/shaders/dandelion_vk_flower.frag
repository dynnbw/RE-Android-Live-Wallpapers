#version 450

/*
 * 蒲公英植株的片元着色器：花头贴图（11 帧竖排）与三根茎。
 *
 * 绑定顺序与 DandelionVkDelegate 里的全局贴图下标一致：3..6 对应
 * head_sheet / stem_a / stem_b / stem_c，这里减去 3 换成 0..3。
 * 花头的帧选择走顶点传下来的 v 范围，不在这里做。
 */

layout(set = 0, binding = 0) uniform sampler2D uTexture0;
layout(set = 0, binding = 1) uniform sampler2D uTexture1;
layout(set = 0, binding = 2) uniform sampler2D uTexture2;
layout(set = 0, binding = 3) uniform sampler2D uTexture3;

layout(location = 0) in vec2 vUV;
layout(location = 1) in float vAlpha;
layout(location = 2) flat in float vTexIndex;

layout(location = 0) out vec4 fragColor;

void main() {
    int index = int(vTexIndex + 0.5) - 3;
    vec4 color;
    if (index < 1) {
        color = textureLod(uTexture0, vUV, 0.0);
    } else if (index < 2) {
        color = textureLod(uTexture1, vUV, 0.0);
    } else if (index < 3) {
        color = textureLod(uTexture2, vUV, 0.0);
    } else {
        color = textureLod(uTexture3, vUV, 0.0);
    }
    fragColor = color * vAlpha;
}
