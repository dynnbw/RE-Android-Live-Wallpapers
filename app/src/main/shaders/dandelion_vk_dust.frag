#version 450

/*
 * 背景浮尘的片元着色器：两层整片旋转的 dust_sheet 与大小两种点点图。
 *
 * 绑定顺序与 DandelionVkDelegate 里的全局贴图下标一致：0/1/2 分别是
 * dust_sheet / dust_small / dust_large。贴图是预乘 alpha 的，混合由 pass 的
 * ONE / ONE_MINUS_SRC_ALPHA 因子完成，这里只乘顶点带来的整体透明度。
 */

layout(set = 0, binding = 0) uniform sampler2D uTexture0;
layout(set = 0, binding = 1) uniform sampler2D uTexture1;
layout(set = 0, binding = 2) uniform sampler2D uTexture2;

layout(location = 0) in vec2 vUV;
layout(location = 1) in float vAlpha;
layout(location = 2) flat in float vTexIndex;

layout(location = 0) out vec4 fragColor;

void main() {
    int index = int(vTexIndex + 0.5);
    vec4 color;
    if (index < 1) {
        color = textureLod(uTexture0, vUV, 0.0);
    } else if (index < 2) {
        color = textureLod(uTexture1, vUV, 0.0);
    } else {
        color = textureLod(uTexture2, vUV, 0.0);
    }
    fragColor = color * vAlpha;
}
