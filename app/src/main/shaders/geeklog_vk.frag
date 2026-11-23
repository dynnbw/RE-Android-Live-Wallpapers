#version 450

/*
 * 字符图集片元：只取图集的 alpha 作为字形遮罩，颜色来自顶点。
 * 与 GLES 的 terminal_fs.glsl 一致；混合由 pass 的
 * SRC_ALPHA / ONE_MINUS_SRC_ALPHA 因子完成，着色器本身不做混合。
 */

layout(set = 0, binding = 0) uniform sampler2D uTexture;

layout(location = 0) in vec2 vUV;
layout(location = 1) in vec3 vColor;

layout(location = 0) out vec4 fragColor;

void main() {
    float a = texture(uTexture, vUV).a;
    fragColor = vec4(vColor, a);
}
