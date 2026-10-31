#version 450

/*
 * 部件着色 —— 与 GLES 的 droid_sprite_fs.glsl 相同：
 * 按 new = c*(255-color)/255 + color 把黑色剪影染成目标颜色，
 * 白色细节保持不变，即 mix(color, white, tex.rgb)。
 */

layout(set = 0, binding = 0) uniform sampler2D uTexture;

layout(push_constant) uniform PushConstants {
    vec4 uTintAlpha;
} pc;

layout(location = 0) in vec2 vTexCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 tex = texture(uTexture, vTexCoord);
    vec3 rgb = mix(pc.uTintAlpha.rgb, vec3(1.0), tex.rgb);
    fragColor = vec4(rgb, tex.a * pc.uTintAlpha.a);
}
