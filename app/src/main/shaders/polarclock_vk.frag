#version 450

/*
 * 每道环一次纯色填充：颜色来自调色板（固定色，或随时间循环的 HSV 色），
 * 与 GLES 的 glUniform4f(uColor) 同值。混合由 pass 的
 * SRC_ALPHA / ONE_MINUS_SRC_ALPHA 因子完成，着色器本身不做混合。
 */

layout(push_constant) uniform PushConstants {
    mat4 uMvp;
    vec4 uColor;
} pc;

layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = pc.uColor;
}
