#version 450

/* 星图必须全亮，对应 GLES 的 earth_sky_fs.glsl。 */

layout(set = 0, binding = 0) uniform sampler2D uSampler;

layout(location = 0) in vec2 vTexCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = texture(uSampler, vec2(fract(vTexCoord.x + 0.5), vTexCoord.y));
}
