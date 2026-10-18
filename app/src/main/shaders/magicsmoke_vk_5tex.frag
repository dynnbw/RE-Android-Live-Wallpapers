#version 450

/*
 * Magic Smoke, 5-layer variant (mask 0x1f).
 *
 * Mirrors the GLES fragment shader: start from the preset's clear color and
 * "over" each layer with mix(color, tex, tex.a), with blending disabled.
 *
 * The generic Vulkan uploader uses CLAMP_TO_EDGE while GLES sets GL_REPEAT;
 * fract() reproduces the wrap.
 */

layout(push_constant) uniform PushConstants {
    vec4 uLayer0;
    vec4 uLayer1;
    vec4 uLayer2;
    vec4 uLayer3;
    vec4 uLayer4;
    vec2 uPanOffset;
    vec2 uAspectScale;
    vec4 uClearColor;
} pc;

layout(set = 0, binding = 0) uniform sampler2D uTexture0;
layout(set = 0, binding = 1) uniform sampler2D uTexture1;
layout(set = 0, binding = 2) uniform sampler2D uTexture2;
layout(set = 0, binding = 3) uniform sampler2D uTexture3;
layout(set = 0, binding = 4) uniform sampler2D uTexture4;

layout(location = 0) in vec2 vTexCoord0;
layout(location = 1) in vec2 vTexCoord1;
layout(location = 2) in vec2 vTexCoord2;
layout(location = 3) in vec2 vTexCoord3;
layout(location = 4) in vec2 vTexCoord4;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = pc.uClearColor;

    vec4 tex = texture(uTexture0, fract(vTexCoord0));
    color = mix(color, tex, tex.a);

    tex = texture(uTexture1, fract(vTexCoord1));
    color = mix(color, tex, tex.a);

    tex = texture(uTexture2, fract(vTexCoord2));
    color = mix(color, tex, tex.a);

    tex = texture(uTexture3, fract(vTexCoord3));
    color = mix(color, tex, tex.a);

    tex = texture(uTexture4, fract(vTexCoord4));
    color = mix(color, tex, tex.a);

    fragColor = color;
}
