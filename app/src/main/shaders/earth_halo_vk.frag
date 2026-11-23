#version 450

/* 光晕贴图直接叠加，对应 GLES 的 earth_overlay_fs.glsl。 */

layout(set = 0, binding = 0) uniform sampler2D uSampler;

layout(location = 0) in vec2 vTexCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = texture(uSampler, vTexCoord);
}
