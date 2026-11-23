#version 450

/*
 * 蒲公英所有贴图四边形的公共顶点着色器 —— 对应 GLES 的 dandelion_sprite_vs.glsl。
 *
 * 顶点坐标已经在 CPU 上算成屏幕像素（茎的摆动、花头的倾角、浮尘的旋转都是 2D 仿射），
 * uMvp 是正交投影乘过 GL → Vulkan 裁剪空间修正。
 *
 * 贴图下标按顶点传下来：通用 pass 的贴图绑定是静态的，每个 pass 一次绑定它这一组的
 * 全部候选贴图，片元再按下标选。同一个四边形的 6 个顶点下标相同，所以用 flat 插值。
 */

layout(push_constant) uniform PushConstants {
    mat4 uMvp;
} pc;

layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aUV;
layout(location = 2) in float aAlpha;
layout(location = 3) in float aTexIndex;

layout(location = 0) out vec2 vUV;
layout(location = 1) out float vAlpha;
layout(location = 2) flat out float vTexIndex;

void main() {
    gl_Position = pc.uMvp * vec4(aPos, 0.0, 1.0);
    vUV = aUV;
    vAlpha = aAlpha;
    vTexIndex = aTexIndex;
}
