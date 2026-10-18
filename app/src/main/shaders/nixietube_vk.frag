#version 450

layout(set = 0, binding = 0) uniform sampler2D uTexture;

layout(push_constant) uniform PushConstants {
    mat4 uMvp;
} pc;

layout(location = 0) in vec2 vTexCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    // 与 GLES 的 nixietube_fs.glsl 一致：原样输出图集采样，alpha 交给混合。
    fragColor = texture(uTexture, vTexCoord);
}
