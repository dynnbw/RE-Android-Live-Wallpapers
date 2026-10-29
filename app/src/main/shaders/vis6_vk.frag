#version 450

layout(location = 0) out vec4 fragColor;

void main() {
    // GLES 的纯色路径固定输出白色、alpha 0.75。
    // 那边只调用了 glEnable(GL_BLEND)，混合因子仍是默认的 (ONE, ZERO)，
    // 等价于直接覆盖写入；对应的 VK pass 也不启用混合。
    fragColor = vec4(1.0, 1.0, 1.0, 0.75);
}
